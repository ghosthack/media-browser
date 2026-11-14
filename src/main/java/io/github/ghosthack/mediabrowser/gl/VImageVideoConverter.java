package io.github.ghosthack.mediabrowser.gl;

import io.github.ghosthack.panama.media.accelerate.Accelerate;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static io.github.ghosthack.panama.media.accelerate.Accelerate.*;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * macOS video frame conversion over Accelerate's vImage (the vendored
 * {@code panama-media-accelerate} binding) — the CPU alternative to the CGL
 * shader in {@link GlVideoRenderer}/{@link MujglVideoRenderer}, with no
 * OpenGL context:
 *
 * <ul>
 *   <li>{@link #convertSurface}: a VideoToolbox NV12 IOSurface frame to BGRA.
 *       For a rotation of an even-sized frame the NV12 planes (1.5 bytes/pixel)
 *       are rotated first and then converted straight into place, instead of
 *       rotating the 4-byte BGRA result; both orders give identical bytes. An
 *       odd width or height would shift the half-size chroma plane by a sample
 *       against the luma, so such frames convert first and rotate the BGRA.
 *       Chroma is taken from the nearest sample and the matrix is exact
 *       BT.709/BT.601, so the result differs from the shader (rounded
 *       coefficients, bilinear chroma) by a few levels.</li>
 *   <li>{@link #rotateBgra}: a CPU BGRA frame rotated by
 *       {@code vImageRotate90_ARGB8888} — the same pixels as
 *       {@code VideoPresenter.rotateInto}.</li>
 * </ul>
 *
 * <p>vImage multithreads each call internally. An instance holds per-session
 * scratch planes and conversion tables and is confined to one thread (the
 * playback thread).</p>
 */
public final class VImageVideoConverter {

    /** Geometry.h rotation constants, indexed by clockwise quarter-turns. */
    private static final byte[] ROTATION = {
            kRotate0DegreesClockwise,
            kRotate90DegreesClockwise,
            kRotate180DegreesClockwise,
            kRotate90DegreesCounterClockwise, // = 270° clockwise
    };

    /** Whether Accelerate and CoreVideo resolved (macOS). */
    public static final boolean AVAILABLE = Accelerate.isAvailable() && MacVideoSurfaces.AVAILABLE;

    private final Arena arena = Arena.ofConfined();
    /** ARGB → BGRA: vImage writes channel {@code permute[i]} of ARGB to byte {@code i}. */
    private final MemorySegment permute;
    private final MemorySegment backColor;
    private final MemorySegment srcY;
    private final MemorySegment srcC;
    private final MemorySegment rotY;
    private final MemorySegment rotC;
    private final MemorySegment out;
    /** Conversion tables by [bt709][fullRange], generated on first use. */
    private final MemorySegment[][] conversions = new MemorySegment[2][2];
    private MemorySegment scratchY;
    private MemorySegment scratchC;
    private MemorySegment scratchBgra;
    private boolean closed;

    /** @throws UnsupportedOperationException off macOS */
    public VImageVideoConverter() {
        if (!AVAILABLE) {
            throw new UnsupportedOperationException("vImage (Accelerate) is macOS-only");
        }
        permute = arena.allocateFrom(JAVA_BYTE, (byte) 3, (byte) 2, (byte) 1, (byte) 0);
        backColor = arena.allocate(4);
        srcY = allocateBuffer(arena);
        srcC = allocateBuffer(arena);
        rotY = allocateBuffer(arena);
        rotC = allocateBuffer(arena);
        out = allocateBuffer(arena);
    }

    /**
     * Converts a {@code width × height} NV12 CVPixelBuffer ('420v'/'420f') to
     * top-down BGRA in {@code dst}, rotated by {@code quarterTurns} clockwise
     * (the output is height × width for odd turns). Returns {@code false} when
     * the buffer is not NV12 (e.g. 10-bit P010), leaving {@code dst} untouched.
     *
     * @param bt709 BT.709 matrix, else BT.601
     * @param fullRange full (JPEG) levels; '420f' buffers are full range regardless
     */
    public boolean convertSurface(long cvPixelBuffer, int width, int height, int quarterTurns,
                                  boolean bt709, boolean fullRange, ByteBuffer dst) {
        int pixelFormat = MacVideoSurfaces.pixelFormatType(cvPixelBuffer);
        if (pixelFormat != MacVideoSurfaces.PIXEL_FORMAT_NV12_VIDEO
                && pixelFormat != MacVideoSurfaces.PIXEL_FORMAT_NV12_FULL) {
            return false;
        }
        boolean full = fullRange || pixelFormat == MacVideoSurfaces.PIXEL_FORMAT_NV12_FULL;
        MemorySegment info = conversion(bt709, full);
        int q = Math.floorMod(quarterTurns, 4);
        boolean swap = (q & 1) == 1;
        int ow = swap ? height : width;
        int oh = swap ? width : height;
        int cw = (width + 1) / 2;
        int ch = (height + 1) / 2;
        check(MacVideoSurfaces.lockBaseAddress(cvPixelBuffer, MacVideoSurfaces.LOCK_READ_ONLY),
                "CVPixelBufferLockBaseAddress");
        try {
            setBuffer(srcY, MacVideoSurfaces.baseAddressOfPlane(cvPixelBuffer, 0), height, width,
                    MacVideoSurfaces.bytesPerRowOfPlane(cvPixelBuffer, 0));
            setBuffer(srcC, MacVideoSurfaces.baseAddressOfPlane(cvPixelBuffer, 1), ch, cw,
                    MacVideoSurfaces.bytesPerRowOfPlane(cvPixelBuffer, 1));
            if (q != 0 && ((width | height) & 1) != 0) {
                // Odd size: convert upright, then rotate the BGRA.
                long bytes = (long) width * height * 4;
                if (scratchBgra == null || scratchBgra.byteSize() < bytes) {
                    scratchBgra = arena.allocate(bytes, 64);
                }
                setBuffer(out, scratchBgra, height, width, width * 4L);
                check(vImageConvert420Yp8CbCr8ToARGB8888(srcY, srcC, out, info, permute, (byte) -1,
                        kvImageNoFlags), "vImageConvert_420Yp8_CbCr8ToARGB8888");
                setBuffer(rotY, scratchBgra, height, width, width * 4L);
                setBuffer(out, MemorySegment.ofBuffer(dst), oh, ow, ow * 4L);
                check(vImageRotate90ARGB8888(rotY, out, ROTATION[q], backColor, kvImageNoFlags),
                        "vImageRotate90_ARGB8888");
                return true;
            }
            MemorySegment y = srcY;
            MemorySegment c = srcC;
            if (q != 0) {
                ensureScratch((long) width * height, (long) cw * ch * 2);
                int cow = swap ? ch : cw;
                int coh = swap ? cw : ch;
                setBuffer(rotY, scratchY, oh, ow, ow);
                setBuffer(rotC, scratchC, coh, cow, cow * 2L);
                check(vImageRotate90Planar8(srcY, rotY, ROTATION[q], (byte) 0, kvImageNoFlags),
                        "vImageRotate90_Planar8");
                // CbCr pairs rotate as one 16-bit unit, keeping Cb before Cr.
                check(vImageRotate90Planar16U(srcC, rotC, ROTATION[q], (short) 0, kvImageNoFlags),
                        "vImageRotate90_Planar16U");
                y = rotY;
                c = rotC;
            }
            setBuffer(out, MemorySegment.ofBuffer(dst), oh, ow, ow * 4L);
            check(vImageConvert420Yp8CbCr8ToARGB8888(y, c, out, info, permute, (byte) -1,
                    kvImageNoFlags), "vImageConvert_420Yp8_CbCr8ToARGB8888");
            return true;
        } finally {
            MacVideoSurfaces.unlockBaseAddress(cvPixelBuffer, MacVideoSurfaces.LOCK_READ_ONLY);
        }
    }

    /**
     * Rotates a tightly packed BGRA frame by {@code quarterTurns} clockwise into
     * {@code dst} (a straight copy for zero turns); byte-identical to
     * {@code VideoPresenter.rotateInto}.
     */
    public void rotateBgra(MemorySegment bgra, int width, int height, int quarterTurns,
                           ByteBuffer dst) {
        int q = Math.floorMod(quarterTurns, 4);
        MemorySegment target = MemorySegment.ofBuffer(dst);
        if (q == 0) {
            MemorySegment.copy(bgra, 0, target, 0, (long) width * height * 4);
            return;
        }
        boolean swap = (q & 1) == 1;
        int ow = swap ? height : width;
        int oh = swap ? width : height;
        setBuffer(srcY, bgra, height, width, width * 4L);
        setBuffer(out, target, oh, ow, ow * 4L);
        check(vImageRotate90ARGB8888(srcY, out, ROTATION[q], backColor, kvImageNoFlags),
                "vImageRotate90_ARGB8888");
    }

    private MemorySegment conversion(boolean bt709, boolean fullRange) {
        int m = bt709 ? 1 : 0;
        int r = fullRange ? 1 : 0;
        if (conversions[m][r] == null) {
            MemorySegment info = arena.allocate(VIMAGE_YPCBCR_TO_ARGB);
            check(vImageConvertYpCbCrToARGBGenerateConversion(
                    bt709 ? ypCbCrToARGBMatrixITU709() : ypCbCrToARGBMatrixITU601(),
                    fullRange ? pixelRange8BitFull(arena) : pixelRange8BitVideo(arena), info,
                    kvImage420Yp8_CbCr8, kvImageARGB8888, kvImageNoFlags),
                    "vImageConvert_YpCbCrToARGB_GenerateConversion");
            conversions[m][r] = info;
        }
        return conversions[m][r];
    }

    private void ensureScratch(long yBytes, long cBytes) {
        if (scratchY == null || scratchY.byteSize() < yBytes) scratchY = arena.allocate(yBytes, 64);
        if (scratchC == null || scratchC.byteSize() < cBytes) scratchC = arena.allocate(cBytes, 64);
    }

    private static void check(long error, String call) {
        if (error != kvImageNoError) {
            throw new IllegalStateException(call + " failed (vImage_Error " + error + ")");
        }
    }

    private static void check(int status, String call) {
        if (status != 0) {
            throw new IllegalStateException(call + " failed (CVReturn " + status + ")");
        }
    }

    /** Frees the scratch planes and tables; idempotent, on the owning thread. */
    public void close() {
        if (closed) return;
        closed = true;
        arena.close();
    }
}
