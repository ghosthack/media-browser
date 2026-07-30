package io.github.ghosthack.mediabrowser.ui.mosaic;

import io.github.ghosthack.mediabrowser.media.FolderVerdict;

import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Darkroom Specimens: full-bleed synthetic thumbnails designed to sit beside
 * crop-to-fill photography in a seamless grid.
 */
final class DarkroomMosaicTileSet implements MosaicTileSet {

    private static final Color FOLDER_BG = Color.web("#28251f");
    private static final Color FOLDER_FG = Color.web("#8b7b58");
    private static final Color TEXT_BG = Color.web("#1c2b31");
    private static final Color TEXT_FG = Color.web("#71a0ad");
    private static final Color DOCUMENT_BG = Color.web("#292c35");
    private static final Color DOCUMENT_FG = Color.web("#8891ad");
    private static final Color DATA_BG = Color.web("#1d2e28");
    private static final Color DATA_FG = Color.web("#6e9d87");
    private static final Color BINARY_BG = Color.web("#29243a");
    private static final Color BINARY_FG = Color.web("#8b7daf");
    private static final Color EXEC_BG = Color.web("#1c3028");
    private static final Color EXEC_FG = Color.web("#70a88c");
    private static final Color ARCHIVE_BG = Color.web("#39271f");
    private static final Color ARCHIVE_FG = Color.web("#b08164");
    private static final Color AUDIO_BG = Color.web("#30222e");
    private static final Color AUDIO_FG = Color.web("#ac789e");
    private static final Color VIDEO_BG = Color.web("#252238");
    private static final Color VIDEO_FG = Color.web("#8880ba");
    private static final Color IMAGE_BG = Color.web("#1d2c30");
    private static final Color IMAGE_FG = Color.web("#70969e");
    private static final Color UNKNOWN_BG = Color.web("#252525");
    private static final Color UNKNOWN_FG = Color.web("#777777");
    private static final Color VEIL = Color.rgb(0, 0, 0, 0.28);
    private static final Color SOFT_BLACK = Color.rgb(0, 0, 0, 0.42);
    private static final Color SOFT_WHITE = Color.rgb(255, 255, 255, 0.72);

    @Override public String id() { return "darkroom"; }
    @Override public String label() { return "Darkroom Specimens"; }

    @Override
    public void paint(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        g.save();
        try {
            if (isMedia(c.identity()) && c.drawMediaThumbnail()) {
                drawMediaMark(c);
                drawModifiers(c);
                c.drawCaption();
                return;
            }

            fillField(c);
            if (c.identity() == MosaicTileIdentity.FOLDER) {
                boolean collage = c.drawFolderCollage();
                drawFolder(c, collage);
                c.drawReticule();
            } else {
                drawIdentity(c);
            }
            drawModifiers(c);
            c.drawCaption();
        } finally {
            g.restore();
        }
    }

    private static boolean isMedia(MosaicTileIdentity identity) {
        return identity == MosaicTileIdentity.MEDIA_IMAGE
                || identity == MosaicTileIdentity.MEDIA_VIDEO
                || identity == MosaicTileIdentity.MEDIA_AUDIO;
    }

