package io.github.ghosthack.mediabrowser.media;

/**
 * The set of <em>non-destructive</em>, user-owned adjustments persisted for a
 * single media file in its directory {@code .picasa.ini} sidecar (see
 * {@link RotationStore} / {@link PicasaIniStore}). All of these compose
 * <strong>above the decoders</strong> — on top of the already-upright,
 * EXIF/container-oriented BGRA frame and any Apple {@code .AAE} edit — so they
 * never re-decode or touch the thumbnail cache. {@link RasterFrames#apply}
 * realizes them onto a frame; the mosaic applies the same composition at draw
 * time (geometry as a canvas transform, colour from a small cached pass).
 *
 * <p>Composition order (matching {@link RasterFrames#apply} and the mosaic's
 * draw-time transform) is: user rotation, then mirror, then the colour
 * operations. The two colour operations commute (inverting a luma equals the
 * luma of the inverse), so their relative order is immaterial.
 *
 * @param quarterTurnsCw user rotation, 90&deg; clockwise steps normalized to
 *                       {@code 0..3}
 * @param mirrorH        flip left&ndash;right (horizontal mirror)
 * @param mirrorV        flip top&ndash;bottom (vertical mirror)
 * @param grayscale      desaturate to luma (black &amp; white)
 * @param invert         invert colours (photographic negative)
 */
public record Adjustments(int quarterTurnsCw, boolean mirrorH, boolean mirrorV,
                          boolean grayscale, boolean invert) {

    /** The identity adjustment (nothing applied). */
    public static final Adjustments NONE =
            new Adjustments(0, false, false, false, false);

    public Adjustments {
        quarterTurnsCw = ((quarterTurnsCw % 4) + 4) % 4;
    }

    /** True when nothing is applied (the displayed pixels equal the source). */
    public boolean isIdentity() {
        return quarterTurnsCw == 0 && !mirrorH && !mirrorV && !grayscale && !invert;
    }

    /** True when a geometric change applies (rotation or either mirror). */
    public boolean hasGeometry() {
        return quarterTurnsCw != 0 || mirrorH || mirrorV;
    }

    /** True when a colour change applies (grayscale or invert). */
    public boolean hasColor() {
        return grayscale || invert;
    }
}
