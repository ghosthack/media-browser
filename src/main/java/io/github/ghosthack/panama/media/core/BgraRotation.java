package io.github.ghosthack.panama.media.core;

/**
 * The single BGRA quarter-turn pixel permutation shared by the panama-media
 * native bindings (Apple AVFoundation and Windows Media Foundation) to bake a
 * container's display rotation into decoded video frames and posters.
 *
 * <p>Both bindings deliberately produce the <b>exact same pixel mapping</b> as
 * the application's canonical {@code media.RasterFrames#rotateCw} (and
 * {@code ui.VideoPresenter#rotateInto}); that equivalence is pinned by a
 * cross-test ({@code BgraRotationTest}) so the still/poster/playback/user-rotation
 * paths can never silently diverge. It lives in {@code core} — which every
 * binding already depends on — rather than being copied into each binding.</p>
 */
public final class BgraRotation {

    private BgraRotation() {}

    /**
     * Rotates a source BGRA frame ({@code srcW × srcH} logical pixels, one
     * {@code int} per pixel, rows laid out every {@code srcStrideInts} ints so a
     * padded decoder buffer's stride does not leak in) by {@code qcw} 90°
     * clockwise quarter-turns into the tightly packed, top-down {@code dst}.
     *
     * <p>{@code qcw} is taken mod 4 (negatives normalized). Odd turns swap the
     * output dimensions, so {@code dst} must hold {@code srcW*srcH} ints with the
     * output laid out at width {@code (qcw odd ? srcH : srcW)}. Whole pixels are
     * moved as ints, so the byte order within a pixel is irrelevant.</p>
     */
    public static void rotate(int[] src, int srcStrideInts, int srcW, int srcH,
                              int qcw, int[] dst) {
        int q = ((qcw % 4) + 4) % 4;
        boolean swap = (q & 1) == 1;
        int ow = swap ? srcH : srcW;
        int oh = swap ? srcW : srcH;
        for (int oy = 0; oy < oh; oy++) {
            int dRow = oy * ow;
            for (int ox = 0; ox < ow; ox++) {
                int sx;
                int sy;
                switch (q) {
                    case 1 -> { sx = oy;            sy = srcH - 1 - ox; } // 90 CW
                    case 2 -> { sx = srcW - 1 - ox; sy = srcH - 1 - oy; } // 180
                    case 3 -> { sx = srcW - 1 - oy; sy = ox; }            // 270 CW
                    default -> { sx = ox;           sy = oy; }            // 0 (identity)
                }
                dst[dRow + ox] = src[sy * srcStrideInts + sx];
            }
        }
    }
}
