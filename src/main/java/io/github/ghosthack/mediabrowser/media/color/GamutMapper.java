package io.github.ghosthack.mediabrowser.media.color;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Source-profile → sRGB conversion with OKLCH chroma compression for
 * out-of-gamut colors (CSS Color 4 gamut mapping) instead of per-channel clip.
 *
 * <p>Owns the whole conversion rather than post-processing LittleCMS output,
 * because clipping inside the CMM destroys the out-of-gamut coordinates this
 * mapping needs. In-gamut pixels take a pure matrix + curve fast path whose
 * results match the LittleCMS clip path within rounding; only out-of-gamut
 * pixels (typically a small fraction) pay for the chroma search. The
 * destination matrix comes from the JDK's own sRGB profile so both paths
 * share one set of colorants.</p>
 */
final class GamutMapper {

    /** Result of a full-frame conversion: how many pixels needed compression. */
    record Stats(long outOfGamut, long pixels) {
        String percent() {
            double share = pixels == 0 ? 0 : 100.0 * outOfGamut / pixels;
            return String.format(java.util.Locale.ROOT, "%.1f%%", share);
        }
    }

    /** Just-noticeable OKLab difference; below it, plain clip is accepted. */
    private static final double JND = 0.02;
    private static final double CHROMA_EPSILON = 0.0001;
    /** Slack for float noise so barely-outside pixels clip instead of searching. */
    private static final double GAMUT_EPSILON = 0.0005;
    /** Minimum pixels per band, matching IccColorConverter's parallel policy. */
    private static final int MIN_BAND_PIXELS = 512 * 1024;

    /** XYZ(D50) → linear sRGB, inverted once from the JDK sRGB profile's colorants. */
    private static final double[][] XYZ_D50_TO_LINEAR_SRGB = invert(
            MatrixShaperProfile.parse(
                    ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData())
                    .orElseThrow(() -> new IllegalStateException(
                            "JDK sRGB profile is not matrix-shaper"))
                    .rgbToXyzD50());

    private GamutMapper() {}

    /**
     * Converts {@code bgra} in place from the source profile's space to sRGB,
     * compressing out-of-gamut colors; alpha bytes are never touched.
     */
    static Stats convert(byte[] bgra, int width, int height, MatrixShaperProfile source) {
        double[][] toLinearSrgb = multiply(XYZ_D50_TO_LINEAR_SRGB, source.rgbToXyzD50());
        long pixels = (long) width * height;
        AtomicLong outOfGamut = new AtomicLong();
        int bands = (int) Math.max(1, Math.min(
                Runtime.getRuntime().availableProcessors(), pixels / MIN_BAND_PIXELS));
        int rowsPerBand = (height + bands - 1) / bands;
        if (bands == 1) {
            outOfGamut.addAndGet(convertRows(bgra, width, 0, height, source, toLinearSrgb));
        } else {
            java.util.stream.IntStream.range(0, bands).parallel().forEach(band -> {
                int firstRow = band * rowsPerBand;
                int rows = Math.min(rowsPerBand, height - firstRow);
                if (rows > 0) {
                    outOfGamut.addAndGet(
                            convertRows(bgra, width, firstRow, rows, source, toLinearSrgb));
                }
            });
        }
        return new Stats(outOfGamut.get(), pixels);
    }

    private static long convertRows(byte[] bgra, int width, int firstRow, int rows,
                                    MatrixShaperProfile source, double[][] m) {
        long mapped = 0;
        int from = Math.multiplyExact(Math.multiplyExact(firstRow, width), 4);
        int to = from + Math.multiplyExact(rows, width) * 4;
        for (int at = from; at < to; at += 4) {
            double lr = source.linear(0, bgra[at + 2] & 0xff);
            double lg = source.linear(1, bgra[at + 1] & 0xff);
            double lb = source.linear(2, bgra[at] & 0xff);
            double r = m[0][0] * lr + m[0][1] * lg + m[0][2] * lb;
            double g = m[1][0] * lr + m[1][1] * lg + m[1][2] * lb;
            double b = m[2][0] * lr + m[2][1] * lg + m[2][2] * lb;
            if (r < -GAMUT_EPSILON || r > 1 + GAMUT_EPSILON
                    || g < -GAMUT_EPSILON || g > 1 + GAMUT_EPSILON
                    || b < -GAMUT_EPSILON || b > 1 + GAMUT_EPSILON) {
                double[] fitted = compress(r, g, b);
                r = fitted[0];
                g = fitted[1];
                b = fitted[2];
                mapped++;
            }
            bgra[at + 2] = encode(r);
            bgra[at + 1] = encode(g);
            bgra[at] = encode(b);
        }
        return mapped;
    }

