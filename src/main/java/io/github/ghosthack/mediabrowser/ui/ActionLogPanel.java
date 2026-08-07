package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.media.move.ActionLogEntry;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Session action-log panel, split in two: on the left a compact scrollable
 * list of the file-organization actions performed this session (moves,
 * renames, quick-moves — from every window, via the shared {@link ActionLog}),
 * each with a timestamp; on the right the current quick-move targets — the
 * directories the F1–F4 shortcuts move to ({@code moveHistory[0..3]}),
 * dimmed while the shortcuts are disabled. The pane's header is a checkbox
 * that enables/disables the shortcuts, mirroring the toggle in the move
 * dialog; below the targets sits the persisted Extension fix toggle
 * (mirroring Settings ▸ General), so the feature whose renames land in this
 * log is switchable where the log is read.
 * The browser, the mosaic and the viewer each host their own instance
 * above their status bar (all instances observe the one shared
 * {@link ActionLog}); hidden by default behind one app-wide visibility flag,
 * toggled via Show ▸ Action Log / modifier1+J in any view (startup default in
 * Settings ▸ General). Ported from the Swing predecessor's {@code ActionLogPanel}.
 */
public final class ActionLogPanel extends VBox {

    /** Panel height: a handful of rows without crowding the browser. */
    private static final double PANEL_HEIGHT = 120;

    /**
     * Every log row is one 11px line, so pin the cell height instead of
     * letting VirtualFlow sample it: an unsampled/empty cell measuring ~0
     * throws off the flow's max-cell estimate and logs "index exceeds
     * maxCellCount … ActionLogPanel$EntryCell" during layout.
     */
    private static final double ROW_HEIGHT = 22;

    /** Right-hand quick-move targets pane width. */
    private static final double MOVE_TARGETS_WIDTH = 230;

    private static final String[] MOVE_TARGET_KEYS = {"F1", "F2", "F3", "F4"};

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AppSettings settings;
    private final ListView<ActionLogEntry> listView = new ListView<>(ActionLog.get().entries());
    private final CheckBox targetsToggle = new CheckBox("Move targets");
    private final CheckBox extensionFixToggle = new CheckBox("Extension fix");
    private final Label[] targetKeyLabels = new Label[MOVE_TARGET_KEYS.length];
    private final Label[] targetValueLabels = new Label[MOVE_TARGET_KEYS.length];

    public ActionLogPanel(AppSettings settings) {
        this.settings = settings;
        // Themed background of its own: the mosaic hosts this panel over its
        // black root, where a transparent panel would be unreadable in light themes.
        setStyle("-fx-font-size: 11px; -fx-background-color: -fx-background;");

        listView.setPrefHeight(PANEL_HEIGHT);
        listView.setFixedCellSize(ROW_HEIGHT);
        listView.setFocusTraversable(false);
        listView.setPlaceholder(new Label("No file actions this session"));
        listView.setCellFactory(view -> new EntryCell());
        HBox.setHgrow(listView, Priority.ALWAYS);

        // Follow the tail, like a terminal: the newest action stays visible.
        // Deferred so the virtual flow has re-laid-out for the new row first.
        // Only while this panel is actually shown — three instances observe the
        // shared log, and scrolling a hidden, zero-height flow is exactly the
        // degenerate layout the maxCellCount INFO points at; a hidden panel
        // catches up the moment it is shown instead (listener below).
        ActionLog.get().entries().addListener((ListChangeListener<ActionLogEntry>) change ->
                Platform.runLater(() -> {
                    if (isVisible()) scrollToTail();
                }));
        visibleProperty().addListener((o, was, shown) -> {
            if (shown) Platform.runLater(this::scrollToTail);
        });

        var content = new HBox(listView, new Separator(Orientation.VERTICAL),
                buildMoveTargetsPane());
        getChildren().addAll(new Separator(), content);

        ActionLog.get().moveTargetsRevision().addListener((o, a, v) -> refreshMoveTargets());
        refreshMoveTargets();

        // Entries seeded from the on-disk log (actionLog.file) predate this
        // panel; start scrolled to the newest, like the live tail-follow above
        // (same visibility guard: a panel constructed hidden scrolls on show).
        if (!listView.getItems().isEmpty()) {
            Platform.runLater(() -> {
                if (isVisible()) scrollToTail();
            });
        }
    }

    /** Scrolls the log list to its newest (last) entry. */
    private void scrollToTail() {
        int size = listView.getItems().size();
        if (size > 0) {
            listView.scrollTo(size - 1);
        }
    }

