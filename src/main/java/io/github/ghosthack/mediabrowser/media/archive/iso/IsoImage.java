package io.github.ghosthack.mediabrowser.media.archive.iso;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * A read-only ISO 9660 disc image: volume descriptors, the directory
 * hierarchy, and the bytes of each file. This is the parser proper — the
 * {@code java.nio.file} face of it is {@link IsoFileSystemProvider}, which is
 * what the rest of the app talks to.
 *
 * <p><b>Name source.</b> A disc can describe the same tree up to three ways,
 * and this picks the richest one available, once, at open time: a Joliet
 * supplementary descriptor (UCS-2 names, up to 64 chars) wins outright; failing
 * that, Rock Ridge {@code NM} entries in the primary tree's system-use area
 * supply POSIX names; failing both, the bare 8.3 identifier is used with its
 * mandatory {@code ;1} version suffix and trailing dot stripped. Mixing them
 * per-entry would be worse than useless — the same file would list under
 * different names depending on which record happened to carry an extension —
 * so the choice is image-wide.</p>
 *
 * <p><b>What is deliberately not handled.</b> UDF, the other filesystem a
 * {@code .iso} can carry, is a separate spec and not implemented; an image with
 * no ISO 9660 descriptors at all is rejected loudly by {@link #open} rather
 * than opening as an empty tree, so a UDF-only disc reports what it is instead
 * of silently listing nothing. Rock Ridge deep-directory relocation
 * ({@code CL}/{@code PL}/{@code RE}) is not followed: a relocated branch lists
 * under its {@code RR_MOVED} home, which is where an unaware reader would see
 * it anyway.</p>
 *
 * <p>Instances are thread-safe for reading: every read is a positional
 * {@link FileChannel#read(ByteBuffer, long)}, which does not disturb or depend
 * on channel position.</p>
 */
public final class IsoImage implements Closeable {

    /** Logical sector size. Fixed by the spec at 2048 for data tracks. */
    static final int SECTOR = 2048;

    /** Volume descriptors begin here — 16 sectors of reserved system area. */
    private static final long DESCRIPTOR_START = 16L * SECTOR;

    /** Refuse a descriptor chain longer than this; a malformed image can loop. */
    private static final int MAX_DESCRIPTORS = 64;

    private static final byte TYPE_BOOT_RECORD = 0;
    private static final byte TYPE_PRIMARY = 1;
    private static final byte TYPE_SUPPLEMENTARY = 2;
    private static final int TYPE_TERMINATOR = 255;

    private static final byte FLAG_DIRECTORY = 0x02;
    private static final byte FLAG_MULTI_EXTENT = (byte) 0x80;

    private final FileChannel channel;
    private final IsoEntry root;
    private final String volumeName;
    private final boolean joliet;
    private final boolean rockRidge;
    /** The primary descriptor, kept so its descriptive fields stay readable. */
    private final byte[] primaryDescriptor;
    private final boolean bootable;

    private IsoImage(FileChannel channel, IsoEntry root, String volumeName,
                     boolean joliet, boolean rockRidge,
                     byte[] primaryDescriptor, boolean bootable) {
        this.channel = channel;
        this.root = root;
        this.volumeName = volumeName;
        this.joliet = joliet;
        this.rockRidge = rockRidge;
        this.primaryDescriptor = primaryDescriptor;
        this.bootable = bootable;
    }

    /**
     * Opens {@code file} as an ISO 9660 image.
     *
     * @throws IOException if the file cannot be read, or carries no ISO 9660
     *                     descriptor — including the UDF-only case, which is
     *                     named explicitly in the message so the caller can say
     *                     something better than "unreadable"
     */
    public static IsoImage open(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            byte[] primary = null;
            byte[] supplementary = null;
            boolean boot = false;
            for (int i = 0; i < MAX_DESCRIPTORS; i++) {
                long at = DESCRIPTOR_START + (long) i * SECTOR;
                if (at + SECTOR > channel.size()) break;
                byte[] descriptor = read(channel, at, SECTOR);
                if (!"CD001".equals(ascii(descriptor, 1, 5))) break;
                int type = descriptor[0] & 0xFF;
                if (type == TYPE_TERMINATOR) break;
                if (type == TYPE_BOOT_RECORD) {
                    boot = true;                      // El Torito: a bootable disc
                } else if (type == TYPE_PRIMARY && primary == null) {
                    primary = descriptor;
                } else if (type == TYPE_SUPPLEMENTARY && supplementary == null
                        && isJoliet(descriptor)) {
                    supplementary = descriptor;
                }
            }
            if (primary == null && supplementary == null) {
                throw new IOException("no ISO 9660 volume descriptor in " + file.getFileName()
                        + " (a UDF-only or non-ISO image; not supported)");
            }
            byte[] descriptor = supplementary != null ? supplementary : primary;
            boolean useJoliet = supplementary != null;
            // The root's own directory record is embedded in the descriptor at
            // offset 156, in the same layout as any record inside a directory.
            IsoEntry rootRecord = new IsoEntry("", true,
                    List.of(new IsoEntry.Extent(
                            (long) intLE(descriptor, 156 + 2) * SECTOR,
                            intLE(descriptor, 156 + 10))),
                    intLE(descriptor, 156 + 10), 0);
            String volume = ascii(descriptor, 40, 32).trim();
            if (useJoliet) volume = ucs2(descriptor, 40, 32).trim();
            byte[] describing = primary != null ? primary : descriptor;
            IsoImage image = new IsoImage(channel, rootRecord, volume, useJoliet, false,
                    describing, boot);
            // Rock Ridge announces itself with an SP entry on the root's "."
            // record; only worth probing when Joliet did not already win.
            boolean rr = !useJoliet && image.detectRockRidge();
            return new IsoImage(channel, rootRecord, volume, useJoliet, rr, describing, boot);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** The volume identifier, for display (may be empty). */
    public String volumeName() {
        return volumeName;
    }

    /** Whether names come from a Joliet supplementary descriptor. */
    public boolean joliet() {
        return joliet;
    }

    /** Whether names come from Rock Ridge {@code NM} entries. */
    public boolean rockRidge() {
        return rockRidge;
    }

    /** The root directory, whose {@link IsoEntry#name()} is the empty string. */
    public IsoEntry root() {
        return root;
    }

    /**
     * The descriptive contents of the volume descriptor, for display. Reads
     * nothing further — every byte comes from the descriptor already parsed at
     * open — so this is free to call.
     */
    public IsoVolumeInfo volumeInfo() {
        byte[] d = primaryDescriptor;
        var fields = new LinkedHashMap<String, String>();
        put(fields, "Volume label", ascii(d, 40, 32));
        put(fields, "Volume set", ascii(d, 190, 128));
        put(fields, "Publisher", ascii(d, 318, 128));
        put(fields, "Data preparer", ascii(d, 446, 128));
        put(fields, "Application", ascii(d, 574, 128));
        put(fields, "System", ascii(d, 8, 32));
        put(fields, "Copyright file", ascii(d, 702, 37));
        put(fields, "Abstract file", ascii(d, 739, 37));
        put(fields, "Bibliographic file", ascii(d, 776, 37));
        // The real mastering date. Distinct from the .iso file's own timestamp,
        // which only records when the image was made or downloaded.
        String created = decimalDateTime(d, 813);
        put(fields, "Created", created);
        // Mastering tools routinely stamp the same instant into all four date
        // fields; four identical rows say nothing, so only differences show.
        putIfDifferent(fields, "Modified", decimalDateTime(d, 830), created);
        putIfDifferent(fields, "Expires", decimalDateTime(d, 847), created);
        putIfDifferent(fields, "Effective", decimalDateTime(d, 864), created);

        int blockSize = shortLE(d, 128);
        long sectors = Integer.toUnsignedLong(intLE(d, 80));
        return new IsoVolumeInfo(namingScheme(), bootable,
                sectors * Math.max(blockSize, 1), blockSize, fields);
    }

    /** How this image's names are being read — what the user actually sees. */
    private String namingScheme() {
        if (joliet) return "ISO 9660 + Joliet";
        if (rockRidge) return "ISO 9660 + Rock Ridge";
        return "ISO 9660";
    }

    /** Adds a field only when it carries something; blanks are omitted. */
    private static void put(LinkedHashMap<String, String> fields, String name, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) fields.put(name, trimmed);
    }

    /** Adds a field only when it says something the reference row did not. */
    private static void putIfDifferent(LinkedHashMap<String, String> fields, String name,
                                       String value, String reference) {
        if (value != null && !value.isBlank() && !value.equals(reference)) {
            fields.put(name, value);
        }
    }

    /**
     * The 17-byte decimal date form used by volume descriptors
     * ({@code YYYYMMDDHHMMSShh} plus a signed 15-minute offset), rendered for
     * display. Empty when unset — an all-zero or all-space field, which is how
     * an unused date is written.
     */
    private static String decimalDateTime(byte[] d, int pos) {
        String text = ascii(d, pos, 16).trim();
        if (text.isEmpty() || text.chars().allMatch(c -> c == '0')) return "";
        try {
            String stamp = text + "0".repeat(Math.max(0, 16 - text.length()));
            int year = Integer.parseInt(stamp.substring(0, 4));
            if (year == 0) return "";
            // The recorded wall-clock time, with no timezone rendered. The 17th
            // byte is meant to be a binary 15-minute offset, but mastering tools
            // of the era wrote the whole field as ASCII digits, leaving 0x30
            // there — which reads as a spurious +12:00. Since a real +12:00 and
            // that artifact are indistinguishable, claiming either would be
            // worse than showing the date as the disc states it.
            return stamp.substring(0, 4) + "-" + stamp.substring(4, 6)
                    + "-" + stamp.substring(6, 8) + " " + stamp.substring(8, 10)
                    + ":" + stamp.substring(10, 12) + ":" + stamp.substring(12, 14);
        } catch (RuntimeException e) {
            return "";                    // malformed: not worth a row
        }
    }

    /** Little-endian half of a both-endian 16-bit field. */
    private static int shortLE(byte[] data, int pos) {
        return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8;
    }

    /**
     * The children of {@code dir}, in the order the image stores them, with
     * the {@code .} and {@code ..} self/parent records dropped.
     */
    public List<IsoEntry> children(IsoEntry dir) throws IOException {
        if (!dir.directory()) throw new IOException("not a directory: " + dir.name());
        byte[] data = readAll(dir);
        var out = new ArrayList<IsoEntry>();
        int pos = 0;
        // The multi-extent flag marks a record that continues into the *next*
        // one, so splicing is driven by the previous record's flag, not this
        // record's.
        boolean previousContinues = false;
        // A record never straddles a sector: a directory extent pads with zeros
        // from the last record to the sector end, and a zero length byte is the
        // signal to skip to the next sector rather than the end of the listing.
        while (pos < data.length) {
            int length = data[pos] & 0xFF;
            if (length == 0) {
                int next = (pos / SECTOR + 1) * SECTOR;
                if (next <= pos) break;
                pos = next;
                continue;
            }
            if (pos + length > data.length) break;
            IsoEntry entry = parseRecord(data, pos, length);
            if (entry != null) {
                boolean continues = (data[pos + 25] & FLAG_MULTI_EXTENT) != 0;
                // A file too large for one extent is stored as a chain of
                // records under the same name; splice them so callers see one
                // entry with one size.
                IsoEntry previous = out.isEmpty() ? null : out.get(out.size() - 1);
                if (previousContinues && previous != null
                        && previous.name().equals(entry.name())) {
                    var extents = new ArrayList<>(previous.extents());
                    extents.addAll(entry.extents());
                    out.set(out.size() - 1, new IsoEntry(previous.name(),
                            previous.directory(), extents,
                            previous.size() + entry.size(), previous.mtimeMillis()));
                } else {
                    out.add(entry);
                }
                previousContinues = continues;
            } else {
                previousContinues = false;      // the "." / ".." records
            }
            pos += length;
        }
        return out;
    }

    /**
     * The entry at an absolute, {@code /}-separated path inside the image
     * ({@code "/"} being the root), or empty when no such name exists. Matching
     * is case-insensitive as a fallback only: plain ISO 9660 stores uppercase
     * 8.3 names, so a path typed in the case a user actually sees on an
     * extracted copy still resolves.
     */
    public Optional<IsoEntry> resolve(String path) throws IOException {
        IsoEntry current = root;
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if (!current.directory()) return Optional.empty();
            List<IsoEntry> children = children(current);
            IsoEntry match = null;
            for (IsoEntry child : children) {
                if (child.name().equals(part)) { match = child; break; }
            }
            if (match == null) {
                for (IsoEntry child : children) {
                    if (child.name().equalsIgnoreCase(part)) { match = child; break; }
                }
            }
            if (match == null) return Optional.empty();
            current = match;
        }
        return Optional.of(current);
    }

    /** All bytes of {@code entry}, across every extent. */
    public byte[] readAll(IsoEntry entry) throws IOException {
        long total = entry.size();
        if (total > Integer.MAX_VALUE) {
            throw new IOException("entry too large to read whole: " + entry.name());
        }
        byte[] out = new byte[(int) total];
        int written = 0;
        for (IsoEntry.Extent extent : entry.extents()) {
            int want = (int) Math.min(extent.length(), out.length - written);
            if (want <= 0) break;
            ByteBuffer buffer = ByteBuffer.wrap(out, written, want);
            long at = extent.offset();
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, at);
                if (n < 0) throw new IOException("truncated image reading " + entry.name());
                at += n;
            }
            written += want;
        }
        return out;
    }

    /**
     * Reads up to {@code length} bytes of {@code entry} starting at
     * {@code position} in the entry's own byte space, mapping that window onto
     * whichever extents it spans.
     *
     * @return the number of bytes read, or {@code -1} at end of entry
     */
    int read(IsoEntry entry, long position, ByteBuffer destination) throws IOException {
        if (position >= entry.size()) return -1;
        long remaining = position;
        for (IsoEntry.Extent extent : entry.extents()) {
            if (remaining < extent.length()) {
                long at = extent.offset() + remaining;
                long inExtent = extent.length() - remaining;
                long inEntry = entry.size() - position;
                int limit = (int) Math.min(destination.remaining(),
                        Math.min(inExtent, inEntry));
                if (limit <= 0) return -1;
                int savedLimit = destination.limit();
                destination.limit(destination.position() + limit);
                try {
                    return channel.read(destination, at);
                } finally {
                    destination.limit(savedLimit);
                }
            }
            remaining -= extent.length();
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // --- record parsing ---------------------------------------------------

    /** @return the entry, or null for the {@code .} / {@code ..} records */
    private IsoEntry parseRecord(byte[] data, int pos, int length) {
        int nameLength = data[pos + 32] & 0xFF;
        if (nameLength == 0 || pos + 33 + nameLength > data.length) return null;
        if (nameLength == 1) {
            byte first = data[pos + 33];
            if (first == 0 || first == 1) return null;   // "." and ".."
        }
        boolean directory = (data[pos + 25] & FLAG_DIRECTORY) != 0;
        String name = joliet
                ? ucs2(data, pos + 33, nameLength)
                : ascii(data, pos + 33, nameLength);
        // Joliet identifiers carry the ";1" version suffix too — it is part of
        // the ISO 9660 record format, not of the name encoding.
        name = stripVersion(name, directory);
        if (rockRidge) {
            // The system-use area follows the name, plus a pad byte when the
            // name length is even (records are 2-aligned).
            int systemStart = pos + 33 + nameLength + (nameLength % 2 == 0 ? 1 : 0);
            String posix = rockRidgeName(data, systemStart, pos + length);
            if (posix != null && !posix.isEmpty()) name = posix;
        }
        long extent = (long) intLE(data, pos + 2) * SECTOR;
        long size = intLE(data, pos + 10);
        return new IsoEntry(name, directory,
                List.of(new IsoEntry.Extent(extent, size)), size,
                timestamp(data, pos + 18));
    }

    /**
     * The Rock Ridge {@code NM} name assembled from {@code [start,end)}, or
     * null when the record carries none. {@code CE} continuation areas are
     * followed, since a name long enough to need one is exactly the case Rock
     * Ridge exists for.
     */
    private String rockRidgeName(byte[] data, int start, int end) {
        var name = new StringBuilder();
        byte[] area = data;
        int pos = start;
        int limit = end;
        for (int hop = 0; hop < 8; hop++) {          // bound CE chasing
            Continuation next = null;
            while (pos + 4 <= limit) {
                String signature = ascii(area, pos, 2);
                int length = area[pos + 2] & 0xFF;
                if (length < 4 || pos + length > limit) break;
                if ("NM".equals(signature) && length > 5) {
                    // Byte 4 is flags; bit 0 set means the name continues in a
                    // later NM entry, so pieces are concatenated, not replaced.
                    name.append(new String(area, pos + 5, length - 5, StandardCharsets.UTF_8));
                } else if ("CE".equals(signature) && length >= 28) {
                    next = new Continuation((long) intLE(area, pos + 4) * SECTOR
                            + intLE(area, pos + 12), intLE(area, pos + 20));
                } else if ("ST".equals(signature)) {
                    break;
                }
                pos += length;
            }
            if (next == null || next.length() <= 0 || next.length() > SECTOR * 8L) break;
            try {
                area = read(channel, next.offset(), (int) next.length());
            } catch (IOException e) {
                break;                                // keep the name we have
            }
            pos = 0;
            limit = area.length;
        }
        return name.isEmpty() ? null : name.toString();
    }

    private record Continuation(long offset, long length) {}

    /**
     * Whether the root's {@code .} record carries the {@code SP} entry that
     * announces a Rock Ridge (SUSP) system-use area.
     */
    private boolean detectRockRidge() {
        try {
            byte[] data = readAll(root);
            if (data.length < 34) return false;
            int length = data[0] & 0xFF;
            int nameLength = data[32] & 0xFF;
            int systemStart = 33 + nameLength + (nameLength % 2 == 0 ? 1 : 0);
            for (int pos = systemStart; pos + 4 <= Math.min(length, data.length); ) {
                String signature = ascii(data, pos, 2);
                int entryLength = data[pos + 2] & 0xFF;
                if (entryLength < 4) break;
                if ("SP".equals(signature) || "RR".equals(signature)) return true;
                pos += entryLength;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Strips the {@code ;1} version suffix every ISO 9660 file identifier
     * carries, and the trailing dot an extension-less name is padded with.
     * Directory identifiers have neither.
     */
    private static String stripVersion(String name, boolean directory) {
        if (directory) return name;
        int semicolon = name.lastIndexOf(';');
        String stripped = semicolon > 0 ? name.substring(0, semicolon) : name;
        return stripped.endsWith(".") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    /** The 7-byte directory-record timestamp, or 0 when unset. */
    private static long timestamp(byte[] data, int pos) {
        int year = data[pos] & 0xFF;
        int month = data[pos + 1] & 0xFF;
        int day = data[pos + 2] & 0xFF;
        if (month < 1 || month > 12 || day < 1 || day > 31) return 0;
        int hour = data[pos + 3] & 0xFF;
        int minute = data[pos + 4] & 0xFF;
        int second = data[pos + 5] & 0xFF;
        int offsetQuarters = data[pos + 6];          // signed, 15-minute units
        if (hour > 23 || minute > 59 || second > 59) return 0;
        try {
            return LocalDateTime.of(1900 + year, month, day, hour, minute, second)
                    .toInstant(ZoneOffset.ofTotalSeconds(offsetQuarters * 15 * 60))
                    .toEpochMilli();
        } catch (RuntimeException e) {
            return 0;                                 // out-of-range date: unknown
        }
    }

    private static boolean isJoliet(byte[] descriptor) {
        // Escape sequences at offset 88 select UCS-2 level 1/2/3.
        for (int level : new int[] {0x40, 0x43, 0x45}) {
            if ((descriptor[88] & 0xFF) == 0x25 && (descriptor[89] & 0xFF) == 0x2F
                    && (descriptor[90] & 0xFF) == level) {
                return true;
            }
        }
        return false;
    }

    private static byte[] read(FileChannel channel, long at, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = at;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position);
            if (n < 0) {
                if (buffer.position() == 0) throw new NoSuchFileException("read past end of image");
                break;
            }
            position += n;
        }
        return buffer.array();
    }

    /** Little-endian half of a both-endian 32-bit field. */
    private static int intLE(byte[] data, int pos) {
        return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8
                | (data[pos + 2] & 0xFF) << 16 | (data[pos + 3] & 0xFF) << 24;
    }

    private static String ascii(byte[] data, int pos, int length) {
        int end = Math.min(pos + length, data.length);
        return new String(data, pos, Math.max(0, end - pos), StandardCharsets.US_ASCII);
    }

    private static String ucs2(byte[] data, int pos, int length) {
        int end = Math.min(pos + length, data.length);
        return new String(data, pos, Math.max(0, end - pos) & ~1, StandardCharsets.UTF_16BE);
    }
}
