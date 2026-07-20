package io.github.ghosthack.mediabrowser;

import java.util.Locale;

/**
 * How the application hosts its three views (browser, mosaic, viewer).
 *
 * <ul>
 *   <li>{@link #SINGLE} — one window; the views fill it one at a time and
 *       Escape leaves the viewer, while the mosaic is left through the menu
 *       bar (see {@code ui.AppShell}).</li>
 *   <li>{@link #MULTI} — the classic layout: each view is its own window,
 *       opened and raised independently, with the Keep Focus / auto-open
 *       behaviours that only make sense across separate windows.</li>
 * </ul>
 *
 * <p>Read once at startup ({@code window.mode} in {@code app.properties});
 * changing it takes effect on the next start.</p>
 */
public enum WindowMode {
    SINGLE("Single window"),
    MULTI("Separate windows (browser / mosaic / viewer)");

    private final String label;

    WindowMode(String label) {
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

    /**
     * Parses a persisted setting value (an enum constant name) into a mode,
     * falling back to {@code fallback} for {@code null} or unrecognised input so
     * an old or hand-edited properties file can't crash startup.
     */
    public static WindowMode fromSettings(String value, WindowMode fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
