package io.github.ghosthack.mediabrowser;

/** Where Mosaic paints dead-end and won't-open reticules. */
public enum MosaicReticuleScope {
    TILE("Entire tile"),
    GLYPH("Icon glyph only"),
    GLYPH_BLACK("Icon glyph only — black");

    public static final MosaicReticuleScope DEFAULT = GLYPH_BLACK;

    private final String label;

    MosaicReticuleScope(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public boolean glyphOnly() {
        return this != TILE;
    }

    public boolean black() {
        return this == GLYPH_BLACK;
    }

    /** Parses a persisted enum name without letting a hand-edited value break startup. */
    public static MosaicReticuleScope fromSettings(
            String value, MosaicReticuleScope fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
