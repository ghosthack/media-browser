package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Per-key locks, so two threads never extract the same entry twice. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private volatile Path directory;
    private long heldBytes;

    ArchiveEntryCache(long budgetBytes) {
        this.budgetBytes = budgetBytes;
    }

    public static ArchiveEntryCache shared() {
        return SHARED;
    }

    private record Copy(Path file, long bytes) {}

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
        String key = keyFor(path);
        synchronized (this) {
            Copy hit = copies.get(key);
            if (hit != null && Files.exists(hit.file())) return hit.file();
            if (hit != null) {                       // deleted behind our back
                copies.remove(key);
                heldBytes -= hit.bytes();
            }
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                synchronized (this) {                // another thread may have won
                    Copy hit = copies.get(key);
                    if (hit != null && Files.exists(hit.file())) return hit.file();
                }
                Path extracted = extract(path, key);
                long bytes = Files.size(extracted);
                synchronized (this) {
                    copies.put(key, new Copy(extracted, bytes));
                    heldBytes += bytes;
                    evictDown();
                }
                return extracted;
            }
        } finally {
            locks.remove(key, lock);
        }
    }

    /** Extracts to a sibling {@code .part} file, then moves it into place. */
    private Path extract(Path entry, String key) throws IOException {
        Path into = directory().resolve(key);
        Files.createDirectories(into);
        Extraction extraction = extraction(entry);
        Path target = into.resolve(extraction.name());
        Path partial = into.resolve(extraction.name() + ".part");
        try (InputStream in = extraction.input()) {
            Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
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
        long size;
        try {
            size = Files.size(entry);
        } catch (IOException e) {
            size = -1;
        }
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

    /** Drops least-recently-used copies until the cache is inside its budget. */
    private void evictDown() {
        if (heldBytes <= budgetBytes) return;
        var victims = new ArrayList<Map.Entry<String, Copy>>();
        long freed = 0;
        for (Map.Entry<String, Copy> candidate : copies.entrySet()) {   // eldest first
            if (heldBytes - freed <= budgetBytes) break;
            victims.add(Map.entry(candidate.getKey(), candidate.getValue()));
            freed += candidate.getValue().bytes();
        }
        for (Map.Entry<String, Copy> victim : victims) {
            copies.remove(victim.getKey());
            heldBytes -= victim.getValue().bytes();
            try {
                Files.deleteIfExists(victim.getValue().file());
                Files.deleteIfExists(victim.getValue().file().getParent());
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
