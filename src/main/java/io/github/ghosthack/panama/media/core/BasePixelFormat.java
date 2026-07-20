package io.github.ghosthack.panama.media.core;

/**
 * Marker interface for pixel format enums across panama-media modules.
 * <p>
 * Most modules use {@link PixelFormat} (RGBA, RGB, RGB16). Modules with
 * domain-specific formats (e.g., dav1d's YUV planes, FFmpeg's AVPixelFormat)
 * define their own enums implementing this interface.
 * <p>
 * Cross-module code can use {@code DecodedImage<? extends BasePixelFormat>}
 * when the concrete format type is not statically known.
 */
public interface BasePixelFormat {
}
