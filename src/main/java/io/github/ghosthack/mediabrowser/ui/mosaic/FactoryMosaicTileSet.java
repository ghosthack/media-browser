package io.github.ghosthack.mediabrowser.ui.mosaic;

import io.github.ghosthack.mediabrowser.media.FolderVerdict;

import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.TextAlignment;

/**
 * Factory Proofs: bold, editioned screen-print tiles with large format labels.
 *
 * <p>The flat two-ink fields hold their own beside full-bleed photography while
 * the repeated registration marks, halftone corners, and deliberately offset
 * underprint give the set one visual authorship. The silhouettes remain literal
 * enough to identify before the extension is read.</p>
 */
final class FactoryMosaicTileSet implements MosaicTileSet {

    private record Palette(Color field, Color ink, Color flash) {}

    private static final Palette FOLDER =
            palette("#dfb34b", "#33230f", "#f3d77f");
    private static final Palette IMAGE =
            palette("#35a9bc", "#102e35", "#f1cf59");
    private static final Palette VIDEO =
            palette("#6e61ad", "#201a3c", "#ee8a65");
    private static final Palette AUDIO =
            palette("#ce5682", "#3b1528", "#f1c957");
    private static final Palette TEXT =
            palette("#3a9d90", "#103631", "#f0d79c");
    private static final Palette DOCUMENT =
            palette("#7a88c7", "#202747", "#eee5d3");
    private static final Palette DATA =
            palette("#6aaa60", "#1b3a1c", "#f2cc59");
    private static final Palette BINARY =
            palette("#8a70b1", "#2b1e40", "#e5a260");
    private static final Palette EXECUTABLE =
            palette("#df6247", "#42150f", "#f1ce55");
    private static final Palette ARCHIVE =
            palette("#cf803d", "#42230f", "#ead3a7");
    private static final Palette UNKNOWN =
            palette("#797a76", "#242523", "#e7decd");

    private static final Color PAPER = Color.web("#f2ead8");
    private static final Color SOFT_PAPER = Color.rgb(242, 234, 216, 0.82);
    private static final Color VEIL = Color.rgb(15, 14, 13, 0.42);

    @Override public String id() { return "factory"; }
    @Override public String label() { return "Factory Proofs"; }

    @Override
    public void paint(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        g.save();
        try {
            if (isMedia(c.identity()) && c.drawMediaThumbnail()) {
                drawMediaProofMark(c);
                drawModifiers(c);
                c.drawCaption();
                return;
            }

            Palette p = palette(c.identity());
            drawField(c, p);
            if (c.identity() == MosaicTileIdentity.FOLDER) {
                boolean collage = c.drawFolderCollage();
                drawFolder(c, p, collage);
                c.drawReticule();
            } else {
                drawIdentity(c, p);
            }
            drawModifiers(c);
            c.drawCaption();
        } finally {
            g.restore();
        }
    }

    private static Palette palette(String field, String ink, String flash) {
        return new Palette(Color.web(field), Color.web(ink), Color.web(flash));
    }

    private static Palette palette(MosaicTileIdentity identity) {
        return switch (identity) {
            case PARENT, FOLDER -> FOLDER;
            case MEDIA_IMAGE -> IMAGE;
            case MEDIA_VIDEO -> VIDEO;
            case MEDIA_AUDIO -> AUDIO;
            case TEXT -> TEXT;
            case DOCUMENT -> DOCUMENT;
            case DATA -> DATA;
            case BINARY -> BINARY;
            case EXECUTABLE -> EXECUTABLE;
            case ARCHIVE_BROWSABLE, ARCHIVE_SEALED -> ARCHIVE;
            case UNKNOWN -> UNKNOWN;
        };
    }

    private static boolean isMedia(MosaicTileIdentity identity) {
        return identity == MosaicTileIdentity.MEDIA_IMAGE
                || identity == MosaicTileIdentity.MEDIA_VIDEO
                || identity == MosaicTileIdentity.MEDIA_AUDIO;
    }

