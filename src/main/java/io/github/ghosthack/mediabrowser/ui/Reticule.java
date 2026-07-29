package io.github.ghosthack.mediabrowser.ui;

import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.ImagePattern;

/**
 * The dead-end dither for a mosaic folder tile — the 8-bit trick of faking a
 * shadow with a screen door rather than a translucent layer.
 *
 * <p>Density is ordinal on purpose: more dither means less behind it, so the
 * three marks read as one scale instead of three unrelated symbols.</p>
 *
 * <p>Each pattern is a 4×4 motif of a semi-opaque black veil. Its
 * {@link ImagePattern} cell is proportional to the painted tile bounds, so the
 * dither keeps the same visual scale as tiles grow and shrink instead of
 * becoming fine noise on large tiles or a coarse mask on small ones. The
 * pattern is built once, on the FX thread, at first paint.</p>
 */
enum Reticule {

    /** 25%, sparse — junk-only: something is in there, but only droppings. */
    SPARSE,

    /** 50% checkerboard — empty, and empty subtrees when that check is on. */
    HALF,

    /** Diagonal hatch — unreadable. Not a dead end, a folder we could not judge. */
    HATCH;

    /** Cell edge in logical pixels. Small enough to read as texture, not stripes. */
    private static final int CELL = 4;

    /** A cell spans 4% of the tile edge: 4 px at the default 100 px tile. */
    static final double CELL_FRACTION = 0.04;

    /** ~80% black: it should sink the glyph, not erase it. */
    private static final int VEIL = 0xCC000000;

    private static final int CLEAR = 0x00000000;

    private ImagePattern pattern;

    /**
     * The fill for this density, built on first use. Not thread-safe by
     * design: it is only ever touched from the FX thread during a paint.
     */
    ImagePattern pattern() {
        if (pattern == null) {
            pattern = new ImagePattern(
                    render(), 0, 0, CELL_FRACTION, CELL_FRACTION, true);
        }
        return pattern;
    }

    private WritableImage render() {
        var image = new WritableImage(CELL, CELL);
        PixelWriter px = image.getPixelWriter();
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                px.setArgb(x, y, covers(x, y) ? VEIL : CLEAR);
            }
        }
        return image;
    }

    /** Whether this cell position is veiled. */
    private boolean covers(int x, int y) {
        return switch (this) {
            case SPARSE -> x % 2 == 0 && y % 2 == 0;    // one pixel in four
            case HALF -> (x + y) % 2 == 0;              // checkerboard
            case HATCH -> (x + y) % CELL == 0;          // diagonal rule
        };
    }
}
