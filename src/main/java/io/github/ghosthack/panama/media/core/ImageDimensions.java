package io.github.ghosthack.panama.media.core;

/**
 * Shared image dimension utilities for panama-media modules.
 */
public final class ImageDimensions {

    /**
     * Maximum total pixel count accepted by panama-media decoders.
     * <p>
     * 256 MP — large enough for professional medium-format images at full
     * resolution, small enough to prevent OOM on a malicious or corrupt header
     * claiming an impossibly large image.
     */
    public static final long MAX_PIXELS = 256L * 1024 * 1024;

    private ImageDimensions() {}

    /**
     * Validates image dimensions before buffer allocation.
     *
     * @param w image width in pixels
     * @param h image height in pixels
     * @throws IllegalArgumentException if {@code w} or {@code h} is &lt;= 0,
     *         or if {@code (long) w * h} exceeds {@link #MAX_PIXELS}
     */
    public static void validateDimensions(int w, int h) {
        if (w <= 0 || h <= 0)
            throw new IllegalArgumentException(
                    "Invalid image dimensions: " + w + "x" + h);
        long total = (long) w * h;
        if (total > MAX_PIXELS)
            throw new IllegalArgumentException(
                    "Image too large: " + w + "x" + h +
                    " (" + total + " px exceeds limit of " + MAX_PIXELS + ")");
    }
}
