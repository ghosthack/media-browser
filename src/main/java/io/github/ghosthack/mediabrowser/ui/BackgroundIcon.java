package io.github.ghosthack.mediabrowser.ui;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The menu-bar (macOS) or system-tray (Windows, Linux) icon shown while the
 * main window is closed and the app keeps serving the phone page
 * (docs/web-mode.md, "Lifecycle"): it says so, reopens the app, or quits it.
 * Absent where the platform has no tray (a headless JVM, a Linux desktop
 * without one) — the app then quits on close instead of running unseen.
 * After micro-harness-manager's {@code StatusIcon}.
 */
public final class BackgroundIcon {

    private static final String APP_NAME = "Media Browser";
    private static final String APP_ICON = "/io/github/ghosthack/mediabrowser/media-browser.png";

    /** What the menu does; called on the AWT event thread. */
    public interface Actions {
        void open();

        void quit();
    }

    private final SystemTray tray;
    private final TrayIcon icon;

    private BackgroundIcon(SystemTray tray, TrayIcon icon) {
        this.tray = tray;
        this.icon = icon;
    }

    /** The menu bar's appearance, read once off the FX thread by {@link #prepare}. */
    private static volatile Boolean darkMenuBar;

    /**
     * Starts AWT and reads the menu bar's appearance in the background, so a
     * later {@link #show} is quick. AWT's first start takes a moment, and on
     * macOS the window-close handler runs on the main thread AWT needs.
     */
    public static void prepare() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) {
                    SystemTray.getSystemTray().getTrayIconSize();
                    if (isMac()) darkMenuBar = macDarkMenuBar();
                }
            } catch (RuntimeException | Error e) {
                System.err.println("[BackgroundIcon] tray not available: " + e);
            }
        });
    }

    /**
     * Adds the icon for a phone page served on {@code port}, without waiting
     * on the caller's thread: completes with the icon, or empty when there is
     * no tray (or it could not be added).
     */
    public static CompletableFuture<Optional<BackgroundIcon>> show(int port, Actions actions) {
        return CompletableFuture.supplyAsync(() -> {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                return Optional.<BackgroundIcon>empty();
            }
            try {
                return Optional.of(onEventThread(() -> create(port, actions)));
            } catch (RuntimeException e) {
                System.err.println("[BackgroundIcon] could not add the tray icon: " + e);
                return Optional.<BackgroundIcon>empty();
            }
        });
    }

    private static BackgroundIcon create(int port, Actions actions) {
        SystemTray tray = SystemTray.getSystemTray();
        boolean mac = isMac();
        PopupMenu menu = new PopupMenu();
        MenuItem serving = new MenuItem(servingLabel(port));
        serving.setEnabled(false);
        MenuItem open = new MenuItem("Open " + APP_NAME);
        open.addActionListener(e -> actions.open());
        MenuItem quit = new MenuItem("Quit " + APP_NAME);
        quit.addActionListener(e -> actions.quit());
        menu.add(serving);
        menu.addSeparator();
        menu.add(open);
        menu.add(quit);

        int size = tray.getTrayIconSize().height;
        Boolean known = darkMenuBar;
        boolean dark = mac && (known != null ? known : macDarkMenuBar());
        TrayIcon icon = new TrayIcon(image(mac, dark, size), tooltip(port), menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> actions.open());   // double-click on Windows
        try {
            tray.add(icon);
        } catch (AWTException e) {
            throw new IllegalStateException(e);
        }
        return new BackgroundIcon(tray, icon);
    }

    /** Takes the icon away (on reopening or quitting). */
    public void remove() {
        EventQueue.invokeLater(() -> tray.remove(icon));
    }

    static String servingLabel(int port) {
        return "Serving the phone page on port " + port;
    }

    static String tooltip(int port) {
        return APP_NAME + " — phone page on port " + port;
    }

    /**
     * The icon at {@code size} points: on macOS a monochrome 2x2 mosaic in the
     * menu bar's text colour (AWT cannot mark an image as a template),
     * elsewhere the app icon. Drawn at twice the size for Retina/HiDPI trays.
     */
    static BufferedImage image(boolean mac, boolean dark, int size) {
        int px = Math.max(16, size) * 2;
        if (!mac) {
            BufferedImage appIcon = appIcon();
            if (appIcon != null) return scaled(appIcon, px);
        }
        return glyph(px, mac && !dark ? Color.BLACK : Color.WHITE);
    }

    /** Four rounded tiles, the mosaic, with a gap between them. */
    static BufferedImage glyph(int px, Color color) {
        BufferedImage image = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            double margin = px * 0.14;
            double gap = px * 0.08;
            double tile = (px - 2 * margin - gap) / 2;
            double arc = tile * 0.35;
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    g.fill(new RoundRectangle2D.Double(margin + col * (tile + gap),
                            margin + row * (tile + gap), tile, tile, arc, arc));
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage appIcon() {
        try (InputStream in = BackgroundIcon.class.getResourceAsStream(APP_ICON)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage scaled(BufferedImage source, int px) {
        BufferedImage out = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source.getScaledInstance(px, px, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("mac");
    }

    /** {@code defaults read -g AppleInterfaceStyle} prints "Dark" in dark mode and fails in light mode. */
    static boolean macDarkMenuBar() {
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return true;
            }
            return out.equalsIgnoreCase("Dark");
        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private static <T> T onEventThread(Callable<T> task) {
        if (EventQueue.isDispatchThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        FutureTask<T> future = new FutureTask<>(task);
        EventQueue.invokeLater(future);
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("the AWT event thread did not answer", e);
        }
    }
}
