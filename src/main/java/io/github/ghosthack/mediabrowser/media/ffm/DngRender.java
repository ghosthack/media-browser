package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.RasterFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.IntStream;

/**
 * Pure-Java DNG 1.6/1.7 linear render chain, for raw images the LibRaw build
 * cannot process (JPEG-XL-compressed DNGs). Input is the stitched LinearRaw
 * mosaic from {@link DngJxlStills} — stored-value samples scaled to 16 bits —
 * plus the profile tags read by {@link DngTiff}. The steps, per the DNG spec:
 *
 * <ol>
 *   <li>LinearizationTable, then black/white-level normalize.</li>
 *   <li>Camera RGB → XYZ(D50): dual-illuminant ColorMatrix interpolation at
 *       the white point's correlated color temperature (mired-space weights,
 *       iterated), AnalogBalance/CameraCalibration folded in, then Bradford
 *       adaptation of the as-shot white to D50. ForwardMatrix is honored when
 *       present; Apple's JXL DNGs omit it.</li>
 *   <li>XYZ(D50) → linear RIMM/ProPhoto, × 2^BaselineExposure.</li>
 *   <li>ProfileGainTableMap — Apple's spatially varying local tone map;
 *       without it the output is visibly flat and dark vs. Photos.</li>
 *   <li>ProfileToneCurve per channel, then ProPhoto → sRGB, gamma encode.</li>
 * </ol>
 */
final class DngRender {

    private DngRender() {
    }

    /** Renders the stitched linear mosaic to an upright-unaware BGRA frame (caller applies orientation). */
    static RasterFrame render(DngTiff dng, short[] rgb, int width, int height) {
        int[] crop = dng.cropRectangle();
        int outW = crop[2];
        int outH = crop[3];
        int left = crop[0];
        int top = crop[1];

        float[] linearize = storedToLinear(dng);
        double[][] cameraToProPhoto = cameraToProPhoto(dng);
        double exposure = Math.pow(2, dng.baselineExposure());
        GainTableMap gainMap = GainTableMap.parse(dng.profileGainTableMap());
        float[] tone = toneCurveLut(dng.profileToneCurve());
        double[][] proPhotoToSrgb = mul(XYZ_D50_TO_SRGB, PROPHOTO_TO_XYZ_D50);
        float[] srgbEncode = srgbEncodeLut();

        // Flattened per-row constants for the hot loop.
        double m00 = cameraToProPhoto[0][0];
        double m01 = cameraToProPhoto[0][1];
        double m02 = cameraToProPhoto[0][2];
        double m10 = cameraToProPhoto[1][0];
        double m11 = cameraToProPhoto[1][1];
        double m12 = cameraToProPhoto[1][2];
        double m20 = cameraToProPhoto[2][0];
        double m21 = cameraToProPhoto[2][1];
        double m22 = cameraToProPhoto[2][2];
        double s00 = proPhotoToSrgb[0][0];
        double s01 = proPhotoToSrgb[0][1];
        double s02 = proPhotoToSrgb[0][2];
        double s10 = proPhotoToSrgb[1][0];
        double s11 = proPhotoToSrgb[1][1];
        double s12 = proPhotoToSrgb[1][2];
        double s20 = proPhotoToSrgb[2][0];
        double s21 = proPhotoToSrgb[2][1];
        double s22 = proPhotoToSrgb[2][2];

        byte[] bgra = new byte[outW * outH * 4];
        IntStream.range(0, outH).parallel().forEach(oy -> {
            int y = top + oy;
            GainTableMap.RowSampler gains =
                    gainMap == null ? null : gainMap.rowSampler(y, width, height);
            int src = (y * width + left) * 3;
            int dst = oy * outW * 4;
            for (int ox = 0; ox < outW; ox++, src += 3, dst += 4) {
                double r = linearize[rgb[src] & 0xFFFF];
                double g = linearize[rgb[src + 1] & 0xFFFF];
                double b = linearize[rgb[src + 2] & 0xFFFF];

                // camera → linear ProPhoto, exposure
                double pr = (m00 * r + m01 * g + m02 * b) * exposure;
                double pg = (m10 * r + m11 * g + m12 * b) * exposure;
                double pb = (m20 * r + m21 * g + m22 * b) * exposure;
                if (pr < 0) pr = 0;
                if (pg < 0) pg = 0;
                if (pb < 0) pb = 0;

                if (gains != null) {
                    double gain = gains.gainAt(left + ox, pr, pg, pb);
                    pr *= gain;
                    pg *= gain;
                    pb *= gain;
                }

                pr = toneLookup(tone, pr);
                pg = toneLookup(tone, pg);
                pb = toneLookup(tone, pb);

                double sr = s00 * pr + s01 * pg + s02 * pb;
                double sg = s10 * pr + s11 * pg + s12 * pb;
                double sb = s20 * pr + s21 * pg + s22 * pb;

                bgra[dst] = encode(srgbEncode, sb);
                bgra[dst + 1] = encode(srgbEncode, sg);
                bgra[dst + 2] = encode(srgbEncode, sr);
                bgra[dst + 3] = (byte) 0xFF;
            }
        });
        return new RasterFrame(outW, outH, bgra);
    }

