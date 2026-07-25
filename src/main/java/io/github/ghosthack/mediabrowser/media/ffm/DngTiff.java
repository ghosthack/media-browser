package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal read-only TIFF/IFD walker for the JXL-compressed-DNG pipeline —
 * deliberately not a general TIFF library. It exists to locate the raw SubIFD
 * whose Compression is JPEG XL (52546, DNG 1.7), hand its tile geometry and
 * tile codestreams to the FFmpeg/libjxl decoder, and surface the DNG color
 * profile tags the pure-Java render chain ({@link DngRender}) needs.
 *
 * <p>The file is memory-mapped; tile byte ranges are copied out on demand.
 * Everything here is parsing only — no pixel work, no mutation.</p>
 */
final class DngTiff {

    /** DNG 1.7 Compression value for JPEG-XL-compressed image data. */
    static final int COMPRESSION_JXL = 52546;

    // TIFF/EXIF/DNG tag numbers used below.
    private static final int TAG_NEW_SUBFILE_TYPE = 254;
    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_PHOTOMETRIC = 262;
    private static final int TAG_ORIENTATION = 274;
    private static final int TAG_SAMPLES_PER_PIXEL = 277;
    private static final int TAG_SUB_IFDS = 330;
    private static final int TAG_TILE_WIDTH = 322;
    private static final int TAG_TILE_LENGTH = 323;
    private static final int TAG_TILE_OFFSETS = 324;
    private static final int TAG_TILE_BYTE_COUNTS = 325;
    private static final int TAG_LINEARIZATION_TABLE = 50712;
    private static final int TAG_BLACK_LEVEL = 50714;
    private static final int TAG_WHITE_LEVEL = 50717;
    private static final int TAG_DEFAULT_CROP_ORIGIN = 50719;
    private static final int TAG_DEFAULT_CROP_SIZE = 50720;
    private static final int TAG_COLOR_MATRIX_1 = 50721;
    private static final int TAG_COLOR_MATRIX_2 = 50722;
    private static final int TAG_CAMERA_CALIBRATION_1 = 50723;
    private static final int TAG_CAMERA_CALIBRATION_2 = 50724;
    private static final int TAG_ANALOG_BALANCE = 50727;
    private static final int TAG_AS_SHOT_NEUTRAL = 50728;
    private static final int TAG_BASELINE_EXPOSURE = 50730;
    private static final int TAG_CALIBRATION_ILLUMINANT_1 = 50778;
    private static final int TAG_CALIBRATION_ILLUMINANT_2 = 50779;
    private static final int TAG_ACTIVE_AREA = 50829;
    private static final int TAG_FORWARD_MATRIX_1 = 50964;
    private static final int TAG_FORWARD_MATRIX_2 = 50965;
    private static final int TAG_PROFILE_TONE_CURVE = 50940;
    private static final int TAG_PROFILE_GAIN_TABLE_MAP = 52525;
    private static final int TAG_PROFILE_GAIN_TABLE_MAP_2 = 52537;

    private final ByteBuffer buf;
    private final Map<Integer, Entry> ifd0;
    private final Map<Integer, Entry> rawIfd;

    private DngTiff(ByteBuffer buf, Map<Integer, Entry> ifd0, Map<Integer, Entry> rawIfd) {
        this.buf = buf;
        this.ifd0 = ifd0;
        this.rawIfd = rawIfd;
    }

