package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaService;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Supplier;

/**
 * Right-edge panel of internal diagnostics for the thumbnail pipeline:
 * renditions processed so far, the worker pool's queue depth and active
 * thread count, and the in-memory cache's entry count and retained bytes —
 * a point-in-time {@link MediaService.ThumbnailStats} snapshot rendered in
 * the Info panel's two-column table language. A fresh snapshot is taken when
 * the panel is (re)shown and on its Update button; each window (browser,
 * mosaic, viewer) hosts its own instance.
 */
public final class DiagnosticsPanel extends VBox {

    private final Supplier<MediaService.ThumbnailStats> stats;
    private final TableView<InfoPanel.Row> table = new TableView<>();

    public DiagnosticsPanel(Supplier<MediaService.ThumbnailStats> stats) {
        this.stats = stats;

        var title = new Label("Diagnostics");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 6 8;");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var update = new Button("Update");
        update.setTooltip(new Tooltip(
                "Take a fresh snapshot of the thumbnail-pipeline counters"));
        update.setOnAction(e -> refresh());
        var header = new HBox(title, spacer, update);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-padding: 0 4 0 0;");

        InfoPanel.addPropertyValueColumns(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(header, table);
        setPrefWidth(280);
        setMinWidth(160);

        // A fresh snapshot whenever the panel is (re)attached — each window's
        // right split removes it while toggled off — so it never opens stale.
        sceneProperty().addListener((o, was, now) -> {
            if (now != null) refresh();
        });
    }

    /** Takes a fresh stats snapshot and repopulates the table. */
    private void refresh() {
        MediaService.ThumbnailStats s = stats.get();
        table.getItems().setAll(List.of(
                new InfoPanel.Row("Processed", Long.toString(s.processed())),
                new InfoPanel.Row("Queue", Integer.toString(s.queuedTasks())),
                new InfoPanel.Row("Active threads",
                        s.activeThreads() + " of " + s.poolThreads()),
                new InfoPanel.Row("Items in memory", Integer.toString(s.cachedItems())),
                new InfoPanel.Row("Memory", MediaProbe.humanBytes(s.cachedBytes())
                        + " of " + MediaProbe.humanBytes(s.budgetBytes()))));
    }
}