    // ---- linearization -----------------------------------------------------

    /**
     * Stored sample → normalized linear [0,1]: LinearizationTable, then
     * black/white normalize. libjxl hands sub-16-bit samples through as raw
     * codes (a 10-bit DNG decodes to values 0..1023 in the 16-bit frame), so
     * codes index the table directly; out-of-range codes clamp. If an encoder
     * ever scales instead, the SmokeTest jxl-dng-parity guard goes loud.
     */
    private static float[] storedToLinear(DngTiff dng) {
        int bits = dng.bitsPerSample();
        int codes = 1 << bits;
        int[] table = dng.linearizationTable();
        double[] blackArr = dng.blackLevel();
        double black = blackArr.length == 0 ? 0 : blackArr[0];
        double white = dng.whiteLevel();
        double scale = white > black ? 1.0 / (white - black) : 1.0;

        float[] lut = new float[65536];
        for (int v = 0; v < 65536; v++) {
            int code = Math.min(v, codes - 1);
            double linear = table.length == codes ? table[code] : code;
            double normalized = (linear - black) * scale;
            lut[v] = (float) Math.max(0, Math.min(1, normalized));
        }
        return lut;
    }

    // ---- color matrices ----------------------------------------------------

    private static final double[] D50_WHITE = {0.9642, 1.0, 0.8249};

    /** Bradford cone response matrix. */
    private static final double[][] BRADFORD = {
            {0.8951, 0.2664, -0.1614},
            {-0.7502, 1.7135, 0.0367},
            {0.0389, -0.0685, 1.0296}};

    /** XYZ(D50) → linear sRGB (Bradford-adapted, ICC convention). */
    private static final double[][] XYZ_D50_TO_SRGB = {
            {3.1338561, -1.6168667, -0.4906146},
            {-0.9787684, 1.9161415, 0.0334540},
            {0.0719453, -0.2289914, 1.4052427}};

    /** Linear RIMM/ProPhoto → XYZ(D50) (both are D50-native). */
    private static final double[][] PROPHOTO_TO_XYZ_D50 = {
            {0.7976749, 0.1351917, 0.0313534},
            {0.2880402, 0.7118741, 0.0000857},
            {0.0, 0.0, 0.8252100}};

