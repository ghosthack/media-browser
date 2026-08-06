package io.github.ghosthack.mediabrowser.media.color;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * The matrix + tone-curve subset of an ICC profile, parsed from raw bytes.
 *
 * <p>Gamut compression needs the <em>unclamped</em> conversion result, which
 * the JDK's LittleCMS binding never exposes, so the compress path re-derives
 * the transform from the profile's own tags: colorant matrix (already
 * D50-adapted per the spec) plus per-channel transfer curves materialized as
 * 256-entry decode LUTs. Profiles that are not pure matrix-shaper (LUT-based
 * {@code A2B*} pipelines, missing tags, non-RGB) parse to empty, and callers
 * fall back to the clip path.</p>
 */
final class MatrixShaperProfile {

    private final double[][] rgbToXyzD50;
    private final double[][] decode = new double[3][];

    private MatrixShaperProfile(double[][] matrix, double[] r, double[] g, double[] b) {
        this.rgbToXyzD50 = matrix;
        this.decode[0] = r;
        this.decode[1] = g;
        this.decode[2] = b;
    }

    /** Column-major colorants: XYZ(D50) = matrix · linear RGB. */
    double[][] rgbToXyzD50() {
        return rgbToXyzD50;
    }

    /** Linearization of one 8-bit channel value ({@code channel} 0=R, 1=G, 2=B). */
    double linear(int channel, int value) {
        return decode[channel][value];
    }

    static Optional<MatrixShaperProfile> parse(byte[] icc) {
        if (icc == null || icc.length < 132) return Optional.empty();
        try {
            ByteBuffer buf = ByteBuffer.wrap(icc).order(ByteOrder.BIG_ENDIAN);
            double[] rXyz = null;
            double[] gXyz = null;
            double[] bXyz = null;
            double[] rTrc = null;
            double[] gTrc = null;
            double[] bTrc = null;
            int count = buf.getInt(128);
            if (count <= 0 || count > 1024) return Optional.empty();
            for (int i = 0; i < count; i++) {
                int base = 132 + i * 12;
                if (base + 12 > icc.length) return Optional.empty();
                int sig = buf.getInt(base);
                int offset = buf.getInt(base + 4);
                int size = buf.getInt(base + 8);
                if (offset < 0 || size < 8 || offset + size > icc.length) continue;
                switch (sig) {
                    case 0x7258595A -> rXyz = xyz(buf, offset);            // rXYZ
                    case 0x6758595A -> gXyz = xyz(buf, offset);            // gXYZ
                    case 0x6258595A -> bXyz = xyz(buf, offset);            // bXYZ
                    case 0x72545243 -> rTrc = curve(buf, offset, size);    // rTRC
                    case 0x67545243 -> gTrc = curve(buf, offset, size);    // gTRC
                    case 0x62545243 -> bTrc = curve(buf, offset, size);    // bTRC
                    default -> { }
                }
            }
            if (rXyz == null || gXyz == null || bXyz == null
                    || rTrc == null || gTrc == null || bTrc == null) {
                return Optional.empty();
            }
            double[][] matrix = {
                    {rXyz[0], gXyz[0], bXyz[0]},
                    {rXyz[1], gXyz[1], bXyz[1]},
                    {rXyz[2], gXyz[2], bXyz[2]},
            };
            return Optional.of(new MatrixShaperProfile(matrix, rTrc, gTrc, bTrc));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** XYZType: sig, reserved, then three s15Fixed16 components. */
    private static double[] xyz(ByteBuffer buf, int offset) {
        if (buf.getInt(offset) != 0x58595A20) return null; // 'XYZ '
        return new double[] {
                s15Fixed16(buf.getInt(offset + 8)),
                s15Fixed16(buf.getInt(offset + 12)),
                s15Fixed16(buf.getInt(offset + 16)),
        };
    }

    /** Materializes a 'curv' or 'para' TRC as a 256-entry decode LUT. */
    private static double[] curve(ByteBuffer buf, int offset, int size) {
        int type = buf.getInt(offset);
        if (type == 0x63757276) return sampledCurve(buf, offset, size);    // 'curv'
        if (type == 0x70617261) return parametricCurve(buf, offset, size); // 'para'
        return null;
    }

    private static double[] sampledCurve(ByteBuffer buf, int offset, int size) {
        int n = buf.getInt(offset + 8);
        double[] lut = new double[256];
        if (n == 0) {                                      // identity
            for (int i = 0; i < 256; i++) lut[i] = i / 255.0;
            return lut;
        }
        if (n == 1) {                                      // gamma as u8Fixed8
            double gamma = (buf.getShort(offset + 12) & 0xffff) / 256.0;
            for (int i = 0; i < 256; i++) lut[i] = Math.pow(i / 255.0, gamma);
            return lut;
        }
        if (offset + 12 + n * 2 > offset + size) return null;
        for (int i = 0; i < 256; i++) {                    // linear interpolation
            double position = i / 255.0 * (n - 1);
            int low = (int) position;
            int high = Math.min(low + 1, n - 1);
            double fraction = position - low;
            double a = (buf.getShort(offset + 12 + low * 2) & 0xffff) / 65535.0;
            double b = (buf.getShort(offset + 12 + high * 2) & 0xffff) / 65535.0;
            lut[i] = a + (b - a) * fraction;
        }
        return lut;
    }

    /** parametricCurveType function types 0–4 (ICC v4 spec table 68). */
    private static double[] parametricCurve(ByteBuffer buf, int offset, int size) {
        int function = buf.getShort(offset + 8) & 0xffff;
        int params = switch (function) {
            case 0 -> 1;
            case 1 -> 3;
            case 2 -> 4;
            case 3 -> 5;
            case 4 -> 7;
            default -> -1;
        };
        if (params < 0 || offset + 12 + params * 4 > offset + size) return null;
        double[] p = new double[params];
        for (int i = 0; i < params; i++) {
            p[i] = s15Fixed16(buf.getInt(offset + 12 + i * 4));
        }
        double[] lut = new double[256];
        for (int i = 0; i < 256; i++) {
            double x = i / 255.0;
            lut[i] = switch (function) {
                case 0 -> Math.pow(x, p[0]);
                case 1 -> x >= -p[2] / p[1] ? Math.pow(p[1] * x + p[2], p[0]) : 0;
                case 2 -> x >= -p[2] / p[1] ? Math.pow(p[1] * x + p[2], p[0]) + p[3] : p[3];
                case 3 -> x >= p[4] ? Math.pow(p[1] * x + p[2], p[0]) : p[3] * x;
                default -> x >= p[4] ? Math.pow(p[1] * x + p[2], p[0]) + p[5]
                        : p[3] * x + p[6];
            };
        }
        return lut;
    }

    private static double s15Fixed16(int raw) {
        return raw / 65536.0;
    }
}
