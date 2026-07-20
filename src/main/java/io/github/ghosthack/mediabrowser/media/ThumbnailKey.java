package io.github.ghosthack.mediabrowser.media;

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
        String material = path + "\0" + mtimeMillis + "\0" + size + "\0" + maxEdge
                + "\0" + mode;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandated by the JLS
        }
    }
}
