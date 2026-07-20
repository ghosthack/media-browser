package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.StartupLayout;

import javafx.application.Platform;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The classic separate-windows shell: each view gets its own {@link Stage}
 * (the browser on the primary stage), raised and hidden independently, so the
 * mosaic and viewer can sit side by side and the Keep Focus / auto-open flows
 * work across windows again.
 *
 * <p>The view lifecycle rides the stage lifecycle: a window coming on screen
 * runs {@code activate()} (re-attach sources, restart timers, claim focus
 * within its scene) and a window hiding runs {@code deactivate()} (pause
 * playback, detach sources) — including on app close, so a playing video's
 * native resources are still released. {@link #back} hides the current
 * auxiliary window and refocuses the previous one; {@link #revealPassive}
 * shows a window without stealing keyboard focus by queueing a focus hand-back
 * to the window that had it (voided if something takes focus deliberately in
 * the meantime, mirroring the old generation-counter dance).</p>
 *
 * <p>The mosaic's and viewer's window geometry is captured when they hide and
 * re-applied before the next show; without this JavaFX re-derives the size
 * from the scene and re-centers the stage on every show. The multi-window
 * {@link StartupLayout} tilings (mosaic|viewer halves, browser|mosaic|viewer
 * thirds) apply here and only here.</p>
 */
final class MultiWindowShell extends AppShell {

    private final AppSettings settings;
    private final Map<AppView, Stage> stages = new EnumMap<>(AppView.class);
    /** Window geometry captured as an aux stage hides; re-applied before show. */
    private final Map<AppView, double[]> hiddenGeometry = new HashMap<>();
    private final ArrayDeque<AppView> backStack = new ArrayDeque<>();
    /** The view of the last deliberate switch; where {@link #back} starts from. */
    private AppView current;
    /**
     * Generation counter for the deferred focus hand-back queued by
     * {@link #revealPassive}: each hand-back only runs if it still matches, so
     * a deliberate {@link #showView} in the meantime voids the stale one.
     */
    private long passiveRevealSeq;
    private final boolean undecorated;

    MultiWindowShell(Stage primaryStage, AppSettings settings) {
        super(settings);
        this.settings = settings;
        this.undecorated = settings.undecoratedWindows();
        stages.put(AppView.BROWSER, primaryStage);
        stages.put(AppView.MOSAIC, new Stage());
        stages.put(AppView.VIEWER, new Stage());
        // Scenes exist up front (with placeholder roots swapped out by
        // register) so the views can wire scene-level listeners — the focus
        // bounces, the menu auto-hide — in their constructors.
        initStage(AppView.BROWSER, 1200, 760, "Media Browser");
        initStage(AppView.MOSAIC, 1100, 760, "Mosaic");
        initStage(AppView.VIEWER, 960, 700, "Viewer");
        Stage viewer = stages.get(AppView.VIEWER);
        // Escape is handled by the viewer's own key filter, where it can mean
        // "leave full screen" or "back to the opener".
        viewer.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        viewer.setFullScreenExitHint("");
    }

    private void initStage(AppView v, int width, int height, String title) {
        Stage stage = stages.get(v);
        var scene = new Scene(new StackPane(), width, height);
        // Paint the first frame black (the scene's default fill is white), so
        // there is no white flash before the view's content renders.
        scene.setFill(Color.BLACK);
        ThemeManager.get().register(scene);
        stage.setScene(scene);
        stage.setTitle(title);
        if (undecorated) {
            stage.initStyle(StageStyle.UNDECORATED);
            WindowChrome.installShellChrome(stage, settings.undecoratedResizable());
        }
    }

    @Override
    public void register(AppView v, ShellView view) {
        views.put(v, view);
        Stage stage = stages.get(v);
        stage.getScene().setRoot(view.root());
        stage.titleProperty().bind(view.titleProperty());
        // The view's lifecycle rides the window's: activate on show (any
        // source — showView, revealPassive, the OS restoring it), deactivate
        // on hide (✕, Escape paths, app close).
        stage.showingProperty().addListener((o, was, showing) -> {
            if (showing) view.activate();
            else view.deactivate();
        });
        // Track user-driven focus changes (clicking between windows, a passive
        // reveal later clicked into) in the back-stack, so back() from any
        // window returns to the one the user actually came from — not just the
        // last deliberate showView.
        stage.focusedProperty().addListener((o, was, focused) -> {
            if (focused && current != v) {
                if (current != null) {
                    backStack.remove(current);
                    backStack.push(current);
                }
                current = v;
            }
        });
        // Capture the geometry as the window hides (not in full screen, whose
        // geometry is the screen's), so the next show restores position/size.
        if (v != AppView.BROWSER) {
            stage.setOnHiding(e -> {
                if (!stage.isFullScreen()) {
                    hiddenGeometry.put(v, new double[] {
                            stage.getX(), stage.getY(),
                            stage.getWidth(), stage.getHeight()});
                }
            });
        }
        if (undecorated) {
            WindowChrome.addDragHandle(stage, view.toolBar());
            if (view.menuBar() != null) {
                WindowChrome.addDragHandle(stage, view.menuBar());
            }
            // Browser ✕ closes the app (its stage is the primary one); the
            // mosaic/viewer ✕ just hides that window, like the old windows did.
            WindowChrome.addCloseButton(view.toolBar(),
                    v == AppView.BROWSER ? stage::close : stage::hide);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also applies the multi-window {@link StartupLayout} tilings: mosaic
     * and viewer to screen halves, or browser, mosaic and viewer to screen
     * thirds (the viewer tiles the first time it appears, since it stays
     * hidden until an item opens).</p>
     */
    @Override
    public void start(AppView initial) {
        showView(initial);
        StartupLayout layout = settings.startupLayout();
        switch (layout) {
            case MOSAIC_VIEWER -> {
                Platform.runLater(() ->
                        maximizer().selectLeft(stages.get(AppView.MOSAIC)));
                armViewerStartupTile(() ->
                        maximizer().selectRight(stages.get(AppView.VIEWER)));
            }
            case BROWSER_MOSAIC_VIEWER -> {
                WindowChrome.showWithoutFlash(stages.get(AppView.BROWSER));
                Platform.runLater(() -> {
                    maximizer().selectLeftThird(stages.get(AppView.BROWSER));
                    maximizer().selectCenterThird(stages.get(AppView.MOSAIC));
                });
                armViewerStartupTile(() ->
                        maximizer().selectRightThird(stages.get(AppView.VIEWER)));
            }
            default -> { }   // BROWSER / MOSAIC: a single window, nothing to tile
        }
    }

    /**
     * Runs {@code tile} the first time the viewer window appears this session,
     * via a one-shot listener that disarms once it fires. The viewer is hidden
     * on startup until the first item opens, so its startup tile cannot be
     * applied up front.
     */
    private void armViewerStartupTile(Runnable tile) {
        ReadOnlyBooleanProperty showing = stages.get(AppView.VIEWER).showingProperty();
        showing.addListener(new ChangeListener<>() {
            @Override public void changed(ObservableValue<? extends Boolean> obs,
                                          Boolean was, Boolean now) {
                if (now) {
                    showing.removeListener(this);
                    tile.run();
                }
            }
        });
    }

    @Override
    public void showView(AppView v) {
        passiveRevealSeq++;   // a deliberate switch voids any pending hand-back
        if (current != null && current != v) {
            // Dedupe so hopping between two windows never grows the stack.
            backStack.remove(current);
            backStack.push(current);
        }
        current = v;
        Stage stage = stages.get(v);
        restoreGeometry(v, stage);
        WindowChrome.showWithoutFlash(stage);
        stage.toFront();
        stage.requestFocus();
        ShellView view = views.get(v);
        if (view != null) view.activate();
    }

    @Override
    public void back(AppView from) {
        AppView target = backStack.isEmpty() ? AppView.BROWSER : backStack.pop();
        if (target == from) target = AppView.BROWSER;
        current = target;
        passiveRevealSeq++;
        Stage to = stages.get(target);
        // Reveal the target first (it may never have been shown — e.g. the
        // browser when the app started straight in the mosaic), then hide the
        // window we are leaving; the browser only ever recedes, never hides.
        restoreGeometry(target, to);
        WindowChrome.showWithoutFlash(to);
        to.toFront();
        to.requestFocus();
        if (from != AppView.BROWSER && from != target) {
            stages.get(from).hide();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Shows the window if hidden and queues a focus hand-back to the window
     * that currently has it, so e.g. the mosaic's auto-open keeps the grid
     * navigable while the viewer updates alongside. Always returns true — a
     * separate window can always be shown passively.</p>
     */
    @Override
    public boolean revealPassive(AppView v) {
        Stage stage = stages.get(v);
        if (stage.isShowing()) return true;
        long seq = ++passiveRevealSeq;
        Stage refocus = focusedStage();
        restoreGeometry(v, stage);
        WindowChrome.showWithoutFlash(stage);
        // Showing a hidden stage activates it asynchronously; queue the focus
        // hand-back so it lands after that activation settles. A later
        // showView (e.g. deliberately switching to this window) bumps the
        // generation so a stale hand-back becomes a no-op.
        Platform.runLater(() -> {
            if (seq == passiveRevealSeq && refocus != null) refocus.requestFocus();
        });
        return true;
    }

    /** The stage currently holding focus, or the current view's as a fallback. */
    private Stage focusedStage() {
        for (Stage s : stages.values()) {
            if (s.isFocused()) return s;
        }
        return current != null ? stages.get(current) : stages.get(AppView.BROWSER);
    }

    private void restoreGeometry(AppView v, Stage stage) {
        double[] g = hiddenGeometry.get(v);
        if (g != null && !stage.isShowing()) {
            stage.setX(g[0]);
            stage.setY(g[1]);
            stage.setWidth(g[2]);
            stage.setHeight(g[3]);
        }
    }

    @Override
    public boolean singleWindow() {
        return false;
    }

    /**
     * "Active" across separate windows means "holds window focus" — the OS
     * arbitrates it, exactly as the old per-window {@code focusedProperty}
     * bindings did.
     */
    @Override
    public BooleanExpression isActive(AppView v) {
        return BooleanExpression.booleanExpression(stages.get(v).focusedProperty());
    }

    @Override
    public boolean isActiveNow(AppView v) {
        return stages.get(v).isFocused();
    }

    @Override
    public BooleanExpression isShowing(AppView v) {
        return BooleanExpression.booleanExpression(stages.get(v).showingProperty());
    }

    @Override
    public boolean isShowingNow(AppView v) {
        return stages.get(v).isShowing();
    }

    @Override
    public Stage stageFor(AppView v) {
        return stages.get(v);
    }

    /** Full screen is the viewer's affair; its stage carries the state. */
    @Override
    void setFullScreen(boolean on) {
        stages.get(AppView.VIEWER).setFullScreen(on);
    }

    @Override
    boolean isFullScreen() {
        return stages.get(AppView.VIEWER).isFullScreen();
    }

    @Override
    ReadOnlyBooleanProperty fullScreenProperty() {
        return stages.get(AppView.VIEWER).fullScreenProperty();
    }
}
