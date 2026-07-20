package io.github.ghosthack.mediabrowser.media;

/**
 * Sizing math and a portable box-average downscale for preview renditions.
 * The native backends downscale during decode (libvips shrink-on-load,
 * FFmpeg swscale); {@link #fit} is the backend-agnostic fallback used by
 * {@link MediaFacade#loadThumbnail} when a backend has no native thumbnail
 * path and hands back a full-size raster.
 */
public final class Thumbnails {

    private Thumbnails() {}

    /**
     * The largest size that fits {@code w × h} within a {@code maxEdge} box
     * while preserving aspect ratio. Never upscales (a source already within
     * the box is returned unchanged) and never returns a zero dimension.
     */
    public static int[] fittedSize(int w, int h, int maxEdge) {
        if (w <= 0 || h <= 0) return new int[] {Math.max(1, w), Math.max(1, h)};
        int longEdge = Math.max(w, h);
        if (maxEdge <= 0 || longEdge <= maxEdge) return new int[] {w, h};
        double scale = maxEdge / (double) longEdge;
        return new int[] {Math.max(1, (int) Math.round(w * scale)),
                          Math.max(1, (int) Math.round(h * scale))};
    }

    /**
     * Backend-agnostic downscale honouring the requested {@link ThumbnailMode}:
     * {@link #fit} for {@code FIT}, {@link #fill} (centre-cropped square) for
     * {@code FILL}. The JVM fallback dispatch for {@link MediaFacade#loadThumbnail}.
     */
    public static RasterFrame scale(RasterFrame src, int maxEdge, ThumbnailMode mode) {
        return mode == ThumbnailMode.FILL ? fill(src, maxEdge) : fit(src, maxEdge);
    }

    /**
     * Box-average downscale of a BGRA frame to fit within {@code maxEdge},
     * preserving aspect ratio. Returns {@code src} unchanged when it already
     * fits (thumbnails never upscale). Averages each channel over the source
     * box mapping to a destination pixel — adequate quality for a fallback.
     */
    public static RasterFrame fit(RasterFrame src, int maxEdge) {
        int sw = src.width(), sh = src.height();
        int[] dst = fittedSize(sw, sh, maxEdge);
        int dw = dst[0], dh = dst[1];
        if (dw == sw && dh == sh) return src;

        byte[] in = src.bgra();
        byte[] out = new byte[dw * dh * 4];
        for (int dy = 0; dy < dh; dy++) {
            int sy0 = (int) ((long) dy * sh / dh);
            int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * sh / dh));
            for (int dx = 0; dx < dw; dx++) {
                int sx0 = (int) ((long) dx * sw / dw);
                int sx1 = Math.max(sx0 + 1, (int) ((long) (dx + 1) * sw / dw));
                long b = 0, g = 0, r = 0, a = 0;
                int count = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int row = sy * sw * 4;
                    for (int sx = sx0; sx < sx1; sx++) {
                        int s = row + sx * 4;
                        b += in[s] & 0xff;
                        g += in[s + 1] & 0xff;
                        r += in[s + 2] & 0xff;
                        a += in[s + 3] & 0xff;
                        count++;
                    }
                }
                int d = (dy * dw + dx) * 4;
                out[d] = (byte) (b / count);
                out[d + 1] = (byte) (g / count);
                out[d + 2] = (byte) (r / count);
                out[d + 3] = (byte) (a / count);
            }
        }
        return new RasterFrame(dw, dh, out);
    }

    /**
     * Crop-to-fill box-average downscale: centre-crops {@code src} to a square
     * and shrinks it to {@code side × side}, where {@code side} is the smaller
     * of {@code maxEdge} and the source's shorter edge (never upscales). Used
     * for the seamless square mosaic ({@link ThumbnailMode#FILL}).
     */
    public static RasterFrame fill(RasterFrame src, int maxEdge) {
        int sw = src.width(), sh = src.height();
        int crop = Math.min(sw, sh);                       // source square edge
        int side = maxEdge <= 0 ? crop : Math.min(maxEdge, crop);
        int ox = (sw - crop) / 2, oy = (sh - crop) / 2;    // centre the crop

        byte[] in = src.bgra();
        byte[] out = new byte[side * side * 4];
        for (int dy = 0; dy < side; dy++) {
            int sy0 = oy + (int) ((long) dy * crop / side);
            int sy1 = Math.max(sy0 + 1, oy + (int) ((long) (dy + 1) * crop / side));
            for (int dx = 0; dx < side; dx++) {
                int sx0 = ox + (int) ((long) dx * crop / side);
                int sx1 = Math.max(sx0 + 1, ox + (int) ((long) (dx + 1) * crop / side));
                long b = 0, g = 0, r = 0, a = 0;
                int count = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int row = sy * sw * 4;
                    for (int sx = sx0; sx < sx1; sx++) {
                        int s = row + sx * 4;
                        b += in[s] & 0xff;
                        g += in[s + 1] & 0xff;
                        r += in[s + 2] & 0xff;
                        a += in[s + 3] & 0xff;
                        count++;
                    }
                }
                int d = (dy * side + dx) * 4;
                out[d] = (byte) (b / count);
                out[d + 1] = (byte) (g / count);
                out[d + 2] = (byte) (r / count);
                out[d + 3] = (byte) (a / count);
            }
        }
        return new RasterFrame(side, side, out);
    }

    /**
     * Centre-crops an already-scaled BGRA frame to {@code side × side} (no
     * resampling). Used by the native FFmpeg path, which cover-scales with
     * swscale and then crops the square out. Returns {@code src} unchanged when
     * it is already that square.
     */
    public static RasterFrame cropCenterSquare(RasterFrame src, int side) {
        int sw = src.width(), sh = src.height();
        side = Math.min(side, Math.min(sw, sh));
        if (sw == side && sh == side) return src;
        int ox = (sw - side) / 2, oy = (sh - side) / 2;
        byte[] in = src.bgra();
        byte[] out = new byte[side * side * 4];
        int rowBytes = side * 4;
        for (int dy = 0; dy < side; dy++) {
            int srcOff = ((oy + dy) * sw + ox) * 4;
            System.arraycopy(in, srcOff, out, dy * rowBytes, rowBytes);
        }
        return new RasterFrame(side, side, out);
    }
}
