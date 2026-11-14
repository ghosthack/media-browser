package io.github.ghosthack.panama.media.core;

/**
 * Marker interface for pixel format enums across panama-media modules.
 * <p>
 * Most modules use {@link PixelFormat} (RGBA, RGB, RGB16). Bindings with
 * domain-specific native formats can define their own enums implementing this
 * interface.
 * <p>
 * Cross-module code can use {@code DecodedImage<? extends BasePixelFormat>}
 * when the concrete format type is not statically known.
 */
public interface BasePixelFormat {
}
