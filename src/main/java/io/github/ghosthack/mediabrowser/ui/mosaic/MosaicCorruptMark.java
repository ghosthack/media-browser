package io.github.ghosthack.mediabrowser.ui.mosaic;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * The shared {@link MosaicTileModifier#CORRUPT} sign: an amber warning
 * triangle with an exclamation mark. Every built-in tile set draws its
 * failure state differently (a red cross, a strike, a banner), so corrupt data
 * gets one sign of its own that reads the same in all of them — and never
 * looks like any set's plain failure.
 */
public final class MosaicCorruptMark {

    /** Amber: a warning, not the red of a plain failure. */
    public static final Color COLOR = Color.web("#D29922");
    /** Feather's alert-triangle on a 24×24 grid: triangle, exclamation stem, dot. */
    public static final String PATH =
            "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86"
                    + "a2 2 0 0 0-3.42 0z M12 9v4 M12 17h.01";

    private static final Color BADGE_BACKING = Color.rgb(0, 0, 0, 0.62);

    private MosaicCorruptMark() {}

    /** The sign centred in a {@code box}-square at ({@code left}, {@code top}). */
    public static void paint(GraphicsContext g, double left, double top, double box) {
        if (box <= 0) return;
        double factor = box / 24.0;
        g.save();
        try {
            g.translate(left, top);
            g.scale(factor, factor);
            g.setStroke(COLOR);
            g.setLineWidth(2);
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.beginPath();
            g.appendSVGPath(PATH);
            g.stroke();
        } finally {
            g.restore();
        }
    }

    /** The sign as the tile's main glyph, at {@code fraction} of the tile side. */
    public static void paintCentred(GraphicsContext g, double x, double y, double size,
                                    double fraction) {
        double box = size * fraction;
        paint(g, x + (size - box) / 2, y + (size - box) / 2, box);
    }

    /**
     * The sign as a top-right corner badge on a dark backing, for a tile whose
     * own artwork stays (an archive's folder glyph).
     */
    public static void paintBadge(GraphicsContext g, double x, double y, double size) {
        double box = Math.max(12, size * 0.26);
        double pad = Math.max(2, size * 0.04);
        double left = x + size - pad - box;
        double top = y + pad;
        g.save();
        try {
            g.setFill(BADGE_BACKING);
            g.fillRoundRect(left - 2, top - 2, box + 4, box + 4, 6, 6);
        } finally {
            g.restore();
        }
        paint(g, left, top, box);
    }

    /** {@link #paintBadge} on a paint context. */
    public static void paintBadge(MosaicTilePaintContext c) {
        paintBadge(c.graphics(), c.x(), c.y(), c.size());
    }

    /**
     * Whether {@code c} is a media tile decoded from corrupt data: CORRUPT
     * without THUMBNAIL_FAILED, so its thumbnail is drawn and the sign goes
     * on as a corner badge rather than in place of the picture.
     */
    public static boolean decodedButCorrupt(MosaicTilePaintContext c) {
        return c.has(MosaicTileModifier.CORRUPT)
                && !c.has(MosaicTileModifier.THUMBNAIL_FAILED)
                && switch (c.identity()) {
                    case MEDIA_IMAGE, MEDIA_VIDEO, MEDIA_AUDIO -> true;
                    default -> false;
                };
    }
}
