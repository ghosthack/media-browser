package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.Metadata;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/**
 * On-demand full-metadata panel for the viewer (a separate, slower companion to
 * the fast {@link InfoPanel}). Shows the raw {@link Metadata} of the current
 * item grouped by source (EXIF IFD0, XMP, Container, Stream N, …) in a
 * collapsible {@link TreeTableView}, with a filter, per-row copy and Copy All.
 *
 * <p><b>Manual by default.</b> The read is driven by the viewer; this panel only
 * exposes the controls. The {@code Load} button fires {@link #setOnLoadRequested}
 * for the on-screen item; the {@code Auto} toggle (default OFF) lets the viewer
 * opt into debounced auto-loading on navigation. Showing the panel reads
 * nothing — until a read lands the table shows a “Press Load…” placeholder.</p>
 *
 * <p><b>Safe by construction.</b> The model already caps every value to
 * {@link Metadata#MAX_VALUE_CHARS} and renders blobs as {@code <binary, N bytes>},
 * so the value cell never receives a 10k+ char string; the full value is used
 * only for copy.</p>
 */
public final class MetadataPanel extends VBox {

    private static final String PLACEHOLDER = "Press Load to read full metadata";

    /** A group header row; carries the entry count for the value column. */
    private record GroupHeader(String name, int count) {}

    private final TreeTableView<Object> table = new TreeTableView<>();
    private final TreeItem<Object> root = new TreeItem<>(null);
    private final Label placeholder = new Label(PLACEHOLDER);
    private final Button loadButton = new Button("Load");
    private final ToggleButton autoToggle = new ToggleButton("Auto");
    private final TextField filterField = new TextField();
    private final HBox titleRow;

    /** Most recently loaded metadata, kept unfiltered so the filter can re-render. */
    private Metadata master;
    private Runnable onLoadRequested = () -> { };

    public MetadataPanel() {
        var title = new Label("Metadata");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 6 8;");

        loadButton.setTooltip(new Tooltip("Read the full metadata of the on-screen item"));
        loadButton.setOnAction(e -> onLoadRequested.run());

        autoToggle.setTooltip(new Tooltip(
                "Auto-load metadata on navigation (off by default; debounced)"));

        var titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        titleRow = new HBox(6, title, titleSpacer, loadButton, autoToggle);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setPadding(new Insets(0, 6, 0, 0));

        filterField.setPromptText("Filter…");
        filterField.textProperty().addListener((o, was, now) -> applyFilter());
        HBox.setHgrow(filterField, Priority.ALWAYS);

        var copyAll = new MenuButton("Copy All");
        var copyText = new MenuItem("Copy as text");
        copyText.setOnAction(e -> copyToClipboard(asText()));
        var copyJson = new MenuItem("Copy as JSON");
        copyJson.setOnAction(e -> copyToClipboard(asJson()));
        copyAll.getItems().addAll(copyText, copyJson);

        var filterRow = new HBox(6, filterField, copyAll);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.setPadding(new Insets(2, 6, 4, 6));

        var keyCol = new TreeTableColumn<Object, String>("Key");
        keyCol.setCellValueFactory(d -> new ReadOnlyStringWrapper(keyText(d.getValue().getValue())));
        keyCol.setCellFactory(c -> styledKeyCell());
        keyCol.setPrefWidth(170);
        var valueCol = new TreeTableColumn<Object, String>("Value");
        valueCol.setCellValueFactory(d -> new ReadOnlyStringWrapper(valueText(d.getValue().getValue())));
        valueCol.setCellFactory(c -> styledValueCell());

        root.setExpanded(true);
        table.setRoot(root);
        table.setShowRoot(false);
        table.getColumns().setAll(List.of(keyCol, valueCol));
        table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(placeholder);
        table.setRowFactory(tv -> rowWithCopyMenu());
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(titleRow, filterRow, table);
        setPrefWidth(360);
        setMinWidth(200);
    }

    // --- viewer-facing API --------------------------------------------------

    /** Appends a control (the pin toggle) to the right of the header title row. */
    public void addHeaderControl(Node control) {
        titleRow.getChildren().add(control);
    }

    /** Callback fired when the user clicks {@code Load} (the viewer starts the read). */
    public void setOnLoadRequested(Runnable handler) {
        this.onLoadRequested = handler == null ? () -> { } : handler;
    }

    /** The {@code Auto} toggle state (default OFF); the viewer observes/binds this. */
    public BooleanProperty autoLoadProperty() {
        return autoToggle.selectedProperty();
    }

    public boolean isAutoLoad() {
        return autoToggle.isSelected();
    }

    /** Populates the table with a freshly read snapshot (or a "no metadata" note). */
    public void show(Metadata metadata) {
        this.master = metadata;
        if (metadata == null || metadata.isEmpty()) {
            this.master = null;
            root.getChildren().clear();
            placeholder.setText(metadata == null
                    ? PLACEHOLDER
                    : "No metadata exposed by this backend for this item");
            return;
        }
        applyFilter();
    }

    /** Clears the table and shows {@code message} in the placeholder area. */
    public void showMessage(String message) {
        this.master = null;
        root.getChildren().clear();
        placeholder.setText(message);
    }

    /** Resets to the manual-default placeholder for a newly navigated item. */
    public void resetToPlaceholder() {
        showMessage(PLACEHOLDER);
    }

