package io.github.ghosthack.mediabrowser.ui;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javafx.animation.AnimationTimer;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The single maximize/restore <em>and</em> half-screen tiling mechanism shared
 * by every window. Snaps a window to the screen's available area (the screen
 * minus the menu bar and dock) or to its left/right half, remembering the
 * pre-snap geometry so toggling the same target again restores it. State is
 * tracked per {@link Stage}, so each window maximizes, tiles and restores
 * independently through the same Window ▸ Maximize&nbsp;/&nbsp;Restore and
 * Tile&nbsp;Left&nbsp;/&nbsp;Right actions.
 *
 * <p>The pre-snap geometry is captured once on the first move away from the
 * unsnapped state, so hopping between targets (e.g. left half → maximize →
 * right half) never overwrites it — only re-selecting the current target
 * restores it. Callers that layer extra behaviour on the maximized state (e.g.
 * the viewer hiding unpinned chrome for its focus mode) pass an
 * {@code onMaximize} hook (run when the window enters the maximized state) and
 * an {@code onRestore}/{@code onLeaveMaximize} hook (run whenever it leaves it,
 * whether by un-maximizing or by tiling to a half). Undecorated windows can
 * optionally overscan the screen edges to push their hairline border
 * off-screen on a full maximize; the half tiles stay flush so the seam between
 * two tiled windows keeps a visible edge.</p>
 */
final class WindowMaximizer {

    /**
     * Pixels a maximize overshoots the visual bounds on every edge, pushing an
     * undecorated window's hairline border just off-screen. The sides can only
     * overscan once {@link MacWindowFrame#ensureUnconstrained} removes Glass's
     * horizontal screen-constraint; otherwise they stay flush.
     */
    private static final double OVERSCAN = 2;

    /** Where a window is currently snapped, driving the toggle-to-restore logic. */
    private enum Mode { NONE, MAXIMIZED, LEFT, RIGHT, LEFT_THIRD, CENTER_THIRD, RIGHT_THIRD }

    /** Pre-snap geometry and the current snap mode for one stage. */
    private static final class State {
        double x, y, width, height;
        Mode mode = Mode.NONE;
    }

    /** Whether maximize overscans the screen edges for undecorated windows. */
    private final boolean overscan;
    private final Map<Stage, State> states = new IdentityHashMap<>();

    WindowMaximizer(boolean overscan) {
        this.overscan = overscan;
    }

    /** Maximizes or restores {@code stage} with no extra per-transition hooks. */
    void toggle(Stage stage) {
        toggle(stage, null, null);
    }

    /**
     * Maximizes {@code stage} to the visual bounds of the screen it sits on, or
     * restores its remembered geometry if it is already maximized. The
     * pre-snap geometry is captured only when moving away from the unsnapped
     * state, so selecting maximize again while already maximized restores rather
     * than clobbering the saved size. {@code onMaximize} runs after the window
     * is sized up and {@code onRestore} after it leaves the maximized state
     * (either may be {@code null}). No-op in full-screen mode, which already
     * fills the screen.
     */
    void toggle(Stage stage, Runnable onMaximize, Runnable onRestore) {
        apply(stage, Mode.MAXIMIZED, onMaximize, onRestore);
    }

    /** Tiles {@code stage} to the left half of its screen (or restores). */
    void snapLeft(Stage stage) {
        apply(stage, Mode.LEFT, null, null);
    }

    /**
     * Tiles {@code stage} to the left half, running {@code onLeaveMaximize} if it
     * was maximized (so a focus-mode window restores its hidden chrome as it
     * tiles). Re-selecting the left half restores the pre-snap geometry.
     */
    void snapLeft(Stage stage, Runnable onLeaveMaximize) {
        apply(stage, Mode.LEFT, null, onLeaveMaximize);
    }

    /** Tiles {@code stage} to the right half of its screen (or restores). */
    void snapRight(Stage stage) {
        apply(stage, Mode.RIGHT, null, null);
    }

