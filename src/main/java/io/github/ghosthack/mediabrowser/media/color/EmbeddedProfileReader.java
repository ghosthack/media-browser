package io.github.ghosthack.mediabrowser.media.color;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.jpeg.JpegSegmentReader;
import com.drew.imaging.jpeg.JpegSegmentType;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import io.github.ghosthack.mediabrowser.media.ColorProfile;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Metadata-only embedded-ICC readers; no pixel data is decoded. */
public final class EmbeddedProfileReader {

    private static final int MAX_PROFILE_BYTES = 64 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

    private EmbeddedProfileReader() {}

    static Optional<ColorProfile> read(Path file) {
        String ext = extension(file);
        try {
            byte[] bytes = switch (ext) {
                case "jpg", "jpeg", "jpe", "jfif" -> jpeg(file);
                case "png", "apng" -> png(file);
                case "webp" -> webp(file);
                case "heic", "heif", "avif" -> bmffIcc(file);
                case "tif", "tiff", "psd" -> metadataExtractorBlob(file);
                default -> null;
            };
            return ColorProfile.parse(bytes);
        } catch (IOException | RuntimeException ex) {
            // Color management is additive. A malformed/unsupported metadata
            // envelope must never turn an otherwise decodable still into an error.
            return Optional.empty();
        }
    }

    /** Reassembles APP2 ICC_PROFILE chunks by their explicit sequence number. */
    private static byte[] jpeg(Path file) throws IOException {
        com.drew.imaging.jpeg.JpegSegmentData data;
        try {
            data = JpegSegmentReader.readSegments(file.toFile(),
                    java.util.List.of(JpegSegmentType.APP2));
        } catch (com.drew.imaging.jpeg.JpegProcessingException ex) {
            return null;
        }
        return assembleJpegSegments(data.getSegments(JpegSegmentType.APP2));
    }

