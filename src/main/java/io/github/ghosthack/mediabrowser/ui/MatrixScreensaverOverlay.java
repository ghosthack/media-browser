package io.github.ghosthack.mediabrowser.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Mosaic's app-local screensaver: traditional vertical grayscale Matrix rain
 * over a near-black field, with cached images from the active directory first
 * transformed into ASCII art and then slowly surfaced behind the glyph rain.
 *
 * <p>The overlay never requests or owns media decodes. Its caller supplies a
 * snapshot of thumbnails Mosaic has already cached. With no cached visuals it
 * remains a pure rain animation. The node is mouse-transparent so wake events
 * reach Mosaic's root event filter.</p>
 */
final class MatrixScreensaverOverlay extends Region {

    private static final double CELL = 18;
    private static final int MIN_TRAIL = 7;
    private static final int MAX_TRAIL = 22;
    private static final double MIN_SPEED = 55;
    private static final double MAX_SPEED = 125;
    private static final double IMAGE_PERIOD_SECONDS = 12;
    private static final double IMAGE_CROSSFADE_SECONDS = 3;
    private static final double ASCII_IMAGE_OPACITY = 0.34;
    private static final int ASCII_IMAGE_COLUMNS = 58;
    private static final int ASCII_IMAGE_MAX_ROWS = 42;
    private static final int MAX_IMAGES = 16;
    /** Ordered from visually empty/dark to dense/bright. */
    private static final String ASCII_RAMP = " .,:;i1tfLCG08@";
    private static final String GLYPHS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            + "<>[]{}#@$%&*+=:/\\\\|-";
    private static final Font RAIN_FONT =
            Font.font("Monospaced", FontWeight.BOLD, CELL * 0.82);

    private final Canvas canvas = new Canvas();
    private final Random random = new Random();
    private final List<Drop> drops = new ArrayList<>();
    private final AnimationTimer animation;
    private List<AsciiImage> images = List.of();
    private long startNanos;
    private long lastNanos;
    private int layoutColumns;

    MatrixScreensaverOverlay() {
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setMouseTransparent(true);
        setVisible(false);
        setAccessibleText("Matrix screensaver");
        getChildren().add(canvas);

        animation = new AnimationTimer() {
            @Override public void handle(long now) {
                if (startNanos == 0) {
                    startNanos = now;
                    lastNanos = now;
                }
                double elapsed = (now - startNanos) / 1_000_000_000.0;
                double delta = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
                lastNanos = now;
                update(delta);
                draw(elapsed);
            }
        };
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        if (canvas.getWidth() != width) canvas.setWidth(width);
        if (canvas.getHeight() != height) canvas.setHeight(height);
        int columns = Math.max(1, (int) Math.ceil(width / CELL));
        if (columns != layoutColumns) rebuildDrops(columns, height);
    }

    /**
     * Converts a shuffled snapshot of already-decoded thumbnails into ASCII
     * compositions, then starts the animation. Source pixels are not rendered.
     */
    void start(List<Image> cachedImages) {
        var usable = new ArrayList<AsciiImage>();
        if (cachedImages != null) {
            for (Image image : cachedImages) {
                if (image == null || image.isError()
                        || image.getWidth() <= 0 || image.getHeight() <= 0) continue;
                AsciiImage ascii = toAscii(image);
                if (ascii == null) continue;
                usable.add(ascii);
                if (usable.size() == MAX_IMAGES) break;
            }
        }
        Collections.shuffle(usable, random);
        images = List.copyOf(usable);
        rebuildDrops(Math.max(1, (int) Math.ceil(getWidth() / CELL)), getHeight());
        startNanos = 0;
        lastNanos = 0;
        setVisible(true);
        animation.start();
    }

