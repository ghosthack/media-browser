package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;

import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Gives the right-edge panel stack — the Info / Metadata / Diagnostics panels —
 * one width across every view: dragged on its divider, remembered in
 * {@link AppSettings#sidePanelWidth()}, and re-applied wherever the stack shows
 * up. Attaches to any horizontal {@link SplitPane} whose <em>last</em> item is
 * the stack: the browser's three-pane split (tree | list | stack) as well as the
 * viewer's and mosaic's {@link SidePanelSplit}.
 *
 * <p>Sizing the stack by its preferred width — what {@code BorderPane.right}
 * did, and what a fixed divider fraction approximates — moved the window's panel
 * edge whenever a panel was toggled on and gave the user no say. Here the only
 * thing that changes the remembered width is a drag: the stack keeps its width
 * when the window resizes ({@code setResizableWithParent(false)}), and a window
 * too narrow to honor it squeezes the stack without writing that squeeze back
 * (otherwise one narrow window would permanently shrink the setting).</p>
 */
final class SidePanelWidth {

    /** Never leave the stack narrower than a panel's own minimum. */
    private static final double MIN_PANEL_WIDTH = 160;
    /** …nor the content with nothing left, however narrow the window gets. */
    private static final double MIN_CONTENT_WIDTH = 120;

    private final SplitPane split;
    private final Region panels;
    private final AppSettings settings;
    /** Coalesces a drag's stream of widths into one settings write. */
    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(250));
    /** Set while waiting for the first layout pass to give the split a width. */
    private ChangeListener<Number> pendingApply;
    /** The split's width at the last stack-width change; see the listener. */
    private double lastTotalWidth = -1;
    /** The width {@link #apply()} last asked for, so we don't persist our own placement. */
    private double requestedWidth = -1;

    SidePanelWidth(SplitPane split, Region panels, AppSettings settings) {
        this.split = split;
        this.panels = panels;
        this.settings = settings;
        // The stack keeps its width when the window resizes; the content next to
        // it absorbs the change (the way the panels behaved in BorderPane).
        SplitPane.setResizableWithParent(panels, false);

        panels.widthProperty().addListener((o, was, now) -> {
            double width = now.doubleValue();
            double total = split.getWidth();
            boolean splitResized = total != lastTotalWidth;
            lastTotalWidth = total;
            if (width < 1 || !split.getItems().contains(panels)) return;   // hidden, not resized
            if (splitResized) return;                       // the window moved it, not the user
            if (Math.abs(width - requestedWidth) < 1) return;   // our own placement
            if (Math.abs(width - settings.sidePanelWidth()) < 1) return;
            settings.setSidePanelWidth((int) Math.round(width));
            saveDebounce.playFromStart();
        });
        saveDebounce.setOnFinished(e -> {
            try {
                settings.save();
            } catch (java.io.IOException ignored) {
                // a failed save is non-fatal; the live width still applies
            }
        });
    }

    /**
     * Places the split's last divider so the stack is exactly the remembered
     * width wide. Call after the stack joins the split (and after any other
     * divider is positioned, since this one is set last).
     */
    void apply() {
        if (!split.getItems().contains(panels)) return;
        double total = split.getWidth();
        if (total <= 0) {
            // Called before the first layout: a divider fraction needs a width
            // to mean anything, so re-run once the split has one.
            if (pendingApply == null) {
                pendingApply = (o, was, now) -> {
                    if (now.doubleValue() <= 0) return;
                    split.widthProperty().removeListener(pendingApply);
                    pendingApply = null;
                    apply();
                };
                split.widthProperty().addListener(pendingApply);
            }
            return;
        }
        double target = Math.min(Math.max(MIN_PANEL_WIDTH, settings.sidePanelWidth()),
                total - MIN_CONTENT_WIDTH);
        if (target <= 0) return;    // window narrower than both minimums; leave it be
        var dividers = split.getDividers();
        if (dividers.isEmpty()) return;
        requestedWidth = target;
        dividers.get(dividers.size() - 1).setPosition((total - target) / total);
    }
}