    /**
     * CSS Color 4 §13.2: hold OKLab lightness and hue, binary-search the
     * largest chroma whose sRGB clip is within one JND of the candidate.
     */
    private static double[] compress(double r, double g, double b) {
        double[] lab = oklabFromLinearSrgb(r, g, b);
        double lightness = lab[0];
        if (lightness >= 1) return new double[] {1, 1, 1};
        if (lightness <= 0) return new double[] {0, 0, 0};
        double chroma = Math.hypot(lab[1], lab[2]);
        if (chroma < CHROMA_EPSILON) {
            return new double[] {clamp01(r), clamp01(g), clamp01(b)};
        }
        double hueA = lab[1] / chroma;
        double hueB = lab[2] / chroma;
        double low = 0;
        double high = chroma;
        double[] fit = null;
        while (high - low > CHROMA_EPSILON) {
            double candidateChroma = (low + high) / 2;
            double[] candidate = linearSrgbFromOklab(
                    lightness, candidateChroma * hueA, candidateChroma * hueB);
            if (inGamut(candidate)) {
                low = candidateChroma;
                fit = candidate;
            } else {
                double[] clipped = {clamp01(candidate[0]), clamp01(candidate[1]),
                        clamp01(candidate[2])};
                if (deltaEok(candidate, clipped) < JND) {
                    return clipped;
                }
                high = candidateChroma;
            }
        }
        if (fit == null) {
            double[] neutral = linearSrgbFromOklab(lightness, 0, 0);
            return new double[] {clamp01(neutral[0]), clamp01(neutral[1]),
                    clamp01(neutral[2])};
        }
        return new double[] {clamp01(fit[0]), clamp01(fit[1]), clamp01(fit[2])};
    }

    private static boolean inGamut(double[] rgb) {
        return rgb[0] >= 0 && rgb[0] <= 1 && rgb[1] >= 0 && rgb[1] <= 1
                && rgb[2] >= 0 && rgb[2] <= 1;
    }

    private static double deltaEok(double[] rgbA, double[] rgbB) {
        double[] a = oklabFromLinearSrgb(rgbA[0], rgbA[1], rgbA[2]);
        double[] b = oklabFromLinearSrgb(rgbB[0], rgbB[1], rgbB[2]);
        double dl = a[0] - b[0];
        double da = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    // OKLab (Björn Ottosson's published matrices; also CSS Color 4 conversion code)

    private static double[] oklabFromLinearSrgb(double r, double g, double b) {
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[] {
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        };
    }

    private static double[] linearSrgbFromOklab(double lightness, double a, double b) {
        double l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b);
        double m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b);
        double s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b);
        return new double[] {
                +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        };
    }

    private static double cube(double v) {
        return v * v * v;
    }

    private static byte encode(double linear) {
        double v = clamp01(linear);
        double encoded = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (byte) Math.round(encoded * 255);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    c[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return c;
    }

    private static double[][] invert(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        double[][] inv = new double[3][3];
        inv[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
        inv[0][1] = -(m[0][1] * m[2][2] - m[0][2] * m[2][1]) / det;
        inv[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
        inv[1][0] = -(m[1][0] * m[2][2] - m[1][2] * m[2][0]) / det;
        inv[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
        inv[1][2] = -(m[0][0] * m[1][2] - m[0][2] * m[1][0]) / det;
        inv[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
        inv[2][1] = -(m[0][0] * m[2][1] - m[0][1] * m[2][0]) / det;
        inv[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
        return inv;
    }
}
