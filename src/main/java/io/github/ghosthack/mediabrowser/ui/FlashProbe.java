package io.github.ghosthack.mediabrowser.ui;

import javafx.stage.Stage;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;

/**
 * TEMPORARY diagnostic — remove with the other {@code [flash-diag]} sites.
 *
 * <p>Second, deliberately <em>light</em> version: the first version's
 * per-pulse AnimationTimer and Win32 readbacks around the reveal changed
 * the FX thread's timing enough to mask the flash (10+ clean runs with it,
 * 1 in 3 flashing without). This one touches the FX thread only through
 * property listeners; everything else is a background thread sampling
 * three small on-screen patches inside the stage rectangle as fast as the
 * screen capture allows, for two seconds after {@code stage.show()}.
 */
final class FlashProbe {
    private static volatile double x = Double.NaN, y = Double.NaN, w, h;
    private static volatile boolean running;
    private static volatile Robot robot;
    private static volatile long showNanos;
    private static volatile long revealNanos;
    private static final CountDownLatch shown = new CountDownLatch(1);

    private FlashProbe() {}

    /** Call early (shell construction): warms the AWT Robot off the FX thread. */
    static void prepare() {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            try {
                robot = new Robot();
            } catch (Throwable e) {
                System.err.println("[flash-diag] screen probe unavailable: " + e);
                return;
            }
            try {
                shown.await();
            } catch (InterruptedException e) {
                return;
            }
            sample();
        }, "flash-probe");
        t.setDaemon(true);
        t.start();
    }

    static void start(Stage stage, long showNanosArg) {
        prepare();
        showNanos = showNanosArg;
        x = stage.getX(); y = stage.getY(); w = stage.getWidth(); h = stage.getHeight();
        stage.xProperty().addListener((o, a, b) -> x = b.doubleValue());
        stage.yProperty().addListener((o, a, b) -> y = b.doubleValue());
        stage.widthProperty().addListener((o, a, b) -> w = b.doubleValue());
        stage.heightProperty().addListener((o, a, b) -> h = b.doubleValue());
        shown.countDown();
    }

    /** Called from the reveal, right after {@code setOpacity(1)}; one volatile write. */
    static void revealed() {
        revealNanos = System.nanoTime();
    }

    private static long ms(long nanos) {
        return (nanos - showNanos) / 1_000_000;
    }

    private static void sample() {
        int lastWhite = -1;
        long lastLog = -1000;
        int samples = 0;
        boolean revealLogged = false;
        while (ms(System.nanoTime()) < 2000) {
            double sw = w, sh = h, sx = x, sy = y;
            if (sw < 40 || sh < 40 || Double.isNaN(sx) || Double.isNaN(sy)) {
                Thread.onSpinWait();
                continue;
            }
            // Three 48x32 patches: centre, upper-left quarter, lower-right quarter.
            int[][] pts = {
                {(int) (sx + sw / 2 - 24), (int) (sy + sh / 2 - 16)},
                {(int) (sx + sw / 4 - 24), (int) (sy + sh / 4 - 16)},
                {(int) (sx + 3 * sw / 4 - 24), (int) (sy + 3 * sh / 4 - 16)},
            };
            long t0 = System.nanoTime();
            int white = 0, light = 0, n = 0;
            long luma = 0;
            try {
                for (int[] p : pts) {
                    BufferedImage img = robot.createScreenCapture(new Rectangle(p[0], p[1], 48, 32));
                    for (int py = 0; py < 32; py += 4) {
                        for (int px = 0; px < 48; px += 4) {
                            int rgb = img.getRGB(px, py);
                            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                            int l = (r * 299 + g * 587 + b * 114) / 1000;
                            luma += l;
                            if (r >= 250 && g >= 250 && b >= 250) white++;
                            else if (l >= 200) light++;
                            n++;
                        }
                    }
                }
            } catch (Throwable e) {
                System.err.println("[flash-diag] screen capture failed: " + e);
                return;
            }
            long tEnd = System.nanoTime();
            samples++;
            int whitePct = white * 100 / n;
            int lightPct = light * 100 / n;
            long nowMs = ms(tEnd);
            long rev = revealNanos;
            if (rev != 0 && !revealLogged) {
                revealLogged = true;
                System.err.println("[flash-diag] reveal observed at +" + ms(rev) + "ms");
            }
            boolean changed = Math.abs(whitePct - lastWhite) >= 2;
            if (changed || whitePct > 0 || lightPct > 0 || samples <= 5 || nowMs - lastLog >= 50) {
                System.err.println("[flash-diag] screen +" + ms(t0) + ".." + nowMs + "ms"
                        + (rev == 0 ? " (before reveal)" : " (reveal+" + (tEnd - rev) / 1_000_000 + "ms)")
                        + " white=" + whitePct + "% light=" + lightPct + "%"
                        + " meanLuma=" + (luma / n) + " sample#" + samples);
                lastLog = nowMs;
                lastWhite = whitePct;
            }
        }
        System.err.println("[flash-diag] screen probe done, samples=" + samples);
    }
}
