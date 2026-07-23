package io.github.ghosthack.mediabrowser.media.ffm.bind;

import io.github.ghosthack.mediabrowser.media.VideoRotation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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
     * when there is no display matrix. Rotation only — the video path's
     * contract; stills use {@link #exifOrientationFromSideData}, which also
     * honours mirroring.
     */
    public static int rotationQuarterTurnsFromSideData(MemorySegment sideDataArray, int count) {
        int[] m = displayMatrixFromSideData(sideDataArray, count);
        return m == null ? 0 : VideoRotation.quarterTurnsCw(displayRotationDegrees(m));
    }

    /**
     * The display matrix as a full EXIF orientation (1..8) — rotation
     * <em>and</em> mirror, so an {@code imir}-mirrored HEIF item bakes
     * correctly (the mov demuxer encodes {@code irot}/{@code imir} as
     * rotation-then-column-flip in this matrix). Returns {@code 1} when there
     * is no display matrix; a matrix that is no axis-aligned orientation
     * falls back to the nearest pure rotation, like the video path.
     */
    public static int exifOrientationFromSideData(MemorySegment sideDataArray, int count) {
        int[] m = displayMatrixFromSideData(sideDataArray, count);
        if (m == null) {
            return 1;
        }
        int orientation = exifOrientationFromDisplayMatrix(m);
        if (orientation != 0) {
            return orientation;
        }
        return EXIF_FOR_QUARTER_TURNS[
                VideoRotation.quarterTurnsCw(displayRotationDegrees(m))];
    }

    /** EXIF code realizing {@code q} clockwise quarter-turns (see RasterFrames). */
    private static final int[] EXIF_FOR_QUARTER_TURNS = {1, 6, 3, 8};

    /**
     * Maps a display matrix to the EXIF orientation (1..8) whose
     * {@code RasterFrames.applyExifOrientation} realizes the same
     * stored-to-display transform, or {@code 0} when the matrix is not an
     * axis-aligned orientation (skew, non-quarter rotation, singular).
     *
     * <p>Convention (libavutil/display.h): row vectors, image coordinates —
     * {@code (x', y') = (x·m0 + y·m3, x·m1 + y·m4)} after per-axis scale
     * normalization. The eight orientations are the eight sign patterns of an
     * orthogonal axis-aligned 2×2; mirrored ones have negative determinant.</p>
     */
    public static int exifOrientationFromDisplayMatrix(int[] m) {
        double scaleX = Math.hypot(m[0] / 65536.0, m[3] / 65536.0);
        double scaleY = Math.hypot(m[1] / 65536.0, m[4] / 65536.0);
        if (scaleX == 0.0 || scaleY == 0.0) {
            return 0;
        }
        int a = snap(m[0] / 65536.0 / scaleX);
        int c = snap(m[3] / 65536.0 / scaleX);
        int b = snap(m[1] / 65536.0 / scaleY);
        int d = snap(m[4] / 65536.0 / scaleY);
        if (a == 1 && b == 0 && c == 0 && d == 1) return 1;
        if (a == -1 && b == 0 && c == 0 && d == 1) return 2;
        if (a == -1 && b == 0 && c == 0 && d == -1) return 3;
        if (a == 1 && b == 0 && c == 0 && d == -1) return 4;
        if (a == 0 && b == 1 && c == 1 && d == 0) return 5;
        if (a == 0 && b == 1 && c == -1 && d == 0) return 6;
        if (a == 0 && b == -1 && c == -1 && d == 0) return 7;
        if (a == 0 && b == -1 && c == 1 && d == 0) return 8;
        return 0;
    }

    /** Snaps a normalized matrix entry to −1/0/1, or 2 when it is none of them (±2% tolerance). */
    private static int snap(double v) {
        if (Math.abs(v) < 0.02) return 0;
        if (Math.abs(v - 1) < 0.02) return 1;
        if (Math.abs(v + 1) < 0.02) return -1;
        return 2;
    }

    /** The nine fixed-point ints of the first display matrix in the side-data array, or null. */
    private static int[] displayMatrixFromSideData(MemorySegment sideDataArray, int count) {
        if (count <= 0 || sideDataArray == null || sideDataArray.equals(MemorySegment.NULL)) {
            return null;
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
                return null;
            }
            MemorySegment matrix = data.reinterpret(DISPLAY_MATRIX_BYTES);
            int[] m = new int[9];
            for (int k = 0; k < 9; k++) {
                m[k] = matrix.get(ValueLayout.JAVA_INT, k * 4L);
            }
            return m;
        }
        return null;
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
