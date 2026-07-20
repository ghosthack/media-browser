package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayDeque;
import java.util.EnumMap;

/**
 * The single-window shell. Owns the one {@link Stage} and {@link Scene} and
 * swaps the scene root between the three registered views, so exactly one
 * view is live at a time. Because the inactive views' node trees are detached
 * from the scene, their key filters and menu accelerators simply don't fire;
 * no per-key "am I the active view" gating is needed anywhere.
 *
 * <p>Navigation is a small back-stack: {@link #showView} pushes the view it
 * replaces, {@link #back} pops (falling back to the browser). Escape unwinds
 * only from the viewer; leaving the mosaic goes through the menu bar
 * (Window ▸ Browser / Mosaic ▸ Close Mosaic). Switching
 * away from the viewer leaves full-screen first, mirroring the separate-window
 * behaviour where Escape exited full-screen before hiding the viewer stage.</p>
 *
 * <p>The shell also centralizes what used to be per-stage plumbing: the shared
 * theme registration, the undecorated-window chrome (edge resize, drag
 * handles, per-view ✕ button), and the stage title, which is bound to the
 * active view's {@code titleProperty}.</p>
 */
final class SingleWindowShell extends AppShell {

    private final Stage stage;
    private final Scene scene;
    private final boolean undecorated;
    private final ArrayDeque<AppView> backStack = new ArrayDeque<>();
    private final ReadOnlyObjectWrapper<AppView> activeView =
            new ReadOnlyObjectWrapper<>(null);
    private final EnumMap<AppView, BooleanExpression> actives =
            new EnumMap<>(AppView.class);

    SingleWindowShell(Stage stage, AppSettings settings) {
        super(settings);
        this.stage = stage;
        this.undecorated = settings.undecoratedWindows();
        // Black fill so a root swap never flashes white while the incoming
        // view runs its first CSS/layout pass.
        this.scene = new Scene(new StackPane(), 1200, 760);
        scene.setFill(Color.BLACK);
        ThemeManager.get().register(scene);
        stage.setScene(scene);
        stage.setTitle("Media Browser");
        // Escape is handled by the viewer's own key filter, where it can mean
        // "leave full screen" or "back to the previous view".
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("");
        if (undecorated) {
            stage.initStyle(StageStyle.UNDECORATED);
            WindowChrome.installShellChrome(stage, settings.undecoratedResizable());
        }
        // The views deactivate as they are swapped out, so on stage close only
        // the active one still holds live resources (e.g. a playing video).
        stage.setOnHidden(e -> {
            ShellView active = views.get(activeView.get());
            if (active != null) active.deactivate();
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>When the window is undecorated this also makes the view's toolbar and
     * menu bar window-drag handles and appends the ✕ button: closing from the
     * browser closes the app, from the mosaic or viewer it goes back.</p>
     */
    @Override
    public void register(AppView v, ShellView view) {
        views.put(v, view);
        if (undecorated) {
            WindowChrome.addDragHandle(stage, view.toolBar());
            if (view.menuBar() != null) {
                WindowChrome.addDragHandle(stage, view.menuBar());
            }
            WindowChrome.addCloseButton(view.toolBar(),
                    v == AppView.BROWSER ? stage::close : () -> back(v));
        }
    }

    /** Shows the window on {@code initial} (flash-free like a plain stage show). */
    @Override
    public void start(AppView initial) {
        show(initial, false);
        WindowChrome.showWithoutFlash(stage);
        // Re-claim focus once the stage is actually showing; a requestFocus
        // that ran before show() may not have been granted.
        Platform.runLater(() -> {
            ShellView view = views.get(activeView.get());
            if (view != null) view.activate();
        });
    }

    @Override
    public void showView(AppView v) {
        show(v, true);
    }

    /** {@inheritDoc} In the single window {@code from} is always the active view. */
    @Override
    public void back(AppView from) {
        AppView target = backStack.isEmpty() ? AppView.BROWSER : backStack.pop();
        if (target == activeView.get()) {
            target = AppView.BROWSER;
        }
        show(target, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The single window cannot show a second view without hiding the
     * caller's, so a passive reveal is only "visible" when {@code v} already
     * fills the window — the Keep Focus / auto-open flows silently no-op from
     * any other view.</p>
     */
    @Override
    public boolean revealPassive(AppView v) {
        return isActiveNow(v);
    }

    @Override
    public boolean singleWindow() {
        return true;
    }

    private void show(AppView v, boolean push) {
        ShellView next = views.get(v);
        AppView prev = activeView.get();
        if (next == null || v == prev) return;
        if (prev != null) {
            views.get(prev).deactivate();
            if (push) {
                // Dedupe so toggling between two views never grows the stack.
                backStack.remove(prev);
                backStack.push(prev);
            }
            if (prev == AppView.VIEWER && stage.isFullScreen()) {
                stage.setFullScreen(false);
            }
        }
        activeView.set(v);
        scene.setRoot(next.root());
        stage.titleProperty().bind(next.titleProperty());
        next.activate();
    }

    /**
     * Cached per view and held strongly by the shell: a binding's dependencies
     * only reference it weakly, so handing out a fresh binding per call lets
     * GC collect it — along with the caller's listeners (the Window menu ticks
     * froze on whichever view was active at the first collection).
     */
    @Override
    public BooleanExpression isActive(AppView v) {
        return actives.computeIfAbsent(v, view ->
                BooleanExpression.booleanExpression(Bindings.createBooleanBinding(
                        () -> activeView.get() == view, activeView)));
    }

    @Override
    public boolean isActiveNow(AppView v) {
        return activeView.get() == v;
    }

    /** In the single window, "on screen" and "active" are the same thing. */
    @Override
    public BooleanExpression isShowing(AppView v) {
        return isActive(v);
    }

    @Override
    public boolean isShowingNow(AppView v) {
        return isActiveNow(v);
    }

    /** The single application stage, whichever view asks. */
    @Override
    public Stage stageFor(AppView v) {
        return stage;
    }

    @Override
    void setFullScreen(boolean on) {
        stage.setFullScreen(on);
    }

    @Override
    boolean isFullScreen() {
        return stage.isFullScreen();
    }

    @Override
    ReadOnlyBooleanProperty fullScreenProperty() {
        return stage.fullScreenProperty();
    }
}
