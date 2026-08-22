package io.github.ghosthack.mediabrowser;

import java.util.Locale;

/**
 * The structural chrome variant — which Browser and Mosaic toolbar
 * compositions the app builds. Deliberately independent of {@link Theme},
 * {@link IconPack} and the mosaic tile set: any style pairs with any of those.
 *
 * <ul>
 *   <li>{@link #COMPOSE} (the default) — the icon-first Browser and Mosaic
 *       toolbars transcribed from the Kotlin/Compose port.</li>
 *   <li>{@link #CLASSIC} — this app's long-standing text-labelled toolbars.</li>
 * </ul>
 *
 * <p>Read once at startup ({@code ui.style} in {@code app.properties}), like
 * {@link WindowMode}; changing it takes effect on the next start.</p>
 */
public enum UiStyle {
    CLASSIC("Classic"),
    COMPOSE("Compose");

    private final String label;

    UiStyle(String label) {
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
     * Parses a persisted setting value (an enum constant name) into a style,
     * falling back to {@code fallback} for {@code null} or unrecognised input so
     * an old or hand-edited properties file can't crash startup.
     */
    public static UiStyle fromSettings(String value, UiStyle fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