    /**
     * Extracts ICC APP2 data from JPEG bytes already read by TurboJPEG. This is
     * the mosaic hot-path seam: color inspection adds no second file read.
     */
    public static Optional<ColorProfile> readJpeg(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff
                || (jpeg[1] & 0xff) != 0xd8) return Optional.empty();
        java.util.ArrayList<byte[]> segments = new java.util.ArrayList<>();
        int position = 2;
        while (position + 4 <= jpeg.length) {
            while (position < jpeg.length && (jpeg[position] & 0xff) == 0xff) position++;
            if (position >= jpeg.length) break;
            int marker = jpeg[position++] & 0xff;
            if (marker == 0xda || marker == 0xd9) break;
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            if (position + 2 > jpeg.length) return Optional.empty();
            int length = (jpeg[position] & 0xff) << 8 | (jpeg[position + 1] & 0xff);
            position += 2;
            if (length < 2 || position + length - 2 > jpeg.length) return Optional.empty();
            if (marker == 0xe2) {
                segments.add(java.util.Arrays.copyOfRange(jpeg, position, position + length - 2));
            }
            position += length - 2;
        }
        return ColorProfile.parse(assembleJpegSegments(segments));
    }

    private static byte[] assembleJpegSegments(Iterable<byte[]> segments) {
        byte[][] chunks = null;
        int expected = 0;
        int bytes = 0;
        for (byte[] segment : segments) {
            if (!iccPreamble(segment) || segment.length < 15) continue;
            int sequence = segment[12] & 0xff;
            int count = segment[13] & 0xff;
            if (sequence == 0 || count == 0 || sequence > count) return null;
            if (chunks == null) {
                expected = count;
                chunks = new byte[count][];
            } else if (count != expected) {
                return null;
            }
            if (chunks[sequence - 1] != null) return null;
            int length = segment.length - 14;
            if ((long) bytes + length > MAX_PROFILE_BYTES) return null;
            chunks[sequence - 1] = java.util.Arrays.copyOfRange(segment, 14, segment.length);
            bytes += length;
        }
        if (chunks == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes);
        for (byte[] chunk : chunks) {
            if (chunk == null) return null;
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static boolean iccPreamble(byte[] data) {
        byte[] expected = {'I','C','C','_','P','R','O','F','I','L','E',0};
        if (data.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (data[i] != expected[i]) return false;
        }
        return true;
    }

    /** PNG requires iCCP before IDAT, so this bounded chunk scan never touches pixels. */
    private static byte[] png(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (!java.util.Arrays.equals(in.readNBytes(8), PNG_SIGNATURE)) return null;
            while (true) {
                int length = in.readInt();
                int type = in.readInt();
                if (length < 0 || length > MAX_PROFILE_BYTES) return null;
                if (type == fourCc("IDAT") || type == fourCc("IEND")) return null;
                byte[] payload = in.readNBytes(length);
                if (payload.length != length) return null;
                in.readInt(); // CRC (chunk integrity is the decoder's authority)
                if (type != fourCc("iCCP")) continue;
                int zero = 0;
                while (zero < payload.length && payload[zero] != 0) zero++;
                if (zero + 2 > payload.length || payload[zero + 1] != 0) return null;
                return inflate(payload, zero + 2, payload.length - zero - 2);
            }
        }
    }

    /** RIFF chunk scan; payloads are skipped by seek, not materialized. */
    private static byte[] webp(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            if (channel.read(header) != 12) return null;
            header.flip();
            if (header.getInt() != fourCcLe("RIFF")) return null;
            header.getInt();
            if (header.getInt() != fourCcLe("WEBP")) return null;
            ByteBuffer chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while (channel.position() + 8 <= channel.size()) {
                chunk.clear();
                if (channel.read(chunk) != 8) return null;
                chunk.flip();
                int type = chunk.getInt();
                long size = Integer.toUnsignedLong(chunk.getInt());
                if (size > MAX_PROFILE_BYTES && type == fourCcLe("ICCP")) return null;
                if (type == fourCcLe("ICCP")) {
                    byte[] profile = new byte[(int) size];
                    ByteBuffer dst = ByteBuffer.wrap(profile);
                    while (dst.hasRemaining() && channel.read(dst) >= 0) { }
                    return dst.hasRemaining() ? null : profile;
                }
                long next = channel.position() + size + (size & 1);
                if (next < channel.position() || next > channel.size()) return null;
                channel.position(next);
            }
            return null;
        }
    }

    /**
     * Finds an ICC-bearing colr property in HEIF/AVIF's meta/iprp/ipco tree.
     * This intentionally parses only box envelopes; it neither reads mdat nor
     * depends on the private pure-decoder tree omitted by the public export.
     */
    private static byte[] bmffIcc(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return findBmffIcc(channel, 0, channel.size(), 0);
        }
    }

    private static byte[] findBmffIcc(FileChannel channel, long start, long end, int depth)
            throws IOException {
        if (depth > 8 || start < 0 || end < start) return null;
        long position = start;
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        while (position + 8 <= end) {
            header.clear();
            header.limit(8);
            if (!readFully(channel, header, position)) return null;
            header.flip();
            long size = Integer.toUnsignedLong(header.getInt());
            int type = header.getInt();
            int headerBytes = 8;
            if (size == 1) {
                header.clear();
                header.limit(8);
                if (!readFully(channel, header, position + 8)) return null;
                header.flip();
                size = header.getLong();
                headerBytes = 16;
            } else if (size == 0) {
                size = end - position;
            }
            if (size < headerBytes || size > end - position) return null;
            long payload = position + headerBytes;
            long boxEnd = position + size;
            if (type == fourCc("colr") && boxEnd - payload >= 4) {
                ByteBuffer colorType = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                if (!readFully(channel, colorType, payload)) return null;
                colorType.flip();
                int color = colorType.getInt();
                long profileSize = boxEnd - payload - 4;
                if ((color == fourCc("prof") || color == fourCc("rICC"))
                        && profileSize >= 132 && profileSize <= MAX_PROFILE_BYTES) {
                    byte[] profile = new byte[(int) profileSize];
                    if (readFully(channel, ByteBuffer.wrap(profile), payload + 4)
                            && looksLikeIcc(profile)) return profile;
                }
            }
            int childPrefix = type == fourCc("meta") ? 4 : 0;
            if (type == fourCc("meta") || type == fourCc("iprp") || type == fourCc("ipco")) {
                byte[] nested = findBmffIcc(channel, payload + childPrefix, boxEnd, depth + 1);
                if (nested != null) return nested;
            }
            position = boxEnd;
        }
        return null;
    }

    private static boolean readFully(FileChannel channel, ByteBuffer target, long position)
            throws IOException {
        while (target.hasRemaining()) {
            int count = channel.read(target, position);
            if (count < 0) return false;
            if (count == 0) return false;
            position += count;
        }
        return true;
    }

    /** TIFF/PSD readers retain raw byte-valued tags; select only a valid ICC blob. */
    private static byte[] metadataExtractorBlob(Path file) throws IOException {
        com.drew.metadata.Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.toFile());
        } catch (com.drew.imaging.ImageProcessingException ex) {
            return null;
        }
        for (Directory directory : metadata.getDirectories()) {
            for (Tag tag : directory.getTags()) {
                Object value = directory.getObject(tag.getTagType());
                if (value instanceof byte[] bytes && looksLikeIcc(bytes)) return bytes;
            }
        }
        return null;
    }

    private static boolean looksLikeIcc(byte[] bytes) {
        return bytes.length >= 132 && bytes[36] == 'a' && bytes[37] == 'c'
                && bytes[38] == 's' && bytes[39] == 'p';
    }

    private static byte[] inflate(byte[] data, int offset, int length) {
        Inflater inflater = new Inflater();
        inflater.setInput(data, offset, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length * 4, 65536));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) return null;
                if ((long) out.size() + count > MAX_PROFILE_BYTES) return null;
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (DataFormatException ex) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static int fourCc(String value) {
        return value.charAt(0) << 24 | value.charAt(1) << 16
                | value.charAt(2) << 8 | value.charAt(3);
    }

    private static int fourCcLe(String value) {
        return value.charAt(0) | value.charAt(1) << 8
                | value.charAt(2) << 16 | value.charAt(3) << 24;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
