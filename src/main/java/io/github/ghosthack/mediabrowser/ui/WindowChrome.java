package io.github.ghosthack.mediabrowser.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.util.function.BooleanSupplier;

/**
 * Substitute window controls for undecorated stages: drag-to-move on the
 * toolbar's empty space, edge/corner resizing at the scene borders, and a
 * ✕ button appended to the toolbar. Installed only when the app runs
 * without OS window chrome (see {@code AppSettings.undecoratedWindows}).
 */
final class WindowChrome {

    /** macOS hosts the menu bar as a single, global, top-of-screen system bar. */
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** Resize grip thickness, in pixels, inside each scene edge. */
    private static final double EDGE = 6;
    private static final double MIN_WIDTH = 320;
    private static final double MIN_HEIGHT = 220;

    private WindowChrome() {}

    /**
     * Gives a context menu the "dismiss readily" behaviour shared by the
     * viewer's view menu and the mosaic's per-tile popup: it autohides on any
     * press outside the popup, that press is <em>not</em> swallowed (so it still
     * lands as a normal click where it fell), a press anywhere in {@code scene}
     * closes it, and the first item is highlighted as it opens so Enter / the
     * item accelerators act immediately.
     */
    static void makeDismissive(ContextMenu menu, Scene scene) {
        menu.setAutoHide(true);
        menu.setConsumeAutoHidingEvents(false);
        menu.setOnShown(e -> highlightFirstMenuItem(menu));
        if (scene != null) {
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (menu.isShowing()) menu.hide();
            });
        }
    }

    /**
     * Highlights the first item of {@code menu} as it opens, by posting a Down
     * key to its content so the popup opens with a selection. A no-op until the
     * skin exists.
     */
    static void highlightFirstMenuItem(ContextMenu menu) {
        var skin = menu.getSkin();
        if (skin == null || skin.getNode() == null) return;
        skin.getNode().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.DOWN, false, false, false, false));
    }

    /**
     * Shows {@code stage} without the brief white flash a native window paints
     * before JavaFX renders its first frame: the window is made fully
     * transparent before {@code show()} and revealed once a couple of pulses
     * have painted the (dark) scene. A no-op (just {@code show()}) if the stage
     * is already showing, so re-shows don't blink. Pair with the scene's
     * {@code setFill(...)} so the first painted frame is the right colour.
     */
    static void showWithoutFlash(Stage stage) {
        if (stage.isShowing()) return;
        stage.setOpacity(0);
        stage.show();
        new AnimationTimer() {
            private int frames;
            @Override public void handle(long now) {
                if (++frames >= 2) {   // let the dark scene paint before revealing
                    stage.setOpacity(1);
                    stop();
                }
            }
        }.start();
    }

    /**
     * Installs the stage-wide part of the substitute controls (the stage's
     * scene must be set): edge-drag resizing when {@code resizable}, and
     * resizability for external window managers regardless (see
     * {@link MacWindowResizable}). The per-view drag handles and ✕ button are
     * added separately via {@link #addDragHandle} and {@link #addCloseButton}.
     */
    static void installShellChrome(Stage stage, boolean resizable) {
        if (resizable) {
            installResize(stage);
        }
        MacWindowResizable.installOnShown(stage);
    }

    /**
     * Appends the substitute ✕ button (right-aligned by a growing spacer) to
     * {@code toolBar}, running {@code closeAction} when pressed.
     */
    static void addCloseButton(ToolBar toolBar, Runnable closeAction) {
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var closeButton = new Button("✕");
        closeButton.setTooltip(new Tooltip("Close window"));
        closeButton.setOnAction(e -> closeAction.run());
        toolBar.getItems().addAll(spacer, closeButton);
    }

    /**
     * Registers an extra node (e.g. a menu bar installed after construction) as
     * a window move handle; mirrors the {@code extraDragHandles} of
     * {@link #install}.
     */
    static void addDragHandle(Stage stage, Node handle) {
        installDrag(stage, handle, () -> true);
    }

    /**
     * Registers {@code handle} as a window move handle that is only live while
     * {@code enabled} yields true, so a setting can switch dragging on and off
     * without reinstalling the handler. Unlike {@link #addDragHandle(Stage, Node)}
     * this is meant for a content node (e.g. the viewer's viewport), so it works
     * regardless of whether the window is undecorated.
     */
    static void addDragHandle(Stage stage, Node handle, BooleanSupplier enabled) {
        installDrag(stage, handle, enabled);
    }

    /**
     * Binds {@code bar}'s visibility (and layout managed-ness) to
     * {@code baseVisible} — the per-window Show ▸ Menu Bar toggle — but
     * additionally reveals the bar while the logical <b>modifier 2</b>
     * (Alt/Option by default; see {@link KeyScheme}) is held in this window
     * (Windows-style auto-hide), or while one of its menus is open. The
     * menu-item accelerators stay registered on the scene whether or not the
     * bar is showing, so every shortcut (the Show ▸ Menu Bar toggle included)
     * keeps working while the bar is collapsed, and modifier 2 can always summon
     * it back.
     *
     * <p>When {@code systemMenu} is set (the in-window-menu setting is off) and
     * we are on macOS, the menus hoist to the global system menu bar at the top
     * of the screen. That bar is a <em>single, app-wide</em> bar that only the
     * focused window may own, and it ignores the node's {@code visible}/{@code
     * managed} flags — so this gates {@code useSystemMenuBar} on
     * <em>attached&nbsp;AND focus&nbsp;AND</em> {@code baseVisible} and reasserts
     * it on every scene and focus change. Focusing a window therefore re-installs its bar (Show ▸ Menu Bar
     * on) or clears the global bar (off), instead of leaving another window's
     * bar lingering and masking this one's hidden state — the bug where one
     * window's “Show the menu bar” appeared to do nothing while a sibling
     * window's bar still occupied the top of the screen. While hoisted, the
     * in-window node stays hidden (the menus live at the top of the screen)
     * except for the modifier-2 peek, which summons an in-window copy while the
     * bar is hidden. Off macOS — or with the in-window-menu setting on —
     * {@code useSystemMenuBar} is a no-op, so the in-window {@code visible}
     * binding rules and the peek brings a hidden in-window bar back.</p>
     *
     * <p>{@code active} is the shell's "this view is the one the user is in"
     * flag: fills the single window, or holds window focus across separate
     * windows. It gates the macOS system menu bar (so the views' bars don't
     * race for the one global bar) and the modifier-2 peek — but not the
     * in-window {@code visible} binding, which must keep an unfocused separate
     * window's bar on screen (in the single window an inactive view's bar is
     * detached from the scene anyway).</p>
     */
    static void bindMenuAutoHide(MenuBar bar, Scene scene, Stage stage,
                                 ObservableValue<Boolean> baseVisible,
                                 ObservableValue<Boolean> active, KeyScheme keys,
                                 boolean systemMenu) {
        var reveal = new SimpleBooleanProperty(false);
        // Track modifier 2 in the capturing phase, by key code (unambiguous
        // across the press/release pair), so the flag flips before the menu reacts.
        scene.addEventFilter(KeyEvent.KEY_PRESSED,
                e -> { if (keys.isMod2Key(e.getCode())) reveal.set(true); });
        scene.addEventFilter(KeyEvent.KEY_RELEASED,
                e -> { if (keys.isMod2Key(e.getCode())) reveal.set(false); });
        // A modifier-down focus switch (e.g. Alt+Tab) never delivers the release:
        // reset on blur so a hidden bar doesn't stay stuck open.
        stage.focusedProperty().addListener((o, was, focused) -> { if (!focused) reveal.set(false); });

        if (systemMenu && IS_MAC) {
            // The macOS menu bar is one global bar that only the focused window
            // may own. Gate it on (focused && base) and reassert on focus / base
            // changes — driven imperatively because the toolkit may set the
            // property itself — so the focused window's Show ▸ Menu Bar governs
            // the top-of-screen bar and a blurred window releases it rather than
            // leaving its menus to mask a sibling's hidden bar.
            //
            // Also gated on the bar being attached to a scene: the single-window
            // shell flips the active view before it swaps the scene root, and
            // toggling useSystemMenuBar on a detached bar makes MenuBarSkin
            // rebuild its in-window menu buttons (the system-menu path is
            // skipped while scene == null). The subsequent attach then hoists
            // the menus to the system bar without clearing those buttons, and
            // since both wrap the same Menu objects, opening a menu in the
            // system bar (which sets Menu.showing) also popped the stale
            // in-window copy — a native and an in-app menu open at once. The
            // scene listener re-syncs on attach, so the hoist always happens
            // through an attached rebuild, which starts by clearing the
            // in-window buttons.
            Runnable syncSystem = () -> bar.setUseSystemMenuBar(
                    bar.getScene() != null
                            && stage.isFocused()
                            && Boolean.TRUE.equals(active.getValue())
                            && Boolean.TRUE.equals(baseVisible.getValue()));
            baseVisible.addListener((o, was, now) -> syncSystem.run());
            active.addListener((o, was, now) -> syncSystem.run());
            stage.focusedProperty().addListener((o, was, now) -> syncSystem.run());
            bar.sceneProperty().addListener((o, was, now) -> syncSystem.run());
            // The primary stage is shown (and focused) during toolkit startup, so
            // it can already be focused before this listener is attached — with no
            // focus *transition* left to react to. Re-assert once it is shown
            // (deferred a pulse so focus has settled), so the first window's
            // persisted Show ▸ Menu Bar state governs the global bar from the
            // start, exactly like the later-shown viewer/mosaic do on their own
            // clean focus transition.
            stage.showingProperty().addListener((o, was, now) -> {
                if (Boolean.TRUE.equals(now)) Platform.runLater(syncSystem);
            });
            syncSystem.run();
            // The menus live at the top of the screen, so the in-window node is
            // hidden — except the modifier-2 peek, which reveals an in-window
            // copy only while the bar is hidden (base off).
            BooleanBinding peek = Bindings.createBooleanBinding(
                    () -> reveal.get()
                            && Boolean.TRUE.equals(active.getValue())
                            && !Boolean.TRUE.equals(baseVisible.getValue()),
                    baseVisible, active, reveal);
            bar.visibleProperty().bind(peek);
            bar.managedProperty().bind(peek);
            return;
        }

        // In-window bar (Windows/Linux, or macOS with the in-window-menu setting
        // on): useSystemMenuBar is a no-op, so leave it off and let the visible
        // binding rule. Keep the bar up while a menu is actually open, so
        // releasing the modifier mid-navigation doesn't yank it from under a popup.
        bar.setUseSystemMenuBar(false);
        Observable[] menuShowing = bar.getMenus().stream()
                .map(Menu::showingProperty).toArray(Observable[]::new);
        BooleanBinding anyMenuOpen = Bindings.createBooleanBinding(
                () -> bar.getMenus().stream().anyMatch(Menu::isShowing), menuShowing);
        BooleanBinding visible = Bindings.createBooleanBinding(
                () -> Boolean.TRUE.equals(baseVisible.getValue())
                        || reveal.get() || anyMenuOpen.get(),
                baseVisible, reveal, anyMenuOpen);
        bar.visibleProperty().bind(visible);
        bar.managedProperty().bind(visible);
    }

    /**
     * Dragging the handle's empty space (not its controls) moves the window,
     * while {@code enabled} yields true. The full-screen guard and inner-control
     * skip keep a maximized window pinned and let nested buttons/scrollbars
     * handle their own drags.
     */
    private static void installDrag(Stage stage, Node handle, BooleanSupplier enabled) {
        var offset = new double[2];
        handle.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            offset[0] = stage.getX() - e.getScreenX();
            offset[1] = stage.getY() - e.getScreenY();
        });
        handle.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!enabled.getAsBoolean() || stage.isFullScreen() || onControl(e, handle)) return;
            stage.setX(e.getScreenX() + offset[0]);
            stage.setY(e.getScreenY() + offset[1]);
        });
    }

    /** True when the event targets a control inside the handle (a button, a menu). */
    private static boolean onControl(MouseEvent e, Node handle) {
        for (Node n = e.getTarget() instanceof Node node ? node : null;
                n != null && n != handle; n = n.getParent()) {
            if (n instanceof Control) return true;
        }
        return false;
    }

    private static void installResize(Stage stage) {
        Scene scene = stage.getScene();
        var dir = new int[2];      // active resize direction (horizontal, vertical)
        var start = new double[6]; // screen x/y, stage x/y, width, height at press

        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e ->
                scene.setCursor(stage.isFullScreen()
                        ? null : cursorFor(edgeH(scene, e), edgeV(scene, e))));
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (stage.isFullScreen()) return;
            dir[0] = edgeH(scene, e);
            dir[1] = edgeV(scene, e);
            if (dir[0] == 0 && dir[1] == 0) return;
            start[0] = e.getScreenX();
            start[1] = e.getScreenY();
            start[2] = stage.getX();
            start[3] = stage.getY();
            start[4] = stage.getWidth();
            start[5] = stage.getHeight();
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (dir[0] == 0 && dir[1] == 0) return;
            double dx = e.getScreenX() - start[0];
            double dy = e.getScreenY() - start[1];
            if (dir[0] > 0) {
                stage.setWidth(Math.max(MIN_WIDTH, start[4] + dx));
            } else if (dir[0] < 0) {
                // keep the right edge fixed, also when clamped to the minimum
                double w = Math.max(MIN_WIDTH, start[4] - dx);
                stage.setX(start[2] + start[4] - w);
                stage.setWidth(w);
            }
            if (dir[1] > 0) {
                stage.setHeight(Math.max(MIN_HEIGHT, start[5] + dy));
            } else if (dir[1] < 0) {
                double h = Math.max(MIN_HEIGHT, start[5] - dy);
                stage.setY(start[3] + start[5] - h);
                stage.setHeight(h);
            }
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            dir[0] = 0;
            dir[1] = 0;
        });
    }

    private static int edgeH(Scene scene, MouseEvent e) {
        return e.getSceneX() < EDGE ? -1
                : e.getSceneX() > scene.getWidth() - EDGE ? 1 : 0;
    }

    private static int edgeV(Scene scene, MouseEvent e) {
        return e.getSceneY() < EDGE ? -1
                : e.getSceneY() > scene.getHeight() - EDGE ? 1 : 0;
    }

    private static Cursor cursorFor(int h, int v) {
        if (h < 0) return v < 0 ? Cursor.NW_RESIZE : v > 0 ? Cursor.SW_RESIZE : Cursor.W_RESIZE;
        if (h > 0) return v < 0 ? Cursor.NE_RESIZE : v > 0 ? Cursor.SE_RESIZE : Cursor.E_RESIZE;
        if (v != 0) return v < 0 ? Cursor.N_RESIZE : Cursor.S_RESIZE;
        return null;
    }
}
