package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.WindowMode;

import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.scene.Parent;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ToolBar;
import javafx.stage.Stage;

import java.util.EnumMap;
import java.util.Map;

/**
 * How the application hosts its three views — browser, mosaic, viewer. The
 * views are self-contained components ({@link ShellView}: a root node, a
 * title, an activate/deactivate lifecycle) that never create windows
 * themselves; they ask the shell to switch ({@link #showView}), go back
 * ({@link #back}) or reveal a view without taking focus
 * ({@link #revealPassive}), and borrow {@link #stageFor} as the owner for
 * dialogs and window operations.
 *
 * <p>Two implementations, chosen once at startup from the persisted
 * {@link WindowMode} setting ({@code window.mode}):</p>
 *
 * <ul>
 *   <li>{@link SingleWindowShell} — one stage/scene whose root swaps between
 *       the views, with a back-stack so Escape unwinds
 *       viewer → mosaic → browser.</li>
 *   <li>{@link MultiWindowShell} — the classic layout: each view is its own
 *       window, raised and hidden independently, with the Keep Focus /
 *       auto-open behaviours that only make sense across separate windows.</li>
 * </ul>
 */
public abstract class AppShell {

    /** The three views the shell hosts. */
    public enum AppView { BROWSER, MOSAIC, VIEWER }

    /**
     * What a view must expose to be hosted by a shell. {@code activate()}
     * runs when the view comes on screen (claim focus, re-attach live
     * sources); {@code deactivate()} runs as it leaves (pause playback,
     * detach sources, park timers) and again when its window hides, so app
     * close still releases a playing video's native resources.
     */
    public interface ShellView {
        Parent root();
        ReadOnlyStringProperty titleProperty();
        void activate();
        void deactivate();
        ToolBar toolBar();
        MenuBar menuBar();
    }

    protected final Map<AppView, ShellView> views = new EnumMap<>(AppView.class);
    private final WindowMaximizer maximizer;

    protected AppShell(AppSettings settings) {
        this.maximizer = new WindowMaximizer(settings.maximizeOverscan());
    }

    /** The shell for the persisted {@code window.mode} setting. */
    public static AppShell create(Stage primaryStage, AppSettings settings) {
        return settings.windowMode() == WindowMode.MULTI
                ? new MultiWindowShell(primaryStage, settings)
                : new SingleWindowShell(primaryStage, settings);
    }

    /**
     * Registers {@code view} for {@code v}; call once per view after the view
     * classes are constructed (so toolbars and the shared menu bar exist).
     */
    public abstract void register(AppView v, ShellView view);

    /** Puts the application on screen showing {@code initial}. */
    public abstract void start(AppView initial);

    /** Brings {@code v} on screen and gives it keyboard focus. */
    public abstract void showView(AppView v);

    /**
     * Returns from {@code from} to the previous view (the browser when there
     * is none): the single window swaps its root back, a separate window
     * hides {@code from}'s window and refocuses the previous one.
     */
    public abstract void back(AppView from);

    /**
     * Makes {@code v} visible <em>without</em> taking keyboard focus, so the
     * caller can keep browsing while updating it (the Keep Focus / auto-open
     * flows). Returns whether the view is now visible and worth updating: the
     * single window can't show a second view passively, so there this is true
     * only when {@code v} already fills the window.
     */
    public abstract boolean revealPassive(AppView v);

    /** Whether this is the single-window shell (one view at a time). */
    public abstract boolean singleWindow();

    /**
     * Observable "{@code v} is the view the user is working in": fills the
     * window (single) or holds window focus (multi). Gates the per-view menu
     * bars' auto-hide, the Window menu ticks and the focus bounces.
     */
    public abstract BooleanExpression isActive(AppView v);

    /** Imperative counterpart of {@link #isActive} for event-handler guards. */
    public abstract boolean isActiveNow(AppView v);

    /**
     * Observable "{@code v} is on screen": fills the window (single) or its
     * window is showing, focused or not (multi). Gates menu enablement and
     * "is this view already showing X" checks.
     */
    public abstract BooleanExpression isShowing(AppView v);

    /** Imperative counterpart of {@link #isShowing}. */
    public abstract boolean isShowingNow(AppView v);

    /**
     * The stage hosting {@code v}: the owner for its dialogs and the target
     * of its maximize/tile/drag operations. The single window returns the one
     * stage for every view.
     */
    public abstract Stage stageFor(AppView v);

    /** The application-wide maximize/tile mechanism (per-stage state). */
    WindowMaximizer maximizer() {
        return maximizer;
    }

    /** Full-screen state; applies to the viewer's stage in the multi shell. */
    abstract void setFullScreen(boolean on);

    abstract boolean isFullScreen();

    abstract ReadOnlyBooleanProperty fullScreenProperty();
}