    /** Right-half counterpart of {@link #snapLeft(Stage, Runnable)}. */
    void snapRight(Stage stage, Runnable onLeaveMaximize) {
        apply(stage, Mode.RIGHT, null, onLeaveMaximize);
    }

    /**
     * Tiles {@code stage} to the left half, always (never toggles back off when
     * it is already there). Used to force a startup layout, where selecting a
     * fixed side — rather than toggling the current one — is what is wanted.
     */
    void selectLeft(Stage stage) {
        select(stage, Mode.LEFT);
    }

    /** Non-toggling right-half counterpart of {@link #selectLeft(Stage)}. */
    void selectRight(Stage stage) {
        select(stage, Mode.RIGHT);
    }

    /** Forces {@code stage} to the screen's left third (three-panel layout). */
    void selectLeftThird(Stage stage) {
        select(stage, Mode.LEFT_THIRD);
    }

    /** Forces {@code stage} to the screen's centre third (three-panel layout). */
    void selectCenterThird(Stage stage) {
        select(stage, Mode.CENTER_THIRD);
    }

    /** Forces {@code stage} to the screen's right third (three-panel layout). */
    void selectRightThird(Stage stage) {
        select(stage, Mode.RIGHT_THIRD);
    }

    /**
     * Toggling drive: pushes {@code stage} into {@code target}, or — if it is
     * already there — back to its remembered geometry. The pre-snap geometry is
     * captured once on the first move away from {@link Mode#NONE}, so hopping
     * between targets keeps the original size for the eventual restore.
     * {@code onEnterMax} fires when the window enters the maximized state and
     * {@code onLeaveMax} whenever it leaves it (either may be {@code null}).
     * No-op in full-screen mode.
     */
    private void apply(Stage stage, Mode target, Runnable onEnterMax, Runnable onLeaveMax) {
        drive(stage, target, true, onEnterMax, onLeaveMax);
    }

    /**
     * Non-toggling counterpart of {@link #apply}: drives {@code stage} into
     * {@code target} and leaves it there even if it is already in that mode (no
     * toggle-back-to-restore). Selecting a window's fixed side on startup must
     * not depend on its current snap state.
     */
    private void select(Stage stage, Mode target) {
        drive(stage, target, false, null, null);
    }

    private void drive(Stage stage, Mode target, boolean toggle,
                       Runnable onEnterMax, Runnable onLeaveMax) {
        if (stage.isFullScreen()) return;
        State s = states.computeIfAbsent(stage, k -> new State());
        Mode prev = s.mode;
        if (prev == target) {
            // Already in the target mode. A toggle restores the pre-snap
            // geometry; a (non-toggling) select is idempotent and leaves it.
            if (!toggle) return;
            restoreGeometry(stage, s);
            s.mode = Mode.NONE;
            if (prev == Mode.MAXIMIZED && onLeaveMax != null) onLeaveMax.run();
            return;
        }
        // Capture the un-snapped geometry once; subsequent target hops leave it
        // alone, so the eventual restore returns to the original size.
        if (prev == Mode.NONE) {
            s.x = stage.getX();
            s.y = stage.getY();
            s.width = stage.getWidth();
            s.height = stage.getHeight();
        }
        Rectangle2D vb = currentScreen(stage).getVisualBounds();
        switch (target) {
            case MAXIMIZED -> maximizeTo(stage, vb);
            case LEFT -> snapTo(stage, leftHalf(vb));
            case RIGHT -> snapTo(stage, rightHalf(vb));
            case LEFT_THIRD -> snapTo(stage, leftThird(vb));
            case CENTER_THIRD -> snapTo(stage, centerThird(vb));
            case RIGHT_THIRD -> snapTo(stage, rightThird(vb));
            case NONE -> { }
        }
        s.mode = target;
        if (prev == Mode.MAXIMIZED && onLeaveMax != null) onLeaveMax.run();
        if (target == Mode.MAXIMIZED && onEnterMax != null) onEnterMax.run();
    }

