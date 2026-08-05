package io.github.ghosthack.mediabrowser.ui.mosaic;

import io.github.ghosthack.mediabrowser.media.FolderVerdict;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.geometry.VPos;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.Locale;

/**
 * Xedge: the Current tile set with folder and extension geometry continued to
 * every edge of the tile. In a seamless grid, adjacent generated tiles read as
 * pieces of one larger construction instead of isolated badges.
 */
final class XedgeMosaicTileSet implements MosaicTileSet {

    private static final Color NEUTRAL_FILL = Color.web("#4a4a4a");
    private static final Color HALF_NEUTRAL_FILL = Color.web("#252525");
    private static final Color QUARTER_NEUTRAL_FILL = Color.web("#131313");
    private static final Color LINE_GRAY = Color.web("#292929");
    private static final Color SHARP_LINE = Color.web("#777777");
    private static final Color SHARP_TEXT = Color.web("#b8b8b8");
    private static final Color PARENT_GRAY = Color.web("#555555");
    private static final Color BORDER_SHADOW = Color.web("#1d1d1d");

    private static final Palette MONOCHROME = new Palette(
            NEUTRAL_FILL, NEUTRAL_FILL,
            LINE_GRAY, LINE_GRAY, LINE_GRAY, LINE_GRAY, LINE_GRAY,
            HALF_NEUTRAL_FILL, NEUTRAL_FILL,
            LINE_GRAY, LINE_GRAY, LINE_GRAY, PARENT_GRAY,
            Color.BLACK, LINE_GRAY, true, false, false, false, true, true);
    private static final Palette COLOR = new Palette(
            Color.web("#c99a3d"), NEUTRAL_FILL,
            LINE_GRAY, LINE_GRAY, Color.web("#3f78a8"),
            LINE_GRAY, Color.web("#a33f3f"), NEUTRAL_FILL, Color.WHITE,
            LINE_GRAY, LINE_GRAY, LINE_GRAY, PARENT_GRAY,
            Color.BLACK, LINE_GRAY, true, false, false, false, true, true);
    private static final Palette SHARP = sharpPalette(true);
    private static final Palette LITE = sharpPalette(false);

    /*
     * The Current square folder is 46% x 34% of the tile, centred, with a tab
     * 42% as wide and 22% as tall as the body. These are its six contour axes.
     */
    private static final double LEFT = 0.27;
    private static final double TAB_RIGHT = 0.27 + 0.46 * 0.42;
    private static final double RIGHT = 0.73;
    private static final double TAB_TOP = 0.33 - 0.34 * 0.22;
    private static final double BODY_TOP = 0.33;
    private static final double BOTTOM = 0.67;

    /** One third of the original dash-width study, so the extensions stay quiet. */
    private static final double LINE_SCALE = 0.02;

    private final String id;
    private final String label;
    private final Palette palette;
    private final double lineScale;
    private final boolean additiveLines;
    private final boolean simplifiedStates;
    private final boolean allFileDiamonds;
    private final boolean fadingFolderFill;

    XedgeMosaicTileSet() {
        this("xedge", "Xedge", MONOCHROME, 1.0, false, false, false, false);
    }

    private XedgeMosaicTileSet(String id, String label, Palette palette) {
        this(id, label, palette, 1.0, false, false, false, false);
    }

    private XedgeMosaicTileSet(
            String id, String label, Palette palette,
            double lineScale, boolean additiveLines, boolean simplifiedStates,
            boolean allFileDiamonds, boolean fadingFolderFill) {
        this.id = id;
        this.label = label;
        this.palette = palette;
        this.lineScale = lineScale;
        this.additiveLines = additiveLines;
        this.simplifiedStates = simplifiedStates;
        this.allFileDiamonds = allFileDiamonds;
        this.fadingFolderFill = fadingFolderFill;
    }

    static XedgeMosaicTileSet colorVariant() {
        return new XedgeMosaicTileSet("xedge-color", "Xedge Color", COLOR);
    }

    static XedgeMosaicTileSet sharpVariant() {
        return new XedgeMosaicTileSet("xedge-sharp", "Xedge Sharp", SHARP);
    }

