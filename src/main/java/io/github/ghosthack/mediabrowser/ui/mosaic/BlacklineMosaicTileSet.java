package io.github.ghosthack.mediabrowser.ui.mosaic;

import io.github.ghosthack.mediabrowser.media.FolderVerdict;

import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.TextAlignment;

/**
 * Blackline Index: an information-first tile system built from a shared soot
 * field, warm bone silhouettes, and a restrained semantic spot ink.
 *
 * <p>The artwork follows a twelve-unit grid and progressive disclosure: at the
 * 64 px minimum the silhouette and short plate survive; family wording and
 * micro-state labels appear only when the tile can carry them. Color reinforces
 * identity but is never required to recognize it.</p>
 */
final class BlacklineMosaicTileSet implements MosaicTileSet {

    private record Palette(Color accent, String family) {}

    private static final Color SOOT = Color.web("#191b1a");
    private static final Color BONE = Color.web("#e8e2d5");
    private static final Color BONE_FAINT = Color.rgb(232, 226, 213, 0.56);
    private static final Color SMOKE = Color.rgb(8, 9, 8, 0.52);
    private static final Color OXIDE = Color.web("#b5654e");
    private static final Color STONE = Color.web("#85837e");

    private static final Palette PARENT = palette("#c39a52", "PARENT");
    private static final Palette FOLDER = palette("#c39a52", "FOLDER");
    private static final Palette IMAGE = palette("#4d9694", "IMAGE");
    private static final Palette VIDEO = palette("#7276a6", "MOVING IMAGE");
    private static final Palette AUDIO = palette("#a96c87", "SOUND");
    private static final Palette TEXT = palette("#5f8fa1", "TEXT / CODE");
    private static final Palette DOCUMENT = palette("#7186a3", "DOCUMENT");
    private static final Palette DATA = palette("#68866e", "STRUCTURED DATA");
    private static final Palette BINARY = palette("#80748e", "BINARY");
    private static final Palette EXECUTABLE = palette("#b5654e", "INSTALLER");
    private static final Palette ARCHIVE = palette("#b87948", "ARCHIVE");
    private static final Palette UNKNOWN = palette("#85837e", "UNKNOWN FILE");

    @Override public String id() { return "blackline"; }
    @Override public String label() { return "Blackline Index"; }

    @Override
    public void paint(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        g.save();
        try {
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineJoin(StrokeLineJoin.ROUND);

            if (isMedia(c.identity()) && c.drawMediaThumbnail()) {
                drawMediaCorner(c);
                drawModifiers(c);
                c.drawCaption();
                return;
            }

            Palette p = palette(c.identity());
            drawField(c, p);
            if (c.identity() == MosaicTileIdentity.FOLDER) {
                boolean collage = c.drawFolderCollage();
                drawFolderBody(c, collage);
                c.drawReticule();
                drawHeader(c, p);
                drawPlate(c, p, folderStamp(c.folderVerdict()));
            } else {
                drawIdentity(c);
                drawHeader(c, p);
                drawPlate(c, p, primaryStamp(c));
            }
            drawModifiers(c);
            c.drawCaption();
        } finally {
            g.restore();
        }
    }

    private static Palette palette(String accent, String family) {
        return new Palette(Color.web(accent), family);
    }

