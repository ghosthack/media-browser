package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.cue.CueArchive;
import io.github.ghosthack.mediabrowser.media.archive.iso.IsoImage;
import io.github.ghosthack.mediabrowser.media.archive.iso.IsoVolumeInfo;
import io.github.ghosthack.seven.SevenArchive;
import io.github.ghosthack.unrar.RarArchive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a container says about itself: its format, and whatever identifying
 * detail it carries — an ISO's publisher and mastering date, a ZIP's comment,
 * or a compressed archive's entry and content counts.
 *
 * <p>Read <em>without mounting</em>, on purpose. This is produced when an
 * archive is merely selected, and arrowing down a folder of fifty discs must
 * not open fifty filesystems: an ISO costs a handful of 2 KB descriptor reads,
 * a ZIP one read of its tail; RAR/7z parse bounded indexes and close them
 * immediately. Nothing is published into the mount table.</p>
 *
 * @param format  the container format
 * @param summary a one-line headline for the format, naming the scheme in force
 * @param fields  ordered, display-ready facts, empty values already dropped
 */
public record ArchiveInfo(ArchiveFormat format, String summary, List<Field> fields) {

    /** One display row. */
    public record Field(String name, String value) {}

    public ArchiveInfo {
        fields = List.copyOf(fields);
    }

    /**
     * Reads {@code archive}'s self-description.
     *
     * @throws IOException if it is not a readable container
     */
    public static ArchiveInfo read(Path archive) throws IOException {
        ArchiveFormat format = ArchiveFormat.of(archive)
                .orElseThrow(() -> new IOException("not a readable archive: " + archive));
        return switch (format) {
            case ISO -> readIso(archive);
            case ZIP -> readZip(archive);
            case RAR -> readRar(archive);
            case SEVEN_Z -> readSeven(archive);
            case CUE -> readCue(archive);
        };
    }

    private static ArchiveInfo readIso(Path archive) throws IOException {
        // Opening parses the descriptors and probes for Rock Ridge, then closes
        // again — a few kilobytes, no mount kept alive.
        try (IsoImage image = IsoImage.open(archive)) {
            IsoVolumeInfo volume = image.volumeInfo();
            var fields = new ArrayList<Field>();
            for (Map.Entry<String, String> field : volume.fields().entrySet()) {
                fields.add(new Field(field.getKey(), field.getValue()));
            }
            if (volume.contentBytes() > 0) {
                fields.add(new Field("Content size", humanBytes(volume.contentBytes())));
            }
            if (volume.bootable()) {
                fields.add(new Field("Boot record", "El Torito (bootable)"));
            }
            return new ArchiveInfo(ArchiveFormat.ISO, volume.namingScheme(), fields);
        }
    }

    private static ArchiveInfo readCue(Path archive) throws IOException {
        var fields = new ArrayList<Field>();
        try (CueArchive cue = CueArchive.open(archive);
                IsoImage image = IsoImage.openCue(archive)) {
            IsoVolumeInfo volume = image.volumeInfo();
            for (Map.Entry<String, String> field : volume.fields().entrySet()) {
                fields.add(new Field(field.getKey(), field.getValue()));
            }
            fields.add(new Field("Tracks", String.valueOf(cue.tracks().size())));
            fields.add(new Field("BIN companions", String.valueOf(cue.files().size())));
            if (volume.contentBytes() > 0) {
                fields.add(new Field("Content size", humanBytes(volume.contentBytes())));
            }
            if (volume.bootable()) fields.add(new Field("Boot record", "El Torito (bootable)"));
            return new ArchiveInfo(ArchiveFormat.CUE,
                    "CUE/BIN — " + volume.namingScheme(), fields);
        }
    }

