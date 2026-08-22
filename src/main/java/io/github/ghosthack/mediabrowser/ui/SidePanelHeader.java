package io.github.ghosthack.mediabrowser.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The one construction of a side-panel title strip, so the Browser's Folders
 * panel and the Info/Metadata/Diagnostics panels line up: same title
 * treatment, same edge insets, same height in every theme.
 */
final class SidePanelHeader {

    private SidePanelHeader() {
    }

    /**
     * Builds the strip: bold title, a growing spacer, then the trailing
     * controls at the right edge. Padding and spacing are deliberately not set
     * here — an API value overrides the stylesheet, and the panels once set
     * them each their own way, leaving the bare Info header 10px taller than
     * its neighbours under Modena's {@code .tool-bar} padding. Leaving both to
     * that rule keeps every header identical and themes in control. A caller
     * needing a node between the title and the spacer (Metadata's provider
     * label) inserts it at child index 1.
     */
    static HBox create(String titleText, Node... trailing) {
        var title = new Label(titleText);
        title.getStyleClass().add("side-panel-title");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 6 8;");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(title, spacer);
        header.getChildren().addAll(trailing);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().addAll("tool-bar", "side-panel-header");
        return header;
    }
}
