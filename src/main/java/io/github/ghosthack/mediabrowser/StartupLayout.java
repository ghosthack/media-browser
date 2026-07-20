package io.github.ghosthack.mediabrowser;

import java.util.Locale;

/**
 * Which windows the application opens, and how it tiles them, on startup.
 *
 * <ul>
 *   <li>{@link #BROWSER} — just the file-browser (main) window.</li>
 *   <li>{@link #MOSAIC} — just the mosaic, the browser hidden.</li>
 *   <li>{@link #MOSAIC_VIEWER} — the mosaic tiled to the screen's left half and
 *       the viewer to the right half (a two-window layout).</li>
 *   <li>{@link #BROWSER_MOSAIC_VIEWER} — the browser, mosaic and viewer tiled
 *       left-to-right into screen thirds (a three-panel layout).</li>
 * </ul>
 *
 * <p>The viewer is hidden until the first item is opened, so layouts that
 * include it tile it the first time it appears rather than up front (see
 * {@code ui.MainWindow}).</p>
 */
public enum StartupLayout {
    BROWSER("Browser only"),
    MOSAIC("Mosaic only"),
    MOSAIC_VIEWER("2 windows: Mosaic | Viewer"),
    BROWSER_MOSAIC_VIEWER("3 panels: Browser | Mosaic | Viewer");

    private final String label;

    StartupLayout(String label) {
        this.label = label;
    }

    /** Human-readable name for the Settings dialog. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** Whether startup opens the mosaic (every layout except {@link #BROWSER}). */
    public boolean opensMosaic() {
        return this != BROWSER;
    }

    /** Whether startup also shows the file-browser window. */
    public boolean showsBrowser() {
        return this == BROWSER || this == BROWSER_MOSAIC_VIEWER;
    }

    /** Whether the viewer is part of this layout (and so tiles when it appears). */
    public boolean showsViewer() {
        return this == MOSAIC_VIEWER || this == BROWSER_MOSAIC_VIEWER;
    }

    /**
     * Parses a persisted setting value (an enum constant name) into a layout,
     * falling back to {@code fallback} for {@code null} or unrecognised input so
     * an old or hand-edited properties file can't crash startup.
     */
    public static StartupLayout fromSettings(String value, StartupLayout fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
