package io.github.ghosthack.mediabrowser.ui.mosaic;

/**
 * Pluggable procedural artwork for mosaic tiles. Implementations are discovered
 * through {@link java.util.ServiceLoader}; built-ins are always registered
 * first.
 */
public interface MosaicTileSet {

    /** Stable persistence/plugin identifier, for example {@code "current"}. */
    String id();

    /** Human-readable Settings label. */
    String label();

    /** Paints one tile's content canvas. Selection is painted by the host. */
    void paint(MosaicTilePaintContext context);

    /**
     * Whether the legacy folder-glyph selector is meaningful for this set.
     * Settings disables that control for sets with their own folder language.
     */
    default boolean usesLegacyFolderGlyph() {
        return false;
    }
}
