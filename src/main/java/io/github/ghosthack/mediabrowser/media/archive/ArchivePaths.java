package io.github.ghosthack.mediabrowser.media.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Crossing the boundary between the real filesystem and the inside of an
 * archive: which files can be entered, what a path inside one is called, and
 * where {@code ..} leads from an archive's root.
 *
 * <p>The textual form is {@code /real/path/disc.iso!/DIR/FILE.JPG} — the
 * {@code !/} convention JAR URLs have used for decades, which reads unambiguously
 * in an address bar and round-trips through {@link #parse}. A path inside an
 * archive has no meaning on its own ({@code /DIR/FILE.JPG} would look like a
 * root-level file), so anything that persists or displays a path must go
 * through {@link #format} rather than {@code Path.toString}.</p>
 */
public final class ArchivePaths {

    /** Separates the container from the entry inside it. */
    public static final String SEPARATOR = "!/";

    private ArchivePaths() {}

    /**
     * Whether {@code file} is a container the browser can descend into. Costs
     * a small read (the magic check) and only for names that look the part.
     */
    public static boolean isArchiveFile(Path file) {
        return !ArchiveMounts.shared().inArchive(file) && ArchiveFormat.of(file).isPresent();
    }

    /** Whether the name alone suggests an archive — no I/O, for fast listings. */
    public static boolean looksLikeArchiveFile(Path file) {
        return !ArchiveMounts.shared().inArchive(file)
                && ArchiveFormat.looksLikeArchive(file);
    }

    /** Whether {@code path} is inside a mounted archive. */
    public static boolean inArchive(Path path) {
        return ArchiveMounts.shared().inArchive(path);
    }

    /** The archive file hosting {@code path}, or empty for an ordinary file. */
    public static Optional<Path> hostArchive(Path path) {
        return ArchiveMounts.shared().hostArchive(path);
    }

    /** Whether {@code path} is the root directory of a mounted archive. */
    public static boolean isArchiveRoot(Path path) {
        return inArchive(path) && path.getParent() == null;
    }

    /**
     * The directory to open for {@code target}: the archive's root when it is
     * a container file, otherwise {@code target} unchanged. This is the one
     * call a navigation path needs to gain archive support.
     */
    public static Path enter(Path target) throws IOException {
        if (isArchiveFile(target)) return ArchiveMounts.shared().mount(target);
        return target;
    }

    /**
     * Releases everything the archive layer holds on {@code target} — the mount
     * when it is a browsed container, the mounts on every container under it
     * when it is a directory, and the extracted copies of their entries — so
     * the file itself can be moved, renamed or trashed. Without this the
     * reader's open handle blocks the rename on Windows, and on any platform
     * the mount would survive keyed on the old path, failing every later read.
     * A later browse at the new location mounts afresh.
     *
     * <p>Until the returned fence is closed, nothing re-mounts those
     * containers: a thumbnail or decode already in flight that tries to
     * revive one fails loudly instead of re-opening the file mid-move. Close
     * it once the mutation is done, whether or not it succeeded.</p>
     */
    public static ArchiveMounts.Fence releaseForMutation(Path target) {
        return releaseForMutation(ArchiveMounts.shared(), ArchiveEntryCache.shared(), target);
    }

    /**
     * {@link #releaseForMutation(Path)} over the given layer. A release that
     * throws (a provider's {@code close()} failing unchecked) lifts the fence
     * before propagating: the caller never receives it to close, and left up
     * it would refuse every later mount under {@code target} until restart.
     */
    static ArchiveMounts.Fence releaseForMutation(ArchiveMounts mounts, ArchiveEntryCache cache,
                                                  Path target) {
        ArchiveMounts.Fence fence = mounts.fence(target);
        try {
            mounts.release(target);
            cache.invalidateHostedUnder(target);
        } catch (RuntimeException | Error e) {
            fence.close();
            throw e;
        }
        return fence;
    }

    /** {@link #releaseForMutation(Path)} for each target, as one fence. */
    public static ArchiveMounts.Fence releaseForMutation(List<Path> targets) {
        return releaseForMutation(ArchiveMounts.shared(), ArchiveEntryCache.shared(), targets);
    }

    /**
     * {@link #releaseForMutation(List)} over the given layer; a target that
     * fails lifts the fences already raised for the targets before it.
     */
    static ArchiveMounts.Fence releaseForMutation(ArchiveMounts mounts, ArchiveEntryCache cache,
                                                  List<Path> targets) {
        var fences = new ArrayList<ArchiveMounts.Fence>(targets.size());
        try {
            for (Path target : targets) fences.add(releaseForMutation(mounts, cache, target));
        } catch (RuntimeException | Error e) {
            fences.forEach(ArchiveMounts.Fence::close);
            throw e;
        }
        return () -> fences.forEach(ArchiveMounts.Fence::close);
    }

    /**
     * Names that do not count when deciding whether an archive holds a single
     * folder. {@code __MACOSX} is the one that matters: a zip made by the macOS
     * Finder carries it beside the real folder, so a plain "exactly one child"
     * test would almost never fire on a Mac-made archive. The rest is the usual
     * per-directory cruft.
     *
     * <p>Deliberately only consulted here — this does not hide anything from a
     * listing, it only decides where a browse lands.</p>
     */
    private static final Set<String> IGNORED_WHEN_DESCENDING = Set.of(
            "__macosx", ".ds_store", "thumbs.db", "desktop.ini",
            ".trashes", ".spotlight-v100", ".fseventsd");

    /** Bound on the descent, so a pathological archive cannot walk forever. */
    private static final int MAX_DESCENT = 8;

    /**
     * Where a browse into {@code target} should actually land: the archive's
     * root, or — when that root holds nothing but a single folder — inside that
     * folder, repeating while the structure stays that shape.
     *
     * <p>Almost every archive made by "compress this folder" wraps its contents
     * in one directory named after the source, so opening one otherwise costs a
     * step through a level with exactly one row in it. Nothing is hidden: the
     * address bar shows the full location, and {@code ..} still walks back up
     * to the real root and then out of the container.</p>
     */
    public static Path browseRoot(Path target) throws IOException {
        Path at = enter(target);
        if (!inArchive(at)) return at;
        for (int depth = 0; depth < MAX_DESCENT; depth++) {
            Path only = solitaryFolder(at);
            if (only == null) return at;
            at = only;
        }
        return at;
    }

    /**
     * The single subfolder {@code dir} contains, or null when it holds anything
     * else — more than one entry, a file, or nothing at all.
     */
    private static Path solitaryFolder(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            // Consumed lazily and abandoned at the second non-junk name: a
            // directory with a hundred thousand entries must cost two reads
            // here, not a materialized list of all of them.
            Path candidate = null;
            for (Iterator<Path> it = children.iterator(); it.hasNext(); ) {
                Path child = it.next();
                Path name = child.getFileName();
                if (name != null && IGNORED_WHEN_DESCENDING.contains(
                        name.toString().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (candidate != null) return null;      // more than one: stop
                candidate = child;
            }
            return candidate != null && Files.isDirectory(candidate) ? candidate : null;
        } catch (IOException | RuntimeException e) {
            return null;                                 // unreadable: land here
        }
    }

    /**
     * Where {@code ..} leads from {@code dir}: normally its parent, but from an
     * archive's root it steps out to the folder holding the archive file —
     * without which a browse into a container would be a trap, since the root
     * of a mounted filesystem has no parent of its own.
     */
    public static Path parentOf(Path dir) {
        if (dir == null) return null;
        Path parent = dir.getParent();
        if (parent != null) return parent;
        return hostArchive(dir).map(Path::getParent).orElse(null);
    }

    /**
     * The displayable, round-trippable form of {@code path}:
     * {@code /discs/photos.iso!/DCIM/IMG_0001.JPG} inside an archive, the plain
     * path outside one.
     */
    public static String format(Path path) {
        Optional<Path> host = hostArchive(path);
        if (host.isEmpty()) return path.toString();
        String inside = path.toAbsolutePath().normalize().toString();
        if (inside.startsWith("/")) inside = inside.substring(1);
        return host.get() + SEPARATOR + inside;
    }

    /**
     * The path denoted by {@code text}, mounting the container when the text
     * carries a {@code !/} locator.
     *
     * <p>{@code !/} is legal in a real name, so the container is the shortest
     * prefix before a {@code !/} that is an archive file on disk: a folder named
     * {@code Help!} is walked through, not mistaken for one. Text with no such
     * prefix is a plain path.</p>
     *
     * @return empty when the container cannot be opened — including one named
     *         like an archive that no longer exists (a moved disc image, an
     *         unplugged volume) — when the text names both an archive entry
     *         and a real file (an {@code a.zip} beside an {@code a.zip!}
     *         folder), or when it is not a usable path at all; the caller
     *         reports "not a folder" as it would for any bad input
     */
    public static Optional<Path> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String trimmed = text.trim();
        try {
            int mark = archiveMark(trimmed);
            Path plain = Path.of(trimmed);
            if (mark < 0) {
                return !Files.exists(plain) && namesAMissingArchive(trimmed)
                        ? Optional.empty() : Optional.of(plain);
            }
            if (Files.exists(plain)) return Optional.empty();      // ambiguous
            Path archive = Path.of(trimmed.substring(0, mark));
            String inside = trimmed.substring(mark + SEPARATOR.length());
            Path root = ArchiveMounts.shared().mount(archive);
            return Optional.of(inside.isEmpty() ? root : root.resolve(inside));
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * Index of the first {@code !/} in {@code text} whose prefix is a regular
     * file named like an archive, or -1 when there is none.
     */
    private static int archiveMark(String text) {
        for (int mark = text.indexOf(SEPARATOR); mark >= 0;
                mark = text.indexOf(SEPARATOR, mark + 1)) {
            Path prefix;
            try {
                prefix = Path.of(text.substring(0, mark));
            } catch (InvalidPathException e) {
                continue;
            }
            if (ArchiveFormat.looksLikeArchive(prefix) && Files.isRegularFile(prefix)) {
                return mark;
            }
        }
        return -1;
    }

    /**
     * Whether some {@code !/} in {@code text} follows a name that looks like
     * an archive but does not exist on disk.
     */
    private static boolean namesAMissingArchive(String text) {
        for (int mark = text.indexOf(SEPARATOR); mark >= 0;
                mark = text.indexOf(SEPARATOR, mark + 1)) {
            try {
                Path prefix = Path.of(text.substring(0, mark));
                if (ArchiveFormat.looksLikeArchive(prefix) && !Files.exists(prefix)) return true;
            } catch (InvalidPathException e) {
                // not a path: no archive named here
            }
        }
        return false;
    }

    /**
     * Whether {@code path} names a location in an archive whose mount has been
     * closed (evicted under the cap, released), so {@link #revive} would have
     * to re-open the container — real I/O a UI thread must hand off. Cheap:
     * consults only the in-memory mount table.
     */
    public static boolean needsRevive(Path path) {
        return path != null && inArchive(path) && !path.getFileSystem().isOpen();
    }

    /**
     * The same location, on a live mount: {@code path} unchanged when its
     * archive is still open, otherwise the equivalent path in a freshly
     * re-mounted copy.
     *
     * <p>Mounts are evicted under a cap, so a path held by history, a pending
     * selection or the viewer can outlive the filesystem it came from. Reviving
     * costs a re-open of the container and is invisible to the user, which is
     * the point: navigating back into an archive browsed a while ago should
     * just work.</p>
     *
     * @throws IOException when the container cannot be re-mounted (moved,
     *         deleted, now unreadable); never hands back the dead path
     */
    public static Path revive(Path path) throws IOException {
        if (path == null || !inArchive(path)) return path;
        if (path.getFileSystem().isOpen()) return path;
        Path host = hostArchive(path).orElseThrow();
        Path root;
        try {
            root = ArchiveMounts.shared().mount(host);
        } catch (IOException e) {
            throw new IOException("its archive was closed and cannot be re-opened: "
                    + e.getMessage(), e);
        }
        String inside = path.toAbsolutePath().normalize().toString();
        if (inside.startsWith("/")) inside = inside.substring(1);
        return inside.isEmpty() ? root : root.resolve(inside);
    }

    /**
     * A name for the archive suitable as a folder title: the volume label of an
     * ISO when it has one, else the file name.
     */
    public static String displayName(Path archiveRoot) {
        Optional<Path> host = hostArchive(archiveRoot);
        if (host.isEmpty()) return String.valueOf(archiveRoot.getFileName());
        Path name = host.get().getFileName();
        return name == null ? host.get().toString() : name.toString();
    }

    /** Whether {@code path} exists and is a directory, safe for either filesystem. */
    public static boolean isDirectory(Path path) {
        try {
            return Files.isDirectory(path);
        } catch (RuntimeException e) {
            return false;                 // e.g. a closed mount after eviction
        }
    }
}
