package io.github.ghosthack.mediabrowser.media;

/**
 * Conversions between the two alpha conventions of tightly packed 8-bit BGRA.
 *
 * <p>{@link RasterFrame} is <em>straight</em> alpha. Some native decoders
 * (CoreGraphics bitmap contexts, WIC {@code 32bppPBGRA}) and JavaFX's
 * {@code PixelBuffer} are <em>premultiplied</em>; these are the two crossings.
 * Opaque pixels (alpha 255) are identical in both conventions and are never
 * rewritten.
 */
public final class BgraAlpha {

    private BgraAlpha() {}

    /**
     * Converts premultiplied BGRA to straight alpha in place:
     * {@code c = round(c * 255 / a)}, clamped to 255 (a well-formed
     * premultiplied pixel never has {@code c > a}; the clamp only bounds
     * malformed input). Alpha-0 pixels carry no colour and become 0,0,0,0.
     *
     * <p>Only call this on bytes known to be premultiplied. In particular a
     * buffer whose fourth byte is undefined padding (e.g. Media Foundation
     * {@code RGB32}) must not go through here: a padding byte of 0 would erase
     * the pixel's colour.
     */
    public static void unpremultiplyInPlace(byte[] bgra) {
        requireBgra(bgra);
        for (int i = 0; i < bgra.length; i += 4) {
            int a = bgra[i + 3] & 0xFF;
            if (a == 255) continue;
            if (a == 0) {
                bgra[i] = 0;
                bgra[i + 1] = 0;
                bgra[i + 2] = 0;
                continue;
            }
            int half = a >> 1;
            bgra[i] = (byte) Math.min(255, ((bgra[i] & 0xFF) * 255 + half) / a);
            bgra[i + 1] = (byte) Math.min(255, ((bgra[i + 1] & 0xFF) * 255 + half) / a);
            bgra[i + 2] = (byte) Math.min(255, ((bgra[i + 2] & 0xFF) * 255 + half) / a);
        }
    }

    /**
     * The premultiplied form of straight-alpha BGRA:
     * {@code c = round(c * a / 255)}. Returns {@code bgra} itself when every
     * pixel is opaque (nothing to convert, no copy); otherwise a new array —
     * the input is never modified, so a shared or cached frame stays straight.
     */
    public static byte[] premultiplied(byte[] bgra) {
        requireBgra(bgra);
        int first = 3;
        while (first < bgra.length && bgra[first] == (byte) 0xFF) first += 4;
        if (first >= bgra.length) return bgra;
        byte[] out = bgra.clone();
        for (int i = first - 3; i < out.length; i += 4) {
            int a = out[i + 3] & 0xFF;
            if (a == 255) continue;
            out[i] = (byte) (((out[i] & 0xFF) * a + 127) / 255);
            out[i + 1] = (byte) (((out[i + 1] & 0xFF) * a + 127) / 255);
            out[i + 2] = (byte) (((out[i + 2] & 0xFF) * a + 127) / 255);
        }
        return out;
    }

    private static void requireBgra(byte[] bgra) {
        if ((bgra.length & 3) != 0) {
            throw new IllegalArgumentException(
                    "not BGRA: " + bgra.length + " bytes is not a multiple of 4");
        }
    }
}
