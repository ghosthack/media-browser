package io.github.ghosthack.panama.media.cgimage;

/**
 * Interpolation quality for CG bitmap-context resampling, matching
 * Apple's {@code CGInterpolationQuality} enum values.
 * <p>
 * Applied via {@code CGContextSetInterpolationQuality} right before
 * {@code CGContextDrawImage}. Affects decode pipelines where the source
 * CGImage size differs from the destination bitmap (e.g. thumbnails where
 * CG returns an embedded preview slightly off the requested cap). When
 * source and destination dimensions match, {@code CGContextDrawImage}
 * short-circuits the resampler and this setting is a no-op.
 *
 * <p>The integer values mirror the C enum:
 * <pre>
 *   typedef enum CGInterpolationQuality : int32_t {
 *       kCGInterpolationDefault = 0,
 *       kCGInterpolationNone    = 1,
 *       kCGInterpolationLow     = 2,
 *       kCGInterpolationMedium  = 4,
 *       kCGInterpolationHigh    = 3
 *   } CGInterpolationQuality;
 * </pre>
 */
public enum InterpolationQuality {
    /** Context's default — typically low/medium, decided by CG. */
    DEFAULT(0),
    /** Nearest-neighbour — fastest, visible aliasing on downscale. */
    NONE(1),
    /** Low — cheap bilinear. */
    LOW(2),
    /** Medium. */
    MEDIUM(4),
    /** High — Lanczos-ish; Apple's best quality for photographic content. */
    HIGH(3);

    final int cgValue;

    InterpolationQuality(int cgValue) {
        this.cgValue = cgValue;
    }
}
