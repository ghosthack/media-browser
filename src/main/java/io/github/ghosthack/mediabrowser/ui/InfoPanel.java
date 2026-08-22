package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaService;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Panel showing everything known about the selected item as one two-column
 * table; overlong values are clipped by their cells. Used by the main window
 * (for the selected item) and by the viewer window (for the shown item). The
 * leading rows are file facts read straight from the filesystem stat — file
 * name/size/timestamps or folder name/timestamps — the moment an item is
 * selected, before (and independent of) the potentially slow native probe,
 * whose rows follow below once it lands. A folder also shows its direct item
 * count when an existing listing already supplied it. While the probe half is
 * pending or failed, its status message shows as the table placeholder when no
 * rows exist yet, or as a trailing row under the file facts.
 */
public final class InfoPanel extends VBox {

    /** One probe fact. */
    public record Row(String property, String value) {
    }

    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * The Info panel's compact row rhythm. Some themes give ordinary
     * TableViews substantially taller rows (Cupertino uses 3em), which would
     * make these dense fact rows waste most of the panel.
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
    /** The filesystem-stat rows currently shown at the top of the table. */
    private List<Row> fileRows = List.of();
    /** The probe's rows as given, before the file facts' duplicates are dropped. */
    private List<Row> probeRows = List.of();
    /** Status of the probe half (Probing…, error) while it has no rows; null once it does. */
    private String message;

    public InfoPanel() {
        header = SidePanelHeader.create("Info");

        configurePropertyValueTable(table);
        table.setPlaceholder(placeholder);
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(header, table);
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
        // Free CSS hook (unstyled by every theme except the Compose pair, whose
        // property-column-cell rule tints it with the kotlin design's
        // textMuted role): lets a theme tell the leading column apart from
        // the value column without a per-theme column-index hack.
        property.getStyleClass().add("property-column-cell");
        var value = new TableColumn<Row, String>("Value");
        value.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().value()));
        table.getColumns().setAll(List.of(property, value));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        configurePropertyTableChrome(table);
    }

    /**
     * Applies the same initial Property-column alignment and headerless compact
     * rows to Metadata's tree table while leaving its columns resizable.
     */
    static void configurePropertyValueTable(
            TreeTableView<?> table, TableColumnBase<?, ?> propertyColumn) {
        propertyColumn.setPrefWidth(PROPERTY_COLUMN_WIDTH);
        propertyColumn.getStyleClass().add("property-column-cell");
        configurePropertyTableChrome(table);
    }

    private static void configurePropertyTableChrome(TableView<?> table) {
        table.getStyleClass().addAll("headerless-table", "striped");
        table.setFixedCellSize(INFO_TABLE_ROW_HEIGHT);
    }

    private static void configurePropertyTableChrome(TreeTableView<?> table) {
        table.getStyleClass().addAll("headerless-table", "striped");
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
        message = null;
        render();
    }

    /**
     * Replaces the probe half with a status message (Probing…, a decode error,
     * "No selection"). Shown as the table placeholder while no file facts are
     * up, otherwise as a trailing row so it stays visible under them.
     */
    public void showMessage(String message) {
        probeRows = List.of();
        this.message = message;
        render();
    }

    /**
     * Shows arbitrary detail rows in the probe slot. Used for a selected
     * archive's format and volume identity, which is detail about the file
     * rather than a media probe — the file-fact rows above are untouched and
     * keep showing the container's own name, size and timestamps.
     */
    public void showDetailRows(List<Row> rows) {
        probeRows = List.copyOf(rows);
        message = null;
        render();
    }

    /**
     * Replaces the file-fact rows at the top of the table; rows typically come
     * from {@link #fileFactRows}. Independent of {@link #show}/
     * {@link #showMessage}, which only touch the probe half below.
     */
    public void showFileFacts(List<Row> rows) {
        fileRows = List.copyOf(rows);
        render();
    }

    /** Drops the file-fact rows when there is no factual selection. */
    public void clearFileFacts() {
        fileRows = List.of();
        render();
    }

    /**
     * Adds or refreshes a folder's known direct-child count without disturbing
     * its name or dates. Callers invoke this only when an already-running
     * listing supplied the count; this method performs no I/O.
     */
    public void showKnownFolderItemCount(int count) {
        var rows = new ArrayList<>(fileRows);
        rows.removeIf(row -> "Items".equals(row.property()));
        int afterName = !rows.isEmpty() && "Name".equals(rows.getFirst().property()) ? 1 : 0;
        rows.add(afterName, new Row("Items", Integer.toString(Math.max(0, count))));
        showFileFacts(rows);
    }

    /**
     * Rebuilds the table: file facts first, then the probe rows minus the
     * facts already stated above (Name, Size — the probe reports both), then
     * the pending/failed probe's status message as a trailing row. Re-run
     * whenever any part changes, since the probe usually lands after the stat
     * facts but cleared file facts have to hand their rows back.
     */
    private void render() {
        var rows = new ArrayList<>(fileRows);
        var alreadyShown = fileRows.stream().map(Row::property).toList();
        probeRows.stream()
                .filter(row -> !alreadyShown.contains(row.property()))
                .forEach(rows::add);
        if (message != null && !rows.isEmpty()) {
            rows.add(new Row("", message));
        }
        placeholder.setText(message == null ? "No selection" : message);
        table.getItems().setAll(rows);
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