    static XedgeMosaicTileSet liteVariant() {
        return new XedgeMosaicTileSet("xedge-lite", "Xedge Lite", LITE);
    }

    static XedgeMosaicTileSet additiveVariant() {
        return new XedgeMosaicTileSet(
                "xedge-additive", "Xedge Additive", LITE,
                0.5, true, false, false, false);
    }

    static XedgeMosaicTileSet xsVariant() {
        return new XedgeMosaicTileSet(
                "xedge-xs", "Xedge XS", LITE,
                0.5, true, true, true, true);
    }

    static XedgeMosaicTileSet xsSolidVariant() {
        return new XedgeMosaicTileSet(
                "xedge-xs-solid", "Xedge XS Solid", LITE,
                0.5, true, true, true, false);
    }

    private static Palette sharpPalette(boolean vanillaDiamond) {
        return new Palette(
                HALF_NEUTRAL_FILL, SHARP_TEXT,
                LINE_GRAY, LINE_GRAY, LINE_GRAY, LINE_GRAY, LINE_GRAY,
                QUARTER_NEUTRAL_FILL, HALF_NEUTRAL_FILL,
                LINE_GRAY, SHARP_LINE, SHARP_LINE, SHARP_TEXT,
                SHARP_TEXT, BORDER_SHADOW,
                false, true, vanillaDiamond, true, false, true);
    }

    boolean vanillaDiamond() {
        return palette.vanillaDiamond();
    }

    boolean additiveLines() {
        return additiveLines;
    }

    boolean tabDetailAxes() {
        return !additiveLines;
    }

    boolean archiveDiamond() {
        return palette.backgroundCross() && !additiveLines;
    }

    boolean hiddenDiamond() {
        return palette.backgroundCross()
                && (palette.vanillaDiamond() || additiveLines);
    }

    boolean fileDiamond(boolean hidden, boolean exceptional) {
        if (!palette.backgroundCross()) return false;
        if (allFileDiamonds) return true;
        if (exceptional) return false;
        return hidden ? hiddenDiamond() : palette.vanillaDiamond();
    }

    boolean dottedHiddenLines() {
        return !additiveLines;
    }

    boolean emptyFolderCross() {
        return !simplifiedStates;
    }

    boolean junkFolderCross() {
        return !simplifiedStates;
    }

    boolean fileStateCross() {
        return !simplifiedStates;
    }

    boolean junkDiamond() {
        return !simplifiedStates;
    }

    boolean fadingFolderFill() {
        return fadingFolderFill;
    }

    @Override public String id() { return id; }
    @Override public String label() { return label; }

    @Override
    public void paint(MosaicTilePaintContext context) {
        if (context.identity() == MosaicTileIdentity.PARENT) {
            paintParent(context);
            return;
        }
        if (context.identity() == MosaicTileIdentity.FOLDER) {
            paintFolder(context);
            return;
        }
        if (context.identity() == MosaicTileIdentity.ARCHIVE_BROWSABLE) {
            paintArchiveFolder(context);
            return;
        }
        if (isNonMediaFile(context.identity())) {
            paintExtension(context);
            return;
        }
        context.paintCurrent();
        if (palette.frameAllTiles()) {
            GraphicsContext graphics = context.graphics();
            graphics.save();
            try {
                drawTileBorder(
                        graphics, context.x(), context.y(),
                        context.size(), effectiveLineWidth(context.size()));
            } finally {
                graphics.restore();
            }
        }
    }

