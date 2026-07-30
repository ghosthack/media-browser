package io.github.ghosthack.mediabrowser.ui;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * A grayscale ASCII-matrix loading indicator. Short glyph streams travel
 * horizontally across staggered rows, with bright heads and charcoal tails,
 * above a compact loading label. The Black Label style keeps the shared
 * terminal plate while additionally backing every glyph run from the left edge
 * through its characters, fading beyond the run's leading edge. The Now
 * Loading fork retains only Black Label's final animated copy strip.
 *
 * <p>The fixed-size, mouse-transparent node is anchored in the same bottom-left
 * viewport corner as {@link GameConsoleLoadingOverlay}. {@link #stop()} keeps
 * the animation alive through the same one-second dissolve before parking it,
 * avoiding a flash when a load finishes soon after the indicator appears.</p>
 */
final class AsciiMatrixLoadingOverlay extends Region {

    enum Style {
        TERMINAL_PLATE("LOADING ...", false, true),
        BLACK_LABEL("NOW LOADING...", true, true),
        NOW_LOADING("NOW LOADING...", true, false);

        private final String label;
        private final boolean blackSequenceStrips;
        private final boolean glyphRain;

        Style(String label, boolean blackSequenceStrips, boolean glyphRain) {
            this.label = label;
            this.blackSequenceStrips = blackSequenceStrips;
            this.glyphRain = glyphRain;
        }

        String label() {
            return label;
        }

        boolean blackSequenceStrips() {
            return blackSequenceStrips;
        }

        boolean glyphRain() {
            return glyphRain;
        }
    }

    private static final double W = 300;
    private static final double H = 112;
    private static final double RIGHT_FADE_START = W * 0.68;
    private static final double GLYPH_ADVANCE = 11;
    private static final double SEQUENCE_FADE_MARGIN = 18;
    private static final double SEQUENCE_PLATE_HEIGHT = 25;
    private static final int TRAIL_LENGTH = 10;
    private static final String GLYPHS = "01<>[]{}#@$%&*+=:/\\\\|-";
    private static final Color TRANSPARENT_BLACK = Color.color(0, 0, 0, 0);
    private static final Duration FADE_OUT = Duration.seconds(1);
    private static final Font GLYPH_FONT = Font.font("Monospaced", FontWeight.BOLD, 13);
    /** Matches the regular system font used for extension glyphs in mosaic tiles. */
    private static final Font LABEL_FONT = Font.font(15);

    /** Row baselines, start offsets and speeds give the streams an irregular cadence. */
    private static final double[] ROW_Y = {20, 36, 52, 68, 28, 60};
    private static final double[] OFFSETS = {0, 126, 54, 214, 248, 154};
    private static final double[] SPEEDS = {72, 93, 61, 108, 79, 88};

    private final Canvas canvas = new Canvas(W, H);
    private final AnimationTimer animationTimer;
    private final FadeTransition fadeOut;
    private final Style style;
    private long startNanos;

    AsciiMatrixLoadingOverlay() {
        this(Style.TERMINAL_PLATE);
    }

    AsciiMatrixLoadingOverlay(Style style) {
        this.style = style;

        setMinSize(W, H);
        setPrefSize(W, H);
        setMaxSize(W, H);
        setMouseTransparent(true);
        setVisible(false);
        setAccessibleText(style.label);
        getChildren().add(canvas);

        animationTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (startNanos == 0) startNanos = now;
                draw((now - startNanos) / 1_000_000_000.0);
            }
        };

        fadeOut = new FadeTransition(FADE_OUT, this);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> {
            setVisible(false);
            animationTimer.stop();
        });
    }

    /** Shows the overlay at full opacity and restarts its animation. */
    void start() {
        fadeOut.stop();
        setOpacity(1);
        setVisible(true);
        startNanos = 0;
        animationTimer.start();
    }

    /** Dissolves the overlay for one second, then parks its animation timer. */
    void stop() {
        if (!isVisible()) return;
        if (fadeOut.getStatus() == Animation.Status.RUNNING) return;
        fadeOut.playFromStart();
    }

    private void draw(double elapsedSeconds) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, W, H);

        if (style.glyphRain) {
            // Rain-bearing styles share the dark-glass terminal treatment.
            // Black Label lowers only its top edge to the first sequence's
            // highlight; the parent's bottom edge stays fixed.
            double terminalY = style.blackSequenceStrips
                    ? ROW_Y[0] - SEQUENCE_PLATE_HEIGHT / 2.0
                    : 4;
            double terminalHeight = H - 4 - terminalY;
            g.setFill(horizontalFade(Color.gray(0.025, 0.82)));
            g.fillRoundRect(4, terminalY, W - 8, terminalHeight, 8, 8);
            if (style.blackSequenceStrips) {
                // Complete the inherited plate to x=0: square left corners sit
                // flush while the parent's right edge stays soft.
                g.fillRect(0, terminalY, 12, terminalHeight);
            }

            g.setStroke(horizontalFade(Color.gray(0.62, 0.08)));
            g.setLineWidth(0.5);
            double scanLeft = style.blackSequenceStrips ? 0 : 9;
            double scanTop = style.blackSequenceStrips ? terminalY + 3.5 : 11.5;
            double scanBottom = terminalY + terminalHeight - 4;
            for (double y = scanTop; y < scanBottom; y += 4) {
                g.strokeLine(scanLeft, y, W - 9, y);
            }

            g.setFont(GLYPH_FONT);
            g.setTextBaseline(VPos.CENTER);
            double travel = W + TRAIL_LENGTH * GLYPH_ADVANCE + 24;
            long glyphTick = (long) (elapsedSeconds * 11);
            if (style.blackSequenceStrips) {
                // Back every moving run independently. Black stays solid from
                // the left edge through its glyphs, then feathers past the head.
                for (int stream = 0; stream < ROW_Y.length; stream++) {
                    double head = streamHead(elapsedSeconds, travel, stream);
                    drawLeftAnchoredBlackPlate(
                            g,
                            head + GLYPH_ADVANCE,
                            ROW_Y[stream],
                            SEQUENCE_PLATE_HEIGHT);
                }
            }
            for (int stream = 0; stream < ROW_Y.length; stream++) {
                double head = streamHead(elapsedSeconds, travel, stream);
                for (int tail = TRAIL_LENGTH - 1; tail >= 0; tail--) {
                    double x = head - tail * GLYPH_ADVANCE;
                    if (x < 10 || x > W - 18) continue;
                    double strength = 1.0 - tail / (double) TRAIL_LENGTH;
                    double gray = 0.30 + 0.70 * strength;
                    double alpha = (0.12 + 0.86 * strength) * rightFadeAt(x);
                    g.setFill(Color.gray(gray, alpha));
                    g.fillText(glyph(stream, tail, glyphTick), x, ROW_Y[stream]);
                }
            }
        }

        // The soft pulse is grayscale and deliberately subtle so the text
        // remains easy to read without visually separating it from the rain.
        double pulse = 0.72 + 0.18 * (0.5 + 0.5 * Math.sin(elapsedSeconds * Math.PI * 2 / 1.6));
        double markerX = 176 + (elapsedSeconds * 34 % 96);
        g.setFont(LABEL_FONT);
        g.setTextBaseline(VPos.CENTER);
        if (style.blackSequenceStrips) {
            // The label row stays black through its traveling white square,
            // then dissolves into the current image beyond the square.
            drawLeftAnchoredBlackPlate(
                    g, markerX + 7, 92.5, SEQUENCE_PLATE_HEIGHT);
        }
        g.setFill(horizontalFade(Color.gray(pulse)));
        g.fillText(style.label, 16, 94);

        // A small traveling block echoes the stream direction without changing
        // the loading copy itself.
        g.setFill(Color.gray(0.92, 0.78 * rightFadeAt(markerX + 3.5)));
        g.fillRect(markerX, 90, 7, 8);
    }

    private static double streamHead(
            double elapsedSeconds, double travel, int stream) {
        return (elapsedSeconds * SPEEDS[stream] + OFFSETS[stream]) % travel
                - TRAIL_LENGTH * GLYPH_ADVANCE;
    }

    /**
     * Draws solid black from the overlay's left edge through a sequence's
     * leading edge, followed by a transparent-black fade margin.
     */
    private static void drawLeftAnchoredBlackPlate(
            GraphicsContext graphics,
            double sequenceRight,
            double centerY, double height) {
        double plateRight = sequenceRight + SEQUENCE_FADE_MARGIN;
        if (plateRight <= 0) return;

        double solidStop = Math.clamp(sequenceRight / plateRight, 0, 1);
        graphics.setFill(new LinearGradient(
                0, 0, plateRight, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.BLACK),
                new Stop(solidStop, Color.BLACK),
                new Stop(1, TRANSPARENT_BLACK)));
        graphics.fillRect(0, centerY - height / 2.0, plateRight, height);
    }

    /** A paint whose alpha falls from the supplied color to zero at the right edge. */
    private static LinearGradient horizontalFade(Color color) {
        return new LinearGradient(RIGHT_FADE_START, 0, W, 0, false,
                CycleMethod.NO_CYCLE,
                new Stop(0, color),
                new Stop(1, Color.color(
                        color.getRed(), color.getGreen(), color.getBlue(), 0)));
    }

    /** Alpha multiplier shared by discrete glyphs and the traveling marker. */
    private static double rightFadeAt(double x) {
        if (x <= RIGHT_FADE_START) return 1;
        return Math.max(0, (W - x) / (W - RIGHT_FADE_START));
    }

    /**
     * A deterministic hash makes glyphs flicker without allocating strings or
     * maintaining mutable random state on every animation frame.
     */
    private static String glyph(int stream, int tail, long tick) {
        long mixed = tick * 31 + stream * 101L + tail * 17L;
        int index = Math.floorMod((int) (mixed ^ mixed >>> 13), GLYPHS.length());
        return GLYPHS.substring(index, index + 1);
    }
}
