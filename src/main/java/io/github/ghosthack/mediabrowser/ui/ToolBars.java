package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.ui.icon.AppIcon;
import io.github.ghosthack.mediabrowser.ui.icon.IconBinding;

import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;

import java.util.List;

/**
 * The one control-height baseline for every window-top strip — the browser,
 * viewer, and mosaic toolbars plus the browser address bar and the mosaic
 * location bar. Each view once sized its own strip (icon-only buttons,
 * text toggles, a slider, and a text field all have different natural
 * heights), so the top strip visibly jumped when the single-window shell
 * switched views; pinning every control to the same baseline makes the
 * strips equal-height in every theme.
 */
final class ToolBars {

    /** Shared baseline for icon, text, menu, field, and separator controls. */
    static final double CONTROL_HEIGHT = 30;

    private ToolBars() {
    }

    static void applyControlBaseline(ToolBar bar) {
        applyControlBaseline(bar.getItems());
    }

    /** For the HBox-based strips (address/location bars). */
    static void applyControlBaseline(Pane bar) {
        applyControlBaseline(bar.getChildren());
    }

    /**
     * Gives an icon-first toolbar control the selected pack's semantic icon,
     * its Original-pack glyph fallback, and the accessible tooltip shared by
     * the Browser and Mosaic toolbars.
     */
    static void iconify(Labeled control, AppIcon icon, String tooltip) {
        IconBinding.install(control, icon, icon.originalGlyph(), "", tooltip);
        control.setTooltip(new Tooltip(tooltip));
    }

    /**
     * Builds the panel-toggle block shared by the Browser and Mosaic toolbars.
     * Keeping the controls and their order here makes both bars present the
     * same five panel icons immediately after Sort:
     * Info, Metadata, Diagnostics, Status Bar, Action Log.
     */
    static List<ToggleButton> panelToggles(
            boolean iconFirst,
            BooleanProperty info,
            BooleanProperty metadata,
            BooleanProperty diagnostics,
            BooleanProperty status,
            BooleanProperty actionLog) {
        return List.of(
                panelToggle("Info Panel", AppIcon.INFO, info, iconFirst),
                panelToggle("Metadata Panel", AppIcon.METADATA, metadata, iconFirst),
                panelToggle("Diagnostics Panel", AppIcon.DIAGNOSTICS,
                        diagnostics, iconFirst),
                panelToggle("Status Bar", AppIcon.STATUS_BAR, status, iconFirst),
                panelToggle("Action Log", AppIcon.ACTION_LOG, actionLog, iconFirst));
    }

    private static ToggleButton panelToggle(
            String text, AppIcon icon, BooleanProperty property, boolean iconFirst) {
        var button = new ToggleButton(text);
        String tooltip = "Show/hide the " + text.toLowerCase();
        button.setTooltip(new Tooltip(tooltip));
        if (iconFirst) iconify(button, icon, text);
        button.selectedProperty().bindBidirectional(property);
        return button;
    }

    private static void applyControlBaseline(List<Node> items) {
        for (Node item : items) {
            if (item instanceof Control control) {
                control.setMinHeight(CONTROL_HEIGHT);
                control.setPrefHeight(CONTROL_HEIGHT);
            }
        }
    }
}
