package io.github.ghosthack.mediabrowser;

/**
 * Which loading indicator the viewer shows while the next media decodes.
 *
 * <ul>
 *   <li>{@link #NONE} — no indicator; the current visual stays on screen until
 *       the next one is ready.</li>
 *   <li>{@link #DEFAULT} — the plain "Loading…" placeholder text (a basic
 *       label).</li>
 *   <li>{@link #GAME_CONSOLE} — a 2000s CD-console "Now loading …" overlay in
 *       the viewport's bottom-left, drawn over the current visual (the default
 *       for a fresh install).</li>
 * </ul>
 */
public enum LoadingIndicator {
    NONE("None"),
    DEFAULT("Basic label"),
    GAME_CONSOLE("Game Console");

    private final String label;

    LoadingIndicator(String label) {
        this.label = label;
    }

    /** A human-readable name for menus and the Settings dialog. */
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
    public static LoadingIndicator fromSettings(String value, LoadingIndicator fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