    /**
     * Camera RGB → linear ProPhoto: the DNG dual-illuminant white-point
     * dance. The interpolation weight and the white point depend on each
     * other, so iterate mired-space interpolation to a fixed point.
     */
    private static double[][] cameraToProPhoto(DngTiff dng) {
        double[] neutral = dng.asShotNeutral();
        if (neutral.length != 3) {
            neutral = new double[] {1, 1, 1};
        }
        double[][] cm1 = matrix3(dng.colorMatrix1());
        double[][] cm2 = matrix3(dng.colorMatrix2());
        if (cm1 == null && cm2 == null) {
            throw new MediaException("DNG: no ColorMatrix");
        }
        if (cm1 == null) {
            cm1 = cm2;
        }
        if (cm2 == null) {
            cm2 = cm1;
        }
        double[][] cc1 = matrix3OrIdentity(dng.cameraCalibration1());
        double[][] cc2 = matrix3OrIdentity(dng.cameraCalibration2());
        double[][] ab = diag(dng.analogBalance());
        double[][] fm1 = matrix3(dng.forwardMatrix1());
        double[][] fm2 = matrix3(dng.forwardMatrix2());

        double t1 = illuminantKelvin(dng.calibrationIlluminant1(), 2856);
        double t2 = illuminantKelvin(dng.calibrationIlluminant2(), 6504);

        double weight = 0.5;
        double[] whiteXyz = D50_WHITE;
        for (int i = 0; i < 20; i++) {
            double[][] xyzToCamera = mul(ab, mul(interp(cc1, cc2, weight),
                    interp(cm1, cm2, weight)));
            whiteXyz = mulVec(invert(xyzToCamera), neutral);
            double t = correlatedTemperature(whiteXyz);
            double next = t1 == t2 ? 1
                    : clamp((1 / t - 1 / t2) / (1 / t1 - 1 / t2), 0, 1);
            if (Math.abs(next - weight) < 1e-6) {
                weight = next;
                break;
            }
            weight = next;
        }

        double[][] cameraToXyzD50;
        if (fm1 != null || fm2 != null) {
            // ForwardMatrix path: FM * refCameraWhite^-1 * (AB*CC)^-1
            double[][] fm = fm1 == null ? fm2 : fm2 == null ? fm1 : interp(fm1, fm2, weight);
            double[][] abcc = mul(ab, interp(cc1, cc2, weight));
            double[] refNeutral = mulVec(invert(abcc), neutral);
            double[][] d = new double[][] {
                    {1 / refNeutral[0], 0, 0},
                    {0, 1 / refNeutral[1], 0},
                    {0, 0, 1 / refNeutral[2]}};
            cameraToXyzD50 = mul(fm, mul(d, invert(abcc)));
        } else {
            double[][] xyzToCamera = mul(ab, mul(interp(cc1, cc2, weight),
                    interp(cm1, cm2, weight)));
            cameraToXyzD50 = mul(bradford(whiteXyz, D50_WHITE), invert(xyzToCamera));
        }
        double[][] xyzToProPhoto = invert(PROPHOTO_TO_XYZ_D50);
        return mul(xyzToProPhoto, mul(cameraToXyzD50, diagInvNeutralScale(cameraToXyzD50, neutral)));
    }

    /**
     * Normalizes so the as-shot neutral maps exactly to the PCS white's Y=1 —
     * the DNG-SDK convention that keeps a white object at 1.0 before exposure.
     */
    private static double[][] diagInvNeutralScale(double[][] cameraToXyzD50, double[] neutral) {
        double[] mapped = mulVec(cameraToXyzD50, neutral);
        double y = mapped[1];
        if (y <= 0) {
            return identity();
        }
        double s = 1 / y;
        return new double[][] {{s, 0, 0}, {0, s, 0}, {0, 0, s}};
    }

    private static double illuminantKelvin(int code, int fallback) {
        return switch (code) {
            case 17 -> 2856;    // Standard light A
            case 18 -> 4874;    // Standard light B
            case 19 -> 6774;    // Standard light C
            case 20 -> 5503;    // D55
            case 21 -> 6504;    // D65
            case 22 -> 7504;    // D75
            case 23 -> 5003;    // D50
            case 24 -> 3200;    // ISO studio tungsten
            case 1, 4, 9 -> 5500;   // daylight, flash
            case 2 -> 4200;     // fluorescent
            case 3 -> 2850;     // tungsten
            default -> fallback;
        };
    }

    /** McCamy's CCT approximation from XYZ. */
    private static double correlatedTemperature(double[] xyz) {
        double sum = xyz[0] + xyz[1] + xyz[2];
        if (sum <= 0) {
            return 5000;
        }
        double x = xyz[0] / sum;
        double y = xyz[1] / sum;
        double n = (x - 0.3320) / (0.1858 - y);
        double t = 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33;
        return clamp(t, 1500, 20000);
    }

    /** Bradford chromatic adaptation from {@code fromWhite} to {@code toWhite}. */
    private static double[][] bradford(double[] fromWhite, double[] toWhite) {
        double[] src = mulVec(BRADFORD, fromWhite);
        double[] dst = mulVec(BRADFORD, toWhite);
        double[][] gain = {
                {dst[0] / src[0], 0, 0},
                {0, dst[1] / src[1], 0},
                {0, 0, dst[2] / src[2]}};
        return mul(invert(BRADFORD), mul(gain, BRADFORD));
    }

