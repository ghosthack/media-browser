package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Region;

/**
 * Horizontal split of a view's main content (the viewer's viewport, the
 * mosaic's grid) and its right-edge panel stack — the Info / Metadata /
 * Diagnostics panels.
 *
 * <p>The stack used to sit in {@code BorderPane.right}, which sizes it to its
 * preferred width: the edge jumped whenever a panel with a different preferred
 * width was toggled on, and the user could not drag it. Here the width is the
 * user's, kept by {@link SidePanelWidth} (the same one the browser's split
 * uses), and hiding every panel drops the stack out of the split so the content
 * gets the full width back with no leftover divider.</p>
 */
final class SidePanelSplit extends SplitPane {

    private final Region panels;
    private final SidePanelWidth width;

    SidePanelSplit(Node content, Region panels, AppSettings settings) {
        this.panels = panels;
        setOrientation(Orientation.HORIZONTAL);
        getItems().add(content);
        this.width = new SidePanelWidth(this, panels, settings);
        // Both hosts paint black behind their media surface; the split must not
        // draw a themed frame around it.
        setStyle("-fx-background-color: black; -fx-padding: 0;");
    }

    /**
     * Shows or hides the panel stack by split membership (not visibility), so a
     * hidden stack leaves no empty divider behind. Re-showing restores the
     * remembered width.
     */
    void setPanelsVisible(boolean visible) {
        if (visible == getItems().contains(panels)) return;
        if (visible) {
            getItems().add(panels);
            width.apply();
        } else {
            getItems().remove(panels);
        }
    }
}