    /** Restores the remembered pre-snap geometry. */
    private static void restoreGeometry(Stage stage, State s) {
        stage.setX(s.x);
        stage.setY(s.y);
        stage.setWidth(s.width);
        stage.setHeight(s.height);
    }

    /** The left half of {@code b}. */
    private static Rectangle2D leftHalf(Rectangle2D b) {
        return new Rectangle2D(b.getMinX(), b.getMinY(), b.getWidth() / 2, b.getHeight());
    }

    /** The right half of {@code b}, reaching the exact right edge on odd widths. */
    private static Rectangle2D rightHalf(Rectangle2D b) {
        double half = b.getWidth() / 2;
        return new Rectangle2D(b.getMaxX() - half, b.getMinY(), half, b.getHeight());
    }

    /** The left third of {@code b}. */
    private static Rectangle2D leftThird(Rectangle2D b) {
        return new Rectangle2D(b.getMinX(), b.getMinY(), b.getWidth() / 3, b.getHeight());
    }

    /** The centre third of {@code b}, flush against the left and right thirds. */
    private static Rectangle2D centerThird(Rectangle2D b) {
        double third = b.getWidth() / 3;
        return new Rectangle2D(b.getMinX() + third, b.getMinY(), third, b.getHeight());
    }

    /** The right third of {@code b}, reaching the exact right edge on odd widths. */
    private static Rectangle2D rightThird(Rectangle2D b) {
        double third = b.getWidth() / 3;
        return new Rectangle2D(b.getMaxX() - third, b.getMinY(), third, b.getHeight());
    }

    /**
     * Sizes {@code stage} flush to {@code bounds}. Unlike a full maximize the
     * half tiles stay within the screen, so there is no horizontal constraint to
     * lift and no overscan: the seam between two tiled windows keeps its edge.
     */
    private static void snapTo(Stage stage, Rectangle2D bounds) {
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
    }

    /** Sizes {@code stage} to {@code bounds}, overscanning the edges if enabled. */
    private void maximizeTo(Stage stage, Rectangle2D bounds) {
        double over = overscan && stage.getStyle() == StageStyle.UNDECORATED
                ? OVERSCAN : 0;
        // Disable the native horizontal screen-constraint first, so the
        // oversized bounds below aren't clamped back inside the screen width.
        // Falls back to flush sides if the native call is unavailable.
        boolean canOverscan = over > 0 && MacWindowFrame.ensureUnconstrained(stage);
        double sideOver = canOverscan ? over : 0;
        stage.setX(bounds.getMinX() - sideOver);
        stage.setY(bounds.getMinY() - over);
        stage.setWidth(bounds.getWidth() + 2 * sideOver);
        stage.setHeight(bounds.getHeight() + 2 * over);
        if (canOverscan) assertOverscan(stage, bounds, over);
    }

    /**
     * Re-asserts the overscanned frame each pulse until Glass stops clobbering
     * it. Glass applies its (clamped) frame asynchronously and can override a
     * native setFrame for a pulse or two, so the window is overscanned
     * immediately instead of after a fixed wait.
     */
    private static void assertOverscan(Stage stage, Rectangle2D bounds, double over) {
        double tx = bounds.getMinX() - over;
        double tw = bounds.getWidth() + 2 * over;
        new AnimationTimer() {
            int asserts;
            @Override public void handle(long now) {
                double[] f = MacWindowFrame.nativeFrame(stage);
                boolean settled = f != null
                        && Math.abs(f[0] - tx) < 1 && Math.abs(f[2] - tw) < 1;
                if ((settled && asserts >= 1) || asserts > 30) {
                    stop();
                    return;
                }
                MacWindowFrame.overscanFrame(stage, bounds.getMinX(), bounds.getMinY(),
                        bounds.getWidth(), bounds.getHeight(), over);
                asserts++;
            }
        }.start();
    }

    /** The screen the window currently sits on, falling back to the primary one. */
    private static Screen currentScreen(Stage stage) {
        List<Screen> screens = Screen.getScreensForRectangle(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }
}