    private void paintParent(MosaicTilePaintContext context) {
        GraphicsContext graphics = context.graphics();
        graphics.save();
        try {
            double x = context.x();
            double y = context.y();
            double size = context.size();
            double line = effectiveLineWidth(size);

            graphics.save();
            try {
                applyLineBlend(graphics);
                graphics.setStroke(palette.line());
                graphics.setLineWidth(line);
                graphics.setLineCap(StrokeLineCap.SQUARE);
                graphics.strokeLine(x, y + size * 0.50, x + size * 0.50, y);
                graphics.strokeLine(x, y + size * 0.25, x + size * 0.25, y);
                graphics.strokeLine(x + size * 0.50, y, x + size, y + size * 0.50);
                graphics.strokeLine(x + size * 0.75, y, x + size, y + size * 0.25);

                graphics.setFill(palette.line());
                fillVertical(graphics, x + size * 0.25, y, size, line);
                fillVertical(graphics, x + size * 0.75, y, size, line);
            } finally {
                graphics.restore();
            }

            double fontSize = parentGlyphFontSize(size);
            graphics.setFill(palette.parentGlyph());
            graphics.setFont(context.font(fontSize, false));
            graphics.setTextAlign(TextAlignment.CENTER);
            graphics.setTextBaseline(VPos.CENTER);
            graphics.fillText("\u2191", x + size / 2.0, y + size / 2.0);

            drawTileBorder(graphics, x, y, size, line);
            context.drawCaption();
        } finally {
            graphics.restore();
        }
    }

    private void paintFolder(MosaicTilePaintContext context) {
        GraphicsContext graphics = context.graphics();
        graphics.save();
        try {
            FolderVerdict verdict = context.folderVerdict();
            if (palette.backgroundCross()) {
                if (verdict == null || verdict == FolderVerdict.NORMAL
                        || verdict == FolderVerdict.NO_VISUAL) {
                    if (palette.vanillaDiamond()) {
                        drawBackgroundDiamond(
                                graphics, context.x(), context.y(),
                                context.size(), effectiveLineWidth(context.size()));
                    }
                } else if (verdict != FolderVerdict.UNREADABLE
                        && (verdict != FolderVerdict.EMPTY || emptyFolderCross())
                        && (verdict != FolderVerdict.JUNK_ONLY || junkFolderCross())) {
                    drawBackgroundCross(
                            graphics, context.x(), context.y(),
                            context.size(), effectiveLineWidth(context.size()));
                }
            }
            boolean collage = context.drawFolderCollage();
            if (!collage) {
                if (palette.accentGradients()
                        && (verdict == null || verdict == FolderVerdict.NORMAL
                        || verdict == FolderVerdict.NO_VISUAL)) {
                    fillTopLeftGradient(
                            graphics,
                            context.x(), context.y(),
                            context.x() + context.size() * LEFT,
                            context.y() + context.size() * TAB_TOP,
                            palette.folderAccent());
                }
                drawEdgeFolder(context, verdict != FolderVerdict.EMPTY);
                drawFolderState(context);
                if (verdict == FolderVerdict.JUNK_ONLY) {
                    // Keep the local folder silhouette legible above the four
                    // junk-state Xs, which intentionally remain behind it.
                    drawFolderContour(
                            graphics,
                            context.x(), context.y(), context.size(),
                            effectiveLineWidth(context.size()),
                            false);
                }
            }
            if (verdict == null || !verdict.isDeadEnd()) {
                context.drawReticule();
            }
            context.drawCaption();
        } finally {
            graphics.restore();
        }
    }

    private void paintArchiveFolder(MosaicTilePaintContext context) {
        GraphicsContext graphics = context.graphics();
        graphics.save();
        try {
            if (archiveDiamond()) {
                drawBackgroundDiamond(
                        graphics, context.x(), context.y(),
                        context.size(), effectiveLineWidth(context.size()));
            }
            if (palette.accentGradients()) {
                fillTopLeftGradient(
                        graphics,
                        context.x(), context.y(),
                        context.x() + context.size() * LEFT,
                        context.y() + context.size() * TAB_TOP,
                        palette.archiveAccent());
            }
            drawEdgeFolder(context, true);
            String format = extensionLabel(context.entry().extension());
            double x = context.x();
            double y = context.y();
            double size = context.size();
            double bodyWidth = size * (RIGHT - LEFT);
            double bodyHeight = size * (BOTTOM - BODY_TOP);
            double byHeight = bodyHeight * 0.42;
            double byWidth = bodyWidth * 0.82
                    / (Math.max(3, format.length()) * 0.62);

            graphics.setFill(palette.archiveLabel());
            graphics.setFont(context.font(
                    Math.min(byHeight, byWidth), false));
            graphics.setTextAlign(TextAlignment.CENTER);
            graphics.setTextBaseline(VPos.CENTER);
            graphics.fillText(
                    format,
                    x + size * (LEFT + RIGHT) / 2.0,
                    y + size * (BODY_TOP + BOTTOM) / 2.0);
            context.drawCaption();
        } finally {
            graphics.restore();
        }
    }

