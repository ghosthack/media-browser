package io.github.ghosthack.media.color;

/**
 * Fixed-point YCbCr -> RGB conversion shared by AVIF, HEIC and future decoders.
 *
 * <p>Supports BT.601 and BT.709 matrix coefficients and two bit-depth
 * normalisation strategies:
 * <ul>
 *   <li>{@code roundShift = false} - truncating right-shift (fast, slight loss
 *       of accuracy; used by HEIC)
 *   <li>{@code roundShift = true}  - rounded right-shift (adds 1/2 before
 *       shifting; used by AVIF)
 * </ul>
 *
 * <p>All arithmetic uses integer fixed-point scaled by 2^16 (SCALEBITS = 16).
 * The pre-computed {@link #CLAMP_TABLE} covers the range
 * [{@code -CLAMP_OFFSET}, {@code 1023 - CLAMP_OFFSET}] for the common case.
 * Values outside that range fall back to direct saturating clamps.
 */
public final class YCbCrConverter {

    public static final int BT601_CR_R = 91881;
    public static final int BT601_CB_G = -22554;
    public static final int BT601_CR_G = -46802;
    public static final int BT601_CB_B = 116130;

    public static final int BT709_CR_R = 103206;
    public static final int BT709_CB_G = -12276;
    public static final int BT709_CR_G = -30679;
    public static final int BT709_CB_B = 121608;

    public static final int CLAMP_OFFSET = 384;
    public static final int[] CLAMP_TABLE;
    static {
        CLAMP_TABLE = new int[1024];
        for (int i = 0; i < 1024; i++) {
            int value = i - CLAMP_OFFSET;
            CLAMP_TABLE[i] = value < 0 ? 0 : (value > 255 ? 255 : value);
        }
    }

    private YCbCrConverter() {}

    private static int clamp8(int value) {
        int index = value + CLAMP_OFFSET;
        if ((index & ~1023) == 0) {
            return CLAMP_TABLE[index];
        }
        return value < 0 ? 0 : 255;
    }

    public static int ycbcrToRgb(int y, int cb, int cr,
                                 int bitDepthY, int bitDepthC,
                                 boolean bt709, boolean roundShift) {
        return ycbcrToRgb(y, cb, cr, bitDepthY, bitDepthC, bt709, true, roundShift);
    }

    public static int ycbcrToRgb(int y, int cb, int cr,
                                 int bitDepthY, int bitDepthC,
                                 boolean bt709, boolean fullRange,
                                 boolean roundShift) {
        if (bitDepthY > 8) {
            int shift = bitDepthY - 8;
            y = roundShift ? (y + (1 << (shift - 1))) >> shift : y >> shift;
        }
        if (bitDepthC > 8) {
            int shift = bitDepthC - 8;
            if (roundShift) {
                int rounding = 1 << (shift - 1);
                cb = (cb + rounding) >> shift;
                cr = (cr + rounding) >> shift;
            } else {
                cb >>= shift;
                cr >>= shift;
            }
        }

        cb -= 128;
        cr -= 128;

        int red;
        int green;
        int blue;
        if (fullRange) {
            if (bt709) {
                red = y + ((BT709_CR_R * cr + 32768) >> 16);
                green = y + ((BT709_CB_G * cb + BT709_CR_G * cr + 32768) >> 16);
                blue = y + ((BT709_CB_B * cb + 32768) >> 16);
            } else {
                red = y + ((BT601_CR_R * cr + 32768) >> 16);
                green = y + ((BT601_CB_G * cb + BT601_CR_G * cr + 32768) >> 16);
                blue = y + ((BT601_CB_B * cb + 32768) >> 16);
            }
        } else {
            int luma = Math.max(0, y - 16);
            if (bt709) {
                red = (76309 * luma + 117489 * cr + 32768) >> 16;
                green = (76309 * luma - 13975 * cb - 34925 * cr + 32768) >> 16;
                blue = (76309 * luma + 138438 * cb + 32768) >> 16;
            } else {
                red = (76309 * luma + 104597 * cr + 32768) >> 16;
                green = (76309 * luma - 25675 * cb - 53279 * cr + 32768) >> 16;
                blue = (76309 * luma + 132201 * cb + 32768) >> 16;
            }
        }

        return (clamp8(red) << 16) |
               (clamp8(green) << 8) |
               clamp8(blue);
    }
}