    /**
     * Opens {@code file} and locates a JXL-compressed raw SubIFD. Empty when
     * the file is not a TIFF/DNG or carries no Compression-52546 SubIFD —
     * callers treat that as "this pipeline does not apply", never an error.
     */
    static Optional<DngTiff> openJxlDng(Path file) {
        ByteBuffer buf;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        } catch (IOException e) {
            return Optional.empty();
        }
        try {
            int b0 = buf.get(0) & 0xFF;
            int b1 = buf.get(1) & 0xFF;
            if (b0 == 'I' && b1 == 'I') {
                buf.order(ByteOrder.LITTLE_ENDIAN);
            } else if (b0 == 'M' && b1 == 'M') {
                buf.order(ByteOrder.BIG_ENDIAN);
            } else {
                return Optional.empty();
            }
            if ((buf.getShort(2) & 0xFFFF) != 42) {
                return Optional.empty();
            }
            Map<Integer, Entry> ifd0 = readIfd(buf, u32(buf.getInt(4)));
            for (long subOffset : longArray(buf, ifd0.get(TAG_SUB_IFDS))) {
                Map<Integer, Entry> sub = readIfd(buf, subOffset);
                Entry compression = sub.get(TAG_COMPRESSION);
                if (compression != null
                        && (int) firstLong(buf, compression) == COMPRESSION_JXL) {
                    return Optional.of(new DngTiff(buf, ifd0, sub));
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            // Truncated/malformed structures surface as absent, like non-DNGs.
            return Optional.empty();
        }
    }

    // ---- raw-IFD geometry --------------------------------------------------

    int width() {
        return (int) firstLong(buf, required(rawIfd, TAG_IMAGE_WIDTH, "ImageWidth"));
    }

    int height() {
        return (int) firstLong(buf, required(rawIfd, TAG_IMAGE_LENGTH, "ImageLength"));
    }

    int tileWidth() {
        return (int) firstLong(buf, required(rawIfd, TAG_TILE_WIDTH, "TileWidth"));
    }

    int tileLength() {
        return (int) firstLong(buf, required(rawIfd, TAG_TILE_LENGTH, "TileLength"));
    }

    int samplesPerPixel() {
        Entry e = rawIfd.get(TAG_SAMPLES_PER_PIXEL);
        return e == null ? 1 : (int) firstLong(buf, e);
    }

    int bitsPerSample() {
        Entry e = rawIfd.get(TAG_BITS_PER_SAMPLE);
        return e == null ? 16 : (int) firstLong(buf, e);
    }

    int photometric() {
        Entry e = rawIfd.get(TAG_PHOTOMETRIC);
        return e == null ? -1 : (int) firstLong(buf, e);
    }

    int tileCount() {
        return (int) required(rawIfd, TAG_TILE_OFFSETS, "TileOffsets").count;
    }

    /** A copy of tile {@code i}'s bytes — an independent bare JXL codestream. */
    byte[] tileBytes(int i) {
        long[] offsets = longArray(buf, required(rawIfd, TAG_TILE_OFFSETS, "TileOffsets"));
        long[] counts = longArray(buf, required(rawIfd, TAG_TILE_BYTE_COUNTS, "TileByteCounts"));
        if (i < 0 || i >= offsets.length || offsets.length != counts.length) {
            throw new MediaException("DNG: tile index " + i + " out of range");
        }
        long offset = offsets[i];
        long count = counts[i];
        if (offset < 0 || count <= 0 || count > Integer.MAX_VALUE
                || offset + count > buf.capacity()) {
            throw new MediaException("DNG: tile " + i + " range invalid");
        }
        byte[] out = new byte[(int) count];
        buf.get((int) offset, out);
        return out;
    }

    // ---- render-chain tags -------------------------------------------------

    /**
     * Stored-value → linear mapping ({@code 2^bits} entries); empty when the
     * data is already linear.
     */
    int[] linearizationTable() {
        Entry e = rawIfd.get(TAG_LINEARIZATION_TABLE);
        if (e == null) {
            return new int[0];
        }
        long[] raw = longArray(buf, e);
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (int) raw[i];
        }
        return out;
    }

    /** Per-sample black level; empty array when absent (treated as 0). */
    double[] blackLevel() {
        return doubleArrayOrEmpty(rawIfd.get(TAG_BLACK_LEVEL));
    }

    /** White level; 2^bits-1 when absent. */
    double whiteLevel() {
        Entry e = rawIfd.get(TAG_WHITE_LEVEL);
        return e == null ? (1 << bitsPerSample()) - 1 : firstDouble(buf, e);
    }

    /** {left, top, width, height} of the visible pixels, raw-IFD relative. */
    int[] cropRectangle() {
        int fullW = width();
        int fullH = height();
        int left = 0;
        int top = 0;
        int w = fullW;
        int h = fullH;
        double[] active = doubleArrayOrEmpty(rawIfd.get(TAG_ACTIVE_AREA));
        if (active.length == 4) {   // top, left, bottom, right
            top = (int) active[0];
            left = (int) active[1];
            w = (int) active[3] - left;
            h = (int) active[2] - top;
        }
        double[] origin = doubleArrayOrEmpty(rawIfd.get(TAG_DEFAULT_CROP_ORIGIN));
        double[] size = doubleArrayOrEmpty(rawIfd.get(TAG_DEFAULT_CROP_SIZE));
        if (origin.length == 2 && size.length == 2) {
            left += (int) Math.round(origin[0]);
            top += (int) Math.round(origin[1]);
            w = (int) Math.round(size[0]);
            h = (int) Math.round(size[1]);
        }
        if (left < 0 || top < 0 || w <= 0 || h <= 0 || left + w > fullW || top + h > fullH) {
            return new int[] {0, 0, fullW, fullH};
        }
        return new int[] {left, top, w, h};
    }

