package io.github.ghosthack.mediabrowser.media.ffm.bind;

import io.github.ghosthack.mediabrowser.media.VideoRotation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Version-agnostic FFM helpers shared by every native binding implementation.
 * These touch no FFmpeg/libvips layout, so they live above the per-version
 * split.
 */
public final class Ffm {

    private Ffm() {}

    /**
     * Guard bytes appended after a tightly packed swscale BGRA destination.
     *
     * <p>FFmpeg's {@code sws_scale} output writers are SIMD and can store a
     * whole vector register <em>past the last pixel of the final row</em> of the
     * destination. When the destination is allocated to exactly
     * {@code width*height*4} bytes that store runs off the end of the allocation
     * and corrupts the native heap. It is silently absorbed by the slack in the
     * macOS/Linux allocators, but on Windows it is a hard heap-corruption fault
     * (STATUS_HEAP_CORRUPTION, {@code 0xC0000374}) raised the next time that
     * arena's memory is freed. Measured over-write here was 32 bytes (an AVX2
     * store); 128 comfortably covers a 64-byte AVX-512 store with margin.</p>
     */
    public static final int SWS_DST_TAIL_PADDING = 128;

    /**
     * Allocates a tightly packed ({@code width*4}-stride) BGRA {@code sws_scale}
     * destination of {@code width × height}, plus {@link #SWS_DST_TAIL_PADDING}
     * trailing guard bytes so swscale's SIMD tail over-write stays inside the
     * allocation (see that constant). The returned segment is the full padded
     * allocation — pass its base address to {@code sws_scale} (with the ordinary
     * unpadded {@code width*4} stride and {@code height}), but expose only the
     * first {@code width*height*4} bytes to consumers via
     * {@link MemorySegment#asSlice(long, long)} so the padding never leaks into a
     * decoded frame.
     */
    public static MemorySegment allocateSwscaleBgraDst(Arena arena, int width, int height) {
        return arena.allocate((long) width * height * 4 + SWS_DST_TAIL_PADDING);
    }

    /**
     * HEIF/AVIF-family filename extensions (lower-case, no dot) whose libvips
     * loader is {@code heifload} — the one loader that wraps libheif and so
     * enforces libheif's denial-of-service limits. Mirrors the still half of
     * {@code ImageSequences}' image-sequence set.
     */
    private static final Set<String> HEIF_FAMILY_EXTENSIONS = Set.of(
            "heic", "heif", "heics", "heifs", "hif",
            "avif", "avifs", "avis");

    /**
     * The libvips "filename argument" for {@code file}: the path, plus any load
     * options appended in the trailing {@code [key=value,...]} block that
     * {@code vips_image_new_from_file} and {@code vips_thumbnail} parse off the
     * end of the name.
     *
     * <p>For HEIF/AVIF-family containers this appends {@code [unlimited]} so
     * libvips removes the libheif denial-of-service limits before decoding.
     * Without it a perfectly valid photo can fail to open — libheif caps how
     * many {@code meta}-box items it will parse, and an iPhone HEIC routinely
     * carries more (e.g. {@code ipma box wants to define properties for 50
     * items, but the security limit has been set to 16 items}). libvips exposes
     * no per-limit knob on the loader, only this blanket {@code unlimited}
     * flag, so that is the lever we pull — and only for the heif loader, so an
     * ordinary JPEG/PNG keeps its decompression-bomb guards.</p>
     */
    public static String vipsLoadArg(Path file) {
        String name = file.toString();
        return isHeifFamily(file) ? name + "[unlimited]" : name;
    }

    private static boolean isHeifFamily(Path file) {
        Path namePath = file.getFileName();
        if (namePath == null) {
            return false;
        }
        String fileName = namePath.toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return HEIF_FAMILY_EXTENSIONS.contains(ext);
    }

    /** Allocates a one-slot pointer holder initialized to {@code target}. */
    public static MemorySegment pointerTo(Arena arena, MemorySegment target) {
        MemorySegment ptr = arena.allocate(ValueLayout.ADDRESS);
        ptr.set(ValueLayout.ADDRESS, 0, target);
        return ptr;
    }

