package io.github.ghosthack.mediabrowser.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Distinguishes Photo CD ImagePac rasters from the control records that share
 * their {@code .PCD} extension.
 */
public final class PhotoCdImagePack {
    private static final byte[] MAGIC =
            "PCD_IPI".getBytes(StandardCharsets.US_ASCII);
    private static final long MAGIC_OFFSET = 0x800;

    private PhotoCdImagePack() {}

    /** Whether the filename uses the overloaded Photo CD suffix. */
    public static boolean hasPcdExtension(Path file) {
        Path name = file.getFileName();
        if (name == null) return false;
        String value = name.toString();
        int dot = value.lastIndexOf('.');
        return dot > 0
                && "pcd".equals(value.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Whether {@code file} carries the ImagePac marker required by the Photo CD
     * decoder. Unreadable and truncated files decline rather than masquerading
     * as images.
     */
    public static boolean isImagePack(Path file) {
        if (!hasPcdExtension(file)) return false;
        ByteBuffer marker = ByteBuffer.allocate(MAGIC.length);
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            if (channel.size() < MAGIC_OFFSET + MAGIC.length) return false;
            channel.position(MAGIC_OFFSET);
            while (marker.hasRemaining()) {
                int read = channel.read(marker);
                if (read < 0) return false;
                if (read == 0) return false;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return marker.flip().mismatch(ByteBuffer.wrap(MAGIC)) == -1;
    }
}
