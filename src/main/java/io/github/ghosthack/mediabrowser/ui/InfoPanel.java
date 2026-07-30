package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaService;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Panel showing the probe result of a media item as a two-column table;
 * overlong values are clipped by their cells. Used by the main window (for
 * the selected item) and by the viewer window (for the shown item). Above
 * the table, an optional File section shows facts read straight from the
 * filesystem stat — file name/size/timestamps or folder name/timestamps — the
 * moment an item is selected, before (and independent of) the potentially slow
 * native probe. A folder also shows its direct item count when an existing
 * listing already supplied it. The section is the same two-column table
 * component, sized to its rows so it never scrolls and the probe table keeps
 * the remaining height.
 */
public final class InfoPanel extends VBox {

    /** One probe fact. */
    public record Row(String property, String value) {
    }

    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Both halves of the Info panel use the same compact row rhythm. Some
     * themes give ordinary TableViews substantially taller rows (Cupertino
     * uses 3em), which otherwise makes the probe rows below the File section
     * appear roughly twice as tall as the filesystem rows above it.
     */
    private static final double INFO_TABLE_ROW_HEIGHT = 24;

    /**
     * Width of the leading Property column, shared with the Diagnostics panel's
     * table and the Metadata panel's Key column so every table in the right-edge
     * stack lines its value column up at the same x.
     */
    static final double PROPERTY_COLUMN_WIDTH = 120;

    private final TableView<Row> table = new TableView<>();
    private final Label placeholder = new Label("No selection");
    private final HBox header;
    private final TableView<Row> fileTable = new TableView<>();
    /** The probe's rows as given, before the File section's duplicates are dropped. */
    private List<Row> probeRows = List.of();