    // ---- tone curve and encoding ------------------------------------------

    private static final int TONE_LUT_SIZE = 4096;

    /** Resamples the (x,y) pair list into a uniform LUT; identity when absent. */
    private static float[] toneCurveLut(float[] pairs) {
        float[] lut = new float[TONE_LUT_SIZE + 1];
        if (pairs.length < 4 || pairs.length % 2 != 0) {
            for (int i = 0; i <= TONE_LUT_SIZE; i++) {
                lut[i] = i / (float) TONE_LUT_SIZE;
            }
            return lut;
        }
        int points = pairs.length / 2;
        int seg = 0;
        for (int i = 0; i <= TONE_LUT_SIZE; i++) {
            double x = i / (double) TONE_LUT_SIZE;
            while (seg < points - 2 && pairs[(seg + 1) * 2] < x) {
                seg++;
            }
            double x0 = pairs[seg * 2];
            double y0 = pairs[seg * 2 + 1];
            double x1 = pairs[(seg + 1) * 2];
            double y1 = pairs[(seg + 1) * 2 + 1];
            double y = x1 > x0 ? y0 + (y1 - y0) * (x - x0) / (x1 - x0) : y0;
            lut[i] = (float) clamp(y, 0, 1);
        }
        return lut;
    }

    private static double toneLookup(float[] lut, double v) {
        if (v <= 0) {
            return lut[0];
        }
        if (v >= 1) {
            return lut[TONE_LUT_SIZE];
        }
        double f = v * TONE_LUT_SIZE;
        int i = (int) f;
        double frac = f - i;
        return lut[i] + (lut[i + 1] - lut[i]) * frac;
    }

    private static final int SRGB_LUT_SIZE = 8192;

    private static float[] srgbEncodeLut() {
        float[] lut = new float[SRGB_LUT_SIZE + 1];
        for (int i = 0; i <= SRGB_LUT_SIZE; i++) {
            double v = i / (double) SRGB_LUT_SIZE;
            double e = v <= 0.0031308 ? v * 12.92
                    : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            lut[i] = (float) (e * 255);
        }
        return lut;
    }

    private static byte encode(float[] lut, double v) {
        if (v <= 0) {
            return 0;
        }
        if (v >= 1) {
            return (byte) 255;
        }
        return (byte) (lut[(int) (v * SRGB_LUT_SIZE)] + 0.5f);
    }

    // ---- ProfileGainTableMap ----------------------------------------------

    /**
     * DNG 1.6/1.7 ProfileGainTableMap: a MapPointsV × MapPointsH grid of
     * MapPointsN-entry gain tables, big-endian (like opcode data). Per pixel:
     * table input = clamp((R,G,B,min,max)·MapInputWeights, 0, 1), table index
     * = input × MapPointsN, gains bilinear across the four surrounding grid
     * points, applied multiplicatively in linear RIMM space.
     */
    private static final class GainTableMap {
        final int pointsV;
        final int pointsH;
        final int pointsN;
        final double spacingV;
        final double spacingH;
        final double originV;
        final double originH;
        final float[] weights = new float[5];
        final float[] gains;    // [v][h][n] flattened

        private GainTableMap(ByteBuffer b) {
            pointsV = b.getInt();
            pointsH = b.getInt();
            spacingV = b.getDouble();
            spacingH = b.getDouble();
            originV = b.getDouble();
            originH = b.getDouble();
            pointsN = b.getInt();
            for (int i = 0; i < 5; i++) {
                weights[i] = b.getFloat();
            }
            long n = (long) pointsV * pointsH * pointsN;
            if (pointsV <= 0 || pointsH <= 0 || pointsN <= 0
                    || spacingV <= 0 || spacingH <= 0
                    || n <= 0 || n > b.remaining() / 4) {
                throw new MediaException("DNG: implausible ProfileGainTableMap header");
            }
            gains = new float[(int) n];
            for (int i = 0; i < gains.length; i++) {
                gains[i] = b.getFloat();
            }
        }