    int orientation() {
        Entry e = ifd0.get(TAG_ORIENTATION);
        return e == null ? 1 : (int) firstLong(buf, e);
    }

    double[] colorMatrix1() {
        return doubleArrayOrEmpty(ifd0.get(TAG_COLOR_MATRIX_1));
    }

    double[] colorMatrix2() {
        return doubleArrayOrEmpty(ifd0.get(TAG_COLOR_MATRIX_2));
    }

    double[] cameraCalibration1() {
        return doubleArrayOrEmpty(ifd0.get(TAG_CAMERA_CALIBRATION_1));
    }

    double[] cameraCalibration2() {
        return doubleArrayOrEmpty(ifd0.get(TAG_CAMERA_CALIBRATION_2));
    }

    double[] analogBalance() {
        return doubleArrayOrEmpty(ifd0.get(TAG_ANALOG_BALANCE));
    }

    double[] asShotNeutral() {
        return doubleArrayOrEmpty(ifd0.get(TAG_AS_SHOT_NEUTRAL));
    }

    double baselineExposure() {
        Entry e = ifd0.get(TAG_BASELINE_EXPOSURE);
        return e == null ? 0 : firstDouble(buf, e);
    }

    int calibrationIlluminant1() {
        Entry e = ifd0.get(TAG_CALIBRATION_ILLUMINANT_1);
        return e == null ? 0 : (int) firstLong(buf, e);
    }

    int calibrationIlluminant2() {
        Entry e = ifd0.get(TAG_CALIBRATION_ILLUMINANT_2);
        return e == null ? 0 : (int) firstLong(buf, e);
    }

    double[] forwardMatrix1() {
        return doubleArrayOrEmpty(ifd0.get(TAG_FORWARD_MATRIX_1));
    }

    double[] forwardMatrix2() {
        return doubleArrayOrEmpty(ifd0.get(TAG_FORWARD_MATRIX_2));
    }

    /** Alternating x,y pairs, 0..1, monotonic in x; empty when absent. */
    float[] profileToneCurve() {
        Entry e = ifd0.get(TAG_PROFILE_TONE_CURVE);
        if (e == null) {
            return new float[0];
        }
        double[] d = doubleArray(buf, e);
        float[] out = new float[d.length];
        for (int i = 0; i < d.length; i++) {
            out[i] = (float) d[i];
        }
        return out;
    }

    /** The raw ProfileGainTableMap blob (DNG 1.6/1.7); empty when absent. */
    byte[] profileGainTableMap() {
        Entry e = rawIfd.get(TAG_PROFILE_GAIN_TABLE_MAP);
        if (e == null) {
            e = ifd0.get(TAG_PROFILE_GAIN_TABLE_MAP);
        }
        if (e == null) {
            e = rawIfd.get(TAG_PROFILE_GAIN_TABLE_MAP_2);
        }
        if (e == null) {
            e = ifd0.get(TAG_PROFILE_GAIN_TABLE_MAP_2);
        }
        return e == null ? new byte[0] : rawBytes(buf, e);
    }

    /** Debug view of both IFDs' tags: tag → "type×count@size". */
    Map<String, String> describeTags() {
        Map<String, String> out = new LinkedHashMap<>();
        ifd0.forEach((tag, e) -> out.put("ifd0/" + tag,
                "type=" + e.type + " count=" + e.count));
        rawIfd.forEach((tag, e) -> out.put("raw/" + tag,
                "type=" + e.type + " count=" + e.count));
        return out;
    }

    // ---- TIFF plumbing -----------------------------------------------------

    private record Entry(int type, long count, long valueOffset, boolean inline) { }

    private static final int TYPE_BYTE = 1;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_SBYTE = 6;
    private static final int TYPE_UNDEFINED = 7;
    private static final int TYPE_SSHORT = 8;
    private static final int TYPE_SLONG = 9;
    private static final int TYPE_SRATIONAL = 10;
    private static final int TYPE_FLOAT = 11;
    private static final int TYPE_DOUBLE = 12;

