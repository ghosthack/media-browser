package io.github.ghosthack.mediabrowser.media.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Iterator;
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
     * @return empty when the container cannot be opened or the text is not a
     *         usable path at all; the caller reports "not a folder" as it
     *         would for any bad input
     */
    public static Optional<Path> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String trimmed = text.trim();
        int mark = trimmed.indexOf(SEPARATOR);
        try {
            if (mark < 0) return Optional.of(Path.of(trimmed));
            Path archive = Path.of(trimmed.substring(0, mark));
            String inside = trimmed.substring(mark + SEPARATOR.length());
            Path root = ArchiveMounts.shared().mount(archive);
            return Optional.of(inside.isEmpty() ? root : root.resolve(inside));
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
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
     */
    public static Path revive(Path path) {
        if (path == null || !inArchive(path)) return path;
        if (path.getFileSystem().isOpen()) return path;
        return parse(format(path)).orElse(path);
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