    /** Reads a NUL-terminated C string, or null for a NULL/absent pointer. */
    public static String cstr(MemorySegment s) {
        return s == null || s.equals(MemorySegment.NULL)
                ? null
                : s.reinterpret(Long.MAX_VALUE).getString(0);
    }

    // -- display-matrix rotation ------------------------------------------

    /** {@code enum AVPacketSideDataType} value of {@code AV_PKT_DATA_DISPLAYMATRIX} (stable across FFmpeg majors). */
    private static final int AV_PKT_DATA_DISPLAYMATRIX = 5;

    /**
     * {@code sizeof(AVPacketSideData)} on LP64: {@code uint8_t *data} (8) +
     * {@code size_t size} (8) + {@code enum type} (4) + 4 tail padding.
     */
    private static final long SIDE_DATA_STRIDE = 24;
    private static final long SIDE_DATA_DATA_OFFSET = 0;
    private static final long SIDE_DATA_SIZE_OFFSET = 8;
    private static final long SIDE_DATA_TYPE_OFFSET = 16;

    /** The 3x3 display matrix is nine {@code int32_t} (16.16 / 2.30 fixed point). */
    private static final long DISPLAY_MATRIX_BYTES = 9 * 4;

    /**
     * Reads the display-matrix rotation out of an {@code AVPacketSideData[]}
     * array — the version-agnostic part of every binding's rotation read, given
     * the {@code (array, count)} pair from wherever that FFmpeg major keeps the
     * stream's coded side data ({@code AVStream.side_data} in 4.x,
     * {@code AVCodecParameters.coded_side_data} in 7.x+). Returns the clockwise
     * quarter-turns (0..3) needed to display the frame upright, or {@code 0}
     * when there is no display matrix.
     */
    public static int rotationQuarterTurnsFromSideData(MemorySegment sideDataArray, int count) {
        if (count <= 0 || sideDataArray == null || sideDataArray.equals(MemorySegment.NULL)) {
            return 0;
        }
        MemorySegment arr = sideDataArray.reinterpret(count * SIDE_DATA_STRIDE);
        for (int i = 0; i < count; i++) {
            long base = i * SIDE_DATA_STRIDE;
            if (arr.get(ValueLayout.JAVA_INT, base + SIDE_DATA_TYPE_OFFSET) != AV_PKT_DATA_DISPLAYMATRIX) {
                continue;
            }
            MemorySegment data = arr.get(ValueLayout.ADDRESS, base + SIDE_DATA_DATA_OFFSET);
            long size = arr.get(ValueLayout.JAVA_LONG, base + SIDE_DATA_SIZE_OFFSET);
            if (data.equals(MemorySegment.NULL) || size < DISPLAY_MATRIX_BYTES) {
                return 0;
            }
            MemorySegment matrix = data.reinterpret(DISPLAY_MATRIX_BYTES);
            int[] m = new int[9];
            for (int k = 0; k < 9; k++) {
                m[k] = matrix.get(ValueLayout.JAVA_INT, k * 4L);
            }
            return VideoRotation.quarterTurnsCw(displayRotationDegrees(m));
        }
        return 0;
    }

    /**
     * Java port of libavutil's {@code av_display_rotation_get}: the rotation (in
     * degrees, counter-clockwise) encoded by a display matrix, or {@code NaN}
     * for a singular matrix. The fixed-point columns are 16.16.
     */
    private static double displayRotationDegrees(int[] m) {
        double scale0 = Math.hypot(m[0] / 65536.0, m[3] / 65536.0);
        double scale1 = Math.hypot(m[1] / 65536.0, m[4] / 65536.0);
        if (scale0 == 0.0 || scale1 == 0.0) {
            return Double.NaN;
        }
        double rotation = Math.atan2((m[1] / 65536.0) / scale1,
                (m[0] / 65536.0) / scale0) * 180.0 / Math.PI;
        return -rotation;
    }
}