    private static Palette palette(MosaicTileIdentity identity) {
        return switch (identity) {
            case PARENT -> PARENT;
            case FOLDER -> FOLDER;
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
        g.setFill(SOOT);
        g.fillRect(x, y, s, s);

        // The index tab is intentionally the only semantic color outside the
        // label plate. Together they occupy less than fifteen percent of a tile.
        g.setFill(p.accent());
        g.fillRect(x + s * 0.86, y, s * 0.14, s * 0.105);

        // A barely visible paper edge gives the field direction without turning
        // every generated tile into a card floating over the mosaic.
        g.setFill(Color.rgb(232, 226, 213, 0.035));
        g.fillRect(x, y, s * 0.055, s);
    }

    private static void drawIdentity(MosaicTilePaintContext c) {
        switch (c.identity()) {
            case PARENT -> drawParent(c);
            case MEDIA_IMAGE -> drawImage(c);
            case MEDIA_VIDEO -> drawVideo(c);
            case MEDIA_AUDIO -> drawAudio(c);
            case TEXT -> drawText(c);
            case DOCUMENT -> drawDocument(c);
            case DATA -> drawData(c);
            case BINARY -> drawBinary(c);
            case EXECUTABLE -> drawExecutable(c);
            case ARCHIVE_BROWSABLE -> drawArchive(c, true);
            case ARCHIVE_SEALED -> drawArchive(c, false);
            case UNKNOWN -> drawUnknown(c);
            case FOLDER -> { /* handled separately */ }
        }
    }

    private static void drawHeader(MosaicTilePaintContext c, Palette p) {
        double s = c.size();
        if (s < 96) return;
        GraphicsContext g = c.graphics();
        g.setFill(BONE_FAINT);
        g.setFont(c.font(Math.max(7, Math.min(11, s * 0.052)), true));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(p.family(), c.x() + s * 0.085, c.y() + s * 0.075);
    }

    private static void drawPlate(
            MosaicTilePaintContext c, Palette p, String value) {
        double s = c.size();
        if (s < 56) return;
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y();
        double top = y + s * 0.69;
        double h = Math.min(34, s * 0.14);
        g.setFill(p.accent());
        g.fillRect(x + s * 0.08, top, s * 0.84, h);

        String stamp = shortStamp(value);
        g.setFill(SOOT);
        g.setFont(c.font(Math.max(8, Math.min(22, s * 0.115)), true));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(stamp, x + s * 0.11, top + h * 0.52);

        if (s >= 144 && stamp.length() <= 4) {
            g.setFill(Color.rgb(25, 27, 26, 0.72));
            g.setFont(c.font(Math.max(8, Math.min(11, s * 0.045)), true));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(p.family(), x + s * 0.89, top + h * 0.52);
        }
    }

    private static String primaryStamp(MosaicTilePaintContext c) {
        if (c.identity() == MosaicTileIdentity.PARENT) return "UP";
        return shortStamp(c.stamp());
    }

    private static String shortStamp(String value) {
        if (value == null || value.isBlank()) return "?";
        String clean = value.trim().toUpperCase();
        return clean.length() <= 7 ? clean : clean.substring(0, 6) + "\u2026";
    }

    private static String folderStamp(FolderVerdict verdict) {
        if (verdict == null || verdict == FolderVerdict.NORMAL) return "FOLDER";
        return switch (verdict) {
            case NORMAL -> "FOLDER";
            case NO_VISUAL -> "FILES";
            case EMPTY -> "EMPTY";
            case JUNK_ONLY -> "DUST";
            case UNREADABLE -> "NO READ";
        };
    }

    private static double line(double s) {
        return Math.max(1.5, s * 0.022);
    }

    private static void drawParent(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(BONE);
        g.beginPath();
        g.moveTo(x + s * 0.50, y + s * 0.16);
        g.lineTo(x + s * 0.80, y + s * 0.43);
        g.lineTo(x + s * 0.64, y + s * 0.43);
        g.lineTo(x + s * 0.64, y + s * 0.63);
        g.lineTo(x + s * 0.36, y + s * 0.63);
        g.lineTo(x + s * 0.36, y + s * 0.43);
        g.lineTo(x + s * 0.20, y + s * 0.43);
        g.closePath();
        g.fill();
        g.setFill(SOOT);
        g.fillRect(x + s * 0.47, y + s * 0.26, s * 0.06, s * 0.27);
    }

    private static void drawFolderBody(MosaicTilePaintContext c, boolean collage) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        if (collage) {
            g.setFill(Color.rgb(25, 27, 26, 0.78));
            g.fillRect(x, y, s, s * 0.12);
            g.setFill(FOLDER.accent());
            g.fillRect(x + s * 0.86, y, s * 0.14, s * 0.105);
            return;
        }

        g.setFill(BONE);
        g.fillRoundRect(x + s * 0.16, y + s * 0.27,
                s * 0.68, s * 0.36, s * 0.025, s * 0.025);
        g.fillRoundRect(x + s * 0.16, y + s * 0.20,
                s * 0.29, s * 0.13, s * 0.025, s * 0.025);
        g.setFill(SOOT);
        g.fillRect(x + s * 0.22, y + s * 0.36, s * 0.56, s * 0.20);

        FolderVerdict verdict = c.folderVerdict();
        if (verdict == null || verdict == FolderVerdict.NORMAL
                || verdict == FolderVerdict.NO_VISUAL) {
            g.setFill(BONE);
            for (int i = 0; i < 3; i++) {
                g.fillRect(x + s * 0.28, y + s * (0.40 + i * 0.055),
                        s * (0.39 - i * 0.06), Math.max(1.5, s * 0.018));
            }
        } else if (verdict == FolderVerdict.EMPTY) {
            g.setStroke(BONE);
            g.setLineWidth(line(s));
            g.strokeOval(x + s * 0.43, y + s * 0.40, s * 0.14, s * 0.14);
        } else if (verdict == FolderVerdict.JUNK_ONLY) {
            g.setFill(BONE);
            double d = Math.max(2, s * 0.022);
            double[][] dust = {{.31, .43}, {.43, .50}, {.57, .42}, {.69, .50}};
            for (double[] point : dust) {
                g.fillOval(x + s * point[0], y + s * point[1], d, d);
            }
        } else {
            g.setStroke(BONE);
            g.setLineWidth(line(s));
            g.strokeLine(x + s * 0.32, y + s * 0.40,
                    x + s * 0.68, y + s * 0.53);
            g.strokeLine(x + s * 0.32, y + s * 0.53,
                    x + s * 0.68, y + s * 0.40);
        }
    }