    private static int typeSize(int type) {
        return switch (type) {
            case TYPE_BYTE, TYPE_SBYTE, TYPE_UNDEFINED, 2 -> 1;   // 2 = ASCII
            case TYPE_SHORT, TYPE_SSHORT -> 2;
            case TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4;
            case TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8;
            default -> 0;
        };
    }

    private static Map<Integer, Entry> readIfd(ByteBuffer buf, long offset) {
        if (offset <= 0 || offset + 2 > buf.capacity()) {
            throw new MediaException("DNG: IFD offset out of range");
        }
        int count = buf.getShort((int) offset) & 0xFFFF;
        Map<Integer, Entry> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int base = (int) offset + 2 + i * 12;
            int tag = buf.getShort(base) & 0xFFFF;
            int type = buf.getShort(base + 2) & 0xFFFF;
            long n = u32(buf.getInt(base + 4));
            int size = typeSize(type);
            boolean inline = size > 0 && size * n <= 4;
            long value = inline ? base + 8 : u32(buf.getInt(base + 8));
            entries.put(tag, new Entry(type, n, value, inline));
        }
        return entries;
    }

    private static Entry required(Map<Integer, Entry> ifd, int tag, String name) {
        Entry e = ifd.get(tag);
        if (e == null) {
            throw new MediaException("DNG: missing required tag " + name);
        }
        return e;
    }

    private static long u32(int v) {
        return v & 0xFFFFFFFFL;
    }

    private static long[] longArray(ByteBuffer buf, Entry e) {
        if (e == null) {
            return new long[0];
        }
        long[] out = new long[(int) e.count];
        for (int i = 0; i < out.length; i++) {
            out[i] = readLongAt(buf, e, i);
        }
        return out;
    }

    private double[] doubleArrayOrEmpty(Entry e) {
        return e == null ? new double[0] : doubleArray(buf, e);
    }

    private static double[] doubleArray(ByteBuffer buf, Entry e) {
        double[] out = new double[(int) e.count];
        for (int i = 0; i < out.length; i++) {
            out[i] = readDoubleAt(buf, e, i);
        }
        return out;
    }

    private static long firstLong(ByteBuffer buf, Entry e) {
        return readLongAt(buf, e, 0);
    }

    private static double firstDouble(ByteBuffer buf, Entry e) {
        return readDoubleAt(buf, e, 0);
    }

    private static long readLongAt(ByteBuffer buf, Entry e, int i) {
        int at = (int) (e.valueOffset + (long) typeSize(e.type) * i);
        return switch (e.type) {
            case TYPE_BYTE, TYPE_UNDEFINED -> buf.get(at) & 0xFFL;
            case TYPE_SBYTE -> buf.get(at);
            case TYPE_SHORT -> buf.getShort(at) & 0xFFFFL;
            case TYPE_SSHORT -> buf.getShort(at);
            case TYPE_LONG -> u32(buf.getInt(at));
            case TYPE_SLONG -> buf.getInt(at);
            default -> (long) readDoubleAt(buf, e, i);
        };
    }

    private static double readDoubleAt(ByteBuffer buf, Entry e, int i) {
        int at = (int) (e.valueOffset + (long) typeSize(e.type) * i);
        return switch (e.type) {
            case TYPE_RATIONAL -> {
                double den = u32(buf.getInt(at + 4));
                yield den == 0 ? 0 : u32(buf.getInt(at)) / den;
            }
            case TYPE_SRATIONAL -> {
                double den = buf.getInt(at + 4);
                yield den == 0 ? 0 : buf.getInt(at) / den;
            }
            case TYPE_FLOAT -> buf.getFloat(at);
            case TYPE_DOUBLE -> buf.getDouble(at);
            default -> readLongAt(buf, e, i);
        };
    }

    private static byte[] rawBytes(ByteBuffer buf, Entry e) {
        long size = (long) typeSize(e.type) * e.count;
        if (size <= 0 || size > Integer.MAX_VALUE
                || e.valueOffset + size > buf.capacity()) {
            throw new MediaException("DNG: tag value range invalid");
        }
        byte[] out = new byte[(int) size];
        buf.get((int) e.valueOffset, out);
        return out;
    }
}
