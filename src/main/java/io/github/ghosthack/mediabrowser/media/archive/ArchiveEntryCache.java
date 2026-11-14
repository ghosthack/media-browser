package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Decoder-ready copies of archive entries, so the native decoders keep working
 * unchanged.
 *
 * <p>Every backend — bundled FFmpeg, LibRaw, Apple ImageIO, Windows WIC —
 * ultimately opens a file <em>by OS path</em> ({@code file.toString()} handed
 * to native code). An entry inside any mounted container has no OS path, so the one
 * honest way to decode it is to write the bytes somewhere real first. A mounted
 * entry may also offer a presentation stream when its physical bytes are a
 * container-native encoding (for example PDF/JBIG2 or a zlib-wrapped JPEG);
 * that private rendition is materialized here without changing the entry's NIO
 * bytes or identity. Doing
 * it at this single choke point keeps all six backends free of archive awareness
 * and keeps behaviour identical inside and outside a container.</p>
 *
 * <p>Copies live in a per-run temp directory, deleted on exit; they are not
 * reused across runs, since validating a stale copy against its container costs
 * about as much as re-extracting it. The cache is bounded by total bytes and
 * evicts least-recently-used. A file that will not delete — Windows refuses
 * while a decoder still holds it open — is simply left for the exit sweep
 * rather than failing the extraction that triggered eviction.</p>
 */
public final class ArchiveEntryCache {

    /** Default ceiling on extracted bytes held at once. */
    private static final long DEFAULT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024;

    private static final ArchiveEntryCache SHARED = new ArchiveEntryCache(DEFAULT_BUDGET_BYTES);

    private final long budgetBytes;

    /** cache key -> extracted copy, access-ordered for eviction. */
    private final Map<String, Copy> copies = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Per-key locks, so two threads never extract the same entry at once.
     * Counted: a lock stays mapped while anyone holds or waits on it, so a
     * later caller always queues on the same lock, and leaves with its last
     * user, success or failure.
     */
    private final ConcurrentHashMap<String, KeyLock> locks = new ConcurrentHashMap<>();

    private static final class KeyLock {
        int users;                          // guarded by the map's compute
    }

    /** Keys with a lock mapped; for tests. */
    int lockedKeys() {
        return locks.size();
    }

    /** Wraps each copy's source stream; identity outside tests. */
    private final UnaryOperator<InputStream> copyTap;

    /** Runs after each revive, before the key is read; a no-op outside tests. */
    volatile Consumer<Path> afterRevive = revived -> {};

    /** Runs after each copy is stored; a no-op outside tests. */
    private final Consumer<Path> afterStore;

    /** Numbers each extraction's folder. */
    private final AtomicLong extractions = new AtomicLong();

    private volatile Path directory;
    private long heldBytes;

    /**
     * Key of the newest copy larger than the whole budget, or null. It does
     * not count against the budget until a newer oversized copy displaces it:
     * a long video is materialized separately for probe, metadata and
     * playback, and must not be deleted by the next sibling thumbnail.
     */
    private String oversized;

    ArchiveEntryCache(long budgetBytes) {
        this(budgetBytes, UnaryOperator.identity());
    }

    /** For tests that need to act in the middle of a copy. */
    ArchiveEntryCache(long budgetBytes, UnaryOperator<InputStream> copyTap) {
        this(budgetBytes, copyTap, stored -> {});
    }

    /** For tests that need to act once a copy is stored, before its lock is let go. */
    ArchiveEntryCache(long budgetBytes, UnaryOperator<InputStream> copyTap, Consumer<Path> afterStore) {
        this.budgetBytes = budgetBytes;
        this.copyTap = copyTap;
        this.afterStore = afterStore;
    }

    public static ArchiveEntryCache shared() {
        return SHARED;
    }

    private record Copy(Path file, long bytes, Path host) {}