    private static void drawImage(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setStroke(BONE);
        g.setLineWidth(line(s));
        g.strokeRect(x + s * 0.19, y + s * 0.18, s * 0.62, s * 0.43);
        g.setFill(BONE);
        g.beginPath();
        g.moveTo(x + s * 0.23, y + s * 0.56);
        g.lineTo(x + s * 0.42, y + s * 0.34);
        g.lineTo(x + s * 0.54, y + s * 0.47);
        g.lineTo(x + s * 0.65, y + s * 0.37);
        g.lineTo(x + s * 0.77, y + s * 0.56);
        g.closePath();
        g.fill();
        g.fillOval(x + s * 0.64, y + s * 0.24, s * 0.08, s * 0.08);
    }

    private static void drawVideo(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(BONE);
        g.fillRoundRect(x + s * 0.17, y + s * 0.19,
                s * 0.66, s * 0.40, s * 0.025, s * 0.025);
        g.setFill(SOOT);
        for (int i = 0; i < 4; i++) {
            g.fillRect(x + s * 0.19, y + s * (0.22 + i * 0.085),
                    s * 0.045, s * 0.04);
            g.fillRect(x + s * 0.765, y + s * (0.22 + i * 0.085),
                    s * 0.045, s * 0.04);
        }
        g.beginPath();
        g.moveTo(x + s * 0.43, y + s * 0.29);
        g.lineTo(x + s * 0.66, y + s * 0.39);
        g.lineTo(x + s * 0.43, y + s * 0.49);
        g.closePath();
        g.fill();
    }

