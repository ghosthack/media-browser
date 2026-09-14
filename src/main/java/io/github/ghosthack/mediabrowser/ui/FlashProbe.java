package io.github.ghosthack.mediabrowser.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;

/**
 * TEMPORARY diagnostic — remove with the other {@code [flash-diag]} sites.
 *
 * <p>Samples the real on-screen pixels at the centre of the stage's
 * rectangle from a background thread, from the instant of {@code
 * stage.show()} for two seconds, logging when (relative to the show and the
 * opacity reveal) pure-white or light pixels appear there. The AWT Robot is
 * created ahead of time by {@link #prepare()} (its initialisation costs
 * ~100ms, which blinded the first version of this probe to everything
 * before +100ms), and the capture is a small centred patch so a sample
 * costs a few ms rather than ~30.
 */
final class FlashProbe {
    private static volatile double x, y, w, h, opacity;
    private static volatile int frame;
    private static volatile boolean running;
    private static volatile Robot robot;
    private static final CountDownLatch robotReady = new CountDownLatch(1);
    private static volatile long showNanos;
    private static final CountDownLatch shown = new CountDownLatch(1);

    private FlashProbe() {}

    /** Call early (shell construction): warms the AWT Robot off the FX thread. */
    static void prepare() {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            try {
                robot = new Robot();
            } catch (Throwable e) {
                System.err.println("[flash-diag] screen probe unavailable: " + e);
            }
            robotReady.countDown();
            System.err.println("[flash-diag] screen probe robot ready in "
                    + (System.nanoTime() - t0) / 1_000_000 + "ms");
            try {
                shown.await();
            } catch (InterruptedException e) {
                return;
            }
            if (robot != null) sample();
        }, "flash-probe");
        t.setDaemon(true);
        t.start();
    }

    static void start(Stage stage, long showNanosArg) {
        prepare();
        showNanos = showNanosArg;
        x = stage.getX(); y = stage.getY();
        w = stage.getWidth(); h = stage.getHeight();
        opacity = stage.getOpacity();
        shown.countDown();
        new AnimationTimer() {
            @Override public void handle(long now) {
                frame++;
                x = stage.getX(); y = stage.getY();
                w = stage.getWidth(); h = stage.getHeight();
                opacity = stage.getOpacity();
                if (System.nanoTime() - showNanos > 2_500_000_000L) stop();
            }
        }.start();
        stage.widthProperty().addListener((o, a, b) -> geom("stage.width", b));
        stage.heightProperty().addListener((o, a, b) -> geom("stage.height", b));
        stage.xProperty().addListener((o, a, b) -> geom("stage.x", b));
        stage.yProperty().addListener((o, a, b) -> geom("stage.y", b));
        stage.maximizedProperty().addListener((o, a, b) -> geom("stage.maximized", b));
        stage.opacityProperty().addListener((o, a, b) -> geom("stage.opacity", b));
        Scene scene = stage.getScene();
        if (scene != null) {
            scene.widthProperty().addListener((o, a, b) -> geom("scene.width", b));
            scene.heightProperty().addListener((o, a, b) -> geom("scene.height", b));
        }
    }

    private static void geom(String what, Object value) {
        System.err.println("[flash-diag] " + what + "=" + value + " +" + ms() + "ms frame=" + frame);
    }

    private static long ms() {
        return (System.nanoTime() - showNanos) / 1_000_000;
    }

    private static void sample() {
        System.err.println("[flash-diag] screen probe sampling from +" + ms() + "ms");
        int lastWhite = -1, lastLight = -1;
        long lastLog = -1000;
        int samples = 0;
        while (ms() < 2000) {
            double sw = w, sh = h;
            if (sw < 40 || sh < 40 || Double.isNaN(x) || Double.isNaN(y)) {
                Thread.onSpinWait();
                continue;
            }
            // A small centred patch: cheap to grab (few ms), and any
            // whole-window white shows up there just the same.
            int rw = (int) Math.min(320, sw * 0.5), rh = (int) Math.min(200, sh * 0.5);
            int rx = (int) (x + (sw - rw) / 2), ry = (int) (y + (sh - rh) / 2);
            long t0 = System.nanoTime();
            BufferedImage img;
            try {
                img = robot.createScreenCapture(new Rectangle(rx, ry, rw, rh));
            } catch (Throwable e) {
                System.err.println("[flash-diag] screen capture failed: " + e);
                return;
            }
            long captureMs = (System.nanoTime() - t0) / 1_000_000;
            int white = 0, light = 0, n = 0;
            long luma = 0;
            for (int py = 0; py < rh; py += 4) {
                for (int px = 0; px < rw; px += 4) {
                    int rgb = img.getRGB(px, py);
                    int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                    int l = (r * 299 + g * 587 + b * 114) / 1000;
                    luma += l;
                    if (r >= 250 && g >= 250 && b >= 250) white++;
                    else if (l >= 200) light++;
                    n++;
                }
            }
            samples++;
            int whitePct = n == 0 ? 0 : white * 100 / n;
            int lightPct = n == 0 ? 0 : light * 100 / n;
            long nowMs = ms();
            boolean changed = Math.abs(whitePct - lastWhite) >= 2 || Math.abs(lightPct - lastLight) >= 2;
            boolean dense = nowMs < 400; // every sample early on, where it matters
            if (changed || dense || nowMs - lastLog >= 100) {
                System.err.println("[flash-diag] screen +" + nowMs + "ms frame=" + frame
                        + " opacity=" + opacity + " rect=" + rx + "," + ry + "," + rw + "x" + rh
                        + " white=" + whitePct + "% light=" + lightPct + "%"
                        + " meanLuma=" + (n == 0 ? 0 : luma / n)
                        + " captureMs=" + captureMs + " sample#" + samples);
                lastLog = nowMs;
                lastWhite = whitePct;
                lastLight = lightPct;
            }
        }
        System.err.println("[flash-diag] screen probe done, samples=" + samples);
    }
}
