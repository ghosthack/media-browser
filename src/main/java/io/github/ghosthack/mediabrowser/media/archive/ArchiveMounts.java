package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.mediabrowser.media.archive.iso.IsoFileSystemProvider;
import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The open archives: one {@link FileSystem} per container, opened on first
 * use and shared from then on, so descending into a folder does not re-parse
 * the container and every path handed around stays valid.
 *
 * <h2>Locking</h2>
 *
 * <p>This is consulted on the hottest paths in the app — every mosaic tile
 * paint asks {@code RotationStore} for adjustments, which formats the path
 * through here, and every directory scan asks whether each file is a container
 * — so the rules are strict:</p>
 *
 * <ul>
 *   <li>A path on the default filesystem is answered with <em>no lock at all</em>
 *       (a reference comparison). That is virtually every path the app ever
 *       handles, so the common case never contends.</li>
 *   <li>The table lock is held only for map operations, never across opening or
 *       closing a container. Containers are opened outside it and published
 *       afterwards.</li>
 * </ul>
 *
 * <p>Both rules exist because breaking either froze the UI: with lookups and
 * mounting sharing one lock that mounting held across a slow open, any mount in
 * flight blocked the FX thread at its next tile paint.</p>
 *
 * <p>Mounts are capped and evicted least-recently-used, since each holds an
 * open file handle and (for ISO) a directory cache. Eviction is survivable: the
 * decode path never reads through a mount (it reads the extracted copy from
 * {@link ArchiveEntryCache}), and {@code ArchivePaths.revive} re-opens.</p>
 */
public final class ArchiveMounts {

    /** Open containers kept at once. Each is a file handle plus caches. */
    private static final int MAX_MOUNTS = 8;

    private static final ArchiveMounts SHARED = new ArchiveMounts();

    /**
     * Guards the two maps below, and nothing else. Never held while opening or
     * closing a container — see the class note.
     */
    private final Object tableLock = new Object();

    /** archive file -> its mounted filesystem, in access order for eviction. */
    private final Map<Path, FileSystem> byArchive = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * The inverse, for asking which archive an arbitrary path came from.
     *
     * <p>Outlives the mount deliberately, so a path handed out before eviction
     * can still name its container — what makes {@code ArchivePaths.revive}
     * possible. Weak keys keep that from leaking: once nothing references the
     * filesystem, the entry goes with it.</p>
     */
    private final Map<FileSystem, Path> byFileSystem = new WeakHashMap<>();

    public static ArchiveMounts shared() {
        return SHARED;
    }

    /**
     * The root directory of {@code archive}, mounting it if needed.
     *
     * @throws IOException if the container cannot be opened — a truncated zip,
     *                     a UDF-only image, an unreadable file
     */
    public Path mount(Path archive) throws IOException {
        Path key = archive.toAbsolutePath().normalize();
        synchronized (tableLock) {
            FileSystem existing = byArchive.get(key);
            if (existing != null && existing.isOpen()) return rootOf(existing);
            if (existing != null) byArchive.remove(key);      // closed elsewhere
        }

        // Opened with no lock held: this reads and parses the container, and is
        // exactly the work that must not block a tile paint.
        ArchiveFormat format = ArchiveFormat.of(key)
                .orElseThrow(() -> new IOException("not a readable archive: " + key));
        FileSystem opened = open(format, key);

        List<FileSystem> evicted;
        synchronized (tableLock) {
            FileSystem raced = byArchive.get(key);
            if (raced != null && raced.isOpen() && raced != opened) {
                // Another thread mounted it while we were opening; keep theirs.
                closeQuietly(opened);
                return rootOf(raced);
            }
            byArchive.put(key, opened);
            byFileSystem.put(opened, key);
            evicted = takeEvictable();
        }
        for (FileSystem victim : evicted) closeQuietly(victim);   // I/O, unlocked
        return rootOf(opened);
    }

    /**
     * The archive file hosting {@code path}, or empty when {@code path} is an
     * ordinary file on the default filesystem.
     */
    public Optional<Path> hostArchive(Path path) {
        if (path == null) return Optional.empty();
        FileSystem fs = path.getFileSystem();
        // The fast path, and the reason this is safe to call from the FX
        // thread: an ordinary path is settled by a reference comparison.
        if (fs == FileSystems.getDefault()) return Optional.empty();
        synchronized (tableLock) {
            return Optional.ofNullable(byFileSystem.get(fs));
        }
    }

    /** Whether {@code path} lives inside a mounted archive. */
    public boolean inArchive(Path path) {
        return hostArchive(path).isPresent();
    }

    /** Closes every mount; for shutdown and tests. */
    public void closeAll() {
        List<FileSystem> open;
        synchronized (tableLock) {
            open = new ArrayList<>(byArchive.values());
            byArchive.clear();
            // byFileSystem is left alone: paths handed out before the close must
            // still be revivable, exactly as after an eviction.
        }
        for (FileSystem fs : open) closeQuietly(fs);
    }

    private static FileSystem open(ArchiveFormat format, Path key) throws IOException {
        try {
            return switch (format) {
                case ISO -> IsoFileSystemProvider.instance().newFileSystem(key, Map.of());
                case CUE -> IsoFileSystemProvider.instance().newCueFileSystem(key);
                case ZIP -> openZip(key);
                case RAR, SEVEN_Z ->
                        StreamFileSystemProvider.instance().newFileSystem(key, format);
            };
        } catch (FileSystemAlreadyExistsException e) {
            // Lost a race with another thread opening the same container; both
            // providers key globally, so ask for the one that won.
            FileSystem existing = format == ArchiveFormat.ISO || format == ArchiveFormat.CUE
                    ? IsoFileSystemProvider.instance().mounted(key)
                    : format == ArchiveFormat.ZIP
                            ? FileSystems.getFileSystem(URI.create("jar:" + key.toUri()))
                            : StreamFileSystemProvider.instance().mounted(key);
            if (existing == null) throw new IOException("cannot open " + key, e);
            return existing;
        }
    }

    /**
     * Opens a zip <em>read-only</em>. This is not a detail: zipfs defaults to
     * read-write, so without {@code accessMode} a stray delete or write against
     * a browsed path would silently rewrite the user's archive. The browser
     * only ever reads, and the filesystem is made incapable of anything else so
     * that stays true by construction rather than by discipline.
     */
    private static FileSystem openZip(Path archive) throws IOException {
        return FileSystems.newFileSystem(archive, Map.of("accessMode", "readOnly"));
    }

    private static Path rootOf(FileSystem fs) {
        return fs.getRootDirectories().iterator().next();
    }

    /** Removes the eldest mounts over the cap and returns them, to close unlocked. */
    private List<FileSystem> takeEvictable() {
        var victims = new ArrayList<FileSystem>();
        var keys = new ArrayList<>(byArchive.keySet());          // access order
        for (Path key : keys) {
            if (byArchive.size() <= MAX_MOUNTS) break;
            FileSystem fs = byArchive.remove(key);
            if (fs != null) victims.add(fs);
        }
        return victims;
    }

    private static void closeQuietly(FileSystem fs) {
        try {
            fs.close();
        } catch (IOException | UnsupportedOperationException e) {
            // A mount that will not close is not worth failing a browse over;
            // it is released when the JVM exits.
        }
    }
}