    /**
     * A real OS path suitable for decoding {@code path}: {@code path} itself
     * when it is already an ordinary file, otherwise an extracted copy or the
     * entry's private presentation rendition.
     *
     * <p>An ordinary copy keeps the entry's original file name, because the
     * backends read it: the FFM facade routes camera RAW by extension and
     * FFmpeg's demuxer probe is extension-hinted. A presentation rendition uses
     * its truthful standalone extension (for example {@code .png}).</p>
     */
    public Path materialize(Path path) throws IOException {
        if (!ArchivePaths.inArchive(path)) return path;
        // The path may outlive its mount: eviction past the cap, a move's
        // release() or shutdown closes it while a decode still holds the path.
        Keyed keyed = keyedLive(path);
        path = keyed.path();
        String key = keyed.key();
        Path host = ArchivePaths.hostArchive(path).orElse(null);
        synchronized (this) {
            Copy hit = copies.get(key);
            if (hit != null && Files.exists(hit.file())) return hit.file();
            if (hit != null) forget(key);            // deleted behind our back
        }
        KeyLock lock = locks.compute(key, (k, held) -> {
            KeyLock using = held == null ? new KeyLock() : held;
            using.users++;
            return using;
        });
        try {
            return materializeLocked(path, key, host, lock);
        } finally {
            locks.computeIfPresent(key, (k, held) -> --held.users == 0 ? null : held);
        }
    }

    private Path materializeLocked(Path path, String key, Path host, KeyLock lock)
            throws IOException {
        synchronized (lock) {
            synchronized (this) {                    // another thread may have won
                Copy hit = copies.get(key);
                if (hit != null && Files.exists(hit.file())) return hit.file();
            }
            Extracted done = extractRemounting(path, key);
            Path extracted = done.file();
            long bytes = done.bytes();
            synchronized (this) {
                Copy replaced = copies.put(key, new Copy(extracted, bytes, host));
                if (replaced != null) heldBytes -= replaced.bytes();
                heldBytes += bytes;
                if (bytes > budgetBytes) oversized = key;
                evictDown(key);
            }
            afterStore.accept(extracted);
            return extracted;
        }
    }

    private record Keyed(Path path, String key) {}

    /**
     * {@code path} on a live mount, with its cache key. The key reads the
     * entry's size, so a close between the revive and that read re-mounts
     * again, bounded like {@link #extractRemounting}.
     */
    private Keyed keyedLive(Path path) throws IOException {
        Path current = path;
        for (int attempt = 1; ; attempt++) {
            current = ArchivePaths.revive(current);
            afterRevive.accept(current);
            Exception closure;
            try {
                return new Keyed(current, keyFor(current));
            } catch (ClosedFileSystemException e) {
                closure = e;
            } catch (IOException e) {
                // The ISO reader reports a closed image as a plain IOException;
                // judged by the mount, exactly as in extractRemounting.
                if (current.getFileSystem().isOpen()) throw e;
                closure = e;
            }
            if (attempt >= MAX_EXTRACT_ATTEMPTS) {
                throw new IOException("cannot extract " + ArchivePaths.format(path)
                        + ": its archive closed " + attempt + " times while opening it", closure);
            }
        }
    }

    /** Copies attempted when the mount keeps closing under them. */
    private static final int MAX_EXTRACT_ATTEMPTS = 3;

    /**
     * {@link #extract}, re-mounting when the archive is closed under the read
     * (another thread's eviction, release or closeAll between the revive above
     * and the copy), up to {@value #MAX_EXTRACT_ATTEMPTS} copies. Re-opening the
     * same container is the documented eviction contract, not a fallback; a
     * container that keeps closing or cannot be re-mounted fails loudly.
     *
     * <p>The readers report a close mid-read as a plain {@link IOException}
     * ("ZIP member stream is closed", "ISO image is closed", ...), so closure is
     * judged by the mount itself having closed, never by the message; any other
     * read failure propagates untouched.</p>
     */
    private Extracted extractRemounting(Path entry, String key) throws IOException {
        Path current = entry;
        for (int attempt = 1; ; attempt++) {
            Exception closure;
            try {
                return extract(current, key);
            } catch (ClosedFileSystemException e) {
                closure = e;
            } catch (IOException e) {
                if (current.getFileSystem().isOpen()) throw e;
                closure = e;
            }
            if (attempt >= MAX_EXTRACT_ATTEMPTS) {
                throw new IOException("cannot extract " + ArchivePaths.format(entry)
                        + ": its archive closed " + attempt + " times during extraction", closure);
            }
            current = ArchivePaths.revive(current);        // throws when re-mounting fails
        }
    }

