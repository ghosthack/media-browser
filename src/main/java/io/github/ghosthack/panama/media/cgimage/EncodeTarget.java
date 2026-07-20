package io.github.ghosthack.panama.media.cgimage;

import java.util.Objects;

/**
 * A single output in a {@link ImageIO#transcode transcode} call.
 * <p>
 * A transcode takes one input image and produces N outputs at potentially
 * different sizes, formats, and destinations (mix of file paths and
 * in-memory byte arrays).
 *
 * <h2>Orientation</h2>
 * The {@code orientation} field controls how EXIF orientation is handled:
 * <ul>
 *   <li><b>0</b> (default, "bake"): the source's EXIF orientation is applied
 *       during decode so the output pixels are upright, and no orientation
 *       tag is written to the output — viewers see the image correctly
 *       without needing to honour metadata.</li>
 *   <li><b>1..8</b> (explicit tag): source pixels are decoded raw (no
 *       transform) and the given orientation value is embedded in the
 *       output. For lossless passthrough, pass the source's orientation as
 *       returned by {@link ImageIO#getOrientation}.</li>
 * </ul>
 *
 * <h2>Quality &amp; interpolation</h2>
 * {@code quality} is in [0.0, 1.0] for lossy UTIs (JPEG, HEIC, AVIF, WebP);
 * pass {@link Float#NaN} to defer to CG's default. {@code interpolation}
 * controls the CG bitmap-context resampler used when scaling in the
 * transcode pipeline (unlike the thumbnail API, the knob is always live
 * here — we do the scale ourselves).
 *
 * @param path           destination path, or {@code null} for in-memory
 *                       output (returned as {@code byte[]} by the transcode call)
 * @param maxPixelSize   cap on the longer edge, or {@code 0} to preserve
 *                       the source dimensions (no scaling)
 * @param uti            destination UTI (see {@link ImageIO#UTI_JPEG}, etc.)
 * @param quality        lossy compression quality in [0, 1]; {@code NaN} =
 *                       CG default
 * @param orientation    {@code 0} to bake the source orientation into
 *                       pixels (no tag written), or {@code 1..8} to
 *                       preserve raw pixels and write an explicit tag
 * @param interpolation  interpolation quality applied during the scale step
 */
public record EncodeTarget(
        String path,
        int maxPixelSize,
        String uti,
        float quality,
        int orientation,
        InterpolationQuality interpolation
) {
    public EncodeTarget {
        Objects.requireNonNull(uti, "uti");
        Objects.requireNonNull(interpolation, "interpolation");
        if (maxPixelSize < 0)
            throw new IllegalArgumentException("maxPixelSize must be >= 0, got " + maxPixelSize);
        if (orientation < 0 || orientation > 8)
            throw new IllegalArgumentException("orientation must be in [0,8], got " + orientation);
    }

    /** Whether this target bakes orientation (pixels upright, no tag). */
    public boolean bakesOrientation() {
        return orientation == 0;
    }

    /** File-output target with HIGH interpolation and baked orientation. */
    public static EncodeTarget toPath(String path, int maxPixelSize, String uti, float quality) {
        return new EncodeTarget(path, maxPixelSize, uti, quality, 0, InterpolationQuality.HIGH);
    }

    /** In-memory target with HIGH interpolation and baked orientation. */
    public static EncodeTarget toBytes(int maxPixelSize, String uti, float quality) {
        return new EncodeTarget(null, maxPixelSize, uti, quality, 0, InterpolationQuality.HIGH);
    }
}
