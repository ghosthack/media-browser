package io.github.ghosthack.panama.media.core;

import java.lang.foreign.MemorySegment;

/**
 * Decoded image data returned by panama-media modules.
 * <p>
 * The {@code pixels} segment is valid for the lifetime of the {@code Arena}
 * that was passed to the decode method. The caller owns the Arena and
 * controls when the memory is freed.
 * <p>
 * The type parameter {@code PF} is the pixel format enum for the module.
 * Most modules use {@link PixelFormat} (RGBA, RGB, RGB16). Modules with
 * domain-specific formats define their own enums implementing
 * {@link BasePixelFormat}.
 * <p>
 * The {@code orientation} field carries the source image's EXIF orientation
 * tag (TIFF tag 0x0112). Values are {@code 1}–{@code 8} per the EXIF
 * specification; {@code 1} means "no rotation" and is also the TIFF-defined
 * default when the tag is absent — this is what producers return for
 * un-tagged inputs so consumers can branch on orientation uniformly without
 * a separate "is it set?" check.
 * <p>
 * Producers that already bake the transform into the returned pixels (e.g.
 * the Apple {@code CGImageSource} path with
 * {@code kCGImageSourceCreateThumbnailWithTransform=true}) still report the
 * source tag value, so downstream encoders can re-embed it or consumers can
 * record it as metadata without re-opening the source.
 * <p>
 * {@link #ORIENTATION_UNSET} ({@code 0}) is reserved as a sentinel for
 * callers / APIs that need to disambiguate "tag truly absent" from "tag
 * present with value 1"; in normal pipelines nothing emits it and consumers
 * don't need to handle it.
 *
 * @param <PF>        the pixel format enum (must implement {@link BasePixelFormat})
 * @param pixels      raw pixel data
 * @param width       image width in pixels
 * @param height      image height in pixels
 * @param stride      bytes per row (may be larger than width * bytes-per-pixel
 *                    due to alignment padding)
 * @param format      pixel format of the data
 * @param orientation EXIF orientation in {@code [1, 8]} ({@code 1} is the
 *                    TIFF default / "no rotation"), or
 *                    {@link #ORIENTATION_UNSET} ({@code 0}) as an opt-in
 *                    "tag truly absent" sentinel
 */
public record DecodedImage<PF extends BasePixelFormat>(
        MemorySegment pixels,
        int width,
        int height,
        int stride,
        PF format,
        int orientation
) {
    /**
     * Sentinel value signalling that the source image carried no EXIF
     * orientation tag. Distinct from {@code 1}, which is the TIFF-defined
     * default meaning "no rotation" and is what consumers should treat an
     * absent tag as in normal pipelines.
     */
    public static final int ORIENTATION_UNSET = 0;

    /**
     * Validates record fields on construction.
     *
     * @throws NullPointerException     if {@code pixels} or {@code format} is null
     * @throws IllegalArgumentException if dimensions/stride are not positive
     *                                  or {@code orientation} is not in
     *                                  {@code [0, 8]}
     */
    public DecodedImage {
        java.util.Objects.requireNonNull(pixels, "pixels must not be null");
        java.util.Objects.requireNonNull(format, "format must not be null");
        if (width <= 0) throw new IllegalArgumentException("width must be positive: " + width);
        if (height <= 0) throw new IllegalArgumentException("height must be positive: " + height);
        if (stride <= 0) throw new IllegalArgumentException("stride must be positive: " + stride);
        if (orientation < 0 || orientation > 8)
            throw new IllegalArgumentException(
                    "orientation must be in [0, 8]: " + orientation);
    }

    /**
     * Backwards-compatible constructor — defaults {@code orientation} to
     * {@code 1} (TIFF "no rotation"). Producers that don't extract an EXIF
     * orientation tag should use this form; callers see the TIFF default
     * and treat the image as already-upright.
     */
    public DecodedImage(MemorySegment pixels, int width, int height, int stride, PF format) {
        this(pixels, width, height, stride, format, 1);
    }
}
