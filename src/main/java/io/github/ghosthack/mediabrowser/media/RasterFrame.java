package io.github.ghosthack.mediabrowser.media;

/** A decoded visual: tightly packed BGRA pixels (4 bytes per pixel, row-major). */
public record RasterFrame(int width, int height, byte[] bgra) {

    public RasterFrame {
        if (width <= 0 || height <= 0 || bgra.length != (long) width * height * 4) {
            throw new IllegalArgumentException(
                    "bad frame: " + width + "x" + height + ", " + bgra.length + " bytes");
        }
    }
}