    /**
     * Extracts to a sibling {@code .part} file, then moves it into place, in a
     * folder of its own: an evicted copy a decoder still holds open will not
     * delete on Windows, and re-extracting over it would fail; nor may two
     * extractions of one entry ever share a {@code .part} file. A failure
     * removes that folder with whatever it holds.
     */
    private Extracted extract(Path entry, String key) throws IOException {
        Path into = directory().resolve(key + "-" + extractions.incrementAndGet())
                .toAbsolutePath().normalize();
        Files.createDirectories(into);
        try {
            return extractInto(into, entry);
        } catch (IOException | RuntimeException e) {
            try (var leftovers = Files.list(into)) {
                for (Path leftover : leftovers.toList()) Files.deleteIfExists(leftover);
                Files.deleteIfExists(into);
            } catch (IOException | UncheckedIOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private record Extracted(Path file, long bytes) {}

    private Extracted extractInto(Path into, Path entry) throws IOException {
        Extraction extraction = extraction(entry);
        Path target;
        Path partial;
        try {
            String name = safeFileName(extraction.name());
            target = resolveUnder(into, name, entry);
            partial = resolveUnder(into, name + ".part", entry);
            if (!into.equals(target.getParent()) || !into.equals(partial.getParent())) {
                throw new IOException("refusing to extract " + ArchivePaths.format(entry)
                        + " outside the cache: " + target);
            }
        } catch (IOException | RuntimeException e) {
            extraction.input().close();
            throw e;
        }
        try (InputStream in = copyTap.apply(extraction.input())) {
            Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Extracted(target, Files.size(target));
    }

    /**
     * {@code name} under {@code into}. A name the OS still rejects after
     * {@link #safeFileName} (an unpaired surrogate the filesystem encoding
     * cannot map, a Windows-only rule) fails as an {@link IOException}, which
     * the decode path reports, never as an unchecked
     * {@link InvalidPathException} escaping it.
     */
    static Path resolveUnder(Path into, String name, Path entry) throws IOException {
        try {
            return into.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("cannot extract " + ArchivePaths.format(entry)
                    + ": its name is not a valid file name here (" + e.getMessage() + ")", e);
        }
    }

    /** Characters that separate or are illegal in a file name on some OS. */
    private static final String UNSAFE_NAME_CHARS = "/\\:*?\"<>|";

    /**
     * Windows device names, which name a device rather than a file whatever
     * extension follows ({@code COM1.jpg}) on Windows 10 and earlier.
     */
    private static final Set<String> RESERVED_STEMS = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "COM\u00B9", "COM\u00B2", "COM\u00B3",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "LPT\u00B9", "LPT\u00B2", "LPT\u00B3");

    /**
     * {@code name} as one plain file name, safe to resolve under the cache on
     * any OS: separators, drive colons and Windows-illegal characters become
     * {@code _}, keeping the extension the backends route on. An entry name is
     * archive data, so "..\\..\\x.jpg" must not climb out of the cache.
     *
     * <p>Windows also strips trailing dots and spaces from a name (so
     * {@code ...} would name the folder holding it) and maps device names
     * such as {@code COM1.jpg} to a device; trailing dots and spaces become
     * {@code _}, and a device stem gains a leading {@code _}.</p>
     *
     * @throws IOException for a name that is nothing but {@code .} or {@code ..}
     */
    static String safeFileName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IOException("archive entry has no usable file name: \"" + name + "\"");
        }
        var safe = new StringBuilder(name.length() + 1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            safe.append(c < 0x20 || UNSAFE_NAME_CHARS.indexOf(c) >= 0 ? '_' : c);
        }
        for (int i = safe.length() - 1; i >= 0; i--) {
            char c = safe.charAt(i);
            if (c != '.' && c != ' ') break;
            safe.setCharAt(i, '_');
        }
        int dot = safe.indexOf(".");
        String stem = (dot < 0 ? safe.toString() : safe.substring(0, dot)).stripTrailing();
        if (RESERVED_STEMS.contains(stem.toUpperCase(Locale.ROOT))) safe.insert(0, '_');
        return safe.toString();
    }

    private record Extraction(String name, InputStream input) {}

    private static Extraction extraction(Path entry) throws IOException {
        if (entry.getFileSystem().provider() instanceof StreamFileSystemProvider provider) {
            var presentation = provider.openPresentation(entry);
            if (presentation.isPresent()) {
                String sourceName = entry.getFileName() == null
                        ? "entry" : entry.getFileName().toString();
                return new Extraction(replaceExtension(
                        sourceName, presentation.get().extension()),
                        presentation.get().input());
            }
        }
        Path name = entry.getFileName();
        return new Extraction(name == null ? "entry" : name.toString(),
                Files.newInputStream(entry));
    }

    private static String replaceExtension(String name, String extension) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "." + extension;
    }

