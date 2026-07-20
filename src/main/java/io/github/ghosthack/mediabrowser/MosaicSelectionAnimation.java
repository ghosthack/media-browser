package io.github.ghosthack.mediabrowser;

/**
 * How the mosaic highlights its selected (lead/cursor) tile over time.
 *
 * <p>All styles repaint only the single lead cell per animation frame, so the
 * cost is one tile redraw regardless of how big the grid is.</p>
 *
 * <ul>
 *   <li>{@link #NONE} — no animation; the static dotted selection marker only.</li>
 *   <li>{@link #PULSE} — a game-style brightness pulse: a white overlay fading
 *       {@code 0 → 50% → 0}, then a black overlay {@code 0 → 50% → 0}, repeating.</li>
 *   <li>{@link #MARCHING_ANTS} — a sober option that animates the dotted
 *       selection border itself, sliding the dots steadily around the tile
 *       perimeter (a "marching ants" crawl) without touching the rendition.</li>
 * </ul>
 */
public enum MosaicSelectionAnimation {
    NONE("None"),
    PULSE("Pulse (brightness)"),
    MARCHING_ANTS("Marching Ants (border)");

    private final String label;

    MosaicSelectionAnimation(String label) {
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
     * falling back to {@code fallback} for {@code null} or unrecognised input
     * so an old or hand-edited properties file can't crash startup.
     */
    public static MosaicSelectionAnimation fromSettings(String value,
                                                        MosaicSelectionAnimation fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