    private static void drawField(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(p.field());
        g.fillRect(x, y, s, s);

        // The narrow flash stripe is the second pass of ink. Its slight
        // asymmetry keeps a wall of equal-size tiles from reading as widgets.
        g.setFill(p.flash());
        g.fillRect(x, y, s * 0.075, s);
        g.fillRect(x, y, s, Math.max(2, s * 0.026));

        // A coarse halftone registration corner survives small tile sizes.
        g.setFill(Color.color(
                p.ink().getRed(), p.ink().getGreen(), p.ink().getBlue(), 0.18));
        double dot = Math.max(1.5, s * 0.018);
        double step = dot * 2.15;
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5 - row; col++) {
                g.fillOval(x + s - s * 0.055 - (col + 1) * step,
                        y + s * 0.055 + row * step, dot, dot);
            }
        }
    }

    private static void drawIdentity(MosaicTilePaintContext c, Palette p) {
        switch (c.identity()) {
            case PARENT -> drawParent(c, p);
            case MEDIA_IMAGE -> drawImage(c, p);
            case MEDIA_VIDEO -> drawVideo(c, p);
            case MEDIA_AUDIO -> drawAudio(c, p);
            case TEXT -> drawText(c, p);
            case DOCUMENT -> drawDocument(c, p);
            case DATA -> drawData(c, p);
            case BINARY -> drawBinary(c, p);
            case EXECUTABLE -> drawExecutable(c, p);
            case ARCHIVE_BROWSABLE -> drawArchive(c, p, true);
            case ARCHIVE_SEALED -> drawArchive(c, p, false);
            case UNKNOWN -> drawUnknown(c, p);
            case FOLDER -> { /* handled separately */ }
        }
    }

    private static void drawParent(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(p.flash());
        upArrow(g, x + s * 0.035, y + s * 0.025, s);
        g.setFill(p.ink());
        upArrow(g, x, y, s);
        drawEdition(c, p, "UP", "PARENT");
    }

    private static void upArrow(GraphicsContext g, double x, double y, double s) {
        g.beginPath();
        g.moveTo(x + s * 0.50, y + s * 0.12);
        g.lineTo(x + s * 0.84, y + s * 0.45);
        g.lineTo(x + s * 0.65, y + s * 0.45);
        g.lineTo(x + s * 0.65, y + s * 0.67);
        g.lineTo(x + s * 0.35, y + s * 0.67);
        g.lineTo(x + s * 0.35, y + s * 0.45);
        g.lineTo(x + s * 0.16, y + s * 0.45);
        g.closePath();
        g.fill();
    }

    private static void drawFolder(MosaicTilePaintContext c, Palette p, boolean collage) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        if (collage) {
            g.setFill(Color.rgb(20, 18, 15, 0.76));
            g.fillRect(x, y, s, s * 0.115);
            g.fillRect(x + s * 0.075, y, s * 0.31, s * 0.17);
            g.setFill(p.flash());
            g.fillRect(x + s * 0.075, y + s * 0.095, s * 0.31, s * 0.025);
            return;
        }

        // Offset underprint: close enough to read as one folder, far enough to
        // give the hard-edged screen-print its characteristic double image.
        g.setFill(p.flash());
        folderSilhouette(g, x + s * 0.035, y + s * 0.025, s);
        g.setFill(p.ink());
        folderSilhouette(g, x, y, s);

        g.setFill(p.field());
        g.fillRect(x + s * 0.19, y + s * 0.38, s * 0.64, s * 0.28);
        drawFolderVerdict(c, p);
        drawEdition(c, p, folderWord(c.folderVerdict()), "DIR");
    }

    private static void folderSilhouette(GraphicsContext g, double x, double y, double s) {
        g.fillRect(x + s * 0.13, y + s * 0.25, s * 0.74, s * 0.48);
        g.fillRect(x + s * 0.13, y + s * 0.17, s * 0.31, s * 0.15);
    }

    private static void drawFolderVerdict(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        FolderVerdict verdict = c.folderVerdict();
        if (verdict == null || verdict == FolderVerdict.NORMAL
                || verdict == FolderVerdict.NO_VISUAL) {
            g.setFill(p.ink());
            for (int i = 0; i < 3; i++) {
                g.fillRect(x + s * 0.25, y + s * (0.43 + i * 0.075),
                        s * (0.46 - i * 0.07), Math.max(2, s * 0.022));
            }
        } else if (verdict == FolderVerdict.EMPTY) {
            g.setStroke(p.ink());
            g.setLineWidth(Math.max(2, s * 0.025));
            g.strokeRect(x + s * 0.40, y + s * 0.43, s * 0.20, s * 0.15);
        } else if (verdict == FolderVerdict.JUNK_ONLY) {
            g.setFill(p.ink());
            double d = Math.max(2.5, s * 0.024);
            double[][] dust = {{.29, .49}, {.42, .56}, {.57, .45}, {.70, .57}};
            for (double[] point : dust) {
                g.fillOval(x + s * point[0], y + s * point[1], d, d);
            }
        } else {
            g.setStroke(p.ink());
            g.setLineWidth(Math.max(2, s * 0.025));
            g.strokeLine(x + s * 0.29, y + s * 0.44,
                    x + s * 0.71, y + s * 0.60);
            g.strokeLine(x + s * 0.29, y + s * 0.60,
                    x + s * 0.71, y + s * 0.44);
        }
    }

    private static String folderWord(FolderVerdict verdict) {
        if (verdict == null || verdict == FolderVerdict.NORMAL) return "FOLDER";
        return switch (verdict) {
            case NO_VISUAL -> "FILES";
            case JUNK_ONLY -> "DUST";
            case EMPTY -> "EMPTY";
            case UNREADABLE -> "NO READ";
            case NORMAL -> "FOLDER";
        };
    }

    private static void drawImage(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        offsetPanel(g, x + s * 0.16, y + s * 0.16, s * 0.68, s * 0.49, s, p);
        g.setFill(p.field());
        g.beginPath();
        g.moveTo(x + s * 0.20, y + s * 0.60);
        g.lineTo(x + s * 0.41, y + s * 0.34);
        g.lineTo(x + s * 0.55, y + s * 0.50);
        g.lineTo(x + s * 0.68, y + s * 0.39);
        g.lineTo(x + s * 0.80, y + s * 0.60);
        g.closePath();
        g.fill();
        g.fillOval(x + s * 0.64, y + s * 0.24, s * 0.09, s * 0.09);
        drawEdition(c, p, stamp(c), "IMAGE");
    }

    private static void drawVideo(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        offsetPanel(g, x + s * 0.13, y + s * 0.18, s * 0.74, s * 0.44, s, p);
        g.setFill(p.field());
        for (int i = 0; i < 4; i++) {
            g.fillRect(x + s * 0.155, y + s * (0.215 + i * 0.095),
                    s * 0.055, s * 0.045);
            g.fillRect(x + s * 0.79, y + s * (0.215 + i * 0.095),
                    s * 0.055, s * 0.045);
        }
        g.beginPath();
        g.moveTo(x + s * 0.43, y + s * 0.28);
        g.lineTo(x + s * 0.68, y + s * 0.40);
        g.lineTo(x + s * 0.43, y + s * 0.52);
        g.closePath();
        g.fill();
        drawEdition(c, p, stamp(c), "VIDEO");
    }

    private static void drawAudio(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(p.flash());
        g.fillOval(x + s * 0.215, y + s * 0.145, s * 0.56, s * 0.56);
        g.setFill(p.ink());
        g.fillOval(x + s * 0.18, y + s * 0.12, s * 0.56, s * 0.56);
        g.setStroke(p.field());
        g.setLineWidth(Math.max(1.5, s * 0.014));
        for (int i = 0; i < 3; i++) {
            double inset = s * (0.08 + i * 0.075);
            g.strokeOval(x + s * 0.18 + inset, y + s * 0.12 + inset,
                    s * 0.56 - inset * 2, s * 0.56 - inset * 2);
        }
        g.setFill(p.flash());
        g.fillOval(x + s * 0.40, y + s * 0.34, s * 0.12, s * 0.12);
        drawEdition(c, p, stamp(c), "AUDIO");
    }

    private static void drawText(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        offsetPanel(g, x + s * 0.18, y + s * 0.13, s * 0.64, s * 0.54, s, p);
        g.setFill(p.field());
        for (int i = 0; i < 5; i++) {
            g.fillRect(x + s * 0.25, y + s * (0.22 + i * 0.075),
                    s * (i == 1 ? 0.35 : i == 4 ? 0.25 : 0.48),
                    Math.max(2, s * 0.021));
        }
        g.setFill(p.flash());
        g.fillRect(x + s * 0.21, y + s * 0.20, s * 0.025, s * 0.36);
        drawEdition(c, p, stamp(c), "TEXT");
    }

    private static void drawDocument(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(p.flash());
        documentSilhouette(g, x + s * 0.035, y + s * 0.025, s);
        g.setFill(p.ink());
        documentSilhouette(g, x, y, s);
        g.setFill(p.field());
        for (int i = 0; i < 5; i++) {
            g.fillRect(x + s * 0.28, y + s * (0.28 + i * 0.068),
                    s * (i == 4 ? 0.25 : 0.40), Math.max(2, s * 0.02));
        }
        drawEdition(c, p, stamp(c), "DOCUMENT");
    }

    private static void documentSilhouette(GraphicsContext g, double x, double y, double s) {
        g.beginPath();
        g.moveTo(x + s * 0.22, y + s * 0.12);
        g.lineTo(x + s * 0.62, y + s * 0.12);
        g.lineTo(x + s * 0.78, y + s * 0.28);
        g.lineTo(x + s * 0.78, y + s * 0.68);
        g.lineTo(x + s * 0.22, y + s * 0.68);
        g.closePath();
        g.fill();
        g.setFill(Color.color(1, 1, 1, 0.24));
        g.beginPath();
        g.moveTo(x + s * 0.62, y + s * 0.12);
        g.lineTo(x + s * 0.62, y + s * 0.28);
        g.lineTo(x + s * 0.78, y + s * 0.28);
        g.closePath();
        g.fill();
    }

    private static void drawData(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        offsetPanel(g, x + s * 0.17, y + s * 0.15, s * 0.66, s * 0.50, s, p);
        g.setStroke(p.field());
        g.setLineWidth(Math.max(2, s * 0.018));
        for (int i = 1; i < 4; i++) {
            double lineX = x + s * (0.17 + i * 0.165);
            g.strokeLine(lineX, y + s * 0.15, lineX, y + s * 0.65);
        }
        for (int i = 1; i < 3; i++) {
            double lineY = y + s * (0.15 + i * 0.167);
            g.strokeLine(x + s * 0.17, lineY, x + s * 0.83, lineY);
        }
        g.setFill(p.flash());
        g.fillRect(x + s * 0.19, y + s * 0.18, s * 0.13, s * 0.11);
        drawEdition(c, p, stamp(c), "DATA");
    }

    private static void drawBinary(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        offsetPanel(g, x + s * 0.14, y + s * 0.15, s * 0.72, s * 0.50, s, p);
        g.setFill(p.field());
        double d = s * 0.055;
        int[][] holes = {{0, 0}, {2, 0}, {5, 0}, {1, 1}, {3, 1}, {4, 1},
                {0, 2}, {1, 2}, {4, 2}, {5, 2}};
        for (int[] hole : holes) {
            g.fillOval(x + s * 0.22 + hole[0] * s * 0.095,
                    y + s * 0.24 + hole[1] * s * 0.11, d, d);
        }
        drawEdition(c, p, stamp(c), "BINARY");
    }

    private static void drawExecutable(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(p.flash());
        g.fillOval(x + s * 0.235, y + s * 0.105, s * 0.56, s * 0.56);
        g.setFill(p.ink());
        g.fillOval(x + s * 0.20, y + s * 0.08, s * 0.56, s * 0.56);
        g.setFill(p.field());
        g.fillOval(x + s * 0.39, y + s * 0.27, s * 0.18, s * 0.18);
        g.setFill(p.flash());
        g.beginPath();
        g.moveTo(x + s * 0.57, y + s * 0.17);
        g.lineTo(x + s * 0.57, y + s * 0.40);
        g.lineTo(x + s * 0.67, y + s * 0.40);
        g.lineTo(x + s * 0.50, y + s * 0.57);
        g.lineTo(x + s * 0.33, y + s * 0.40);
        g.lineTo(x + s * 0.43, y + s * 0.40);
        g.lineTo(x + s * 0.43, y + s * 0.17);
        g.closePath();
        g.fill();
        drawEdition(c, p, stamp(c), "INSTALL");
    }

    private static void drawArchive(
            MosaicTilePaintContext c, Palette p, boolean browsable) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(p.flash());
        g.fillRect(x + s * 0.185, y + s * 0.155, s * 0.66, s * 0.50);
        g.setFill(p.ink());
        g.fillRect(x + s * 0.15, y + s * 0.13, s * 0.66, s * 0.50);
        g.setFill(p.field());
        g.fillRect(x + s * 0.20, y + s * 0.20, s * 0.56, s * 0.36);
        g.setFill(p.ink());
        for (int i = 0; i < 6; i++) {
            g.fillRect(x + s * (0.445 + (i % 2) * 0.055),
                    y + s * (0.20 + i * 0.058), s * 0.055, s * 0.04);
        }
        if (browsable) {
            g.setFill(p.flash());
            g.fillRect(x + s * 0.15, y + s * 0.13, s * 0.30, s * 0.07);
        } else {
            g.setFill(p.ink());
            g.fillRect(x + s * 0.20, y + s * 0.51, s * 0.56, s * 0.05);
        }
        drawEdition(c, p, stamp(c), browsable ? "OPEN ARCHIVE" : "ARCHIVE");
    }

    private static void drawUnknown(MosaicTilePaintContext c, Palette p) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(p.flash());
        g.fillOval(x + s * 0.25, y + s * 0.12, s * 0.52, s * 0.52);
        g.setFill(p.ink());
        g.fillOval(x + s * 0.215, y + s * 0.095, s * 0.52, s * 0.52);
        g.setFill(p.field());
        g.setFont(c.font(Math.max(15, s * 0.40), true));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("?", x + s * 0.475, y + s * 0.35);
        drawEdition(c, p, stamp(c), "UNKNOWN");
    }

    private static void offsetPanel(
            GraphicsContext g, double x, double y, double w, double h,
            double size, Palette p) {
        g.setFill(p.flash());
        g.fillRect(x + size * 0.035, y + size * 0.025, w, h);
        g.setFill(p.ink());
        g.fillRect(x, y, w, h);
    }

    private static void drawEdition(
            MosaicTilePaintContext c, Palette p, String format, String family) {
        double s = c.size();
        // Settings permits 64 px tiles; the edition strip is intentionally
        // still present there so the set never loses its format-first read.
        if (s < 60) return;
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y();
        double top = y + s * 0.705;
        double height = Math.min(s * 0.165, 34);
        g.setFill(p.ink());
        g.fillRect(x + s * 0.075, top, s * 0.85, height);
        g.setFill(PAPER);
        g.setFont(c.font(Math.max(9, Math.min(22, s * 0.125)), true));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(shortStamp(format), x + s * 0.105, top + height * 0.50);

        // Secondary identity belongs inside the printed label, not floating
        // over the object where it can disappear into the silhouette. Suppress
        // it when the primary label is already a full word.
        if (s >= 122 && shortStamp(format).length() <= 4) {
            g.setFill(p.flash());
            g.setFont(c.font(Math.max(8, Math.min(12, s * 0.058)), true));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(family, x + s * 0.89, top + height * 0.52);
        }
    }

    private static String stamp(MosaicTilePaintContext c) {
        return shortStamp(c.stamp());
    }

    private static String shortStamp(String value) {
        if (value == null || value.isBlank()) return "?";
        String clean = value.trim().toUpperCase();
        return clean.length() <= 7 ? clean : clean.substring(0, 6) + "…";
    }

    private static void drawMediaProofMark(MosaicTilePaintContext c) {
        // A still image already says "image" more eloquently than a badge can.
        // Reserve the proof seal for media whose motion/sound is not visible in
        // a single frame; otherwise the seal reads like selection or warning UI.
        if (c.identity() == MosaicTileIdentity.MEDIA_IMAGE) return;
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        Palette p = palette(c.identity());
        double d = Math.max(22, Math.min(42, s * 0.20));
        double bx = x + s - d - s * 0.055;
        double by = y + s * 0.055;
        g.setFill(Color.rgb(15, 14, 13, 0.72));
        g.fillOval(bx, by, d, d);
        g.setStroke(p.flash());
        g.setLineWidth(Math.max(2, d * 0.08));
        g.strokeOval(bx + d * 0.12, by + d * 0.12, d * 0.76, d * 0.76);
        g.setFill(PAPER);
        g.setFont(c.font(Math.max(8, d * 0.31), true));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        String mark = switch (c.identity()) {
            case MEDIA_VIDEO -> "\u25b6";
            case MEDIA_AUDIO -> "\u266a";
            case MEDIA_IMAGE -> "";
            default -> "";
        };
        g.fillText(mark, bx + d * 0.5, by + d * 0.50);
    }

    private static void drawModifiers(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();

        if (c.has(MosaicTileModifier.HIDDEN)) {
            g.setFill(VEIL);
            g.fillRect(x, y, s, s);
            stateBand(c, "HIDDEN", SOFT_PAPER);
        }
        if (c.has(MosaicTileModifier.SYSTEM)) {
            g.setFill(SOFT_PAPER);
            double d = Math.max(2, s * 0.018);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    g.fillOval(x + s * (0.82 + col * 0.045),
                            y + s * (0.12 + row * 0.045), d, d);
                }
            }
        }
        if (c.has(MosaicTileModifier.JUNK)) {
            g.setFill(Color.rgb(40, 30, 20, 0.58));
            double d = Math.max(2, s * 0.02);
            double[][] dust = {{.12, .83}, {.20, .88}, {.29, .82}, {.37, .88}};
            for (double[] point : dust) {
                g.fillOval(x + s * point[0], y + s * point[1], d, d);
            }
        }
        if (c.has(MosaicTileModifier.EXECUTABLE)
                && c.identity() != MosaicTileIdentity.EXECUTABLE
                && c.identity() != MosaicTileIdentity.FOLDER
                && c.identity() != MosaicTileIdentity.PARENT
                && c.identity() != MosaicTileIdentity.ARCHIVE_BROWSABLE) {
            g.setFill(EXECUTABLE.field());
            g.fillRect(x + s * 0.93, y, s * 0.07, s);
            if (s >= 92) {
                g.setFill(EXECUTABLE.ink());
                g.setFont(c.font(Math.max(8, s * 0.06), true));
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.CENTER);
                g.fillText("+X", x + s * 0.965, y + s * 0.50);
            }
        }
        if (c.has(MosaicTileModifier.ZERO_BYTE)) {
            g.setFill(Color.rgb(20, 18, 15, 0.75));
            g.fillOval(x + s * 0.40, y + s * 0.38, s * 0.20, s * 0.20);
            g.setFill(PAPER);
            g.setFont(c.font(Math.max(9, s * 0.10), true));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText("0", x + s * 0.50, y + s * 0.48);
        }
        if (c.has(MosaicTileModifier.UNREADABLE)) {
            g.setStroke(Color.rgb(242, 234, 216, 0.45));
            g.setLineWidth(Math.max(1.5, s * 0.012));
            double step = Math.max(10, s * 0.11);
            for (double at = -s; at < s * 2; at += step) {
                g.strokeLine(x + at, y, x + at - s, y + s);
            }
            stateBand(c, "NO READ", PAPER);
        }
        if (c.has(MosaicTileModifier.SYMLINK)) {
            g.setStroke(PAPER);
            g.setLineWidth(Math.max(2, s * 0.018));
            g.setLineCap(StrokeLineCap.SQUARE);
            g.strokeLine(x + s * 0.69, y + s * 0.19, x + s * 0.88, y + s * 0.19);
            g.strokeLine(x + s * 0.88, y + s * 0.19, x + s * 0.79, y + s * 0.10);
            g.strokeLine(x + s * 0.88, y + s * 0.19, x + s * 0.79, y + s * 0.28);
        }
        if (c.has(MosaicTileModifier.BROKEN_LINK)
                || c.has(MosaicTileModifier.THUMBNAIL_FAILED)) {
            g.setStroke(Color.rgb(242, 234, 216, 0.84));
            g.setLineWidth(Math.max(3, s * 0.035));
            g.strokeLine(x + s * 0.15, y + s * 0.15, x + s * 0.85, y + s * 0.85);
        } else if (c.has(MosaicTileModifier.THUMBNAIL_PENDING)) {
            g.setFill(Color.rgb(20, 18, 15, 0.68));
            g.fillRoundRect(x + s * 0.39, y + s * 0.45,
                    s * 0.25, s * 0.10, s * 0.05, s * 0.05);
            g.setFill(PAPER);
            double d = Math.max(3, s * 0.022);
            for (int i = 0; i < 3; i++) {
                g.fillOval(x + s * (0.43 + i * 0.07), y + s * 0.49, d, d);
            }
        }
    }

    private static void stateBand(MosaicTilePaintContext c, String word, Color color) {
        if (c.size() < 84) return;
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(Color.rgb(20, 18, 15, 0.76));
        g.fillRect(x, y + s * 0.44, s, s * 0.12);
        g.setFill(color);
        g.setFont(c.font(Math.max(8, s * 0.075), true));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(word, x + s * 0.5, y + s * 0.50);
    }
}
