package io.github.ghosthack.mediabrowser;

/**
 * How a mosaic folder tile with no preview collage is drawn.
 *
 * <ul>
 *   <li>{@link #GLYPH} — the muted gray vector folder shape (body + tab) on the
 *       black tile.</li>
 *   <li>{@link #ROUNDED} — the same gray shape with rounded corners.</li>
 *   <li>{@link #INVERSE} — the same shape inverted: a black folder on a tile
 *       filled with the glyph gray.</li>
 *   <li>{@link #IMAGE} — a photographic folder image (a pre-resampled asset
 *       chosen for the tile size), falling back to {@link #GLYPH} if the asset
 *       is absent.</li>
 * </ul>
 */
public enum MosaicFolderGlyph {
    GLYPH("Glyph (gray folder)"),
    ROUNDED("Rounded Glyph (gray folder)"),
    INVERSE("Inverse Glyph (black on gray)"),
    IMAGE("Folder Image (photo)");

    private final String label;

    MosaicFolderGlyph(String label) {
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
    public static MosaicFolderGlyph fromSettings(String value, MosaicFolderGlyph fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