    void stop() {
        animation.stop();
        setVisible(false);
        images = List.of();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    boolean isRunning() {
        return isVisible();
    }

    private void rebuildDrops(int columns, double height) {
        layoutColumns = columns;
        drops.clear();
        double span = Math.max(1, height);
        for (int column = 0; column < columns; column++) {
            int trail = MIN_TRAIL + random.nextInt(MAX_TRAIL - MIN_TRAIL + 1);
            double y = random.nextDouble() * (span + trail * CELL) - trail * CELL;
            double speed = MIN_SPEED + random.nextDouble() * (MAX_SPEED - MIN_SPEED);
            drops.add(new Drop(column * CELL, y, speed, trail, random.nextInt()));
        }
    }

    private void update(double deltaSeconds) {
        double height = canvas.getHeight();
        if (height <= 0) return;
        for (Drop drop : drops) {
            drop.y += drop.speed * deltaSeconds;
            if (drop.y - drop.trail * CELL > height) {
                drop.y = -CELL * (1 + random.nextDouble() * drop.trail);
                drop.speed = MIN_SPEED + random.nextDouble() * (MAX_SPEED - MIN_SPEED);
                drop.trail = MIN_TRAIL + random.nextInt(MAX_TRAIL - MIN_TRAIL + 1);
                drop.seed = random.nextInt();
            }
        }
    }

    private void draw(double elapsedSeconds) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setGlobalAlpha(1);
        g.setEffect(null);
        g.setFill(Color.rgb(0, 0, 0, 0.975));
        g.fillRect(0, 0, width, height);

        drawImages(g, elapsedSeconds, width, height);

        g.setFont(RAIN_FONT);
        g.setTextBaseline(VPos.CENTER);
        long tick = (long) (elapsedSeconds * 10);
        for (int column = 0; column < drops.size(); column++) {
            Drop drop = drops.get(column);
            for (int tail = drop.trail - 1; tail >= 0; tail--) {
                double y = drop.y - tail * CELL;
                if (y < -CELL || y > height + CELL) continue;
                double strength = 1.0 - tail / (double) drop.trail;
                double gray = 0.13 + 0.77 * Math.pow(strength, 1.55);
                double alpha = 0.10 + 0.84 * strength;
                if (tail == 0) {
                    gray = 0.98;
                    alpha = 0.96;
                }
                g.setFill(Color.gray(gray, alpha));
                g.fillText(glyph(drop.seed, column, tail, tick), drop.x, y);
            }
        }
    }

    private void drawImages(
            GraphicsContext g, double elapsedSeconds, double width, double height) {
        if (images.isEmpty()) return;

        double cycle = elapsedSeconds / IMAGE_PERIOD_SECONDS;
        int current = Math.floorMod((int) Math.floor(cycle), images.size());
        double within = elapsedSeconds % IMAGE_PERIOD_SECONDS;
        double crossfadeStart = IMAGE_PERIOD_SECONDS - IMAGE_CROSSFADE_SECONDS;
        double nextMix = within <= crossfadeStart
                ? 0 : (within - crossfadeStart) / IMAGE_CROSSFADE_SECONDS;

        drawImage(g, images.get(current), current, width, height,
                ASCII_IMAGE_OPACITY * (1 - nextMix));
        if (nextMix > 0 && images.size() > 1) {
            int next = (current + 1) % images.size();
            drawImage(g, images.get(next), next, width, height,
                    ASCII_IMAGE_OPACITY * nextMix);
        }
        g.setGlobalAlpha(1);
    }

    private static void drawImage(
            GraphicsContext g, AsciiImage image, int index, double width, double height,
            double opacity) {
        double fontSize = Math.min(18, Math.max(7, Math.min(
                width * 0.66 / (image.columns * 0.61),
                height * 0.72 / (image.rows.size() * 1.05))));
        double advance = fontSize * 0.61;
        double lineHeight = fontSize * 1.05;
        double drawW = image.columns * advance;
        double drawH = image.rows.size() * lineHeight;
        // Stable per-image offsets prevent every picture from appearing in the
        // exact same place while keeping the slow crossfade calm.
        double xRoom = Math.max(0, width - drawW);
        double yRoom = Math.max(0, height - drawH);
        double x = xRoom * (0.18 + 0.58 * unitHash(index * 47 + 11));
        double y = yRoom * (0.16 + 0.52 * unitHash(index * 71 + 29));
        g.setGlobalAlpha(opacity);
        g.setFont(Font.font("Monospaced", fontSize));
        g.setTextBaseline(VPos.TOP);
        g.setFill(Color.gray(0.82));
        for (int row = 0; row < image.rows.size(); row++) {
            g.fillText(image.rows.get(row), x, y + row * lineHeight);
        }
    }

