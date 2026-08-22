package io.github.ghosthack.mediabrowser.ui.mosaic;

import javafx.scene.paint.Paint;

/** Exact compatibility renderer for the mosaic appearance that predates sets. */
final class CurrentMosaicTileSet implements MosaicTileSet {

    @Override public String id() { return "current"; }
    @Override public String label() { return "Current"; }
    @Override public boolean usesLegacyFolderGlyph() { return true; }

    @Override
    public void paint(MosaicTilePaintContext context) {
        context.paintCurrent();
    }

    @Override
    public boolean paintGlyphReticule(
            MosaicTilePaintContext context, Paint reticule) {
        return context.paintCurrentGlyphReticule(reticule);
    }
}