    public InfoPanel() {
        var title = new Label("Info");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 6 8;");
        var headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        header = new HBox(title, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().addAll("tool-bar", "side-panel-header");

        configurePropertyValueTable(fileTable);
        fileTable.setFocusTraversable(false);
        // Exactly as tall as its rows (the column-header strip is hidden), so
        // it never scrolls and the probe table below keeps the remaining height.
        fileTable.prefHeightProperty().bind(Bindings.size(fileTable.getItems())
                .multiply(INFO_TABLE_ROW_HEIGHT).add(2));
        // The two tables stack flush and each draws its own 1px box, so their
        // seam came out a doubled line. Overlap by exactly that pixel — the
        // themes draw the box as a border (Cupertino) or as background layers
        // showing through insets (Modena), so there is no one property to zero.
        VBox.setMargin(fileTable, new Insets(0, 0, -1, 0));
        fileTable.minHeightProperty().bind(fileTable.prefHeightProperty());
        fileTable.maxHeightProperty().bind(fileTable.prefHeightProperty());
        setFileSectionVisible(false);

        configurePropertyValueTable(table);
        table.setPlaceholder(placeholder);
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(header, fileTable, table);
        setPrefWidth(280);
        setMinWidth(160);
    }

    /**
     * Applies the shared headerless two-column property table: Property
     * ({@link #PROPERTY_COLUMN_WIDTH}) and Value (the rest), using the Info
     * panel's compact row rhythm. Package-visible so Diagnostics renders with
     * exactly the same table treatment.
     */
    static void configurePropertyValueTable(TableView<Row> table) {
        var property = new TableColumn<Row, String>("Property");
        property.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().property()));
        fixWidth(property);
        var value = new TableColumn<Row, String>("Value");
        value.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().value()));
        table.getColumns().setAll(List.of(property, value));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        configurePropertyTableChrome(table);
    }

    /**
     * Applies the same Property-column alignment and headerless compact rows to
     * Metadata's tree table while leaving its custom grouped columns intact.
     */
    static void configurePropertyValueTable(
            TreeTableView<?> table, TableColumnBase<?, ?> propertyColumn) {
        fixWidth(propertyColumn);
        configurePropertyTableChrome(table);
    }

    private static void configurePropertyTableChrome(TableView<?> table) {
        table.getStyleClass().add("headerless-table");
        table.setFixedCellSize(INFO_TABLE_ROW_HEIGHT);
    }

    private static void configurePropertyTableChrome(TreeTableView<?> table) {
        table.getStyleClass().add("headerless-table");
        table.setFixedCellSize(INFO_TABLE_ROW_HEIGHT);
    }

    /**
     * Nails a leading Property/Key column to {@link #PROPERTY_COLUMN_WIDTH}.
     *
     * <p>A preferred width alone does not hold it there: the constrained resize
     * policy splits the leftover width <i>equally</i> between the two columns,
     * so each table's split fell where its own available width put it — and a
     * vertical scrollbar, which appears on one table and not its neighbours,
     * takes ~20px off that width and shifted the split by ~10px. Pinning min =
     * max leaves the Value column as the only flexible one, so every table in
     * the stack breaks at the same x, scrollbar or not.</p>
     */
    static void fixWidth(TableColumnBase<?, ?> column) {
        column.setPrefWidth(PROPERTY_COLUMN_WIDTH);
        column.setMinWidth(PROPERTY_COLUMN_WIDTH);
        column.setMaxWidth(PROPERTY_COLUMN_WIDTH);
    }

    /**
     * Appends a control (e.g. a pin toggle) to the right of the panel header.
     * Used by the viewer window; the main window leaves the header bare.
     */
    public void addHeaderControl(Node control) {
        header.getChildren().add(control);
    }

    public void show(MediaProbe probe) {
        probeRows = probe.describe().entrySet().stream()
                .map(e -> new Row(e.getKey(), e.getValue()))
                .toList();
        showProbeRows();
    }

    public void showMessage(String message) {
        probeRows = List.of();
        table.getItems().clear();
        placeholder.setText(message);
    }

    /**
     * Shows arbitrary detail rows in the lower table, the same slot a probe
     * fills. Used for a selected archive's format and volume identity, which is
     * detail about the file rather than a media probe — the File section above
     * is untouched and keeps showing the container's own name, size and
     * timestamps.
     */
    public void showDetailRows(List<Row> rows) {
        probeRows = List.copyOf(rows);
        showProbeRows();
    }

    /**
     * Populates the File section above the probe table; hidden while empty.
     * Rows typically come from {@link #fileFactRows}. Independent of
     * {@link #show}/{@link #showMessage}, which only touch the probe table —
     * beyond hiding the probe rows this section already states.
     */
    public void showFileFacts(List<Row> rows) {
        fileTable.getItems().setAll(rows);
        setFileSectionVisible(!rows.isEmpty());
        showProbeRows();
    }

    /** Empties and hides the File section when there is no factual selection. */
    public void clearFileFacts() {
        fileTable.getItems().clear();
        setFileSectionVisible(false);
        showProbeRows();
    }

    /**
     * Adds or refreshes a folder's known direct-child count without disturbing
     * its name or dates. Callers invoke this only when an already-running
     * listing supplied the count; this method performs no I/O.
     */
    public void showKnownFolderItemCount(int count) {
        var rows = new ArrayList<>(fileTable.getItems());
        rows.removeIf(row -> "Items".equals(row.property()));
        int afterName = !rows.isEmpty() && "Name".equals(rows.getFirst().property()) ? 1 : 0;
        rows.add(afterName, new Row("Items", Integer.toString(Math.max(0, count))));
        showFileFacts(rows);
    }

    /**
     * Fills the probe table, dropping the facts the File section above already
     * shows (Name, Size — the probe reports both). Re-run whenever either half
     * changes, since the probe usually lands after the stat facts but a cleared
     * File section has to hand its rows back.
     */
    private void showProbeRows() {
        var alreadyShown = fileTable.getItems().stream().map(Row::property).toList();
        table.getItems().setAll(probeRows.stream()
                .filter(row -> !alreadyShown.contains(row.property()))
                .toList());
    }

    private void setFileSectionVisible(boolean visible) {
        fileTable.setVisible(visible);
        fileTable.setManaged(visible);
    }

    /** Display rows for a file: name, exact size and stat timestamps. */
    public static List<Row> fileFactRows(String name, MediaService.FileFacts facts) {
        var rows = new ArrayList<Row>(5);
        rows.add(new Row("Name", name));
        rows.add(new Row("Size", facts.size() < 1024
                ? MediaProbe.humanBytes(facts.size())
                : MediaProbe.humanBytes(facts.size()) + " (" + facts.size() + " bytes)"));
        addDateRows(rows, facts);
        return rows;
    }

    /** Immediate folder facts available before its asynchronous stat lands. */
    public static List<Row> folderFactRows(String name, OptionalInt itemCount) {
        var rows = new ArrayList<Row>(5);
        rows.add(new Row("Name", name));
        itemCount.ifPresent(count -> rows.add(new Row("Items", Integer.toString(count))));
        return rows;
    }

    /** Folder name, optional known child count, and filesystem timestamps. */
    public static List<Row> folderFactRows(
            String name, MediaService.FileFacts facts, OptionalInt itemCount) {
        var rows = new ArrayList<>(folderFactRows(name, itemCount));
        addDateRows(rows, facts);
        return rows;
    }

    private static void addDateRows(List<Row> rows, MediaService.FileFacts facts) {
        rows.add(new Row("Modified", FILE_TIME_FORMAT.format(facts.modified().toInstant())));
        rows.add(new Row("Created", FILE_TIME_FORMAT.format(facts.created().toInstant())));
        rows.add(new Row("Accessed", FILE_TIME_FORMAT.format(facts.accessed().toInstant())));
    }
}