        static GainTableMap parse(byte[] blob) {
            if (blob.length < 64) {
                return null;
            }
            try {
                return new GainTableMap(
                        ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN));
            } catch (RuntimeException e) {
                // A malformed map degrades to "no local tone map", loudly.
                System.err.println("media-browser: ignoring malformed ProfileGainTableMap: " + e);
                return null;
            }
        }

        /** Precomputes the vertical interpolation for one image row. */
        RowSampler rowSampler(int y, int imageWidth, int imageHeight) {
            double relV = (y + 0.5) / imageHeight;
            double gv = (relV - originV) / spacingV;
            int v0 = (int) Math.floor(gv);
            double fv = gv - v0;
            if (v0 < 0) {
                v0 = 0;
                fv = 0;
            }
            if (v0 >= pointsV - 1) {
                v0 = pointsV - 1;
                fv = 0;
            }
            return new RowSampler(v0, fv, imageWidth);
        }

        final class RowSampler {
            private final int v0;
            private final double fv;
            private final int imageWidth;

            RowSampler(int v0, double fv, int imageWidth) {
                this.v0 = v0;
                this.fv = fv;
                this.imageWidth = imageWidth;
            }

            double gainAt(int x, double r, double g, double b) {
                double min = Math.min(r, Math.min(g, b));
                double max = Math.max(r, Math.max(g, b));
                double t = r * weights[0] + g * weights[1] + b * weights[2]
                        + min * weights[3] + max * weights[4];
                t = clamp(t, 0, 1);
                double f = t * pointsN;
                int n0 = (int) f;
                double fn = f - n0;
                if (n0 >= pointsN - 1) {
                    n0 = pointsN - 1;
                    fn = 0;
                }

                double relH = (x + 0.5) / imageWidth;
                double gh = (relH - originH) / spacingH;
                int h0 = (int) Math.floor(gh);
                double fh = gh - h0;
                if (h0 < 0) {
                    h0 = 0;
                    fh = 0;
                }
                if (h0 >= pointsH - 1) {
                    h0 = pointsH - 1;
                    fh = 0;
                }

                int v1 = Math.min(v0 + 1, pointsV - 1);
                int h1 = Math.min(h0 + 1, pointsH - 1);
                double g00 = tableAt(v0, h0, n0, fn);
                double g01 = tableAt(v0, h1, n0, fn);
                double g10 = tableAt(v1, h0, n0, fn);
                double g11 = tableAt(v1, h1, n0, fn);
                double top = g00 + (g01 - g00) * fh;
                double bottom = g10 + (g11 - g10) * fh;
                return top + (bottom - top) * fv;
            }

            private double tableAt(int v, int h, int n0, double fn) {
                int base = (v * pointsH + h) * pointsN + n0;
                float a = gains[base];
                if (fn == 0 || n0 + 1 >= pointsN) {
                    return a;
                }
                return a + (gains[base + 1] - a) * fn;
            }
        }
    }

    // ---- 3x3 helpers -------------------------------------------------------

    private static double[][] matrix3(double[] v) {
        return v.length == 9 ? new double[][] {
                {v[0], v[1], v[2]}, {v[3], v[4], v[5]}, {v[6], v[7], v[8]}} : null;
    }

    private static double[][] matrix3OrIdentity(double[] v) {
        double[][] m = matrix3(v);
        return m == null ? identity() : m;
    }

    private static double[][] identity() {
        return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] diag(double[] v) {
        if (v.length != 3) {
            return identity();
        }
        return new double[][] {{v[0], 0, 0}, {0, v[1], 0}, {0, 0, v[2]}};
    }

    private static double[][] interp(double[][] a, double[][] b, double weightA) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][j] * weightA + b[i][j] * (1 - weightA);
            }
        }
        return out;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return out;
    }

    private static double[] mulVec(double[][] m, double[] v) {
        return new double[] {
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static double[][] invert(double[][] m) {
        double a = m[0][0];
        double b = m[0][1];
        double c = m[0][2];
        double d = m[1][0];
        double e = m[1][1];
        double f = m[1][2];
        double g = m[2][0];
        double h = m[2][1];
        double i = m[2][2];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12) {
            throw new MediaException("DNG: singular color matrix");
        }
        double inv = 1 / det;
        return new double[][] {
                {(e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv},
                {(f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv},
                {(d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv}};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