    private static void fillField(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        Color bg = switch (c.identity()) {
            case PARENT, FOLDER -> FOLDER_BG;
            case MEDIA_IMAGE -> IMAGE_BG;
            case MEDIA_VIDEO -> VIDEO_BG;
            case MEDIA_AUDIO -> AUDIO_BG;
            case TEXT -> TEXT_BG;
            case DOCUMENT -> DOCUMENT_BG;
            case DATA -> DATA_BG;
            case BINARY -> BINARY_BG;
            case EXECUTABLE -> EXEC_BG;
            case ARCHIVE_BROWSABLE, ARCHIVE_SEALED -> ARCHIVE_BG;
            case UNKNOWN -> UNKNOWN_BG;
        };
        g.setFill(bg);
        g.fillRect(c.x(), c.y(), c.size(), c.size());
        // Two large translucent planes make the field feel composed rather than
        // like a flat UI swatch, while remaining quiet beside photography.
        g.setFill(Color.rgb(255, 255, 255, 0.025));
        double s = c.size();
        g.fillRect(c.x(), c.y(), s, s * 0.12);
        g.setFill(Color.rgb(0, 0, 0, 0.08));
        g.fillRect(c.x() + s * 0.72, c.y(), s * 0.28, s);
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

    private static void drawFolder(MosaicTilePaintContext c, boolean collage) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        if (collage) {
            // A dark folder crown unifies a collage without stealing meaningful
            // image area. The tab is the same shape used by generated folders.
            g.setFill(Color.rgb(0, 0, 0, 0.46));
            g.fillRect(x, y, s, s * 0.14);
            g.fillRect(x + s * 0.07, y, s * 0.34, s * 0.2);
            g.setStroke(Color.rgb(255, 255, 255, 0.28));
            g.setLineWidth(Math.max(1, s * 0.012));
            g.strokeRect(x + 1, y + 1, s - 2, s - 2);
            return;
        }
        g.setFill(FOLDER_FG);
        double left = x + s * 0.08, top = y + s * 0.25;
        g.fillRect(left, top, s * 0.84, s * 0.62);
        g.fillRect(left, y + s * 0.14, s * 0.38, s * 0.2);
        g.setFill(Color.rgb(0, 0, 0, 0.32));
        g.fillRect(x + s * 0.16, y + s * 0.38, s * 0.68, s * 0.36);

        FolderVerdict verdict = c.folderVerdict();
        if (verdict == FolderVerdict.NO_VISUAL || verdict == FolderVerdict.NORMAL) {
            g.setFill(Color.rgb(255, 255, 255, 0.28));
            for (int i = 0; i < 3; i++) {
                double w = s * (0.48 - i * 0.08);
                g.fillRect(x + s * 0.25, y + s * (0.46 + i * 0.09), w, s * 0.025);
            }
        } else if (verdict == FolderVerdict.EMPTY) {
            // One quiet hollow aperture: visibly vacant, without borrowing the
            // unknown-file question mark or the unreadable-folder hatch.
            g.setStroke(Color.rgb(255, 255, 255, 0.34));
            g.setLineWidth(Math.max(1.5, s * 0.018));
            g.strokeOval(x + s * 0.43, y + s * 0.52, s * 0.14, s * 0.14);
        } else if (verdict == FolderVerdict.JUNK_ONLY) {
            // Sparse "dust" in the folder cavity: occupied, but by material the
            // listing policy treats as non-content.
            g.setFill(Color.rgb(255, 255, 255, 0.32));
            double d = Math.max(2, s * 0.018);
            double[][] dust = {{.30, .55}, {.48, .64}, {.67, .49}, {.73, .68}};
            for (double[] point : dust) {
                g.fillRect(x + s * point[0], y + s * point[1], d, d);
            }
        } else if (verdict == FolderVerdict.UNREADABLE) {
            g.setFill(Color.rgb(0, 0, 0, 0.46));
            g.fillRect(x + s * 0.16, y + s * 0.38, s * 0.68, s * 0.36);
            g.setStroke(Color.rgb(255, 255, 255, 0.36));
            g.setLineWidth(Math.max(1.5, s * 0.018));
            g.strokeLine(x + s * 0.30, y + s * 0.56, x + s * 0.70, y + s * 0.56);
        }
    }

    private static void drawParent(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(FOLDER_FG);
        g.beginPath();
        g.moveTo(x + s * 0.5, y + s * 0.12);
        g.lineTo(x + s * 0.86, y + s * 0.5);
        g.lineTo(x + s * 0.67, y + s * 0.5);
        g.lineTo(x + s * 0.67, y + s * 0.88);
        g.lineTo(x + s * 0.33, y + s * 0.88);
        g.lineTo(x + s * 0.33, y + s * 0.5);
        g.lineTo(x + s * 0.14, y + s * 0.5);
        g.closePath();
        g.fill();
    }

    private static void drawImage(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(IMAGE_FG);
        g.fillRect(x + s * 0.1, y + s * 0.14, s * 0.8, s * 0.72);
        g.setFill(IMAGE_BG);
        g.beginPath();
        g.moveTo(x + s * 0.16, y + s * 0.76);
        g.lineTo(x + s * 0.43, y + s * 0.43);
        g.lineTo(x + s * 0.58, y + s * 0.61);
        g.lineTo(x + s * 0.72, y + s * 0.48);
        g.lineTo(x + s * 0.86, y + s * 0.76);
        g.closePath();
        g.fill();
        g.fillOval(x + s * 0.65, y + s * 0.25, s * 0.1, s * 0.1);
        drawStamp(c, IMAGE_FG);
    }

    private static void drawVideo(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(VIDEO_FG);
        g.fillRect(x + s * 0.08, y + s * 0.15, s * 0.84, s * 0.7);
        g.setFill(VIDEO_BG);
        g.beginPath();
        g.moveTo(x + s * 0.4, y + s * 0.31);
        g.lineTo(x + s * 0.72, y + s * 0.5);
        g.lineTo(x + s * 0.4, y + s * 0.69);
        g.closePath();
        g.fill();
        drawStamp(c, VIDEO_FG);
    }

