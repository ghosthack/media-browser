package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.media.move.ActionLogEntry;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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
 * dialog. The browser and the mosaic each
 * host their own instance above their status bar (all instances observe the
 * one shared {@link ActionLog}); hidden by default, toggled via Show ▸
 * Action Log (startup defaults in Settings ▸ Browser / Mosaic). Ported from
 * {@code iris94.ui.ActionLogPanel}.
 */
public final class ActionLogPanel extends VBox {

    /** Panel height: a handful of rows without crowding the browser. */
    private static final double PANEL_HEIGHT = 120;

    /** Right-hand quick-move targets pane width. */
    private static final double MOVE_TARGETS_WIDTH = 230;

    private static final String[] MOVE_TARGET_KEYS = {"F1", "F2", "F3", "F4"};

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AppSettings settings;
    private final ListView<ActionLogEntry> listView = new ListView<>(ActionLog.get().entries());
    private final CheckBox targetsToggle = new CheckBox("Move targets");
    private final Label[] targetKeyLabels = new Label[MOVE_TARGET_KEYS.length];
    private final Label[] targetValueLabels = new Label[MOVE_TARGET_KEYS.length];

    public ActionLogPanel(AppSettings settings) {
        this.settings = settings;
        // Themed background of its own: the mosaic hosts this panel over its
        // black root, where a transparent panel would be unreadable in light themes.
        setStyle("-fx-font-size: 11px; -fx-background-color: -fx-background;");

        listView.setPrefHeight(PANEL_HEIGHT);
        listView.setFocusTraversable(false);
        listView.setPlaceholder(new Label("No file actions this session"));
        listView.setCellFactory(view -> new EntryCell());
        HBox.setHgrow(listView, Priority.ALWAYS);

        // Follow the tail, like a terminal: the newest action stays visible.
        // Deferred so the virtual flow has re-laid-out for the new row first.
        ActionLog.get().entries().addListener((ListChangeListener<ActionLogEntry>) change ->
                Platform.runLater(() -> {
                    int size = listView.getItems().size();
                    if (size > 0) {
                        listView.scrollTo(size - 1);
                    }
                }));

        var content = new HBox(listView, new Separator(Orientation.VERTICAL),
                buildMoveTargetsPane());
        getChildren().addAll(new Separator(), content);

        ActionLog.get().moveTargetsRevision().addListener((o, a, v) -> refreshMoveTargets());
        refreshMoveTargets();

        // Entries seeded from the on-disk log (actionLog.file) predate this
        // panel; start scrolled to the newest, like the live tail-follow above.
        if (!listView.getItems().isEmpty()) {
            Platform.runLater(() -> listView.scrollTo(listView.getItems().size() - 1));
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
        return pane;
    }

    /**
     * Re-read the quick-move targets (persisted move history) and the transient
     * toggle from {@link AppSettings}; called on every targets-revision bump.
     */
    private void refreshMoveTargets() {
        boolean enabled = settings.quickMoveShortcutsEnabled();
        targetsToggle.setSelected(enabled);
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
        private final Label time = new Label();
        private final Label summary = new Label();
        private final HBox row = new HBox(8, time, summary);

        EntryCell() {
            time.setMinWidth(56);
            time.setStyle("-fx-opacity: 0.7;");
        }

        @Override
        protected void updateItem(ActionLogEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            time.setText(TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMillis())));
            summary.setText(entry.summary());
            setGraphic(row);
        }
    }
}
