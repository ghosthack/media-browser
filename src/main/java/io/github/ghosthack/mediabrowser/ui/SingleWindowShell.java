package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
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
 * (Window ▸ Browser / File ▸ Close Window). Switching
 * away from the viewer preserves full-screen because it belongs to the shared
 * application window, not to whichever view currently fills it.</p>
 *
 * <p>The shell also centralizes what used to be per-stage plumbing: the shared
 * theme registration, the undecorated-window chrome (edge resize, drag
 * handles, per-view ✕ button), and the stage title, which is bound to the
 * active view's {@code titleProperty}.</p>
 */
final class SingleWindowShell extends AppShell {

    private final Stage stage;
    private final Scene scene;
    private final BorderPane shellRoot;
    private final boolean undecorated;
    private final ArrayDeque<AppView> backStack = new ArrayDeque<>();
    private final ReadOnlyObjectWrapper<AppView> activeView =
            new ReadOnlyObjectWrapper<>(null);
    private final EnumMap<AppView, BooleanExpression> actives =
            new EnumMap<>(AppView.class);

    SingleWindowShell(Stage stage, AppSettings settings) {
        super(settings);
        this.stage = stage;
        installApplicationIcon(stage);
        StageStyle style = WindowChrome.stageStyle(settings.windowDecorations());
        this.undecorated = style == StageStyle.UNDECORATED;
        stage.initStyle(style);
        stage.setTitle("Media Browser");
        this.shellRoot = WindowChrome.createShellRoot(stage, style);
        // ThemeManager replaces this initial black with the active theme's
        // fill. EXTENDED caption buttons also use that fill's brightness.
        this.scene = new Scene(shellRoot, 1200, 760);
        scene.setFill(Color.BLACK);
        ThemeManager.get().register(scene);
        WindowChrome.installStylesheet(scene);
        stage.setScene(scene);
        // Escape is handled by the viewer's own key filter. Full screen belongs
        // to this shared application window and survives a viewer-to-view switch.
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("");
        if (undecorated) {
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
        }
        activeView.set(v);
        WindowChrome.setContent(shellRoot, next.root());
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