    // --- rendering ----------------------------------------------------------

    /** Rebuilds the tree from {@link #master}, honouring the current filter. */
    private void applyFilter() {
        if (master == null) return;
        String needle = filterField.getText() == null ? ""
                : filterField.getText().strip().toLowerCase(Locale.ROOT);
        root.getChildren().clear();
        int shown = 0;
        for (Metadata.Group group : master.groups()) {
            var matches = group.entries().stream()
                    .filter(e -> matches(e, needle))
                    .toList();
            if (matches.isEmpty()) continue;
            var groupItem = new TreeItem<Object>(new GroupHeader(group.name(), matches.size()));
            groupItem.setExpanded(true);
            for (Metadata.Entry e : matches) {
                groupItem.getChildren().add(new TreeItem<>(e));
            }
            root.getChildren().add(groupItem);
            shown += matches.size();
        }
        if (shown == 0) placeholder.setText("No matches");
    }

    private static boolean matches(Metadata.Entry e, String needle) {
        if (needle.isEmpty()) return true;
        return e.key().toLowerCase(Locale.ROOT).contains(needle)
                || e.value().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String keyText(Object row) {
        if (row instanceof GroupHeader g) return g.name();
        if (row instanceof Metadata.Entry e) return e.key();
        return "";
    }

    private static String valueText(Object row) {
        if (row instanceof GroupHeader g) return g.count() + (g.count() == 1 ? " item" : " items");
        if (row instanceof Metadata.Entry e) return e.value();
        return "";
    }

    private TreeTableCell<Object, String> styledKeyCell() {
        return new TreeTableCell<>() {
            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                Object row = rowItem();
                setStyle(!empty && row instanceof GroupHeader ? "-fx-font-weight: bold;" : "");
            }

            private Object rowItem() {
                TreeTableRow<Object> r = getTableRow();
                return r == null ? null : r.getItem();
            }
        };
    }

    private TreeTableCell<Object, String> styledValueCell() {
        return new TreeTableCell<>() {
            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                if (empty) {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                    return;
                }
                setText(text);
                TreeTableRow<Object> r = getTableRow();
                Object row = r == null ? null : r.getItem();
                if (row instanceof Metadata.Entry e) {
                    setStyle(e.binary() ? "-fx-font-style: italic; -fx-text-fill: #b58900;" : "");
                    String tip = e.truncated()
                            ? e.value() + "\n…\n(" + e.fullValue().length()
                                    + " chars total — right-click to copy the full value)"
                            : e.value();
                    setTooltip(tooltip(tip));
                } else {
                    setStyle("");
                    setTooltip(null);
                }
            }
        };
    }

    private static Tooltip tooltip(String text) {
        var t = new Tooltip(text);
        t.setWrapText(true);
        t.setMaxWidth(600);
        return t;
    }

    private TreeTableRow<Object> rowWithCopyMenu() {
        var row = new TreeTableRow<Object>();
        var menu = new ContextMenu();
        var copyValue = new MenuItem("Copy value");
        copyValue.setOnAction(e -> withEntry(row, en -> copyToClipboard(en.fullValue())));
        var copyKey = new MenuItem("Copy key");
        copyKey.setOnAction(e -> withEntry(row, en -> copyToClipboard(en.key())));
        var copyPair = new MenuItem("Copy key = value");
        copyPair.setOnAction(e -> withEntry(row, en -> copyToClipboard(en.key() + " = " + en.fullValue())));
        menu.getItems().addAll(copyValue, copyKey, copyPair);
        // Only entry rows get the copy menu; group headers get none.
        row.itemProperty().addListener((o, was, now) ->
                row.setContextMenu(now instanceof Metadata.Entry ? menu : null));
        return row;
    }

    private static void withEntry(TreeTableRow<Object> row, java.util.function.Consumer<Metadata.Entry> action) {
        if (row.getItem() instanceof Metadata.Entry e) action.accept(e);
    }

    // --- copy ---------------------------------------------------------------

    private static void copyToClipboard(String text) {
        var content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Grouped {@code key\tfullValue} text of the full (unfiltered) snapshot. */
    private String asText() {
        if (master == null) return "";
        var sb = new StringBuilder();
        for (Metadata.Group g : master.groups()) {
            sb.append('[').append(g.name()).append("]\n");
            for (Metadata.Entry e : g.entries()) {
                sb.append(e.key()).append('\t').append(e.fullValue()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** {@code { "group": { "key": "fullValue" } }} of the full (unfiltered) snapshot. */
    private String asJson() {
        if (master == null) return "{}";
        var sb = new StringBuilder("{\n");
        List<Metadata.Group> groups = master.groups();
        for (int gi = 0; gi < groups.size(); gi++) {
            Metadata.Group g = groups.get(gi);
            sb.append("  ").append(jsonString(g.name())).append(": {\n");
            List<Metadata.Entry> entries = g.entries();
            for (int ei = 0; ei < entries.size(); ei++) {
                Metadata.Entry e = entries.get(ei);
                sb.append("    ").append(jsonString(e.key())).append(": ")
                        .append(jsonString(e.fullValue()));
                sb.append(ei < entries.size() - 1 ? ",\n" : "\n");
            }
            sb.append("  }").append(gi < groups.size() - 1 ? ",\n" : "\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonString(String s) {
        var sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