    private static boolean isNonMediaFile(MosaicTileIdentity identity) {
        return switch (identity) {
            case TEXT, DOCUMENT, DATA, BINARY, EXECUTABLE,
                    ARCHIVE_SEALED, UNKNOWN -> true;
            default -> false;
        };
    }

    private void paintExtension(MosaicTilePaintContext context) {
        GraphicsContext graphics = context.graphics();
        graphics.save();
        try {
            drawExtensionGrid(context, extensionLabel(context.entry().extension()));
            context.drawCaption();
        } finally {
            graphics.restore();
        }
    }

    static String extensionLabel(String extension) {
        String upper = extension == null ? "" : extension.toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) return "\u2014";
        return upper.length() <= 4
                ? upper
                : upper.substring(0, 3) + "\u2026";
    }

    static double parentGlyphFontSize(double tileSize) {
        return tileSize / 3.0;
    }

    private void drawExtensionGrid(
            MosaicTilePaintContext context, String extension) {
        GraphicsContext graphics = context.graphics();
        double x = context.x();
        double y = context.y();
        double size = context.size();
        double line = effectiveLineWidth(size);
        double fontSize = size * 0.55 / 3.0;
        Font font = context.font(fontSize, false);
        double cellHeight = fontSize * 1.25;
        double textWidth = context.textWidth(extension, font);
        double gridWidth = extensionGridWidth(textWidth, line);
        double left = x + (size - gridWidth) / 2.0;
        double top = y + (size - cellHeight) / 2.0;
        double[] guides = {left, left + gridWidth};
        boolean hidden = context.has(MosaicTileModifier.HIDDEN);
        boolean exceptional = context.has(MosaicTileModifier.JUNK)
                || context.has(MosaicTileModifier.ZERO_BYTE);
        boolean blueAccent = !exceptional;

        if (fileDiamond(hidden, exceptional)) {
            drawBackgroundDiamond(graphics, x, y, size, line);
        } else if (palette.backgroundCross() && exceptional && fileStateCross()) {
            drawBackgroundCross(graphics, x, y, size, line);
        }
        if (blueAccent && palette.accentGradients()) {
            fillTopLeftGradient(
                    graphics, x, y, left, top, palette.fileAccent());
        }

        graphics.save();
        try {
            applyLineBlend(graphics);
            if (hidden && dottedHiddenLines()) {
                beginDottedLines(graphics, palette.line(), line);
                for (double guide : guides) {
                    graphics.strokeLine(
                            guide, y,
                            guide, y + size);
                }
                graphics.strokeLine(x, top, x + size, top);
                graphics.strokeLine(x, top + cellHeight, x + size, top + cellHeight);
                endDottedLines(graphics);
            } else {
                graphics.setFill(palette.line());
                for (double guide : guides) {
                    fillVertical(graphics, guide, y, size, line);
                }
                fillHorizontal(graphics, x, top, size, line);
                fillHorizontal(graphics, x, top + cellHeight, size, line);
            }
        } finally {
            graphics.restore();
        }

        if (context.has(MosaicTileModifier.JUNK) && junkDiamond()) {
            drawJunkCrosses(graphics, x, y, size, line);
        }
        if (context.has(MosaicTileModifier.ZERO_BYTE)
                && !palette.backgroundCross()) {
            drawTileCross(graphics, x, y, size, line);
        }

        // Open the whole label interior after all construction/state lines.
        // This removes both character dividers and junk Xs from the enclosure.
        graphics.setFill(Color.BLACK);
        graphics.fillRect(
                left + line / 2.0,
                top + line / 2.0,
                gridWidth - line,
                cellHeight - line);

        drawExtensionFrame(
                graphics, left, top, gridWidth, cellHeight, line);

        graphics.setFill(palette.labelFill());
        graphics.setFont(font);
        graphics.setTextAlign(TextAlignment.CENTER);
        graphics.setTextBaseline(VPos.CENTER);
        graphics.fillText(extension, x + size / 2.0, y + size / 2.0);

        drawTileBorder(graphics, x, y, size, line);
    }

    static double extensionGridWidth(double textWidth, double lineWidth) {
        // Two line-widths of breathing room on each side of the label.
        return textWidth + lineWidth * 4.0;
    }

    private void drawEdgeFolder(
            MosaicTilePaintContext context, boolean filled) {
        GraphicsContext graphics = context.graphics();
        double x = context.x();
        double y = context.y();
        double size = context.size();
        double line = effectiveLineWidth(size);
        Paint folderFill = folderFill(context, x, y, size);
        boolean foregroundFill =
                context.folderVerdict() == FolderVerdict.JUNK_ONLY && filled;

        if (filled && !foregroundFill) {
            drawFolderFill(graphics, folderFill, x, y, size);
        }

        // Continue the silhouette axes to the tile boundary. Additive omits
        // the tab-detail top horizontal and middle vertical for a quieter tab.
        graphics.save();
        try {
            applyLineBlend(graphics);
            graphics.setFill(palette.line());
            fillVertical(graphics, x + size * LEFT, y, size, line);
            if (tabDetailAxes()) {
                fillVertical(graphics, x + size * TAB_RIGHT, y, size, line);
            }
            fillVertical(graphics, x + size * RIGHT, y, size, line);
            if (tabDetailAxes()) {
                fillHorizontal(graphics, x, y + size * TAB_TOP, size, line);
            }
            fillHorizontal(graphics, x, y + size * BODY_TOP, size, line);
            fillHorizontal(graphics, x, y + size * BOTTOM, size, line);
        } finally {
            graphics.restore();
        }

        if (foregroundFill) {
            // Junk containers deliberately foreground the solid glyph over the
            // continuum axes. State Xs and the local contour are painted later.
            drawFolderFill(graphics, folderFill, x, y, size);
        } else if (filled && !additiveLines) {
            // The tab-right axis is an edge above the body, but not a divider
            // through it: reopen that interior segment in the folder's fill color.
            graphics.setFill(folderFill);
            graphics.fillRect(
                    x + size * TAB_RIGHT - line / 2.0,
                    y + size * BODY_TOP + line / 2.0,
                    line,
                    size * (BOTTOM - BODY_TOP) - line);
        } else if (!filled && !additiveLines) {
            // Empty keeps the horizontal tab/body seam, but not the vertical
            // tab-right axis where it would divide the unfilled body.
            graphics.setFill(Color.BLACK);
            graphics.fillRect(
                    x + size * TAB_RIGHT - line / 2.0,
                    y + size * BODY_TOP + line / 2.0,
                    line,
                    size * (BOTTOM - BODY_TOP) - line);
        }

        // Junk redraws its contour once after the state marks so it stays in
        // the foreground without doubling brightness under additive blending.
        if (!foregroundFill) {
            drawFolderContour(graphics, x, y, size, line, true);
        }
        drawTileBorder(graphics, x, y, size, line);
    }

    private static void drawFolderFill(
            GraphicsContext graphics, Paint fill,
            double x, double y, double size) {
        graphics.setFill(fill);
        // Preserve Current's filled square folder: a tab overlapping its body.
        graphics.fillRect(
                x + size * LEFT,
                y + size * TAB_TOP,
                size * (TAB_RIGHT - LEFT),
                size * (BODY_TOP - TAB_TOP) + 2.0);
        graphics.fillRect(
                x + size * LEFT,
                y + size * BODY_TOP,
                size * (RIGHT - LEFT),
                size * (BOTTOM - BODY_TOP));
    }

    private void drawFolderContour(
            GraphicsContext graphics, double x, double y, double size, double line,
            boolean bodyTopAxisVisible) {
        graphics.save();
        try {
            applyLineBlend(graphics);
            graphics.setStroke(palette.folderContourLine());
            graphics.setLineWidth(line);
            graphics.setLineCap(StrokeLineCap.SQUARE);
            graphics.setLineDashes();
            // The tab keeps its local outline even when Additive suppresses
            // the matching full-tile construction axes.
            graphics.strokeLine(
                    x + size * LEFT, y + size * TAB_TOP,
                    x + size * TAB_RIGHT, y + size * TAB_TOP);
            graphics.strokeLine(
                    x + size * TAB_RIGHT, y + size * TAB_TOP,
                    x + size * TAB_RIGHT, y + size * BODY_TOP);
            // Under Additive, avoid doubling the body-top continuum only while
            // that construction axis is still visible. A junk-only folder's
            // foreground fill covers the axis, so its later contour must restore
            // the short LEFT..TAB_RIGHT segment beneath the tab.
            double bodyContourLeft = bodyContourLeft(bodyTopAxisVisible);
            graphics.strokeLine(
                    x + size * bodyContourLeft, y + size * BODY_TOP,
                    x + size * RIGHT, y + size * BODY_TOP);
            graphics.strokeLine(
                    x + size * RIGHT, y + size * BODY_TOP,
                    x + size * RIGHT, y + size * BOTTOM);
            graphics.strokeLine(
                    x + size * RIGHT, y + size * BOTTOM,
                    x + size * LEFT, y + size * BOTTOM);
            graphics.strokeLine(
                    x + size * LEFT, y + size * BOTTOM,
                    x + size * LEFT, y + size * TAB_TOP);
        } finally {
            graphics.restore();
        }
    }

    double bodyContourLeft(boolean bodyTopAxisVisible) {
        return additiveLines && bodyTopAxisVisible ? TAB_RIGHT : LEFT;
    }

    private void drawExtensionFrame(
            GraphicsContext graphics,
            double left, double top, double width, double height,
            double line) {
        graphics.save();
        try {
            applyLineBlend(graphics);
            graphics.setStroke(palette.extensionFrameLine());
            graphics.setLineWidth(line);
            graphics.setLineCap(StrokeLineCap.SQUARE);
            graphics.setLineDashes();
            graphics.strokeLine(left, top, left + width, top);
            graphics.strokeLine(left + width, top, left + width, top + height);
            graphics.strokeLine(left + width, top + height, left, top + height);
            graphics.strokeLine(left, top + height, left, top);
        } finally {
            graphics.restore();
        }
    }

    private Paint folderFill(
            MosaicTilePaintContext context, double x, double y, double size) {
        FolderVerdict verdict = context.folderVerdict();
        Color currentFill = palette.folderFill();
        if (verdict == FolderVerdict.JUNK_ONLY
                || verdict == FolderVerdict.UNREADABLE) {
            currentFill = palette.exceptionFolderFill();
        }
        if (fadingFolderFill) {
            return fadingFolderPaint(currentFill, x, y, size);
        }
        if (verdict == FolderVerdict.JUNK_ONLY
                || verdict == FolderVerdict.UNREADABLE) {
            return currentFill;
        }
        boolean vanillaFolder = context.identity() == MosaicTileIdentity.FOLDER
                && (verdict == null
                || verdict == FolderVerdict.NORMAL
                || verdict == FolderVerdict.NO_VISUAL);
        boolean archiveFolder =
                context.identity() == MosaicTileIdentity.ARCHIVE_BROWSABLE;
        if ((vanillaFolder || archiveFolder) && palette.accentGradients()) {
            return new LinearGradient(
                    x + size * LEFT, y + size * TAB_TOP,
                    x + size * RIGHT, y + size * BOTTOM,
                    false, CycleMethod.NO_CYCLE,
                    new Stop(0.0, palette.vanillaFolderGradientStart()),
                    new Stop(1.0, palette.folderFill()));
        }
        return currentFill;
    }

    static LinearGradient fadingFolderPaint(
            Color currentFill, double x, double y, double size) {
        return new LinearGradient(
                x + size * LEFT, y + size * TAB_TOP,
                x + size * RIGHT, y + size * BOTTOM,
                false, CycleMethod.NO_CYCLE,
                new Stop(0.0, currentFill),
                new Stop(1.0, withOpacity(currentFill, 0.0)));
    }

    private void drawTileBorder(
            GraphicsContext graphics, double x, double y, double size, double line) {
        graphics.save();
        try {
            applyLineBlend(graphics);
            if (palette.topLeftBorder()) {
                graphics.setFill(palette.line());
                fillVertical(graphics, x + line / 2.0, y, size, line);
                fillHorizontal(graphics, x, y + line / 2.0, size, line);
            }

            if (palette.bottomRightBorder()) {
                graphics.setFill(BORDER_SHADOW);
                fillVertical(graphics, x + size - line / 2.0, y, size, line);
                fillHorizontal(graphics, x, y + size - line / 2.0, size, line);
            }
        } finally {
            graphics.restore();
        }
    }

    private void drawFolderState(MosaicTilePaintContext context) {
        FolderVerdict verdict = context.folderVerdict();
        if (verdict == null || verdict == FolderVerdict.NORMAL
                || verdict == FolderVerdict.NO_VISUAL) {
            return;
        }

        GraphicsContext graphics = context.graphics();
        double x = context.x();
        double y = context.y();
        double size = context.size();
        double line = effectiveLineWidth(size);
        graphics.setFill(palette.line());

        if (verdict == FolderVerdict.EMPTY) {
            if (!palette.backgroundCross()) {
                drawTileCross(graphics, x, y, size, line);
            }
            drawTileBorder(graphics, x, y, size, line);
        } else if (verdict == FolderVerdict.JUNK_ONLY) {
            if (junkDiamond()) {
                drawJunkCrosses(graphics, x, y, size, line);
            }
            drawTileBorder(graphics, x, y, size, line);
        } else if (verdict == FolderVerdict.UNREADABLE) {
            graphics.save();
            try {
                // Stripe endpoints intentionally extend beyond the tile in X;
                // clip them so one unreadable folder cannot paint its neighbors.
                graphics.beginPath();
                graphics.rect(x, y, size, size);
                graphics.closePath();
                graphics.clip();

                double stripe = line * 6.0;
                double diagonalScale = Math.sqrt(2.0);
                double period = stripe * 2.0 * diagonalScale;
                graphics.setStroke(withOpacity(
                        palette.inaccessibleAccent(), 0.40));
                graphics.setLineWidth(stripe);
                for (double offset = -size + period / 2.0;
                        offset <= size * 2.0; offset += period) {
                    graphics.strokeLine(
                            x + offset, y + size,
                            x + offset + size, y);
                }
                graphics.setStroke(Color.rgb(0, 0, 0, 0.40));
                graphics.setLineWidth(stripe);
                for (double offset = -size; offset <= size * 2.0; offset += period) {
                    graphics.strokeLine(
                            x + offset, y + size,
                            x + offset + size, y);
                }
                drawTileBorder(graphics, x, y, size, line);
            } finally {
                graphics.restore();
            }
        }
    }

    private void drawJunkCrosses(
            GraphicsContext graphics, double x, double y, double size, double line) {
        graphics.save();
        try {
            applyLineBlend(graphics);
            graphics.setStroke(palette.stateLine());
            graphics.setLineWidth(line);
            double half = size / 2.0;
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 2; column++) {
                    double left = x + column * half;
                    double top = y + row * half;
                    graphics.strokeLine(left, top, left + half, top + half);
                    graphics.strokeLine(left + half, top, left, top + half);
                }
            }
        } finally {
            graphics.restore();
        }
    }

    private void drawTileCross(
            GraphicsContext graphics, double x, double y, double size, double line) {
        drawRectCross(graphics, x, y, x + size, y + size, line);
    }

    private void drawBackgroundCross(
            GraphicsContext graphics, double x, double y, double size, double line) {
        graphics.save();
        try {
            applyLineBlend(graphics);
            graphics.setStroke(palette.backgroundCrossLine());
            graphics.setLineWidth(line);
            graphics.setLineCap(StrokeLineCap.SQUARE);
            graphics.setLineDashes();
            graphics.strokeLine(x, y, x + size, y + size);
            graphics.strokeLine(x + size, y, x, y + size);
        } finally {
            graphics.restore();
        }
    }

    private void drawBackgroundDiamond(
            GraphicsContext graphics, double x, double y, double size, double line) {
        graphics.save();
        try {
            applyLineBlend(graphics);
            graphics.setStroke(palette.backgroundCrossLine());
            graphics.setLineWidth(line);
            graphics.setLineCap(StrokeLineCap.SQUARE);
            graphics.setLineDashes();
            double half = size / 2.0;
            graphics.strokeLine(x + half, y, x + size, y + half);
            graphics.strokeLine(x + size, y + half, x + half, y + size);
            graphics.strokeLine(x + half, y + size, x, y + half);
            graphics.strokeLine(x, y + half, x + half, y);
        } finally {
            graphics.restore();
        }
    }

    private void drawRectCross(
            GraphicsContext graphics,
            double left, double top, double right, double bottom, double line) {
        graphics.save();
        try {
            applyLineBlend(graphics);
            graphics.setStroke(palette.stateLine());
            graphics.setLineWidth(line);
            graphics.setLineCap(StrokeLineCap.SQUARE);
            graphics.setLineDashes();
            graphics.strokeLine(left, top, right, bottom);
            graphics.strokeLine(right, top, left, bottom);
        } finally {
            graphics.restore();
        }
    }

    private void fillTopLeftGradient(
            GraphicsContext graphics,
            double left, double top, double right, double bottom,
            Color accent) {
        graphics.setFill(new LinearGradient(
                left, top, right, bottom,
                false, CycleMethod.NO_CYCLE,
                new Stop(0.0, accent),
                new Stop(1.0, Color.BLACK)));
        graphics.fillRect(left, top, right - left, bottom - top);
    }

    private void beginDottedLines(
            GraphicsContext graphics, Color color, double line) {
        graphics.setStroke(color);
        graphics.setLineWidth(line);
        graphics.setLineCap(StrokeLineCap.ROUND);
        graphics.setLineDashes(0.0, dottedGap(line));
    }

    private static void endDottedLines(GraphicsContext graphics) {
        graphics.setLineDashes();
        graphics.setLineCap(StrokeLineCap.SQUARE);
    }

    private static Color withOpacity(Color color, double opacity) {
        return Color.color(
                color.getRed(), color.getGreen(), color.getBlue(), opacity);
    }

    static double lineWidth(double tileSize) {
        return Math.max(1.0, tileSize * LINE_SCALE);
    }

    double effectiveLineWidth(double tileSize) {
        return lineWidth(tileSize) * lineScale;
    }

    double dottedGap(double line) {
        return line * (additiveLines ? 1.5 : 3.0);
    }

    private void applyLineBlend(GraphicsContext graphics) {
        if (additiveLines) graphics.setGlobalBlendMode(BlendMode.ADD);
    }

    private static void fillVertical(
            GraphicsContext graphics, double centerX, double y, double size, double width) {
        graphics.fillRect(centerX - width / 2.0, y, width, size);
    }

    private static void fillHorizontal(
            GraphicsContext graphics, double x, double centerY, double size, double width) {
        graphics.fillRect(x, centerY - width / 2.0, size, width);
    }

    private record Palette(
            Color folderFill,
            Color labelFill,
            Color folderAccent,
            Color archiveAccent,
            Color fileAccent,
            Color stateLine,
            Color inaccessibleAccent,
            Color exceptionFolderFill,
            Color vanillaFolderGradientStart,
            Color line,
            Color folderContourLine,
            Color extensionFrameLine,
            Color parentGlyph,
            Color archiveLabel,
            Color backgroundCrossLine,
            boolean accentGradients,
            boolean backgroundCross,
            boolean vanillaDiamond,
            boolean frameAllTiles,
            boolean topLeftBorder,
            boolean bottomRightBorder) {}
}
