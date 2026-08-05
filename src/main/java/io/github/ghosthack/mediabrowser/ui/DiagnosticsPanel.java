package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaEngineTrace;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.ffm.HwDecode;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Right-edge panel of media-engine and thumbnail-pipeline diagnostics. It
 * exposes the user-selected engine plus the latest foreground visual's ordered
 * internal strategy/failure chain, followed by thumbnail worker/cache counters.
 * A fresh snapshot is taken when the panel is (re)shown, after viewer decode,
 * and on its Update button; each window hosts its own instance.
 */
public final class DiagnosticsPanel extends VBox {

    private final MediaService service;
    private final TableView<InfoPanel.Row> table = new TableView<>();

    public DiagnosticsPanel(MediaService service) {
        this.service = service;

        var title = new Label("Diagnostics");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 6 8;");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var update = new Button("Update");
        update.setTooltip(new Tooltip(
                "Take a fresh media-engine and thumbnail-pipeline snapshot"));
        update.setOnAction(e -> refresh());
        var header = new HBox(title, spacer, update);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-padding: 0 4 0 0;");
        header.getStyleClass().addAll("tool-bar", "side-panel-header");

        InfoPanel.configurePropertyValueTable(table);
        @SuppressWarnings("unchecked")
        TableColumn<InfoPanel.Row, String> valueColumn =
                (TableColumn<InfoPanel.Row, String>) table.getColumns().get(1);
        valueColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTooltip(empty || item == null || item.isBlank()
                        ? null : new Tooltip(item));
            }
        });
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

    /** Takes a fresh diagnostic snapshot and repopulates the table. */
    public void refresh() {
        table.getItems().setAll(rows(
                service.mediaEngine(),
                service.latestVisualTrace().orElse(null),
                service.latestThumbnailTrace().orElse(null),
                service.latestPlaybackTrace().orElse(null),
                service.thumbnailStats(),
                service.staleVisualRequests()));
    }

    static List<InfoPanel.Row> rows(
            String engine,
            MediaEngineTrace trace,
            MediaService.ThumbnailStats s) {
        return rows(engine, trace, null, null, s, 0);
    }

    static List<InfoPanel.Row> rows(
            String engine,
            MediaEngineTrace visual,
            MediaEngineTrace thumbnail,
            MediaEngineTrace playback,
            MediaService.ThumbnailStats s,
            long staleVisuals) {
        List<InfoPanel.Row> rows = new ArrayList<>();
        rows.add(new InfoPanel.Row("Media engine", visual == null ? engine : visual.engine()));
        if (visual == null) {
            rows.add(new InfoPanel.Row("Last visual", "No foreground decode recorded"));
        } else {
            String path = name(visual);
            rows.add(new InfoPanel.Row("Last visual", path));
            rows.add(new InfoPanel.Row("Operation", visual.operation()));
            rows.add(new InfoPanel.Row("Decode result",
                    sentence(visual.outcome()) + " in " + duration(visual.elapsedNanos())));
            addTimings(rows, visual.timings());
            addAttempts(rows, "Visual route ", visual);
        }
        rows.add(new InfoPanel.Row("Stale visuals skipped", Long.toString(staleVisuals)));

        if (thumbnail == null) {
            rows.add(new InfoPanel.Row("Last thumbnail", "No generation recorded"));
        } else {
            rows.add(new InfoPanel.Row("Last thumbnail", name(thumbnail) + " — "
                    + sentence(thumbnail.outcome()) + " in "
                    + duration(thumbnail.elapsedNanos())));
            addTimings(rows, thumbnail.timings());
            addAttempts(rows, "Thumbnail route ", thumbnail);
        }
        rows.add(new InfoPanel.Row("Thumbnail work", s.processed() + " generated / "
                + s.cacheHits() + " cache hits / " + s.inFlightJoins() + " joins"));
        rows.addAll(List.of(
                new InfoPanel.Row("Thumbnail queue", Integer.toString(s.queuedTasks())),
                new InfoPanel.Row("Thumbnail workers",
                        s.activeThreads() + " of " + s.poolThreads()),
                new InfoPanel.Row("Thumbnail cache", s.cachedItems() + " items — "
                        + MediaProbe.humanBytes(s.cachedBytes()) + " of "
                        + MediaProbe.humanBytes(s.budgetBytes()))));

        if (playback == null) {
            rows.add(new InfoPanel.Row("Last playback", "No session recorded"));
        } else {
            rows.add(new InfoPanel.Row("Last playback", name(playback) + " — "
                    + sentence(playback.outcome())));
            addAttempts(rows, "Playback route ", playback);
        }
        rows.add(new InfoPanel.Row("Playback totals",
                HwDecode.hwSessions() + " hw / " + HwDecode.swSessions()
                + " sw; requested "
                + HwDecode.policy().name().toLowerCase(Locale.ROOT)));
        return List.copyOf(rows);
    }

    private static String name(MediaEngineTrace trace) {
        return trace.path().getFileName() == null
                ? trace.path().toString() : trace.path().getFileName().toString();
    }

    private static void addTimings(List<InfoPanel.Row> rows, MediaEngineTrace.Timings t) {
        if (t.queueNanos() >= 0) rows.add(new InfoPanel.Row("Queue wait", duration(t.queueNanos())));
        if (t.engineNanos() >= 0) rows.add(new InfoPanel.Row("Engine work", duration(t.engineNanos())));
        if (t.postProcessNanos() >= 0) {
            rows.add(new InfoPanel.Row("Post-process", duration(t.postProcessNanos())));
        }
        rows.add(new InfoPanel.Row("Time to display", t.timeToDisplayNanos() < 0
                ? "Not presented by this view" : duration(t.timeToDisplayNanos())));
    }

    private static void addAttempts(List<InfoPanel.Row> rows, String label,
                                    MediaEngineTrace trace) {
        int index = 1;
        for (MediaEngineTrace.Attempt attempt : trace.attempts()) {
            String value = attempt.strategy()
                    + " — " + attempt.outcome().name().toLowerCase(Locale.ROOT)
                    + " in " + duration(attempt.elapsedNanos());
            if (!attempt.detail().isBlank()) value += " — " + attempt.detail();
            rows.add(new InfoPanel.Row(label + index++, value));
        }
    }

    private static String sentence(MediaEngineTrace.Outcome outcome) {
        String value = outcome.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String duration(long nanos) {
        double millis = nanos / 1_000_000.0;
        if (millis < 0.1) return "<0.1 ms";
        if (millis < 1000.0) return String.format(Locale.ROOT, "%.1f ms", millis);
        return String.format(Locale.ROOT, "%.2f s", millis / 1000.0);
    }
}