    /**
     * Identity of an extracted copy: the container, the entry path inside it,
     * and the entry's size, so a different archive with an identically-named
     * entry never collides.
     */
    private static String keyFor(Path entry) throws IOException {
        String host = ArchivePaths.hostArchive(entry).map(Path::toString).orElse("");
        long size = Files.size(entry);
        String material = host + "\0" + entry.toAbsolutePath().normalize() + "\0" + size;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private Path directory() throws IOException {
        Path existing = directory;
        if (existing != null) return existing;
        synchronized (this) {
            if (directory == null) {
                Path created = Files.createTempDirectory("media-browser-archive-");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(created),
                        "archive-cache-cleanup"));
                directory = created;
            }
            return directory;
        }
    }

    /** Drops {@code key}'s bookkeeping (not its file); under the cache lock. */
    private void forget(String key) {
        Copy gone = copies.remove(key);
        if (gone != null) heldBytes -= gone.bytes();
        if (key.equals(oversized)) oversized = null;
    }

    /**
     * Drops least-recently-used copies until the cache is inside its budget,
     * never {@code keep} — the copy about to be returned — nor the newest
     * oversized copy, which is outside the budget (see {@link #oversized}).
     * The disk held is therefore at most the budget plus one oversized copy.
     */
    private void evictDown(String keep) {
        Copy exempt = oversized == null ? null : copies.get(oversized);
        long budgeted = heldBytes - (exempt == null ? 0 : exempt.bytes());
        if (budgeted <= budgetBytes) return;
        var victims = new ArrayList<Map.Entry<String, Copy>>();
        long freed = 0;
        for (Map.Entry<String, Copy> candidate : copies.entrySet()) {   // eldest first
            if (budgeted - freed <= budgetBytes) break;
            if (candidate.getKey().equals(keep) || candidate.getKey().equals(oversized)) continue;
            victims.add(Map.entry(candidate.getKey(), candidate.getValue()));
            freed += candidate.getValue().bytes();
        }
        for (Map.Entry<String, Copy> victim : victims) {
            forget(victim.getKey());
            try {
                Files.deleteIfExists(victim.getValue().file());
                Files.deleteIfExists(victim.getValue().file().getParent());
            } catch (IOException e) {
                // Still open in a decoder (Windows). The exit sweep gets it.
            }
        }
    }

    /**
     * Drops the extracted copies of every entry hosted by {@code target} — or,
     * for a directory, by any container under it. The copies hold no handle on
     * the container, but once it moves they are keyed on its old path and would
     * only pin the byte budget until eviction; a re-browse at the new location
     * extracts afresh under new keys either way.
     */
    public synchronized void invalidateHostedUnder(Path target) {
        if (target == null) return;
        Path key = target.toAbsolutePath().normalize();
        var entries = copies.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            Copy copy = entry.getValue();
            if (copy.host() == null || !copy.host().startsWith(key)) continue;
            entries.remove();
            heldBytes -= copy.bytes();
            if (entry.getKey().equals(oversized)) oversized = null;
            try {
                Files.deleteIfExists(copy.file());
                Files.deleteIfExists(copy.file().getParent());
            } catch (IOException e) {
                // Still open in a decoder (Windows). The exit sweep gets it.
            }
        }
    }

    /** Deletes everything the cache extracted; for shutdown and tests. */
    public synchronized void clear() {
        Path dir = directory;
        copies.clear();
        heldBytes = 0;
        oversized = null;
        if (dir != null) deleteTree(dir);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> deepestFirst = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .toList();
            for (Path path : deepestFirst) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Best effort: the OS clears the temp directory eventually.
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // Nothing better to do while exiting.
        }
    }
}
