package io.github.ghosthack.mediabrowser;

import java.util.Locale;

/**
 * A selectable application look, applied as a stylesheet overlaid on top of the
 * JavaFX default (Modena) base across every window.
 *
 * <ul>
 *   <li>{@link #DEFAULT} — the stock JavaFX (Modena) look, no overlay.</li>
 *   <li>{@link #PLAIN_DARK} — a flat black/grey dark overlay.</li>
 *   <li>{@link #PLAIN_DARK_GRAY} — the same flat look on a dark-gray base
 *       instead of black.</li>
 *   <li>{@link #CUPERTINO_LIGHT} / {@link #CUPERTINO_DARK} — the macOS-styled
 *       AtlantaFX Cupertino themes.</li>
 * </ul>
 *
 * <p>Each non-default theme names a CSS resource bundled under
 * {@code io/github/ghosthack/mediabrowser/ui/themes/}; {@code ui.ThemeManager} resolves
 * it and applies it live to all open scenes. Selecting a theme takes effect
 * immediately (see {@code ui.MainWindow}'s Settings dialog).</p>
 */
public enum Theme {
    DEFAULT("Default JavaFX", null),
    PLAIN_DARK("Plain Dark", "/io/github/ghosthack/mediabrowser/ui/themes/plain-dark.css"),
    PLAIN_DARK_GRAY("Plain Dark Gray", "/io/github/ghosthack/mediabrowser/ui/themes/plain-dark-gray.css"),
    CUPERTINO_LIGHT("Cupertino Light", "/io/github/ghosthack/mediabrowser/ui/themes/cupertino-light.css"),
    CUPERTINO_DARK("Cupertino Dark", "/io/github/ghosthack/mediabrowser/ui/themes/cupertino-dark.css");

    private final String label;
    private final String resource;

    Theme(String label, String resource) {
        this.label = label;
        this.resource = resource;
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
     * The resolved stylesheet URL (an {@code toExternalForm()} string) to overlay
     * on the default look, or {@code null} for {@link #DEFAULT} (no overlay) or
     * when the resource is missing from the classpath.
     */
    public String stylesheetUrl() {
        if (resource == null) return null;
        var url = Theme.class.getResource(resource);
        return url == null ? null : url.toExternalForm();
    }

    /**
     * Parses a persisted setting value (an enum constant name) into a theme,
     * falling back to {@code fallback} for {@code null} or unrecognised input so
     * an old or hand-edited properties file can't crash startup.
     */
    public static Theme fromSettings(String value, Theme fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