    private static void drawAudio(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(BONE);
        g.fillOval(x + s * 0.24, y + s * 0.15, s * 0.52, s * 0.52);
        g.setStroke(SOOT);
        g.setLineWidth(Math.max(1.5, s * 0.012));
        for (int i = 0; i < 3; i++) {
            double inset = s * (0.07 + i * 0.065);
            g.strokeOval(x + s * 0.24 + inset, y + s * 0.15 + inset,
                    s * 0.52 - inset * 2, s * 0.52 - inset * 2);
        }
        g.setFill(SOOT);
        g.fillOval(x + s * 0.44, y + s * 0.35, s * 0.12, s * 0.12);
    }

    private static void drawText(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setStroke(BONE);
        g.setLineWidth(line(s));
        g.strokeRect(x + s * 0.23, y + s * 0.16, s * 0.54, s * 0.48);
        g.setFill(BONE);
        for (int i = 0; i < 5; i++) {
            g.fillRect(x + s * 0.30, y + s * (0.24 + i * 0.065),
                    s * (i == 1 ? 0.30 : i == 4 ? 0.22 : 0.39),
                    Math.max(1.5, s * 0.018));
        }
        g.fillRect(x + s * 0.26, y + s * 0.22, s * 0.018, s * 0.32);
    }

    private static void drawDocument(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(BONE);
        g.beginPath();
        g.moveTo(x + s * 0.25, y + s * 0.14);
        g.lineTo(x + s * 0.61, y + s * 0.14);
        g.lineTo(x + s * 0.76, y + s * 0.29);
        g.lineTo(x + s * 0.76, y + s * 0.65);
        g.lineTo(x + s * 0.25, y + s * 0.65);
        g.closePath();
        g.fill();
        g.setFill(SOOT);
        g.beginPath();
        g.moveTo(x + s * 0.61, y + s * 0.14);
        g.lineTo(x + s * 0.61, y + s * 0.29);
        g.lineTo(x + s * 0.76, y + s * 0.29);
        g.closePath();
        g.fill();
        for (int i = 0; i < 4; i++) {
            g.fillRect(x + s * 0.32, y + s * (0.34 + i * 0.065),
                    s * (i == 3 ? 0.24 : 0.35), Math.max(1.5, s * 0.018));
        }
    }

    private static void drawData(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setStroke(BONE);
        g.setLineWidth(line(s));
        g.strokeRect(x + s * 0.20, y + s * 0.18, s * 0.60, s * 0.43);
        for (int i = 1; i < 4; i++) {
            double lx = x + s * (0.20 + i * 0.15);
            g.strokeLine(lx, y + s * 0.18, lx, y + s * 0.61);
        }
        for (int i = 1; i < 3; i++) {
            double ly = y + s * (0.18 + i * 0.143);
            g.strokeLine(x + s * 0.20, ly, x + s * 0.80, ly);
        }
        g.setFill(BONE);
        g.fillRect(x + s * 0.225, y + s * 0.205, s * 0.10, s * 0.09);
    }

    private static void drawBinary(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(BONE);
        g.fillRoundRect(x + s * 0.17, y + s * 0.20,
                s * 0.66, s * 0.38, s * 0.025, s * 0.025);
        g.setFill(SOOT);
        int[][] holes = {{0, 0}, {2, 0}, {5, 0}, {1, 1}, {3, 1}, {4, 1},
                {0, 2}, {1, 2}, {4, 2}, {5, 2}};
        double d = Math.max(3, s * 0.055);
        for (int[] hole : holes) {
            g.fillOval(x + s * 0.23 + hole[0] * s * 0.09,
                    y + s * 0.26 + hole[1] * s * 0.095, d, d);
        }
    }