    private GridPane buildMoveTargetsPane() {
        var pane = new GridPane();
        pane.setPadding(new Insets(6, 10, 6, 10));
        pane.setHgap(8);
        pane.setVgap(2);
        pane.setPrefWidth(MOVE_TARGETS_WIDTH);
        pane.setMinWidth(MOVE_TARGETS_WIDTH);
        pane.setMaxWidth(MOVE_TARGETS_WIDTH);

        var keyColumn = new ColumnConstraints();
        var valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        pane.getColumnConstraints().addAll(keyColumn, valueColumn);

        targetsToggle.setStyle("-fx-font-weight: bold;");
        targetsToggle.setFocusTraversable(false);
        targetsToggle.setTooltip(new Tooltip(
                "When checked, pressing F1, F2, F3, or F4 moves the focused file(s) "
                + "to the target directory shown next to that key. Resets when "
                + "the app closes."));
        // setOnAction fires on user gestures only, so the programmatic
        // setSelected in refreshMoveTargets cannot loop back through here.
        targetsToggle.setOnAction(e -> {
            settings.setQuickMoveShortcutsEnabled(targetsToggle.isSelected());
            ActionLog.get().touchMoveTargets();
        });
        pane.add(targetsToggle, 0, 0, 2, 1);
        GridPane.setMargin(targetsToggle, new Insets(0, 0, 4, 0));

        for (int i = 0; i < MOVE_TARGET_KEYS.length; i++) {
            var key = new Label(MOVE_TARGET_KEYS[i]);
            key.setStyle("-fx-font-weight: bold;");
            var value = new Label("—");
            value.setMaxWidth(Double.MAX_VALUE);
            pane.add(key, 0, i + 1);
            pane.add(value, 1, i + 1);
            targetKeyLabels[i] = key;
            targetValueLabels[i] = value;
        }

        // The automatic extension fix's toggle, same style as Move targets but
        // persisted (it mirrors Settings ▸ General ▸ Fix extensions of sniffed
        // files on open, unlike the transient quick-move toggle above). Every
        // panel instance stays in sync through the same revision bump.
        extensionFixToggle.setStyle("-fx-font-weight: bold;");
        extensionFixToggle.setFocusTraversable(false);
        extensionFixToggle.setTooltip(new Tooltip(
                "When checked, a file with no classifying extension that opens "
                + "without error in the viewer is renamed in place to its "
                + "content's canonical extension and logged here as an "
                + "Extension fix. Persisted (Settings ▸ General)."));
        extensionFixToggle.setOnAction(e -> {
            settings.setExtensionFixEnabled(extensionFixToggle.isSelected());
            try {
                settings.save();
            } catch (java.io.IOException ex) {
                System.err.println("media-browser: cannot save settings: "
                        + ex.getMessage());
            }
            ActionLog.get().touchMoveTargets();
        });
        pane.add(extensionFixToggle, 0, MOVE_TARGET_KEYS.length + 1, 2, 1);
        GridPane.setMargin(extensionFixToggle, new Insets(6, 0, 0, 0));
        return pane;
    }

    /**
     * Re-read the quick-move targets (persisted move history), the transient
     * quick-move toggle and the persisted extension-fix toggle from
     * {@link AppSettings}; called on every targets-revision bump (which the
     * Settings dialog and the other panels' toggles also fire).
     */
    private void refreshMoveTargets() {
        boolean enabled = settings.quickMoveShortcutsEnabled();
        targetsToggle.setSelected(enabled);
        extensionFixToggle.setSelected(settings.extensionFixEnabled());
        List<String> history = settings.moveHistory();
        for (int i = 0; i < MOVE_TARGET_KEYS.length; i++) {
            Label value = targetValueLabels[i];
            if (i < history.size()) {
                String path = history.get(i);
                Path name = Path.of(path).getFileName();
                value.setText(name == null ? path : name.toString());
                value.setTooltip(new Tooltip(path));
            } else {
                value.setText("—");
                value.setTooltip(null);
            }
            targetKeyLabels[i].setDisable(!enabled);
            value.setDisable(!enabled);
        }
    }

    /** One log row: a fixed-width timestamp column, then the summary. */
    private static final class EntryCell extends ListCell<ActionLogEntry> {
        private static final double TIME_WIDTH = 56;
        private static final double ROW_GAP = 8;
        private static final double HORIZONTAL_PADDING = 12;

        private final Label time = new Label();
        private final Label summary = new Label();
        private final HBox row = new HBox(ROW_GAP, time, summary);
        private final Tooltip summaryTooltip = new Tooltip();

        EntryCell() {
            setPadding(new Insets(0, HORIZONTAL_PADDING / 2, 0, HORIZONTAL_PADDING / 2));
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            time.setMinWidth(TIME_WIDTH);
            time.setPrefWidth(TIME_WIDTH);
            time.setMaxWidth(TIME_WIDTH);
            time.setStyle("-fx-opacity: 0.7;");

            summary.setMinWidth(0);
            summary.setMaxWidth(Double.MAX_VALUE);
            summary.setTextOverrun(OverrunStyle.ELLIPSIS);
            summary.setTooltip(summaryTooltip);
            HBox.setHgrow(summary, Priority.ALWAYS);

            // A log row belongs to this compact viewport, rather than making
            // VirtualFlow's preferred breadth follow an arbitrarily long path.
            // Without this cap, the horizontal bar can steal one row of height,
            // move the widest entry out of view, disappear, and repeat forever.
            row.maxWidthProperty().bind(Bindings.max(0,
                    widthProperty().subtract(HORIZONTAL_PADDING)));
        }

        @Override
        protected double computePrefWidth(double height) {
            // VirtualFlow uses cell.prefWidth(-1) to decide whether it needs a
            // horizontal bar. Report the row's compressible width, not the full
            // path width held by the summary label.
            Insets insets = getInsets();
            return insets.getLeft() + TIME_WIDTH + ROW_GAP
                    + summary.minWidth(height) + insets.getRight();
        }

        @Override
        protected void updateItem(ActionLogEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                summaryTooltip.setText(null);
                return;
            }
            time.setText(TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMillis())));
            summary.setText(entry.summary());
            summaryTooltip.setText(entry.summary());
            setGraphic(row);
        }
    }
}
