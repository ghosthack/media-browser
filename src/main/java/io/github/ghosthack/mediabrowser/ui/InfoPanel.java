package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaService;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel showing the probe result of a media item as a two-column table;
 * overlong values are clipped by their cells. Used by the main window (for
 * the selected item) and by the viewer window (for the shown item). Above
 * the table, an optional File section shows facts read straight from the
 * filesystem stat — name, size, timestamps — the moment an item is selected,
 * before (and independent of) the potentially slow native probe. The section
 * is the same two-column table component, sized to its rows so it never
 * scrolls and the probe table keeps the remaining height.
 */
public final class InfoPanel extends VBox {

    /** One probe fact. */
    public record Row(String property, String value) {
    }

    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final double FILE_TABLE_ROW_HEIGHT = 24;

    private final TableView<Row> table = new TableView<>();
    private final Label placeholder = new Label("No selection");
    private final HBox header;
    private final TableView<Row> fileTable = new TableView<>();

    public InfoPanel() {
        var title = new Label("Info");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 6 8;");
        var headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        header = new HBox(title, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);

        addPropertyValueColumns(fileTable);
        fileTable.setFocusTraversable(false);
        fileTable.setFixedCellSize(FILE_TABLE_ROW_HEIGHT);
        // Exactly as tall as its rows plus the column-header strip (measured
        // once the skin builds it; a typical default until then), so it never
        // scrolls and the probe table below keeps the remaining height.
        var fileHeaderHeight = new SimpleDoubleProperty(26);
        fileTable.skinProperty().addListener((o, was, skin) -> {
            if (fileTable.lookup(".column-header-background") instanceof Region bg) {
                fileHeaderHeight.bind(bg.heightProperty());
            }
        });
        fileTable.prefHeightProperty().bind(Bindings.size(fileTable.getItems())
                .multiply(FILE_TABLE_ROW_HEIGHT).add(fileHeaderHeight).add(2));
        fileTable.minHeightProperty().bind(fileTable.prefHeightProperty());
        fileTable.maxHeightProperty().bind(fileTable.prefHeightProperty());
        setFileSectionVisible(false);

        addPropertyValueColumns(table);
        table.setPlaceholder(placeholder);
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(header, fileTable, table);
        setPrefWidth(280);
        setMinWidth(160);
    }

    /**
     * The shared two-column shape: Property (90px) and Value (the rest).
     * Package-visible so the Diagnostics panel renders in the same language.
     */
    static void addPropertyValueColumns(TableView<Row> table) {
        var property = new TableColumn<Row, String>("Property");
        property.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().property()));
        property.setPrefWidth(90);
        var value = new TableColumn<Row, String>("Value");
        value.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().value()));
        table.getColumns().setAll(List.of(property, value));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    /**
     * Appends a control (e.g. a pin toggle) to the right of the panel header.
     * Used by the viewer window; the main window leaves the header bare.
     */
    public void addHeaderControl(Node control) {
        header.getChildren().add(control);
    }

    public void show(MediaProbe probe) {
        table.getItems().setAll(probe.describe().entrySet().stream()
                .map(e -> new Row(e.getKey(), e.getValue()))
                .toList());
    }

    public void showMessage(String message) {
        table.getItems().clear();
        placeholder.setText(message);
    }

    /**
     * Populates the File section above the probe table; hidden while empty.
     * Rows typically come from {@link #fileFactRows}. Independent of
     * {@link #show}/{@link #showMessage}, which only touch the probe table.
     */
    public void showFileFacts(List<Row> rows) {
        fileTable.getItems().setAll(rows);
        setFileSectionVisible(!rows.isEmpty());
    }

    /** Empties and hides the File section (no selection, or a folder). */
    public void clearFileFacts() {
        fileTable.getItems().clear();
        setFileSectionVisible(false);
    }

    private void setFileSectionVisible(boolean visible) {
        fileTable.setVisible(visible);
        fileTable.setManaged(visible);
    }

    /** Display rows for the File section: name, exact size and stat timestamps. */
    public static List<Row> fileFactRows(String name, MediaService.FileFacts facts) {
        var rows = new ArrayList<Row>(5);
        rows.add(new Row("Name", name));
        rows.add(new Row("Size", facts.size() < 1024
                ? MediaProbe.humanBytes(facts.size())
                : MediaProbe.humanBytes(facts.size()) + " (" + facts.size() + " bytes)"));
        rows.add(new Row("Modified", FILE_TIME_FORMAT.format(facts.modified().toInstant())));
        rows.add(new Row("Created", FILE_TIME_FORMAT.format(facts.created().toInstant())));
        rows.add(new Row("Accessed", FILE_TIME_FORMAT.format(facts.accessed().toInstant())));
        return rows;
    }
}