    private static void drawAudio(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(AUDIO_FG);
        for (int i = 0; i < 9; i++) {
            double h = s * (0.18 + ((i * 7) % 5) * 0.09);
            double w = s * 0.055;
            g.fillRoundRect(x + s * (0.17 + i * 0.075), y + (s - h) / 2,
                    w, h, w, w);
        }
        drawStamp(c, AUDIO_FG);
    }

    private static void drawText(MosaicTilePaintContext c) {
        drawLargeType(c, "{ }", TEXT_FG, 0.4);
        drawStamp(c, TEXT_FG);
    }

    private static void drawDocument(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setStroke(DOCUMENT_FG);
        g.setLineWidth(Math.max(2, s * 0.035));
        g.strokeRect(x + s * 0.16, y + s * 0.09, s * 0.68, s * 0.82);
        g.setFill(DOCUMENT_FG);
        for (int i = 0; i < 5; i++) {
            g.fillRect(x + s * 0.26, y + s * (0.27 + i * 0.1),
                    s * (i == 4 ? 0.28 : 0.48), s * 0.025);
        }
        drawStamp(c, DOCUMENT_FG);
    }

    private static void drawData(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(DATA_FG);
        double cell = s * 0.15, gap = s * 0.04;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                double alpha = 0.35 + ((row + col) % 3) * 0.25;
                g.setGlobalAlpha(alpha);
                g.fillRect(x + s * 0.13 + col * (cell + gap),
                        y + s * 0.19 + row * (cell + gap), cell, cell);
            }
        }
        g.setGlobalAlpha(1);
        drawStamp(c, DATA_FG);
    }

    private static void drawBinary(MosaicTilePaintContext c) {
        drawLargeType(c, "01", BINARY_FG, 0.48);
        drawStamp(c, BINARY_FG);
    }

    private static void drawExecutable(MosaicTilePaintContext c) {
        drawLargeType(c, ">_", EXEC_FG, 0.44);
        GraphicsContext g = c.graphics();
        double s = c.size();
        g.setFill(EXEC_FG);
        g.fillRect(c.x(), c.y() + s * 0.92, s, s * 0.08);
        drawStamp(c, EXEC_FG);
    }

    private static void drawArchive(MosaicTilePaintContext c, boolean browsable) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        g.setFill(ARCHIVE_FG);
        g.fillRect(x + s * 0.11, y + s * 0.12, s * 0.78, s * 0.76);
        g.setFill(ARCHIVE_BG);
        double seamX = x + s * 0.47;
        for (int i = 0; i < 7; i++) {
            g.fillRect(seamX + (i % 2) * s * 0.055,
                    y + s * (0.18 + i * 0.09), s * 0.055, s * 0.055);
        }
        if (browsable) {
            g.setFill(Color.rgb(255, 255, 255, 0.28));
            g.fillRect(x + s * 0.11, y + s * 0.12, s * 0.34, s * 0.11);
        } else {
            g.setFill(SOFT_BLACK);
            g.fillRect(x + s * 0.11, y + s * 0.72, s * 0.78, s * 0.16);
        }
        drawStamp(c, ARCHIVE_FG);
    }

    private static void drawUnknown(MosaicTilePaintContext c) {
        drawLargeType(c, "?", UNKNOWN_FG, 0.5);
        drawStamp(c, UNKNOWN_FG);
    }

    private static void drawLargeType(
            MosaicTilePaintContext c, String text, Color color, double scale) {
        GraphicsContext g = c.graphics();
        double s = c.size();
        g.setFill(color);
        g.setFont(c.font(Math.max(12, s * scale), true));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(text, c.x() + s * 0.5, c.y() + s * 0.47);
    }

    private static void drawStamp(MosaicTilePaintContext c, Color color) {
        if (c.size() < 74) return;
        String stamp = stamp(c.stamp());
        GraphicsContext g = c.graphics();
        double s = c.size();
        g.setFill(Color.rgb(0, 0, 0, 0.36));
        double h = Math.min(24, s * 0.16);
        g.fillRect(c.x() + s * 0.08, c.y() + s * 0.76, s * 0.54, h);
        g.setFill(color);
        g.setFont(c.font(Math.max(8, Math.min(13, s * 0.09)), true));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(stamp, c.x() + s * 0.12, c.y() + s * 0.76 + h / 2);
    }

    private static String stamp(String value) {
        if (value == null || value.isBlank()) return "?";
        return value.length() <= 6 ? value : value.substring(0, 5) + "…";
    }

    private static void drawMediaMark(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();
        if (c.identity() == MosaicTileIdentity.MEDIA_VIDEO) {
            g.setFill(Color.rgb(0, 0, 0, 0.55));
            double d = Math.max(20, s * 0.24);
            g.fillOval(x + s - d - s * 0.06, y + s * 0.06, d, d);
            g.setFill(SOFT_WHITE);
            g.beginPath();
            g.moveTo(x + s - d * 0.64 - s * 0.06, y + s * 0.06 + d * 0.27);
            g.lineTo(x + s - d * 0.64 - s * 0.06, y + s * 0.06 + d * 0.73);
            g.lineTo(x + s - d * 0.26 - s * 0.06, y + s * 0.06 + d * 0.5);
            g.closePath();
            g.fill();
        } else if (c.identity() == MosaicTileIdentity.MEDIA_AUDIO) {
            g.setFill(Color.rgb(0, 0, 0, 0.48));
            g.fillRect(x, y, s, s * 0.1);
        }
    }

    private static void drawModifiers(MosaicTilePaintContext c) {
        GraphicsContext g = c.graphics();
        double x = c.x(), y = c.y(), s = c.size();

        if (c.has(MosaicTileModifier.HIDDEN)) {
            g.setFill(VEIL);
            g.fillRect(x, y, s, s);
            g.setFill(SOFT_WHITE);
            double d = Math.max(3, s * 0.025);
            g.fillOval(x + s * 0.08, y + s * 0.08, d, d);
        }
        if (c.has(MosaicTileModifier.SYSTEM)) {
            g.setFill(Color.rgb(255, 255, 255, 0.32));
            double d = Math.max(2, s * 0.022), gap = d * 1.8;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    g.fillRect(x + s - s * 0.08 - 3 * gap + col * gap,
                            y + s * 0.08 + row * gap, d, d);
                }
            }
        }
        if (c.has(MosaicTileModifier.JUNK)) {
            g.setFill(Color.rgb(205, 188, 151, 0.46));
            double d = Math.max(2, s * 0.018);
            double[][] dust = {{.13, .79}, {.20, .87}, {.31, .82}, {.38, .90}};
            for (double[] point : dust) {
                g.fillRect(x + s * point[0], y + s * point[1], d, d);
            }
        }
        if (c.has(MosaicTileModifier.EXECUTABLE)
                && c.identity() != MosaicTileIdentity.EXECUTABLE) {
            g.setFill(Color.rgb(112, 168, 140, 0.82));
            g.fillRect(x + s * 0.94, y, s * 0.06, s);
        }
        if (c.has(MosaicTileModifier.ZERO_BYTE)) {
            g.setStroke(SOFT_WHITE);
            g.setLineWidth(Math.max(1.5, s * 0.018));
            g.strokeOval(x + s * 0.39, y + s * 0.39, s * 0.22, s * 0.22);
        }
        if (c.has(MosaicTileModifier.UNREADABLE)) {
            g.setStroke(Color.rgb(255, 255, 255, 0.26));
            g.setLineWidth(Math.max(1, s * 0.014));
            double step = Math.max(8, s * 0.1);
            for (double at = -s; at < s * 2; at += step) {
                g.strokeLine(x + at, y, x + at - s, y + s);
            }
        }
        if (c.has(MosaicTileModifier.SYMLINK)) {
            g.setStroke(SOFT_WHITE);
            g.setLineWidth(Math.max(1.5, s * 0.02));
            g.setLineCap(StrokeLineCap.ROUND);
            g.strokeLine(x + s * 0.68, y + s * 0.18, x + s * 0.88, y + s * 0.18);
            g.strokeLine(x + s * 0.88, y + s * 0.18, x + s * 0.78, y + s * 0.09);
        }
        if (c.has(MosaicTileModifier.BROKEN_LINK)
                || c.has(MosaicTileModifier.THUMBNAIL_FAILED)) {
            g.setStroke(Color.rgb(235, 210, 205, 0.72));
            g.setLineWidth(Math.max(2, s * 0.025));
            g.strokeLine(x + s * 0.18, y + s * 0.18, x + s * 0.82, y + s * 0.82);
        } else if (c.has(MosaicTileModifier.THUMBNAIL_PENDING)) {
            g.setFill(Color.rgb(255, 255, 255, 0.32));
            double d = Math.max(3, s * 0.025);
            for (int i = 0; i < 3; i++) {
                g.fillOval(x + s * (0.44 + i * 0.06), y + s * 0.5, d, d);
            }
        }
    }
}