    /** Samples a thumbnail into a contrast-shaped, aspect-correct ASCII grid. */
    private static AsciiImage toAscii(Image image) {
        PixelReader pixels = image.getPixelReader();
        if (pixels == null) return null;

        double aspect = image.getHeight() / image.getWidth();
        int columns = ASCII_IMAGE_COLUMNS;
        // Text cells are roughly twice as tall as they are wide, hence 0.52.
        int rows = Math.max(6, (int) Math.round(columns * aspect * 0.52));
        if (rows > ASCII_IMAGE_MAX_ROWS) {
            rows = ASCII_IMAGE_MAX_ROWS;
            columns = Math.max(12, (int) Math.round(rows / (aspect * 0.52)));
        }

        var result = new ArrayList<String>(rows);
        int pixelW = (int) image.getWidth();
        int pixelH = (int) image.getHeight();
        for (int row = 0; row < rows; row++) {
            var line = new StringBuilder(columns);
            for (int column = 0; column < columns; column++) {
                double luminance = sampleLuminance(
                        pixels, pixelW, pixelH, column, row, columns, rows);
                // Trim near-black content into space and expand the useful
                // midtones so low-contrast photographs still read as shapes.
                double shaped = Math.max(0, Math.min(1, (luminance - 0.06) / 0.88));
                shaped = Math.pow(shaped, 0.88);
                int rampIndex = (int) Math.round(shaped * (ASCII_RAMP.length() - 1));
                line.append(ASCII_RAMP.charAt(rampIndex));
            }
            result.add(line.toString());
        }
        return new AsciiImage(columns, List.copyOf(result));
    }

    /** Four-point cell average avoids the noisy look of nearest-pixel sampling. */
    private static double sampleLuminance(
            PixelReader pixels, int pixelW, int pixelH,
            int column, int row, int columns, int rows) {
        double total = 0;
        for (int sy = 0; sy < 2; sy++) {
            for (int sx = 0; sx < 2; sx++) {
                double fx = (column + (sx + 0.5) / 2.0) / columns;
                double fy = (row + (sy + 0.5) / 2.0) / rows;
                int x = Math.min(pixelW - 1, Math.max(0, (int) (fx * pixelW)));
                int y = Math.min(pixelH - 1, Math.max(0, (int) (fy * pixelH)));
                Color color = pixels.getColor(x, y);
                double luminance = 0.2126 * color.getRed()
                        + 0.7152 * color.getGreen()
                        + 0.0722 * color.getBlue();
                total += luminance * color.getOpacity();
            }
        }
        return total / 4;
    }

    private static double unitHash(int value) {
        int mixed = value * 0x45d9f3b;
        mixed ^= mixed >>> 16;
        return (mixed & 0x7fffffff) / (double) Integer.MAX_VALUE;
    }

    private static String glyph(int seed, int column, int tail, long tick) {
        long mixed = seed + tick * 31 + column * 101L + tail * 17L;
        int index = Math.floorMod((int) (mixed ^ mixed >>> 13), GLYPHS.length());
        return GLYPHS.substring(index, index + 1);
    }

    private static final class Drop {
        private final double x;
        private double y;
        private double speed;
        private int trail;
        private int seed;

        private Drop(double x, double y, double speed, int trail, int seed) {
            this.x = x;
            this.y = y;
            this.speed = speed;
            this.trail = trail;
            this.seed = seed;
        }
    }

    private record AsciiImage(int columns, List<String> rows) { }
}
