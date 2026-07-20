package io.github.ghosthack.panama.media.core;

/**
 * Common RGB-family pixel formats shared across image decode modules.
 * <p>
 * Most panama-media image modules decode to {@link #RGBA}. Modules with
 * domain-specific formats (e.g., dav1d's YUV planes, FFmpeg's AVPixelFormat)
 * define their own enums implementing {@link BasePixelFormat}.
 */
public enum PixelFormat implements BasePixelFormat {
    /** 8-bit RGBA (4 bytes per pixel, red first). */
    RGBA,
    /** 8-bit BGRA (4 bytes per pixel, blue first). Native format on macOS and Windows. */
    BGRA,
    /** 8-bit RGB (3 bytes per pixel, no alpha). */
    RGB,
    /** 16-bit RGB (6 bytes per pixel, big-endian unsigned shorts). */
    RGB16
}
