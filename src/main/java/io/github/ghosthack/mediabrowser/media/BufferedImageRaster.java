package io.github.ghosthack.mediabrowser.media;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Shared {@link BufferedImage} → {@link RasterFrame} converter for the
 * ImageIO/AWT-based media backends (twelvemonkeys / javacv / jcodec). Produces
 * tightly packed, straight-alpha BGRA bytes (B,G,R,A; 4 bytes per pixel,
 * row-major) — the contract every {@link RasterFrame} and the GL presentation
 * path expects.
 *
 * <p>Two zero-allocation fast paths cover the rasters the real decoders hand us;
 * everything else falls through to a universal {@code getRGB} path:</p>
 * <ul>
 *   <li><b>INT fast path</b> — common straight-alpha {@code TYPE_INT_RGB}/
 *       {@code TYPE_INT_ARGB} rasters, packed straight off their backing
 *       {@link DataBufferInt} (the GIF compositor's INT_ARGB canvas lands here).</li>
 *   <li><b>byte-interleaved fast path</b> — {@code TYPE_3BYTE_BGR} (B,G,R bytes)
 *       and {@code TYPE_4BYTE_ABGR} (A,B,G,R bytes), packed straight off their
 *       backing {@link DataBufferByte}. These are exactly what JavaCV's
 *       {@code Java2DFrameConverter} and jcodec's {@code AWTUtil} emit, so the
 *       two pull-decode video backends fill their reusable native buffer with
 *       <em>zero</em> per-frame allocation (the {@code getRGB} path, by contrast,
 *       allocates a temporary per scanline inside AWT's {@code ColorModel}).</li>
 * </ul>
 *
 * <p>Every other {@link BufferedImage} type — including {@code TYPE_INT_ARGB_PRE}
 * and {@code TYPE_4BYTE_ABGR_PRE} (whose stores are premultiplied), CMYK,
 * indexed, 16-bit and gray — goes through the universal {@code getRGB} fallback,
 * which AWT renders to packed <em>straight-alpha</em> sRGB ARGB for us.
 * Premultiplied rasters must NOT take a verbatim fast path: their raw store holds
 * premultiplied channels, so copying it would corrupt semi-transparent colors
 * (it would emit premultiplied BGRA, not the contracted straight-alpha). The
 * fast paths are gated on the exact non-premultiplied types above, so this is
 * handled by type.</p>
 *
 * <p>Self-contained: imports only {@code io.github.ghosthack.mediabrowser.media.*},
 * {@code java.awt.image} and {@code java.lang.foreign}. It must not depend on
 * {@code media.pure.*} or {@code io.github.ghosthack.*}.</p>
 */
public final class BufferedImageRaster {

    private BufferedImageRaster() {}

    /** Copies a decoded image into a fresh, tightly packed BGRA {@link RasterFrame}. */
    public static RasterFrame toRaster(BufferedImage img) {
        return new RasterFrame(img.getWidth(), img.getHeight(), toBgra(img));
    }

    /** Tightly packed, straight-alpha BGRA bytes (B,G,R,A) for a decoded image. */
    public static byte[] toBgra(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] dst = new byte[Math.multiplyExact(Math.multiplyExact(w, h), 4)];
        writeBgra(img, dst);
        return dst;
    }

    /**
     * Writes a decoded image as BGRA into {@code dst} (a native segment holding
     * at least {@code width * height * 4} bytes), allocating a one-shot
     * per-frame {@link RowScratch}. Prefer
     * {@link #writeBgra(BufferedImage, MemorySegment, RowScratch)} on a hot
     * playback path, where a stream-owned scratch makes the conversion allocate
     * <em>nothing</em> per frame.
     */
    public static void writeBgra(BufferedImage img, MemorySegment dst) {
        writeBgra(img, dst, new RowScratch(img.getWidth()));
    }

    /**
     * Writes a decoded image as BGRA into {@code dst} (a native segment holding
     * at least {@code width * height * 4} bytes), reusing the caller-owned
     * {@code scratch} for the row staging. Used by the playback streams to fill
     * their reusable native buffer on every frame with <em>zero</em> per-frame
     * heap allocation when the source is one of the fast-path rasters (INT or the
     * byte-interleaved BGR/ABGR that JavaCV and jcodec emit).
     *
     * <p>Writes row-by-row through {@code scratch} rather than materializing a
     * whole-frame {@code byte[]} first, so a stream that constructs one
     * {@link RowScratch} (sized to its fixed frame width) up front and passes it
     * on every frame allocates nothing in steady state. The byte ordering and
     * straight-alpha rules are identical to
     * {@link #writeBgra(BufferedImage, byte[])}.</p>
     *
     * @throws IllegalArgumentException if {@code scratch} is too small for
     *     {@code img}'s width
     */
    public static void writeBgra(BufferedImage img, MemorySegment dst, RowScratch scratch) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (scratch.bytes.length < w * 4 || scratch.ints.length < w) {
            throw new IllegalArgumentException("RowScratch too small: have bytes="
                    + scratch.bytes.length + " ints=" + scratch.ints.length
                    + ", need bytes>=" + (w * 4) + " ints>=" + w + " for width " + w);
        }
        long rowBytes = (long) w * 4;
        byte[] bytes = scratch.bytes;
        packRows(img, w, h, bytes, true, scratch.ints, dst, rowBytes);
    }

    /**
     * Writes a decoded image as BGRA into {@code dst} (a byte array holding at
     * least {@code width * height * 4} bytes).
     */
    public static void writeBgra(BufferedImage img, byte[] dst) {
        int w = img.getWidth();
        int h = img.getHeight();
        // No native staging: pack each row straight into dst at its scanline offset.
        // The getRGB path stages into a one-shot row buffer (this overload allocates
        // the whole-frame dst anyway, so it is not the zero-alloc playback path).
        packRows(img, w, h, dst, false, new int[w], null, 0);
    }

    /**
     * Shared row loop for both write overloads. When {@code segment} is non-null,
     * each row is packed into {@code rowDst} (the scratch row, {@code dstOff} 0)
     * and copied into the native segment; otherwise each row is packed straight
     * into {@code rowDst} (the whole-frame array) at its scanline offset. The
     * dispatch on raster type is resolved once into stack locals before the loop,
     * so the fast paths allocate <em>nothing</em> per row.
     */
    private static void packRows(BufferedImage img, int w, int h, byte[] rowDst,
                                 boolean stageToSegment, int[] rowInts,
                                 MemorySegment segment, long rowBytes) {
        DataBufferInt fastInt = fastIntBuffer(img);
        byte[] fastByte = fastInt == null ? fastByteBuffer(img) : null;
        int[] intPx = fastInt == null ? null : fastInt.getData();
        boolean intHasAlpha = img.getType() != BufferedImage.TYPE_INT_RGB;
        int byteStride = fastByte == null ? 0
                : (img.getType() == BufferedImage.TYPE_3BYTE_BGR ? 3 : 4);
        for (int y = 0; y < h; y++) {
            int dstOff = stageToSegment ? 0 : y * w * 4;
            if (intPx != null) {
                packRowInt(intPx, y * w, w, intHasAlpha, rowDst, dstOff);
            } else if (fastByte != null) {
                if (byteStride == 3) {
                    packRowBgr(fastByte, y * w * 3, w, rowDst, dstOff);
                } else {
                    packRowAbgr(fastByte, y * w * 4, w, rowDst, dstOff);
                }
            } else {
                img.getRGB(0, y, w, 1, rowInts, 0, w);
                packRowInt(rowInts, 0, w, true, rowDst, dstOff);
            }
            if (stageToSegment) {
                MemorySegment.copy(rowDst, 0, segment, ValueLayout.JAVA_BYTE, y * rowBytes, w * 4);
            }
        }
    }

    /**
     * Reusable per-stream row staging for
     * {@link BufferedImageRaster#writeBgra(BufferedImage, MemorySegment, RowScratch)}.
     * A playback stream constructs one of these sized to its fixed frame width
     * and passes it on every frame, so the BGRA conversion allocates nothing in
     * steady state. Confined to the stream's thread (not thread-safe), like the
     * native buffer it stages into.
     */
    public static final class RowScratch {
        private final byte[] bytes;
        private final int[] ints;

        /** Allocates staging for one BGRA row of {@code width} pixels. */
        public RowScratch(int width) {
            if (width <= 0) {
                throw new IllegalArgumentException("width must be positive: " + width);
            }
            this.bytes = new byte[width * 4];
            this.ints = new int[width];
        }
    }

    /**
     * The backing {@link DataBufferInt} when {@code img} is a straight-alpha
     * {@code TYPE_INT_RGB}/{@code TYPE_INT_ARGB} raster eligible for the fast
     * path; {@code null} for every other type (including premultiplied
     * {@code TYPE_INT_ARGB_PRE}, which must go through the un-premultiplying
     * {@code getRGB} path).
     *
     * <p>The fast path copies the backing {@code int[]} <em>verbatim</em>, one
     * entry per pixel, so it is only valid when the raster is tightly packed
     * starting at buffer index 0: a single bank, zero data-buffer offset, no
     * sample-model translation, a {@link SinglePixelPackedSampleModel} whose
     * {@code scanlineStride} equals the image width, and a backing array of
     * exactly {@code width * height} ints. A {@link BufferedImage#getSubimage
     * subimage} (or any strided/offset raster) fails these — it shares the
     * parent's larger buffer with {@code scanlineStride != width} and a
     * non-zero translate, so a verbatim copy would read the wrong pixels —
     * and is sent through the correct (offset/stride-aware) {@code getRGB}
     * path instead.</p>
     */
    private static DataBufferInt fastIntBuffer(BufferedImage img) {
        int type = img.getType();
        if (type != BufferedImage.TYPE_INT_RGB && type != BufferedImage.TYPE_INT_ARGB) {
            return null;
        }
        var raster = img.getRaster();
        if (!(raster.getDataBuffer() instanceof DataBufferInt buffer)) {
            return null;
        }
        if (buffer.getNumBanks() != 1 || buffer.getOffset() != 0) {
            return null;
        }
        if (raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0) {
            return null;
        }
        if (raster.getSampleModel() instanceof SinglePixelPackedSampleModel sm
                && sm.getScanlineStride() == img.getWidth()
                && buffer.getData().length == Math.multiplyExact(img.getWidth(), img.getHeight())) {
            return buffer;
        }
        return null;
    }

    /**
     * The backing {@link DataBufferByte} when {@code img} is a tightly packed,
     * non-premultiplied byte-interleaved {@code TYPE_3BYTE_BGR} (B,G,R) or
     * {@code TYPE_4BYTE_ABGR} (A,B,G,R) raster eligible for the byte fast path;
     * {@code null} otherwise (including {@code TYPE_4BYTE_ABGR_PRE}, gated out by
     * type, which goes through the un-premultiplying {@code getRGB} path).
     *
     * <p>Validated exactly like {@link #fastIntBuffer}: single bank, zero
     * data-buffer offset, no sample-model translation, and a canonical
     * {@link PixelInterleavedSampleModel} (pixel stride 3/4, scanline stride
     * {@code width * stride}, backing array of exactly
     * {@code width * height * stride} bytes). The standard band order is
     * implied: {@code getType()} returns {@code TYPE_3BYTE_BGR}/
     * {@code TYPE_4BYTE_ABGR} only for the exact B,G,R / A,B,G,R band layout, so
     * checking the type already guarantees it (and avoids the per-frame clone
     * that {@code getBandOffsets()} would allocate on this hot path). A {@link
     * BufferedImage#getSubimage subimage} or any strided/offset raster fails the
     * stride/translate/length checks and is routed through {@code getRGB}.</p>
     */
    private static byte[] fastByteBuffer(BufferedImage img) {
        int type = img.getType();
        int stride;
        if (type == BufferedImage.TYPE_3BYTE_BGR) {
            stride = 3;
        } else if (type == BufferedImage.TYPE_4BYTE_ABGR) {
            stride = 4;
        } else {
            return null;
        }
        var raster = img.getRaster();
        if (!(raster.getDataBuffer() instanceof DataBufferByte buffer)) {
            return null;
        }
        if (buffer.getNumBanks() != 1 || buffer.getOffset() != 0) {
            return null;
        }
        if (raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0) {
            return null;
        }
        if (raster.getSampleModel() instanceof PixelInterleavedSampleModel sm
                && sm.getPixelStride() == stride
                && sm.getScanlineStride() == Math.multiplyExact(img.getWidth(), stride)
                && buffer.getData().length
                        == Math.multiplyExact(Math.multiplyExact(img.getWidth(), img.getHeight()), stride)) {
            return buffer.getData();
        }
        return null;
    }

    /** Packs {@code w} ARGB ints (from {@code src} at {@code off}) as BGRA into {@code dst} at {@code dstOff}. */
    private static void packRowInt(int[] src, int off, int w, boolean hasAlpha, byte[] dst, int dstOff) {
        for (int x = 0, o = dstOff; x < w; x++, o += 4) {
            int argb = src[off + x];
            dst[o]     = (byte) argb;          // B
            dst[o + 1] = (byte) (argb >> 8);   // G
            dst[o + 2] = (byte) (argb >> 16);  // R
            dst[o + 3] = hasAlpha ? (byte) (argb >>> 24) : (byte) 0xFF;
        }
    }

    /** Packs {@code w} B,G,R byte triples (TYPE_3BYTE_BGR) as opaque BGRA into {@code dst} at {@code dstOff}. */
    private static void packRowBgr(byte[] src, int off, int w, byte[] dst, int dstOff) {
        for (int x = 0, s = off, o = dstOff; x < w; x++, s += 3, o += 4) {
            dst[o]     = src[s];       // B
            dst[o + 1] = src[s + 1];   // G
            dst[o + 2] = src[s + 2];   // R
            dst[o + 3] = (byte) 0xFF;  // A
        }
    }

    /** Packs {@code w} A,B,G,R byte quads (TYPE_4BYTE_ABGR) as straight-alpha BGRA into {@code dst} at {@code dstOff}. */
    private static void packRowAbgr(byte[] src, int off, int w, byte[] dst, int dstOff) {
        for (int x = 0, s = off, o = dstOff; x < w; x++, s += 4, o += 4) {
            dst[o]     = src[s + 1];   // B
            dst[o + 1] = src[s + 2];   // G
            dst[o + 2] = src[s + 3];   // R
            dst[o + 3] = src[s];       // A
        }
    }
}