    private static void drawExecutable(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(BONE);
        g.fillRoundRect(x + s * 0.18, y + s * 0.17,
                s * 0.64, s * 0.45, s * 0.06, s * 0.06);
        g.setFill(SOOT);
        g.fillRoundRect(x + s * 0.23, y + s * 0.22,
                s * 0.54, s * 0.35, s * 0.035, s * 0.035);
        g.setFill(BONE);
        g.beginPath();
        g.moveTo(x + s * 0.46, y + s * 0.27);
        g.lineTo(x + s * 0.54, y + s * 0.27);
        g.lineTo(x + s * 0.54, y + s * 0.41);
        g.lineTo(x + s * 0.64, y + s * 0.41);
        g.lineTo(x + s * 0.50, y + s * 0.53);
        g.lineTo(x + s * 0.36, y + s * 0.41);
        g.lineTo(x + s * 0.46, y + s * 0.41);
        g.closePath();
        g.fill();
    }

    private static void drawArchive(MosaicTilePaintContext c, boolean browsable) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setStroke(BONE);
        g.setLineWidth(line(s));
        g.strokeRoundRect(x + s * 0.20, y + s * 0.24,
                s * 0.60, s * 0.35, s * 0.025, s * 0.025);
        if (browsable) {
            g.strokeLine(x + s * 0.20, y + s * 0.24,
                    x + s * 0.34, y + s * 0.16);
            g.strokeLine(x + s * 0.34, y + s * 0.16,
                    x + s * 0.78, y + s * 0.16);
        } else {
            g.strokeLine(x + s * 0.20, y + s * 0.31,
                    x + s * 0.80, y + s * 0.31);
        }
        g.setFill(BONE);
        for (int i = 0; i < 5; i++) {
            g.fillRect(x + s * (0.46 + (i % 2) * 0.055),
                    y + s * (0.30 + i * 0.05), s * 0.05, s * 0.035);
        }
    }

    private static void drawUnknown(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(BONE);
        g.beginPath();
        g.moveTo(x + s * 0.50, y + s * 0.14);
        g.lineTo(x + s * 0.77, y + s * 0.28);
        g.lineTo(x + s * 0.77, y + s * 0.52);
        g.lineTo(x + s * 0.50, y + s * 0.66);
        g.lineTo(x + s * 0.23, y + s * 0.52);
        g.lineTo(x + s * 0.23, y + s * 0.28);
        g.closePath();
        g.fill();
        g.setFill(SOOT);
        g.setFont(c.font(Math.max(14, s * 0.32), true));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("?", x + s * 0.50, y + s * 0.40);
    }

    private static void drawMediaCorner(MosaicTilePaintContext c) {
        if (c.identity() == MosaicTileIdentity.MEDIA_IMAGE) return;
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        Palette p = palette(c.identity());
        double w = Math.max(28, Math.min(52, s * 0.22));
        double h = w * 0.62;
        g.setFill(p.accent());
        g.fillRect(x + s - w, y, w, h);
        g.setFill(SOOT);
        if (c.identity() == MosaicTileIdentity.MEDIA_VIDEO) {
            g.beginPath();
            g.moveTo(x + s - w * 0.62, y + h * 0.24);
            g.lineTo(x + s - w * 0.30, y + h * 0.50);
            g.lineTo(x + s - w * 0.62, y + h * 0.76);
            g.closePath();
            g.fill();
        } else {
            g.setFont(c.font(Math.max(10, h * 0.58), true));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText("\u266a", x + s - w * 0.50, y + h * 0.50);
        }
    }

    private static void drawModifiers(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        boolean folderLike = c.identity() == MosaicTileIdentity.FOLDER
                || c.identity() == MosaicTileIdentity.PARENT
                || c.identity() == MosaicTileIdentity.ARCHIVE_BROWSABLE;

        if (c.has(MosaicTileModifier.HIDDEN)) {
            g.setFill(SMOKE);
            g.fillRect(x, y, s, s);
            microTag(c, "H", x + s * 0.075, y + s * 0.14, STONE);
        }
        if (c.has(MosaicTileModifier.SYSTEM)) {
            microPattern(g, x + s * 0.66, y + s * 0.135, s, false);
        }
        if (c.has(MosaicTileModifier.JUNK)) {
            microPattern(g, x + s * 0.56, y + s * 0.135, s, true);
        }
        if (c.has(MosaicTileModifier.EXECUTABLE)
                && c.identity() != MosaicTileIdentity.EXECUTABLE
                && !folderLike) {
            g.setFill(EXECUTABLE.accent());
            g.fillRect(x + s * 0.94, y + s * 0.39, s * 0.06, s * 0.20);
            if (s >= 96) {
                g.setFill(SOOT);
                g.setFont(c.font(Math.max(7, s * 0.045), true));
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.CENTER);
                g.fillText("+X", x + s * 0.97, y + s * 0.49);
            }
        }
        if (c.has(MosaicTileModifier.ZERO_BYTE)) {
            g.setStroke(BONE);
            g.setLineWidth(line(s));
            g.strokeOval(x + s * 0.43, y + s * 0.52, s * 0.14, s * 0.14);
            if (s >= 96) {
                g.setFill(BONE);
                g.setFont(c.font(Math.max(7, s * 0.045), true));
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.CENTER);
                g.fillText("0 B", x + s * 0.50, y + s * 0.59);
            }
        }
        if (c.has(MosaicTileModifier.SYMLINK)) {
            g.setStroke(BONE);
            g.setLineWidth(line(s));
            g.strokeLine(x + s * 0.09, y + s * 0.18,
                    x + s * 0.25, y + s * 0.18);
            g.strokeLine(x + s * 0.25, y + s * 0.18,
                    x + s * 0.19, y + s * 0.12);
            g.strokeLine(x + s * 0.25, y + s * 0.18,
                    x + s * 0.19, y + s * 0.24);
        }

        boolean failed = c.has(MosaicTileModifier.BROKEN_LINK)
                || c.has(MosaicTileModifier.THUMBNAIL_FAILED);
        boolean unreadable = c.has(MosaicTileModifier.UNREADABLE) && !folderLike;
        if (failed) {
            stateBanner(c, c.has(MosaicTileModifier.BROKEN_LINK)
                    ? "BROKEN" : "NO PREVIEW");
        } else if (unreadable) {
            stateBanner(c, "NO READ");
        } else if (c.has(MosaicTileModifier.THUMBNAIL_PENDING)) {
            g.setFill(Color.rgb(25, 27, 26, 0.78));
            g.fillRoundRect(x + s * 0.38, y + s * 0.44,
                    s * 0.24, s * 0.10, s * 0.04, s * 0.04);
            g.setFill(BONE);
            double d = Math.max(2.5, s * 0.022);
            for (int i = 0; i < 3; i++) {
                g.fillOval(x + s * (0.425 + i * 0.065),
                        y + s * 0.485, d, d);
            }
        }
    }

    private static void microTag(
            MosaicTilePaintContext c, String text, double x, double y, Color color) {
        GraphicsContext g = c.graphics();
        double s = c.size();
        double d = Math.max(12, s * 0.09);
        g.setFill(color);
        g.fillRect(x, y, d, d);
        if (s >= 96) {
            g.setFill(SOOT);
            g.setFont(c.font(Math.max(7, d * 0.55), true));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(text, x + d * 0.5, y + d * 0.52);
        }
    }

    private static void microPattern(
            GraphicsContext g, double x, double y, double s, boolean round) {
        g.setFill(BONE_FAINT);
        double d = Math.max(1.5, s * 0.013);
        double gap = d * 1.9;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double px = x + col * gap, py = y + row * gap;
                if (round) g.fillOval(px, py, d, d);
                else g.fillRect(px, py, d, d);
            }
        }
    }

    private static void stateBanner(MosaicTilePaintContext c, String text) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(OXIDE);
        g.fillRect(x, y + s * 0.43, s, s * 0.14);
        if (s >= 72) {
            g.setFill(SOOT);
            g.setFont(c.font(Math.max(8, Math.min(15, s * 0.075)), true));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(text, x + s * 0.50, y + s * 0.50);
        }
    }
}
