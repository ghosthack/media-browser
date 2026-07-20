package io.github.ghosthack.mediabrowser.media;

/**
 * Public rotation utilities for tightly packed BGRA {@link RasterFrame}s, usable
 * from the {@code ui} package (unlike the package-private
 * {@code media.pure.Orientation}, which now delegates here so there is a single
 * permutation implementation).
 *
 * <p>{@link #rotateCw} applies a <em>user</em> rotation in 90&deg; clockwise
 * quarter-turns on top of an already-upright frame; {@link #applyExifOrientation}
 * realizes a full EXIF orientation (1..8) and is what the pure decoders use to
 * bake stored orientation into the pixels. {@link #applyAae} composes an Apple
 * Photos {@code .AAE} edit (crop / straighten) on top of the upright frame — the
 * same "above the decoders" seam user rotation uses (see {@link AaeSidecar}).
 * {@link #apply} realizes a whole {@link Adjustments} set (rotation, mirror,
 * grayscale, invert) in one place, and {@link #mirror}, {@link #grayscale} and
 * {@link #invert} are its individual non-destructive operations.
 */
public final class RasterFrames {

    private RasterFrames() {}

    /**
     * EXIF orientation code realizing {@code q} clockwise quarter-turns of an
     * already-upright frame: {@code 0->1} (identity), {@code 1->6} (rotate 90 CW),
     * {@code 2->3} (rotate 180), {@code 3->8} (rotate 90 CCW = 270 CW).
     */
    private static final int[] EXIF_FOR_QUARTER_TURNS = {1, 6, 3, 8};

    /**
     * Rotates {@code src} by {@code quarterTurns} 90&deg; steps clockwise (any
     * integer; taken mod&nbsp;4). Odd turns swap width and height. A zero turn
     * (mod&nbsp;4) returns {@code src} unchanged (no copy).
     */
    public static RasterFrame rotateCw(RasterFrame src, int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        return applyExifOrientation(src, EXIF_FOR_QUARTER_TURNS[q]);
    }

    /**
     * Realizes a complete {@link Adjustments} set onto {@code src}, composing (in
     * order) the user rotation, then the mirror(s), then the colour operations
     * (grayscale, invert) — the same order the mosaic applies at draw time.
     * Returns {@code src} unchanged for the identity adjustment (no copy); each
     * applied step allocates once, so the worst case is a few O(W&middot;H)
     * passes on a single interactive bake (no per-frame use). The two colour
     * operations commute, so their relative order is immaterial.
     */
    public static RasterFrame apply(RasterFrame src, Adjustments adj) {
        if (src == null || adj == null || adj.isIdentity()) {
            return src;
        }
        RasterFrame out = rotateCw(src, adj.quarterTurnsCw());
        out = mirror(out, adj.mirrorH(), adj.mirrorV());
        if (adj.grayscale()) {
            out = grayscale(out);
        }
        if (adj.invert()) {
            out = invert(out);
        }
        return out;
    }

    /**
     * Mirrors {@code src} horizontally and/or vertically (canvas size unchanged),
     * returning a new frame. Returns {@code src} unchanged (no copy) when neither
     * axis is requested.
     */
    public static RasterFrame mirror(RasterFrame src, boolean horizontal, boolean vertical) {
        if (src == null || (!horizontal && !vertical)) {
            return src;
        }
        int w = src.width();
        int h = src.height();
        byte[] in = src.bgra();
        byte[] out = new byte[in.length];
        for (int oy = 0; oy < h; oy++) {
            int sy = vertical ? h - 1 - oy : oy;
            for (int ox = 0; ox < w; ox++) {
                int sx = horizontal ? w - 1 - ox : ox;
                int s = (sy * w + sx) * 4;
                int d = (oy * w + ox) * 4;
                out[d]     = in[s];
                out[d + 1] = in[s + 1];
                out[d + 2] = in[s + 2];
                out[d + 3] = in[s + 3];
            }
        }
        return new RasterFrame(w, h, out);
    }

