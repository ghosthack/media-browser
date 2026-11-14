package io.github.ghosthack.panama.media.accelerate;

import io.github.ghosthack.panama.media.core.Platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Panama FFM bindings to macOS Accelerate's vImage: the Y'CbCr → ARGB
 * conversion and 90° rotations a video presenter needs, and nothing more.
 * <p>
 * Raw bindings: every {@code vImage*} method returns the native
 * {@code vImage_Error} ({@link #kvImageNoError} on success) and leaves
 * checking to the caller; buffers are caller-owned {@link MemorySegment}s
 * described by {@link #VIMAGE_BUFFER} structs. Constant values are those of
 * the macOS SDK headers (vImage_Types.h, Geometry.h, Conversion.h).
 * <p>
 * vImage multithreads each call internally unless {@link #kvImageDoNotTile}
 * is passed. All handles are {@code null} on non-macOS platforms so the class
 * loads safely on any OS; call {@link #isAvailable()} first.
 * <p>
 * Thread-safe when each thread uses its own buffers and {@link Arena}.
 */
public final class Accelerate {

    private Accelerate() {}

    // ── OS guard ────────────────────────────────────────────────────────

    private static final boolean IS_MACOS = Platform.IS_MAC;

    // ── Constants (vImage_Types.h, Geometry.h) ──────────────────────────

    /** {@code vImage_Error} success. */
    public static final long kvImageNoError = 0;

    /** {@code vImage_Flags}: default behaviour (internally multithreaded). */
    public static final int kvImageNoFlags = 0;

    /** {@code vImage_Flags}: disable vImage's internal multithreading. */
    public static final int kvImageDoNotTile = 16;

    /** {@code vImageARGBType}: any 8-bit, four-channel interleaved buffer. */
    public static final int kvImageARGB8888 = 0;

    /** {@code vImageYpCbCrType}: '420v' / '420f' — 8-bit Y' plane plus interleaved CbCr plane (NV12). */
    public static final int kvImage420Yp8_CbCr8 = 4;

    /** {@code vImageRotate90_*} rotation constants (Geometry.h). */
    public static final byte kRotate0DegreesClockwise = 0;
    public static final byte kRotate90DegreesCounterClockwise = 1;
    public static final byte kRotate180DegreesClockwise = 2;
    public static final byte kRotate90DegreesClockwise = 3;

    // ── Layouts ─────────────────────────────────────────────────────────

    /** {@code vImage_Buffer { void *data; vImagePixelCount height, width; size_t rowBytes; }} (LP64). */
    public static final StructLayout VIMAGE_BUFFER = MemoryLayout.structLayout(
            ADDRESS.withName("data"),
            JAVA_LONG.withName("height"),
            JAVA_LONG.withName("width"),
            JAVA_LONG.withName("rowBytes"));

    /** {@code vImage_YpCbCrPixelRange}: eight {@code int32_t} fields. */
    public static final StructLayout VIMAGE_YPCBCR_PIXEL_RANGE = MemoryLayout.structLayout(
            JAVA_INT.withName("Yp_bias"),
            JAVA_INT.withName("CbCr_bias"),
            JAVA_INT.withName("YpRangeMax"),
            JAVA_INT.withName("CbCrRangeMax"),
            JAVA_INT.withName("YpMax"),
            JAVA_INT.withName("YpMin"),
            JAVA_INT.withName("CbCrMax"),
            JAVA_INT.withName("CbCrMin"));

    /** {@code vImage_YpCbCrToARGB}: an opaque, 16-byte aligned 128-byte conversion table. */
    public static final MemoryLayout VIMAGE_YPCBCR_TO_ARGB =
            MemoryLayout.sequenceLayout(128, JAVA_BYTE).withByteAlignment(16);

    private static final long DATA = VIMAGE_BUFFER.byteOffset(groupElement("data"));
    private static final long HEIGHT = VIMAGE_BUFFER.byteOffset(groupElement("height"));
    private static final long WIDTH = VIMAGE_BUFFER.byteOffset(groupElement("width"));
    private static final long ROW_BYTES = VIMAGE_BUFFER.byteOffset(groupElement("rowBytes"));

    // ── Method handles ──────────────────────────────────────────────────

    private static final MethodHandle H_GENERATE_CONVERSION;
    private static final MethodHandle H_CONVERT_420YP8_CBCR8_TO_ARGB8888;
    private static final MethodHandle H_ROTATE90_ARGB8888;
    private static final MethodHandle H_ROTATE90_PLANAR8;
    private static final MethodHandle H_ROTATE90_PLANAR16U;

    /** {@code const vImage_YpCbCrToARGBMatrix *} values, read from the exported variables. */
    private static final MemorySegment MATRIX_ITU_R_709_2;
    private static final MemorySegment MATRIX_ITU_R_601_4;

    // ── Static initializer ─────────────────────────────────────────────

    static {
        if (IS_MACOS) {
            System.load("/System/Library/Frameworks/Accelerate.framework/Accelerate");
            SymbolLookup lookup = SymbolLookup.loaderLookup();
            Linker linker = Linker.nativeLinker();

            // vImage_Error vImageConvert_YpCbCrToARGB_GenerateConversion(
            //     const vImage_YpCbCrToARGBMatrix *, const vImage_YpCbCrPixelRange *,
            //     vImage_YpCbCrToARGB *, vImageYpCbCrType, vImageARGBType, vImage_Flags)
            H_GENERATE_CONVERSION = linker.downcallHandle(
                    lookup.findOrThrow("vImageConvert_YpCbCrToARGB_GenerateConversion"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            // vImage_Error vImageConvert_420Yp8_CbCr8ToARGB8888(const vImage_Buffer *srcYp,
            //     const vImage_Buffer *srcCbCr, const vImage_Buffer *dest,
            //     const vImage_YpCbCrToARGB *, const uint8_t permuteMap[4], uint8_t alpha, vImage_Flags)
            H_CONVERT_420YP8_CBCR8_TO_ARGB8888 = linker.downcallHandle(
                    lookup.findOrThrow("vImageConvert_420Yp8_CbCr8ToARGB8888"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                            JAVA_BYTE, JAVA_INT));
            // vImage_Error vImageRotate90_ARGB8888(const vImage_Buffer *, const vImage_Buffer *,
            //     uint8_t rotationConstant, const Pixel_8888 backColor, vImage_Flags)
            H_ROTATE90_ARGB8888 = linker.downcallHandle(
                    lookup.findOrThrow("vImageRotate90_ARGB8888"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_BYTE, ADDRESS, JAVA_INT));
            // vImage_Error vImageRotate90_Planar8(..., uint8_t rotationConstant, Pixel_8 backColor, vImage_Flags)
            H_ROTATE90_PLANAR8 = linker.downcallHandle(
                    lookup.findOrThrow("vImageRotate90_Planar8"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_BYTE, JAVA_BYTE, JAVA_INT));
            // vImage_Error vImageRotate90_Planar16U(..., uint8_t rotationConstant, Pixel_16U backColor, vImage_Flags)
            H_ROTATE90_PLANAR16U = linker.downcallHandle(
                    lookup.findOrThrow("vImageRotate90_Planar16U"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_BYTE, JAVA_SHORT, JAVA_INT));

            MATRIX_ITU_R_709_2 = lookup.findOrThrow("kvImage_YpCbCrToARGBMatrix_ITU_R_709_2")
                    .reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
            MATRIX_ITU_R_601_4 = lookup.findOrThrow("kvImage_YpCbCrToARGBMatrix_ITU_R_601_4")
                    .reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        } else {
            H_GENERATE_CONVERSION = null;
            H_CONVERT_420YP8_CBCR8_TO_ARGB8888 = null;
            H_ROTATE90_ARGB8888 = null;
            H_ROTATE90_PLANAR8 = null;
            H_ROTATE90_PLANAR16U = null;
            MATRIX_ITU_R_709_2 = null;
            MATRIX_ITU_R_601_4 = null;
        }
    }

    /** {@code true} on macOS, where Accelerate is part of the OS. */
    public static boolean isAvailable() {
        return IS_MACOS;
    }

    // ── Struct helpers ──────────────────────────────────────────────────

    /** Allocates an uninitialised {@link #VIMAGE_BUFFER}. */
    public static MemorySegment allocateBuffer(Arena arena) {
        return arena.allocate(VIMAGE_BUFFER);
    }

    /** Fills a {@link #VIMAGE_BUFFER}: {@code height} rows of {@code width} pixels, {@code rowBytes} apart. */
    public static void setBuffer(MemorySegment buffer, MemorySegment data, long height, long width,
                                 long rowBytes) {
        buffer.set(ADDRESS, DATA, data);
        buffer.set(JAVA_LONG, HEIGHT, height);
        buffer.set(JAVA_LONG, WIDTH, width);
        buffer.set(JAVA_LONG, ROW_BYTES, rowBytes);
    }

    /** A {@link #VIMAGE_YPCBCR_PIXEL_RANGE} in field order. */
    public static MemorySegment pixelRange(Arena arena, int ypBias, int cbCrBias, int ypRangeMax,
                                           int cbCrRangeMax, int ypMax, int ypMin, int cbCrMax,
                                           int cbCrMin) {
        return arena.allocateFrom(JAVA_INT, ypBias, cbCrBias, ypRangeMax, cbCrRangeMax,
                ypMax, ypMin, cbCrMax, cbCrMin);
    }

    /** The 8-bit video-range (16–235 luma, 16–240 chroma) pixel range from the vImage documentation. */
    public static MemorySegment pixelRange8BitVideo(Arena arena) {
        return pixelRange(arena, 16, 128, 235, 240, 235, 16, 240, 16);
    }

    /** The 8-bit full-range (0–255) pixel range. */
    public static MemorySegment pixelRange8BitFull(Arena arena) {
        return pixelRange(arena, 0, 128, 255, 255, 255, 0, 255, 0);
    }

    /** {@code kvImage_YpCbCrToARGBMatrix_ITU_R_709_2} (BT.709). */
    public static MemorySegment ypCbCrToARGBMatrixITU709() {
        return need(MATRIX_ITU_R_709_2);
    }

    /** {@code kvImage_YpCbCrToARGBMatrix_ITU_R_601_4} (BT.601). */
    public static MemorySegment ypCbCrToARGBMatrixITU601() {
        return need(MATRIX_ITU_R_601_4);
    }

    // ── Functions ───────────────────────────────────────────────────────

    /**
     * {@code vImageConvert_YpCbCrToARGB_GenerateConversion}: fills
     * {@code outInfo} (a {@link #VIMAGE_YPCBCR_TO_ARGB}) for
     * {@link #vImageConvert420Yp8CbCr8ToARGB8888}.
     */
    public static long vImageConvertYpCbCrToARGBGenerateConversion(MemorySegment matrix,
            MemorySegment pixelRange, MemorySegment outInfo, int inYpCbCrType, int outARGBType,
            int flags) {
        try {
            return (long) need(H_GENERATE_CONVERSION).invokeExact(matrix, pixelRange, outInfo,
                    inYpCbCrType, outARGBType, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code vImageConvert_420Yp8_CbCr8ToARGB8888}: NV12 planes to four
     * interleaved 8-bit channels. {@code permuteMap} (4 bytes) picks the output
     * order — {@code {3, 2, 1, 0}} for BGRA; {@code alpha} fills the alpha channel.
     */
    public static long vImageConvert420Yp8CbCr8ToARGB8888(MemorySegment srcYp, MemorySegment srcCbCr,
            MemorySegment dest, MemorySegment info, MemorySegment permuteMap, byte alpha, int flags) {
        try {
            return (long) need(H_CONVERT_420YP8_CBCR8_TO_ARGB8888).invokeExact(srcYp, srcCbCr, dest,
                    info, permuteMap, alpha, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vImageRotate90_ARGB8888}; {@code backColor} is a 4-byte {@code Pixel_8888}. */
    public static long vImageRotate90ARGB8888(MemorySegment src, MemorySegment dest, byte rotationConstant,
                                              MemorySegment backColor, int flags) {
        try {
            return (long) need(H_ROTATE90_ARGB8888).invokeExact(src, dest, rotationConstant, backColor, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vImageRotate90_Planar8} — e.g. an NV12 luma plane. */
    public static long vImageRotate90Planar8(MemorySegment src, MemorySegment dest, byte rotationConstant,
                                             byte backColor, int flags) {
        try {
            return (long) need(H_ROTATE90_PLANAR8).invokeExact(src, dest, rotationConstant, backColor, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code vImageRotate90_Planar16U} — each 16-bit pixel moves as one unit,
     * so an interleaved CbCr plane rotates with its pairs intact.
     */
    public static long vImageRotate90Planar16U(MemorySegment src, MemorySegment dest, byte rotationConstant,
                                               short backColor, int flags) {
        try {
            return (long) need(H_ROTATE90_PLANAR16U).invokeExact(src, dest, rotationConstant, backColor, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private static <T> T need(T handle) {
        if (handle == null) {
            throw new UnsupportedOperationException("Accelerate (vImage) is macOS-only");
        }
        return handle;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
