package io.github.ghosthack.mediabrowser.ui.mosaic;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Compose: reproduces the tile language of the Kotlin/Compose port
 * (../kotlin-mosaic), designed to pair with the Compose Dark theme.
 *
 * <p>Non-photographic marks use the Compose port's stroked vector language
 * (SVG path data on a 24x24 grid, 2-unit stroke, round caps/joins), scaled
 * through {@link GraphicsContext#scale} so the stroke weight stays
 * proportional at any tile size, exactly like the Compose {@code Canvas.scale}
 * call it mirrors. The text and binary sheets extend that port's File outline
 * with small semantic marks on the same grid. {@link
 * GraphicsContext#appendSVGPath} keeps the geometry resolution-independent.</p>
 *
 * <p>Canvas painting cannot read CSS, so the Compose Dark palette values this
 * set needs are private constants here (see {@code docs/mosaic-tile-sets.md}
 * for the exact hex/alpha and the deliberate simplifications versus the
 * Kotlin source, including which host callbacks stand in for Compose-only
 * behaviour this SPI does not expose: the folder-glyph style selector, and
 * the true recursive-barren reticule).</p>
 */
final class ComposeMosaicTileSet implements MosaicTileSet {

    // --- Compose Dark palette (private: canvas painting can't read CSS) ---
    private static final Color MUTED = Color.web("#9AA0AA");
    private static final Color MUTED_DISABLED = Color.web("#9AA0AA", 0.5);
    private static final Color DANGER_FAILED = Color.web("#E5534B", 0.70);
    private static final Color BADGE_BG = Color.rgb(0, 0, 0, 0.60);
    private static final Color BADGE_ICON = Color.rgb(255, 255, 255, 0.88);

    // --- Icon.kt path data, verbatim: a 24x24 grid, 2-unit round stroke ----
    private static final String FOLDER_PATH =
            "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z";
    private static final String ARCHIVE_PATH =
            "M21 8v13H3V8M1 3h22v5H1zM10 12h4";
    private static final String IMAGE_PATH =
            "M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2z"
                    + " M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z M21 15l-5-5L5 21";
    private static final String VIDEO_PATH =
            "M19.8 2H4.2A2.2 2.2 0 0 0 2 4.2v15.6A2.2 2.2 0 0 0 4.2 22h15.6a2.2 2.2 0 0 0 2.2-2.2"
                    + "V4.2A2.2 2.2 0 0 0 19.8 2z M7 2v20M17 2v20M2 12h20M2 7h5M2 17h5M17 17h5M17 7h5";
    private static final String AUDIO_PATH =
            "M9 18V5l12-2v13 M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M21 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z";
    private static final String TEXT_FILE_PATH =
            "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"
                    + " M13 2v7h7 M8 13h8 M8 17h8";
    private static final String BINARY_FILE_PATH =
            "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"
                    + " M13 2v7h7 M8 13v4 M11 13v4 M8 13h3 M8 17h3"
                    + " M15 14l1-1v4 M15 17h2";
    private static final String CLOSE_PATH = "M18 6L6 18M6 6l12 12";
    private static final String ARROW_UP_PATH = "M12 19V5M5 12l7-7 7 7";

    /** The grid every path string above is authored on. */
    private static final double ICON_VIEWPORT = 24;
    /** Stroke weight on that grid, so it scales with the icon (Icon.kt: ICON_STROKE). */
    private static final double ICON_STROKE = 2;
    /** A glyph fills this fraction of its tile (Icon.kt: GLYPH_FRACTION). */
    private static final double GLYPH_FRACTION = 0.42;

    private static final double BADGE_ICON_SIZE = 16;
    private static final double BADGE_PADDING = 4;
    private static final double BADGE_INNER_PADDING = 2;
    private static final double BADGE_RADIUS = 4;
    /**
     * Below this tile side the fixed-size badge (Compose's is a constant dp
     * size, not proportional) would cover more of the tile than the artwork
     * it is annotating; Compose mosaic tiles don't get this small in
     * practice, but this host's grid does, so the badge is skipped rather
     * than drawn oversized.
     */
    private static final double BADGE_MIN_TILE = 48;

    @Override public String id() { return "compose"; }
    @Override public String label() { return "Compose"; }

    @Override
    public boolean paintGlyphReticule(MosaicTilePaintContext c, Paint reticule) {
        String path = switch (c.identity()) {
            case FOLDER -> FOLDER_PATH;
            default -> nonMediaFilePath(MosaicTileClassifier.isTextLike(c.entry()));
        };
        double box = c.size() * GLYPH_FRACTION;
        double left = c.x() + (c.size() - box) / 2;
        double top = c.y() + (c.size() - box) / 2;
        double factor = box / ICON_VIEWPORT;
        GraphicsContext g = c.graphics();
        g.save();
        try {
            g.translate(left, top);
            g.scale(factor, factor);
            g.beginPath();
            g.appendSVGPath(path);
            g.clip();
            // Return to tile coordinates while retaining the device-space clip.
            g.scale(1 / factor, 1 / factor);
            g.translate(-left, -top);
            g.setFill(reticule);
            g.fillRect(c.x(), c.y(), c.size(), c.size());
        } finally {
            g.restore();
        }
        return true;
    }

    @Override
    public void paint(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        g.save();
        try {
            switch (c.identity()) {
                case PARENT -> drawGlyph(c, staticGlyphPath(c.identity()), MUTED);
                case FOLDER -> drawFolder(c);
                // Inspectable archives stay directory-like for navigation, but
                // use the same Archive icon as the Compose browser list. Sealed
                // archive-like files continue through the plain-file fallback.
                case ARCHIVE_BROWSABLE -> drawGlyph(c, staticGlyphPath(c.identity()), MUTED);
                case MEDIA_IMAGE, MEDIA_VIDEO, MEDIA_AUDIO -> drawMedia(c);
                // Keep the classifier's extension-only contract: recognized
                // text and textual data get a lined sheet; every other
                // non-media file gets a binary 0/1 sheet.
                default -> drawGlyph(c,
                        nonMediaFilePath(MosaicTileClassifier.isTextLike(c.entry())), MUTED);
            }
            c.drawCaption();
        } finally {
            g.restore();
        }
    }

    /** Static placeholder chosen for identities that do not have a rendition. */
    static String staticGlyphPath(MosaicTileIdentity identity) {
        return switch (identity) {
            case PARENT -> ARROW_UP_PATH;
            case FOLDER -> FOLDER_PATH;
            case ARCHIVE_BROWSABLE -> ARCHIVE_PATH;
            case TEXT -> TEXT_FILE_PATH;
            default -> BINARY_FILE_PATH;
        };
    }

    /** Two-way non-media placeholder split used by the paint path. */
    static String nonMediaFilePath(boolean textLike) {
        return textLike ? TEXT_FILE_PATH : BINARY_FILE_PATH;
    }

    /**
     * A folder tile: the child-preview collage when the host has one,
     * otherwise the stroked folder glyph, plus the host's dead-end mark.
     *
     * <p>Kotlin's MosaicFolderGlyph selector (GLYPH/ROUNDED/INVERSE/IMAGE)
     * has no host hook this SPI exposes to a tile set, so this set fixes on
     * the GLYPH treatment — Compose's own default. Its INVERSE wash and
     * IMAGE photo remain reachable only through the Current set's legacy
     * folder-glyph control.</p>
     *
     * <p>The host's dead-end reticule ({@link MosaicTilePaintContext#drawReticule})
     * is a grey dither pattern keyed off the immediate listing's {@link
     * io.github.ghosthack.mediabrowser.media.FolderVerdict} plus a subtree
     * barren check; Kotlin's is a flat danger-red circle-and-slash keyed off
     * only the subtree check. The subtree check itself is not exposed to a
     * tile set, so reproducing Kotlin's mark exactly is not possible without
     * either duplicating host-only state or silently ignoring the "mark dead
     * ends" setting the host callback already honours. Every other built-in
     * set in this app makes the same call, so this one keeps that
     * consistency and accepts the visual difference.</p>
     */
    private static void drawFolder(MosaicTilePaintContext c) {
        boolean collage = c.drawFolderCollage();
        if (!collage) drawGlyph(c, FOLDER_PATH, MUTED);
        c.drawReticule();
    }

    /**
     * A media tile, matching Kotlin's four rendition states: Ready (thumbnail
     * plus a kind badge for anything that isn't a still), Failed (a danger
     * close mark for visual media, or the normal audio glyph because audio has
     * no poster to decode), Blank/pending (a muted kind glyph plus badge), and
     * the quiet default when nothing has been requested (a dimmer kind glyph,
     * no badge).
     */
    private static void drawMedia(MosaicTilePaintContext c) {
        if (c.drawMediaThumbnail()) {
            if (c.identity() != MosaicTileIdentity.MEDIA_IMAGE) drawKindBadge(c);
            return;
        }
        if (c.has(MosaicTileModifier.THUMBNAIL_FAILED)) {
            boolean audio = c.identity() == MosaicTileIdentity.MEDIA_AUDIO;
            drawGlyph(c, failedMediaPath(c.identity()), audio ? MUTED : DANGER_FAILED);
            return;
        }
        if (c.has(MosaicTileModifier.THUMBNAIL_PENDING)) {
            drawGlyph(c, kindPath(c.identity()), MUTED);
            if (c.identity() != MosaicTileIdentity.MEDIA_IMAGE) drawKindBadge(c);
            return;
        }
        drawGlyph(c, kindPath(c.identity()), MUTED_DISABLED);
    }

    /** Audio's absent poster is not an opening/decode failure worth marking red. */
    static String failedMediaPath(MosaicTileIdentity identity) {
        return identity == MosaicTileIdentity.MEDIA_AUDIO ? AUDIO_PATH : CLOSE_PATH;
    }

    private static String kindPath(MosaicTileIdentity identity) {
        return switch (identity) {
            case MEDIA_VIDEO -> VIDEO_PATH;
            case MEDIA_AUDIO -> AUDIO_PATH;
            default -> IMAGE_PATH;
        };
    }

    /**
     * The top-right kind chip: 4px corner padding, a 60%-black 4px-radius
     * rounded backing, 2px inner padding, and a 16px white-at-88% pictogram.
     * Fixed pixel sizes throughout, matching Compose's constant-dp badge
     * rather than this app's usual tile-proportional scaling.
     */
    private static void drawKindBadge(MosaicTilePaintContext c) {
        if (c.size() < BADGE_MIN_TILE) return;
        GraphicsContext g = c.graphics();
        double chip = BADGE_ICON_SIZE + 2 * BADGE_INNER_PADDING;
        double left = c.x() + c.size() - BADGE_PADDING - chip;
        double top = c.y() + BADGE_PADDING;
        g.setFill(BADGE_BG);
        g.fillRoundRect(left, top, chip, chip, BADGE_RADIUS, BADGE_RADIUS);
        strokeIcon(g, kindPath(c.identity()),
                left + BADGE_INNER_PADDING, top + BADGE_INNER_PADDING,
                BADGE_ICON_SIZE, BADGE_ICON);
    }

    /** One icon, centred at {@link #GLYPH_FRACTION} of the tile side. */
    private static void drawGlyph(MosaicTilePaintContext c, String path, Color tint) {
        double box = c.size() * GLYPH_FRACTION;
        double left = c.x() + (c.size() - box) / 2;
        double top = c.y() + (c.size() - box) / 2;
        strokeIcon(c.graphics(), path, left, top, box, tint);
    }

    /**
     * Strokes one Icon.kt path into a {@code box}-square at ({@code left},
     * {@code top}), scaling the 24x24 authoring grid (and so the stroke
     * weight) uniformly to fit — the Canvas transform equivalent of Compose's
     * {@code scale(factor, factor) { drawPath(..., Stroke(width = ICON_STROKE)) }}.
     */
    private static void strokeIcon(
            GraphicsContext g, String path, double left, double top, double box, Color tint) {
        if (box <= 0) return;
        double factor = box / ICON_VIEWPORT;
        g.save();
        try {
            g.translate(left, top);
            g.scale(factor, factor);
            g.setStroke(tint);
            g.setLineWidth(ICON_STROKE);
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.beginPath();
            g.appendSVGPath(path);
            g.stroke();
        } finally {
            g.restore();
        }
    }
}