    /**
     * Returns a desaturated (black&amp;white) copy of {@code src}: every pixel's
     * colour channels are replaced by its Rec.&nbsp;709 luma
     * ({@code 0.2126R + 0.7152G + 0.0722B}); alpha is preserved. The same
     * coefficients are used by the mosaic's draw-time colour pass so a tile and
     * its open viewer match.
     */
    public static RasterFrame grayscale(RasterFrame src) {
        if (src == null) {
            return null;
        }
        byte[] in = src.bgra();
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i += 4) {
            int b = in[i] & 0xFF;
            int g = in[i + 1] & 0xFF;
            int r = in[i + 2] & 0xFF;
            int y = luma(r, g, b);
            out[i]     = (byte) y;
            out[i + 1] = (byte) y;
            out[i + 2] = (byte) y;
            out[i + 3] = in[i + 3];
        }
        return new RasterFrame(src.width(), src.height(), out);
    }

    /**
     * Returns a colour-inverted (photographic negative) copy of {@code src}: each
     * colour channel becomes {@code 255 - value}; alpha is preserved.
     */
    public static RasterFrame invert(RasterFrame src) {
        if (src == null) {
            return null;
        }
        byte[] in = src.bgra();
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i += 4) {
            out[i]     = (byte) (255 - (in[i] & 0xFF));
            out[i + 1] = (byte) (255 - (in[i + 1] & 0xFF));
            out[i + 2] = (byte) (255 - (in[i + 2] & 0xFF));
            out[i + 3] = in[i + 3];
        }
        return new RasterFrame(src.width(), src.height(), out);
    }

    /** Rec.&nbsp;709 luma of an 8-bit RGB triple, rounded to {@code 0..255}. */
    public static int luma(int r, int g, int b) {
        return (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    /**
     * Rotates a tightly-packed BGRA buffer {@code src} ({@code srcW × srcH}) by
     * {@code quarterTurns} 90&deg; steps clockwise, writing the result into the
     * caller-provided {@code dst}. Odd turns swap the output's width and height;
     * {@code dst} must be {@code srcW * srcH * 4} bytes either way (the pixel
     * count is preserved). Unlike {@link #rotateCw} this allocates nothing,
     * letting per-frame video paths reuse scratch arrays.
     */
    public static void rotateCwInto(byte[] src, int srcW, int srcH, int quarterTurns, byte[] dst) {
        int q = ((quarterTurns % 4) + 4) % 4;
        int n = srcW * srcH * 4;
        if (q == 0) {
            System.arraycopy(src, 0, dst, 0, n);
            return;
        }
        boolean swap = (q & 1) != 0;
        int ow = swap ? srcH : srcW;
        int oh = swap ? srcW : srcH;
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int sx;
                int sy;
                switch (q) {
                    case 1 -> { sx = oy;            sy = srcH - 1 - ox; } // 90 CW
                    case 2 -> { sx = srcW - 1 - ox; sy = srcH - 1 - oy; } // 180
                    default -> { sx = srcW - 1 - oy; sy = ox; }          // 270 CW (q == 3)
                }
                int s = (sy * srcW + sx) * 4;
                int d = (oy * ow + ox) * 4;
                dst[d]     = src[s];
                dst[d + 1] = src[s + 1];
                dst[d + 2] = src[s + 2];
                dst[d + 3] = src[s + 3];
            }
        }
    }

    /**
     * Applies an EXIF orientation (1..8) to a tightly packed BGRA frame,
     * returning an upright copy. Orientations 5..8 swap width and height.
     * Orientation 1 (or an out-of-range value) returns {@code src} unchanged.
     */
    public static RasterFrame applyExifOrientation(RasterFrame src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src; // 1 (or unknown) = already upright
        }
        int w = src.width();
        int h = src.height();
        boolean swap = orientation >= 5;
        int ow = swap ? h : w;
        int oh = swap ? w : h;
        byte[] in = src.bgra();
        byte[] out = new byte[ow * oh * 4];

        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int sx;
                int sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - ox; sy = oy; }            // flip horizontal
                    case 3 -> { sx = w - 1 - ox; sy = h - 1 - oy; }    // rotate 180
                    case 4 -> { sx = ox;         sy = h - 1 - oy; }    // flip vertical
                    case 5 -> { sx = oy;         sy = ox; }            // transpose
                    case 6 -> { sx = oy;         sy = h - 1 - ox; }    // rotate 90 CW
                    case 7 -> { sx = w - 1 - oy; sy = h - 1 - ox; }    // transverse
                    case 8 -> { sx = w - 1 - oy; sy = ox; }            // rotate 90 CCW
                    default -> { sx = ox;        sy = oy; }
                }
                int s = (sy * w + sx) * 4;
                int d = (oy * ow + ox) * 4;
                out[d]     = in[s];
                out[d + 1] = in[s + 1];
                out[d + 2] = in[s + 2];
                out[d + 3] = in[s + 3];
            }
        }
        return new RasterFrame(ow, oh, out);
    }

    /**
     * Composes a parsed {@link AaeSidecar}'s recoverable geometry (straighten,
     * then crop) onto an already-upright frame, returning the edited frame.
     * A no-op (returns {@code src}) when the sidecar has no geometry.
     *
     * <p>The decoder has already baked EXIF/container orientation, so the
     * sidecar's master space should match {@code src} exactly; when the
     * dimensions disagree we <em>skip the edit</em> (return the untouched
     * master) rather than crop the wrong region — better no edit than a wrong
     * one. User rotation, if any, is applied <em>after</em> this (the AAE edit
     * is part of the "image", the user turn sits on top), so callers bake
     * {@code rotateCw(applyAae(upright, aae), userTurns)}.
     */
    public static RasterFrame applyAae(RasterFrame src, AaeSidecar aae) {
        if (src == null || aae == null || !aae.hasGeometry()) {
            return src;
        }
        if (src.width() != aae.masterWidth() || src.height() != aae.masterHeight()) {
            return src; // our oriented space disagrees with the sidecar's; don't guess
        }
        RasterFrame out = src;
        if (aae.straightenAngleRadians() != 0) {
            out = straighten(out, aae.straightenAngleRadians());
        }
        var crop = aae.crop();
        if (crop.isPresent()) {
            AaeSidecar.Crop c = crop.get();
            out = crop(out, c.x(), c.y(), c.width(), c.height());
        }
        return out;
    }

    /**
     * Returns the axis-aligned sub-rectangle {@code (x,y,w,h)} of {@code src} as
     * a new frame, clamping the rectangle to the frame bounds. Returns
     * {@code src} unchanged when the (clamped) rectangle covers the whole frame,
     * and likewise when it degenerates to nothing (a safe no-op).
     */
    public static RasterFrame crop(RasterFrame src, int x, int y, int w, int h) {
        int sx = Math.max(0, x);
        int sy = Math.max(0, y);
        int ex = Math.min(src.width(), x + w);
        int ey = Math.min(src.height(), y + h);
        int cw = ex - sx;
        int ch = ey - sy;
        if (cw <= 0 || ch <= 0) {
            return src;
        }
        if (sx == 0 && sy == 0 && cw == src.width() && ch == src.height()) {
            return src;
        }
        byte[] in = src.bgra();
        byte[] out = new byte[cw * ch * 4];
        int rowBytes = cw * 4;
        for (int row = 0; row < ch; row++) {
            int s = ((sy + row) * src.width() + sx) * 4;
            System.arraycopy(in, s, out, row * rowBytes, rowBytes);
        }
        return new RasterFrame(cw, ch, out);
    }

    /**
     * Rotates {@code src}'s content about its centre by {@code angleRadians}
     * (bilinear sampling, canvas size unchanged, out-of-frame samples left
     * transparent) — the realization of an AAE {@code straightenAngle}.
     *
     * <p><b>Best-effort.</b> A positive angle rotates the content
     * counter-clockwise here; Apple's exact straighten+crop composition is not
     * publicly specified, so the sign/scale may need adjusting against a
     * reference edit. The common case ({@code angle == 0}) is an exact no-op,
     * and the dominant geometric edit ({@link #crop}) is exact.
     */
    public static RasterFrame straighten(RasterFrame src, double angleRadians) {
        if (angleRadians == 0) {
            return src;
        }
        int w = src.width();
        int h = src.height();
        byte[] in = src.bgra();
        byte[] out = new byte[w * h * 4];
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        double cos = Math.cos(angleRadians);
        double sin = Math.sin(angleRadians);
        for (int oy = 0; oy < h; oy++) {
            for (int ox = 0; ox < w; ox++) {
                double dx = ox - cx;
                double dy = oy - cy;
                // Inverse map (output -> source): rotate by -angle about centre.
                double srcX = cos * dx + sin * dy + cx;
                double srcY = -sin * dx + cos * dy + cy;
                int d = (oy * w + ox) * 4;
                sampleBilinear(in, w, h, srcX, srcY, out, d);
            }
        }
        return new RasterFrame(w, h, out);
    }

    /** Writes a bilinear BGRA sample of {@code (sx,sy)} to {@code out[d..d+3]} (transparent if outside). */
    private static void sampleBilinear(byte[] in, int w, int h, double sx, double sy,
                                       byte[] out, int d) {
        if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) {
            return; // leave transparent (0,0,0,0)
        }
        int x0 = (int) Math.floor(sx);
        int y0 = (int) Math.floor(sy);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        double fx = sx - x0;
        double fy = sy - y0;
        int p00 = (y0 * w + x0) * 4;
        int p10 = (y0 * w + x1) * 4;
        int p01 = (y1 * w + x0) * 4;
        int p11 = (y1 * w + x1) * 4;
        for (int c = 0; c < 4; c++) {
            double top = (in[p00 + c] & 0xFF) * (1 - fx) + (in[p10 + c] & 0xFF) * fx;
            double bot = (in[p01 + c] & 0xFF) * (1 - fx) + (in[p11 + c] & 0xFF) * fx;
            out[d + c] = (byte) Math.round(top * (1 - fy) + bot * fy);
        }
    }
}
