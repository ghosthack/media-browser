package io.github.ghosthack.mediabrowser.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * TEMPORARY diagnostic — remove with the other {@code [flash-diag]} sites.
 *
 * <p>Samples the real on-screen pixels inside the stage's rectangle from a
 * background thread for the first two seconds after {@code stage.show()},
 * logging when (relative to the show and the opacity reveal) pure-white or
 * light pixels appear there. This is the one observation the timestamp-only
 * logging cannot make: whether the flash is the native window surface before
 * the first frame (opacity not hiding it), the un-layering at reveal, or a
 * frame painted after the reveal. Pure white (255,255,255) is the native
 * Win32 surface; ~#f4f4f4 is an unstyled Modena control; anything else is
 * scene content.
 */
final class FlashProbe {
    private static volatile double x, y, w, h, opacity;
    private static volatile int frame;
    private static volatile boolean running;

    private FlashProbe() {}

    static void start(Stage stage, long showNanos) {
        if (running) return;
        running = true;
        // FX-thread side: geometry + opacity snapshot every pulse, plus
        // stage/scene size changes as they happen.
        new AnimationTimer() {
            @Override public void handle(long now) {
                frame++;
                x = stage.getX(); y = stage.getY();
                w = stage.getWidth(); h = stage.getHeight();
                opacity = stage.getOpacity();
                if (System.nanoTime() - showNanos > 2_000_000_000L) stop();
            }
        }.start();
        stage.widthProperty().addListener((o, a, b) -> geom("stage.width", b, showNanos));
        stage.heightProperty().addListener((o, a, b) -> geom("stage.height", b, showNanos));
        stage.xProperty().addListener((o, a, b) -> geom("stage.x", b, showNanos));
        stage.yProperty().addListener((o, a, b) -> geom("stage.y", b, showNanos));
        stage.maximizedProperty().addListener((o, a, b) -> geom("stage.maximized", b, showNanos));
        stage.opacityProperty().addListener((o, a, b) -> geom("stage.opacity", b, showNanos));
        Scene scene = stage.getScene();
        if (scene != null) {
            scene.widthProperty().addListener((o, a, b) -> geom("scene.width", b, showNanos));
            scene.heightProperty().addListener((o, a, b) -> geom("scene.height", b, showNanos));
        }
        Thread t = new Thread(() -> sample(showNanos), "flash-probe");
        t.setDaemon(true);
        t.start();
    }

    private static void geom(String what, Object value, long showNanos) {
        System.err.println("[flash-diag] " + what + "=" + value + " +" + ms(showNanos) + "ms frame=" + frame);
    }

    private static long ms(long since) {
        return (System.nanoTime() - since) / 1_000_000;
    }

    private static void sample(long showNanos) {
        Robot robot;
        try {
            robot = new Robot();
        } catch (Throwable e) {
            System.err.println("[flash-diag] screen probe unavailable: " + e);
            return;
        }
        int lastWhite = -1, lastLight = -1;
        long lastLog = 0;
        int samples = 0;
        while (ms(showNanos) < 2000) {
            double sw = w, sh = h;
            if (sw < 40 || sh < 40 || Double.isNaN(x) || Double.isNaN(y)) {
                Thread.onSpinWait();
                continue;
            }
            // Inset by 10% per side so a logical/device coordinate mismatch
            // between AWT and FX still lands the sample inside the window.
            int rx = (int) (x + sw * 0.1), ry = (int) (y + sh * 0.1);
            int rw = (int) (sw * 0.8), rh = (int) (sh * 0.8);
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
            for (int py = 0; py < rh; py += 8) {
                for (int px = 0; px < rw; px += 8) {
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
            long nowMs = ms(showNanos);
            boolean changed = Math.abs(whitePct - lastWhite) >= 2 || Math.abs(lightPct - lastLight) >= 2;
            if (changed || nowMs - lastLog >= 100) {
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
