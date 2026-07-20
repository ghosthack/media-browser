package io.github.ghosthack.panama.media.core;

/**
 * Named image dimension pair returned by {@code getSize()} methods.
 * <p>
 * Both {@link #width()} and {@link #height()} are validated at construction
 * time: each must be positive and the total pixel count must not exceed
 * {@link ImageDimensions#MAX_PIXELS}.
 */
public record Dimensions(int width, int height) {
    public Dimensions {
        ImageDimensions.validateDimensions(width, height);
    }
}
