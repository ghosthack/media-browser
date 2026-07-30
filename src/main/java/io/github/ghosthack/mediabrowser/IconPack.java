package io.github.ghosthack.mediabrowser;

import java.util.Locale;

/**
 * The application-wide icon artwork. Icon packs are independent of
 * {@link Theme}: a pack supplies shapes while the active control/theme supplies
 * their colour.
 */
public enum IconPack {
    /** The Unicode and emoji glyphs used before selectable icon packs existed. */
    ORIGINAL("Original"),
    /** A calm, rounded monoline set. */
    LUCID("Lucid"),
    /** A compact, solid set drawn on a pixel grid. */
    ARCADE("Arcade");

    private final String label;

    IconPack(String label) {
        this.label = label;
    }

    /** Human-readable name for Settings and previews. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * Parses a persisted enum name, preserving the original artwork for missing
     * or unknown values.
     */
    public static IconPack fromSettings(String value, IconPack fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