    private static ArchiveInfo readRar(Path archive) throws IOException {
        try (RarArchive rar = RarArchive.open(archive)) {
            var fields = new ArrayList<Field>();
            fields.add(new Field("Entries", String.valueOf(rar.entries().size())));
            long content = saturatingSum(rar.entries().stream()
                    .filter(entry -> entry.isRegularFile() && entry.uncompressedSizeKnown())
                    .mapToLong(entry -> entry.uncompressedSize()).toArray());
            if (content > 0) fields.add(new Field("Content size", humanBytes(content)));
            if (rar.passwordProtected()) fields.add(new Field("Encryption", "Password protected"));
            return new ArchiveInfo(ArchiveFormat.RAR,
                    rar.format() == io.github.ghosthack.unrar.RarFormat.RAR5 ? "RAR 5–7" : "RAR 1.5–4",
                    fields);
        }
    }

    private static ArchiveInfo readSeven(Path archive) throws IOException {
        try (SevenArchive seven = SevenArchive.open(archive)) {
            var fields = new ArrayList<Field>();
            fields.add(new Field("Entries", String.valueOf(seven.entries().size())));
            long content = saturatingSum(seven.entries().stream()
                    .filter(entry -> entry.isRegularFile())
                    .mapToLong(entry -> entry.uncompressedSize()).toArray());
            if (content > 0) fields.add(new Field("Content size", humanBytes(content)));
            if (seven.passwordProtected()) fields.add(new Field("Encryption", "AES-256"));
            return new ArchiveInfo(ArchiveFormat.SEVEN_Z, "7z", fields);
        }
    }

    private static long saturatingSum(long[] values) {
        long sum = 0;
        for (long value : values) {
            if (Long.MAX_VALUE - sum < value) return Long.MAX_VALUE;
            sum += value;
        }
        return sum;
    }

    /** Bytes scanned from a zip's tail: max comment (64 KB) plus the records. */
    private static final int ZIP_TAIL = 66_000;

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int EOCD64_LOCATOR_SIGNATURE = 0x07064b50;

    /**
     * Reads a zip's end-of-central-directory record — the last few bytes of the
     * file — which already carries the entry count and archive comment. The
     * central directory itself, the expensive part of opening a zip, is never
     * touched.
     */
    private static ArchiveInfo readZip(Path archive) throws IOException {
        var fields = new ArrayList<Field>();
        String summary = "ZIP";
        try (SeekableByteChannel channel = Files.newByteChannel(archive, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, ZIP_TAIL);
            ByteBuffer tail = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            channel.position(size - length);
            while (tail.hasRemaining() && channel.read(tail) > 0) { /* fill */ }
            tail.flip();

            int eocd = lastIndexOf(tail, EOCD_SIGNATURE);
            if (eocd >= 0) {
                int entries = tail.getShort(eocd + 10) & 0xFFFF;
                int commentLength = tail.getShort(eocd + 20) & 0xFFFF;
                boolean zip64 = entries == 0xFFFF
                        || lastIndexOf(tail, EOCD64_LOCATOR_SIGNATURE) >= 0;
                if (zip64) {
                    // The 16-bit count saturates; the true one lives in the
                    // zip64 record, which would cost a central-directory read.
                    summary = "ZIP (Zip64)";
                    fields.add(new Field("Entries", entries == 0xFFFF
                            ? "65535 or more" : String.valueOf(entries)));
                } else {
                    fields.add(new Field("Entries", String.valueOf(entries)));
                }
                if (commentLength > 0 && eocd + 22 + commentLength <= tail.limit()) {
                    byte[] comment = new byte[commentLength];
                    tail.position(eocd + 22).get(comment);
                    String text = new String(comment, StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) fields.add(new Field("Comment", text));
                }
            }
            fields.add(new Field("Content size", humanBytes(size)));
        }
        return new ArchiveInfo(ArchiveFormat.ZIP, summary, fields);
    }

    /** The offset of the last occurrence of a 4-byte little-endian signature. */
    private static int lastIndexOf(ByteBuffer buffer, int signature) {
        for (int at = buffer.limit() - 4; at >= 0; at--) {
            if (buffer.getInt(at) == signature) return at;
        }
        return -1;
    }

    /** The same size wording the probe panel uses. */
    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }

    /** These facts as an ordered map, for the metadata snapshot. */
    public Map<String, String> asMap() {
        var out = new LinkedHashMap<String, String>();
        for (Field field : fields) out.put(field.name(), field.value());
        return out;
    }
}
