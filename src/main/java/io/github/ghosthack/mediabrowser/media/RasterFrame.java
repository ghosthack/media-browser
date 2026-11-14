package io.github.ghosthack.mediabrowser.media;

/**
 * A decoded visual: tightly packed BGRA pixels (4 bytes per pixel, row-major).
 *
 * <p><b>Alpha is straight (non-premultiplied).</b> The colour bytes hold the
 * authored colour regardless of the alpha byte; a half-transparent red is
 * {@code 0,0,255,128}, not {@code 0,0,128,128}. Every consumer relies on this
 * ({@code FxImages} writes frames as {@code PixelFormat.getByteBgraInstance()}),
 * so a backend whose decoder emits premultiplied pixels (CoreGraphics, WIC
 * {@code PBGRA}, premultiplied {@code BufferedImage} types) must convert on the
 * way in, and a sink that needs premultiplied pixels (a JavaFX
 * {@code PixelBuffer}) must convert on the way out — see {@link BgraAlpha}.
 * Opaque sources must set alpha to 255.
 */
public record RasterFrame(int width, int height, byte[] bgra) {

    public RasterFrame {
        if (width <= 0 || height <= 0 || bgra.length != (long) width * height * 4) {
            throw new IllegalArgumentException(
                    "bad frame: " + width + "x" + height + ", " + bgra.length + " bytes");
        }
    }
}
