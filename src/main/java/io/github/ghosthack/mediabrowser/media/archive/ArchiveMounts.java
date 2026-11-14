package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.mediabrowser.media.archive.iso.IsoFileSystemProvider;
import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

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

    /**
     * Mounts taken out of {@link #byArchive} whose close has not finished,
     * with the archive each belongs to. Until {@code close()} returns, the
     * provider still hands the dying filesystem to anyone re-opening that
     * archive, so {@link #mount} waits these out instead of adopting one.
     */
    private final Map<FileSystem, Closing> closing = new IdentityHashMap<>();

    private record Closing(Path archive, CountDownLatch done) {}

    /**
     * Files and folders being moved, renamed or trashed (see {@link #fence});
     * a container at or under one is not mounted until its fence is lifted.
     * A list, not a set: two moves may fence the same path.
     */
    private final List<Path> fences = new ArrayList<>();

    /** Mount attempts before a container that keeps closing fails loudly. */
    private static final int MAX_MOUNT_ATTEMPTS = 8;

    /** Runs just before each close; a no-op outside tests. */
    private final Consumer<FileSystem> beforeClose;

    /** Runs when a mount starts waiting for a close; a no-op outside tests. */
    private final Runnable awaitingClose;

    /** Runs just before each mount opens its container; a no-op outside tests. */
    volatile Consumer<Path> beforeOpen = key -> {};

    private ArchiveMounts() {
        this(fs -> {}, () -> {});
    }

    /** For tests that must act in the middle of a close. */
    ArchiveMounts(Consumer<FileSystem> beforeClose, Runnable awaitingClose) {
        this.beforeClose = beforeClose;
        this.awaitingClose = awaitingClose;
    }

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
        ArchiveFormat format = null;
        for (int attempt = 1; ; attempt++) {
            CountDownLatch pending;
            synchronized (tableLock) {
                FileSystem existing = byArchive.get(key);
                if (existing != null && existing.isOpen()) return rootOf(existing);
                if (existing != null) byArchive.remove(key);      // closed elsewhere
                if (isFenced(key)) throw beingMoved(key);
                pending = closingOf(key);
            }
            if (pending == null) {
                if (format == null) {
                    ArchiveFormat known = ArchiveFormat.of(key).orElse(null);
                    if (known == null) throw new IOException("not a readable archive: " + key);
                    format = known;
                }
                // Opened with no lock held: this reads and parses the container,
                // and is exactly the work that must not block a tile paint.
                beforeOpen.accept(key);
                FileSystem opened = open(format, key);
                if (opened != null) {
                    List<FileSystem> evicted = null;
                    FileSystem raced = null;
                    boolean fenced;
                    boolean discard;
                    synchronized (tableLock) {
                        // A move may have fenced the container while it opened.
                        fenced = isFenced(key);
                        // A close may have begun since the check above, and the
                        // provider hands out a closing mount until it finishes.
                        pending = closingOf(key);
                        boolean registered = false;
                        if (!fenced && pending == null && opened.isOpen()) {
                            raced = byArchive.get(key);
                            if (raced == null || !raced.isOpen() || raced == opened) {
                                raced = null;
                                byArchive.put(key, opened);
                                byFileSystem.put(opened, key);
                                evicted = takeEvictable();
                                registered = true;
                            }
                        }
                        // Opened here but left out of the table (fenced, or the
                        // old mount's close still pending): nothing would track
                        // it, so a throw below would strand it open in the
                        // provider, holding the file and keyed on its path.
                        // Close it, as closing, so no mount adopts it meanwhile;
                        // never a mount another thread owns or is closing.
                        discard = !registered && raced == null && opened.isOpen()
                                && !closing.containsKey(opened)
                                && !byArchive.containsValue(opened);
                        if (discard) closing.put(opened, new Closing(key, new CountDownLatch(1)));
                    }
                    if (discard) {
                        closeTaken(List.of(opened));
                        if (fenced) throw beingMoved(key);
                    }
                    if (raced != null) {
                        // Another thread mounted it while we were opening; keep theirs.
                        closeQuietly(opened);
                        return rootOf(raced);
                    }
                    if (evicted != null) {
                        closeTaken(evicted);                     // I/O, unlocked
                        return rootOf(opened);
                    }
                }
            }
            if (attempt >= MAX_MOUNT_ATTEMPTS) {
                throw new IOException("cannot open " + key + ": it was closed "
                        + attempt + " times while mounting");
            }
            if (pending != null) {
                awaitingClose.run();
                try {
                    pending.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted waiting for " + key + " to close");
                }
            }
        }
    }

    /** A mutation in progress on a container; lifted by {@link #close}. */
    public interface Fence extends AutoCloseable {
        @Override void close();
    }

    /**
     * Refuses to mount {@code target} — or, for a directory, any container
     * under it — until the returned fence is closed. For a move, rename or
     * trash: between releasing a container's mount and moving the file, an
     * in-flight thumbnail or decode would otherwise revive the mount at the
     * old path, holding the file open (Windows refuses the move) or leaving a
     * mount keyed on a path that no longer exists. Such a mount fails loudly
     * ("being moved") instead. Raise the fence before {@link #release}.
     */
    public Fence fence(Path target) {
        Path key = target.toAbsolutePath().normalize();
        synchronized (tableLock) {
            fences.add(key);
        }
        var lifted = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!lifted.compareAndSet(false, true)) return;
            synchronized (tableLock) {
                fences.remove(key);
            }
        };
    }

    /** Whether a move fences {@code archive}; under the table lock. */
    private boolean isFenced(Path archive) {
        for (Path fence : fences) {
            if (archive.startsWith(fence)) return true;
        }
        return false;
    }

    private static IOException beingMoved(Path archive) {
        return new IOException("cannot open " + archive + ": it is being moved");
    }

    /** The close in progress on {@code archive}'s mount, if any; under the table lock. */
    private CountDownLatch closingOf(Path archive) {
        for (Closing c : closing.values()) {
            if (c.archive().equals(archive)) return c.done();
        }
        return null;
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

    /**
     * Closes the mount on {@code target} when it is a mounted container — and,
     * when it is a directory, every mount on a container under it. For a caller
     * about to move, rename or trash the file itself: the reader's open handle
     * blocks the rename on Windows, and on any platform the surviving mount
     * would stay keyed on the old path, failing every later read. Releasing is
     * survivable exactly like an eviction: paths handed out earlier still name
     * their container, and a browse at the file's new location mounts afresh.
     */
    public void release(Path target) {
        if (target == null) return;
        Path key = target.toAbsolutePath().normalize();
        List<FileSystem> victims = new ArrayList<>();
        synchronized (tableLock) {
            var mounts = byArchive.entrySet().iterator();
            while (mounts.hasNext()) {
                var mount = mounts.next();
                if (mount.getKey().startsWith(key)) {
                    victims.add(mount.getValue());
                    closing.put(mount.getValue(), new Closing(mount.getKey(), new CountDownLatch(1)));
                    mounts.remove();
                }
            }
            // byFileSystem is left alone, exactly as after an eviction.
        }
        closeTaken(victims);                                      // I/O, unlocked
    }

    /**
     * Runs {@code action} while holding the mount table's lock; for tests that
     * must prove a path does not wait on it.
     */
    void whileHoldingTableLock(Runnable action) {
        synchronized (tableLock) {
            action.run();
        }
    }

    /** Closes every mount; for shutdown and tests. */
    public void closeAll() {
        List<FileSystem> open = new ArrayList<>();
        synchronized (tableLock) {
            for (var mount : byArchive.entrySet()) {
                open.add(mount.getValue());
                closing.put(mount.getValue(), new Closing(mount.getKey(), new CountDownLatch(1)));
            }
            byArchive.clear();
            // byFileSystem is left alone: paths handed out before the close must
            // still be revivable, exactly as after an eviction.
        }
        closeTaken(open);
    }

    /**
     * Opens {@code key}, or adopts the provider's mount when another thread
     * won the race to open it; null when that mount vanished in between (its
     * close completed), for the caller to try again.
     */
    private static FileSystem open(ArchiveFormat format, Path key) throws IOException {
        try {
            return switch (format) {
                case ISO -> IsoFileSystemProvider.instance().newFileSystem(key, Map.of());
                case CUE -> IsoFileSystemProvider.instance().newCueFileSystem(key);
                case ZIP, RAR, SEVEN_Z, PDF, EPUB ->
                        StreamFileSystemProvider.instance().newFileSystem(key, format);
            };
        } catch (FileSystemAlreadyExistsException e) {
            // Lost a race with another thread opening the same container; both
            // providers key globally, so ask for the one that won.
            FileSystem existing = format == ArchiveFormat.ISO || format == ArchiveFormat.CUE
                    ? IsoFileSystemProvider.instance().mounted(key)
                    : StreamFileSystemProvider.instance().mounted(key);
            return existing;
        }
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
            if (fs != null) {
                victims.add(fs);
                closing.put(fs, new Closing(key, new CountDownLatch(1)));
            }
        }
        return victims;
    }

    /**
     * Closes mounts taken out of the table (each registered in
     * {@link #closing}), then lets any mount waiting on them proceed.
     */
    private void closeTaken(List<FileSystem> victims) {
        for (FileSystem victim : victims) {
            try {
                closeQuietly(victim);
            } finally {
                Closing done;
                synchronized (tableLock) {
                    done = closing.remove(victim);
                }
                if (done != null) done.done().countDown();
            }
        }
    }

    private void closeQuietly(FileSystem fs) {
        beforeClose.accept(fs);
        try {
            fs.close();
        } catch (IOException | UnsupportedOperationException e) {
            // A mount that will not close is not worth failing a browse over;
            // it is released when the JVM exits.
        }
    }
}
