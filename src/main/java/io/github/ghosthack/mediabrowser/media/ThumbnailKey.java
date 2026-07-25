package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identity of a cached preview rendition: the absolute path plus the file's
 * modification time and size (so an edited file misses the cache and is
 * regenerated), the {@code maxEdge} the rendition was sized for, and the
 * {@link ThumbnailMode} it was rendered in (FIT and FILL cache separately).
 * Usable as a map key directly (record equality) and, via {@link #hash()}, as a
 * stable filename for a future on-disk cache tier — both tiers share one key.
 */
public record ThumbnailKey(Path path, long mtimeMillis, long size, int maxEdge,
                           ThumbnailMode mode) {

    public ThumbnailKey {
        path = path.toAbsolutePath().normalize();
        if (mode == null) throw new IllegalArgumentException("mode is null");
    }

    /**
     * A stable, collision-resistant hex digest of this key, suitable as a
     * cache filename. Independent of process or run, so a disk tier keyed by
     * it survives restarts and invalidates automatically when the file changes.
     */
    public String hash() {
        // Identified by the locator, not Path.toString: inside an archive the
        // latter is only the entry path ("/DCIM/IMG_0001.JPG"), which two
        // different discs would share — a disk cache tier keyed on it would
        // serve one disc's thumbnail for the other's file. In-memory record
        // equality is already safe, since an archive Path carries its
        // filesystem identity.
        String material = ArchivePaths.format(path) + "\0" + mtimeMillis + "\0" + size
                + "\0" + maxEdge + "\0" + mode;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandated by the JLS
        }
    }
}
