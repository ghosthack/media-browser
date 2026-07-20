package io.github.ghosthack.mediabrowser.ui;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Group;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * The {@code Game Console} loading indicator: a small overlay in the
 * viewport's bottom-left corner styled after the 2000s CD-console "Now
 * loading …" screens. Three stacked elements, each with its own drop shadow
 * that fades to transparent:
 *
 * <ul>
 *   <li>a spinning CD — a radial-gradient disc whose iridescent tint and two
 *       white sheen wedges rotate continuously (an explicit {@link Rotate}
 *       about the disc centre, so the spin pivots correctly regardless of the
 *       wedges' own bounds), clipped to the disc circle;</li>
 *   <li>a red banner {@link Polygon} that tucks behind the disc (so it "touches"
 *       the CD) and casts its drop shadow across it — the background plate for
 *       the label;</li>
 *   <li>the white "Now loading ..." {@link Text}, drawn on the banner with a
 *       black drop shadow and a pale highlight band that sweeps across the
 *       glyphs left-to-right on a seamless loop.</li>
 * </ul>
 *
 * <p>The node is mouse-transparent (clicks fall through to the viewport) and
 * fixed-size, anchored bottom-left by the viewport StackPane. {@link #start()}
 * shows it and runs the spin + shimmer; {@link #stop()} dissolves it over one
 * second rather than hiding it at once, so a fast/cached decode shows the
 * overlay briefly and fades out instead of flashing on and off in a single
 * frame. Only when the fade lands at zero opacity are the spin Timeline and the
 * shimmer {@link AnimationTimer} parked, so an idle viewer pays nothing.</p>
 */
final class GameConsoleLoadingOverlay extends Region {

    // Layout (local coordinates of this fixed-size node). The disc sits low and
    // left; the banner runs right from behind it; everything keeps a margin for
    // the drop shadows so they are not clipped by the node bounds.
    private static final double W = 300, H = 112;
    private static final double R = 34;                 // disc radius
    private static final double CX = 52, CY = H - 16 - R; // disc centre (= 62)

    /** Per-sweep duration of the disc spin. */
    private static final Duration SPIN_PERIOD = Duration.seconds(1.4);
    /**
     * How long the overlay takes to dissolve when a load finishes. A fade
     * (rather than an instant hide) keeps a fast/cached decode from flashing the
     * overlay on and off in a single frame — it always lingers for this long,
     * spinning, as it fades to transparent.
     */
    private static final Duration FADE_OUT = Duration.seconds(1);
    /** Per-sweep duration of the text highlight band, in seconds. */
    private static final double SHIMMER_PERIOD = 1.6;
    /** Width of the moving highlight band, as a fraction of the label width. */
    private static final double SHIMMER_BAND = 0.45;

    private final Text label = new Text("Now loading ...");
    private final Rotate spin = new Rotate(0, CX, CY);
    private final Timeline spinTimeline;
    private final AnimationTimer shimmer;
    private long shimmerStartNanos;
    /** The 1s dissolve run on {@link #stop()}; only on finish are the node and its animations parked. */
    private final FadeTransition fadeOut;

    GameConsoleLoadingOverlay() {
        setMinSize(W, H);
        setPrefSize(W, H);
        setMaxSize(W, H);
        // An indicator must never steal clicks from the viewport beneath it.
        setMouseTransparent(true);
        setVisible(false);

        getChildren().addAll(buildBanner(), buildDisc(), buildLabel());

        spinTimeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(spin.angleProperty(), 0)),
                new KeyFrame(SPIN_PERIOD,
                        new KeyValue(spin.angleProperty(), 360, Interpolator.LINEAR)));
        spinTimeline.setCycleCount(Animation.INDEFINITE);

        shimmer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (shimmerStartNanos == 0) shimmerStartNanos = now;
                double t = (now - shimmerStartNanos) / 1_000_000_000.0;
                double phase = (t % SHIMMER_PERIOD) / SHIMMER_PERIOD; // 0..1
                label.setFill(shimmerFill(phase));
            }
        };

        // Spin and shimmer keep running through the fade; only when it lands at
        // zero opacity is the node hidden and the animations parked.
        fadeOut = new FadeTransition(FADE_OUT, this);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> {
            setVisible(false);
            spinTimeline.pause();
            shimmer.stop();
        });
    }

    /**
     * Shows the overlay at full opacity and (re)starts the spin + shimmer
     * animations. Cancels any in-progress fade-out, so navigating again while the
     * previous indicator is dissolving snaps it straight back to fully visible.
     */
    void start() {
        fadeOut.stop();
        setOpacity(1);
        setVisible(true);
        spinTimeline.play();
        shimmerStartNanos = 0;
        shimmer.start();
    }

    /**
     * Dismisses the overlay by fading it to transparent over {@link #FADE_OUT}
     * rather than hiding it at once, so a fast/cached load does not flash it on
     * and off. Idempotent: a no-op once hidden or already fading.
     */
    void stop() {
        if (!isVisible()) return;
        if (fadeOut.getStatus() == Animation.Status.RUNNING) return;
        fadeOut.playFromStart();
    }

    // --- element construction -------------------------------------------------

    /**
     * The red banner: a forward-leaning parallelogram whose left end runs behind
     * the disc (drawn first, so the disc paints over it — it "touches" the CD)
     * and whose drop shadow falls across the disc and the background.
     */
    private Polygon buildBanner() {
        double top = CY - 16, bottom = CY + 16, slant = 12;
        double leftBottom = 42, leftTop = leftBottom + slant;
        double rightTop = 272, rightBottom = rightTop - slant;
        var banner = new Polygon(
                leftTop, top,
                rightTop, top,
                rightBottom, bottom,
                leftBottom, bottom);
        banner.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#ef2d20")),
                new Stop(0.5, Color.web("#cf1610")),
                new Stop(1, Color.web("#9c0c07"))));
        banner.setStroke(Color.web("#6f0703"));
        banner.setStrokeWidth(1);
        banner.setEffect(softShadow(10, 3, 4, 0.55));
        return banner;
    }

    /**
     * The spinning CD. The base disc and the hub/hole are static; the iridescent
     * tint wedges and two white sheen wedges live in a clipped, rotating group so
     * the reflection sweeps around the disc. The drop shadow sits on the opaque
     * base circle, giving a clean circular shadow rather than shadowing the
     * translucent wedges.
     */
    private Group buildDisc() {
        var base = new Circle(CX, CY, R, new RadialGradient(0, 0, CX, CY, R, false,
                CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#dfe3ea")),
                new Stop(0.55, Color.web("#888d98")),
                new Stop(0.85, Color.web("#34373f")),
                new Stop(1, Color.web("#15171b"))));
        base.setStroke(Color.web("#ffffff", 0.18));
        base.setStrokeWidth(1);
        base.setEffect(softShadow(12, 3, 4, 0.55));

        // Rotating iridescence: three wide tint wedges spanning the disc plus two
        // narrow opposed white sheen wedges, clipped to the disc circle.
        var spinGroup = new Group(
                wedge(0, 120, Color.web("#00e5ff", 0.16)),
                wedge(120, 120, Color.web("#ff3bd0", 0.16)),
                wedge(240, 120, Color.web("#ffe14d", 0.16)),
                wedge(28, 26, Color.web("#ffffff", 0.42)),
                wedge(208, 22, Color.web("#ffffff", 0.32)));
        spinGroup.setClip(new Circle(CX, CY, R - 0.5));
        spinGroup.getTransforms().add(spin);

        // The clear inner ring and the silver hub clamp around the centre hole.
        var clearRing = new Circle(CX, CY, R * 0.34);
        clearRing.setFill(Color.TRANSPARENT);
        clearRing.setStroke(Color.web("#ffffff", 0.22));
        clearRing.setStrokeWidth(2);
        var hub = new Circle(CX, CY, R * 0.26, new RadialGradient(0, 0, CX, CY, R * 0.26,
                false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#d3d7df")),
                new Stop(1, Color.web("#6a6e77"))));
        hub.setStroke(Color.web("#3a3d44"));
        hub.setStrokeWidth(0.75);
        var hole = new Circle(CX, CY, R * 0.12, Color.web("#1b1d22"));

        return new Group(base, spinGroup, clearRing, hub, hole);
    }

    /** A pie wedge from the disc centre used to fake the CD's iridescent sheen. */
    private Arc wedge(double startAngle, double length, Color fill) {
        var arc = new Arc(CX, CY, R, R, startAngle, length);
        arc.setType(ArcType.ROUND);
        arc.setFill(fill);
        return arc;
    }

    /** The "Now loading ..." label: white, black drop shadow, animated shimmer. */
    private Text buildLabel() {
        label.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        label.setFill(Color.WHITE);
        label.setLayoutX(96);
        label.setLayoutY(CY + 5); // text baseline, vertically centred on the banner
        label.setEffect(softShadow(3, 1, 1, 0.9));
        return label;
    }

    // --- shimmer --------------------------------------------------------------

    /**
     * A horizontal gradient over the label whose bright band is centred at the
     * given phase and tiles via {@link CycleMethod#REPEAT}, so animating the
     * phase sweeps the highlight across the glyphs on a seamless loop.
     */
    private LinearGradient shimmerFill(double phase) {
        double off = phase * SHIMMER_BAND; // 0..band; periodic, so it wraps cleanly
        return new LinearGradient(off, 0, off + SHIMMER_BAND, 0, true, CycleMethod.REPEAT,
                new Stop(0, Color.WHITE),
                new Stop(0.5, Color.web("#9fe8ff")),
                new Stop(1, Color.WHITE));
    }

    // --- shared ---------------------------------------------------------------

    /** A black drop shadow that fades to transparent (shared by all elements). */
    private static DropShadow softShadow(double radius, double dx, double dy, double opacity) {
        var shadow = new DropShadow(radius, dx, dy, Color.web("#000000", opacity));
        return shadow;
    }
}
