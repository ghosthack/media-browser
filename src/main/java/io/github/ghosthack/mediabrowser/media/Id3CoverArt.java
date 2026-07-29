package io.github.ghosthack.mediabrowser.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Cheap ID3v2 attached-picture detection for folder-preview candidate scans.
 *
 * <p>This deliberately does not decode the picture or materialize the tag:
 * it reads the ten-byte tag header, walks frame headers with channel seeks,
 * and stops at the first embedded {@code APIC} (v2.3/v2.4) or {@code PIC}
 * (v2.2) frame. URL-only picture frames ({@code -->}) do not qualify because
 * the media decoder has no embedded pixels to render. Malformed, compressed,
 * encrypted, and unreadable tags decline quietly; folder decoration is
 * best-effort and must never turn browsing into an error.</p>
 */
final class Id3CoverArt {

    private static final int TAG_HEADER_BYTES = 10;

    private Id3CoverArt() {
    }

    static boolean hasEmbeddedPicture(Path file) {
        try (SeekableByteChannel input =
                     Files.newByteChannel(file, StandardOpenOption.READ)) {
            return hasEmbeddedPicture(input);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean hasEmbeddedPicture(SeekableByteChannel input) throws IOException {
        byte[] tagHeader = read(input, TAG_HEADER_BYTES);
        if (tagHeader == null
                || tagHeader[0] != 'I' || tagHeader[1] != 'D' || tagHeader[2] != '3') {
            return false;
        }

        int version = unsigned(tagHeader[3]);
        if (version < 2 || version > 4) return false;

        int tagSize = syncSafeInt(tagHeader, 6);
        if (tagSize < 0) return false;
        long tagEnd = TAG_HEADER_BYTES + (long) tagSize;
        if (tagEnd > input.size()) return false;

        int tagFlags = unsigned(tagHeader[5]);
        if (version == 2 && (tagFlags & 0x40) != 0) {
            return false; // ID3v2.2 whole-tag compression
        }

        long position = TAG_HEADER_BYTES;
        if (version >= 3 && (tagFlags & 0x40) != 0) {
            input.position(position);
            byte[] sizeBytes = read(input, 4);
            if (sizeBytes == null) return false;
            long extendedSize;
            if (version == 3) {
                extendedSize = 4L + unsignedInt(sizeBytes, 0);
            } else {
                int size = syncSafeInt(sizeBytes, 0);
                if (size < 6) return false;
                extendedSize = size;
            }
            position += extendedSize;
            if (position > tagEnd) return false;
        }

        int frameHeaderBytes = version == 2 ? 6 : 10;
        int idBytes = version == 2 ? 3 : 4;
        while (position + frameHeaderBytes <= tagEnd) {
            input.position(position);
            byte[] frameHeader = read(input, frameHeaderBytes);
            if (frameHeader == null || isPadding(frameHeader, idBytes)) return false;
            if (!isFrameId(frameHeader, idBytes)) return false;

            long frameSize = switch (version) {
                case 2 -> unsigned24(frameHeader, 3);
                case 3 -> unsignedInt(frameHeader, 4);
                case 4 -> syncSafeInt(frameHeader, 4);
                default -> -1;
            };
            if (frameSize <= 0) return false;

            long bodyStart = position + frameHeaderBytes;
            long next = bodyStart + frameSize;
            if (next < bodyStart || next > tagEnd) return false;

            boolean picture = version == 2
                    ? matches(frameHeader, "PIC")
                    : matches(frameHeader, "APIC");
            if (picture && isUsablePictureFrame(input, version, frameHeader, bodyStart, frameSize)) {
                return true;
            }
            position = next;
        }
        return false;
    }

    private static boolean isUsablePictureFrame(SeekableByteChannel input, int version,
                                                byte[] header, long bodyStart, long bodySize)
            throws IOException {
        int prefix = 0;
        if (version == 3) {
            int formatFlags = unsigned(header[9]);
            if ((formatFlags & 0xC0) != 0) return false; // compressed or encrypted
            if ((formatFlags & 0x20) != 0) prefix++;     // grouping identity
        } else if (version == 4) {
            int formatFlags = unsigned(header[9]);
            if ((formatFlags & 0x0C) != 0) return false; // compressed or encrypted
            if ((formatFlags & 0x40) != 0) prefix++;     // grouping identity
            if ((formatFlags & 0x01) != 0) prefix += 4;  // data-length indicator
        }

        int leadingBytes = version == 2 ? 4 : 5;
        if (bodySize < prefix + leadingBytes) return false;
        input.position(bodyStart + prefix);
        byte[] leading = read(input, leadingBytes);
        if (leading == null) return false;

        // APIC: encoding, MIME string; PIC: encoding, three-byte image format.
        // In both versions "-->" denotes a linked image rather than embedded data.
        boolean linked = leading[1] == '-' && leading[2] == '-'
                && leading[3] == '>' && (version == 2 || leading[4] == 0);
        return !linked;
    }

    private static byte[] read(SeekableByteChannel input, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        while (buffer.hasRemaining()) {
            int read = input.read(buffer);
            if (read <= 0) return null;
        }
        return buffer.array();
    }

    private static boolean matches(byte[] bytes, String ascii) {
        if (bytes.length < ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (bytes[i] != ascii.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isPadding(byte[] header, int idBytes) {
        for (int i = 0; i < idBytes; i++) {
            if (header[i] != 0) return false;
        }
        return true;
    }

    private static boolean isFrameId(byte[] header, int idBytes) {
        for (int i = 0; i < idBytes; i++) {
            int value = unsigned(header[i]);
            if (!((value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static int syncSafeInt(byte[] bytes, int offset) {
        int out = 0;
        for (int i = 0; i < 4; i++) {
            int value = unsigned(bytes[offset + i]);
            if ((value & 0x80) != 0) return -1;
            out = (out << 7) | value;
        }
        return out;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) unsigned(bytes[offset]) << 24)
                | ((long) unsigned(bytes[offset + 1]) << 16)
                | ((long) unsigned(bytes[offset + 2]) << 8)
                | unsigned(bytes[offset + 3]);
    }

    private static int unsigned24(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 16)
                | (unsigned(bytes[offset + 1]) << 8)
                | unsigned(bytes[offset + 2]);
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
