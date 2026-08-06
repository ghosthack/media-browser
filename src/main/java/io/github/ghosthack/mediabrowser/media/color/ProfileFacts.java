package io.github.ghosthack.mediabrowser.media.color;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Evidence-only ICC profile introspection for the Info panel and the viewer's
 * Color menu: what kind of profile this is and which renderings it actually
 * contains. Never a control surface — intent <em>selection</em> stays out of
 * scope until a LUT-profile use case exists (docs/color-gamut-mapping.md).
 *
 * <p>An ICC profile can only perform the rendering intents it ships tables
 * for: {@code A2B0}=perceptual, {@code A2B1}=colorimetric,
 * {@code A2B2}=saturation. Matrix-shaper profiles carry none and always render
 * colorimetrically, whatever their header suggests. The header's intent field
 * (bytes 64–67) is the author's default suggestion, reported as such.</p>
 */
public record ProfileFacts(boolean matrixShaper, boolean perceptual,
                           boolean colorimetric, boolean saturation,
                           String headerIntent) {

    public static ProfileFacts of(byte[] icc) {
        boolean colorants = false;
        boolean curves = false;
        boolean a2b0 = false;
        boolean a2b1 = false;
        boolean a2b2 = false;
        String header = "perceptual";
        if (icc != null && icc.length >= 132) {
            ByteBuffer buf = ByteBuffer.wrap(icc).order(ByteOrder.BIG_ENDIAN);
            header = intentName(buf.getInt(64));
            int count = buf.getInt(128);
            for (int i = 0; i < Math.min(Math.max(count, 0), 1024); i++) {
                int base = 132 + i * 12;
                if (base + 12 > icc.length) break;
                switch (buf.getInt(base)) {
                    case 0x7258595A -> colorants = true;  // rXYZ
                    case 0x72545243 -> curves = true;     // rTRC
                    case 0x41324230 -> a2b0 = true;       // A2B0
                    case 0x41324231 -> a2b1 = true;       // A2B1
                    case 0x41324232 -> a2b2 = true;       // A2B2
                    default -> { }
                }
            }
        }
        return new ProfileFacts(colorants && curves && !a2b0, a2b0, a2b1, a2b2, header);
    }

    /** One line for evidence surfaces, e.g. {@code "colorimetric only (matrix profile)"}. */
    public String renderingsLine() {
        if (matrixShaper) return "colorimetric only (matrix profile)";
        StringBuilder line = new StringBuilder();
        if (perceptual) line.append("perceptual");
        if (colorimetric) line.append(line.isEmpty() ? "" : " · ").append("colorimetric");
        if (saturation) line.append(line.isEmpty() ? "" : " · ").append("saturation");
        if (line.isEmpty()) return "colorimetric only";
        return line + " (default: " + headerIntent + ")";
    }

    private static String intentName(int value) {
        return switch (value) {
            case 1 -> "relative colorimetric";
            case 2 -> "saturation";
            case 3 -> "absolute colorimetric";
            default -> "perceptual";
        };
    }
}
