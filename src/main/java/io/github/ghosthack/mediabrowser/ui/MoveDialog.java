package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.move.MoveDialogFocusZone;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogIntents;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogModel;
import io.github.ghosthack.mediabrowser.media.move.TreeNode;

import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Move dialog — a custom modal {@link Stage} that renders a
 * {@link MoveDialogModel} and emits {@link MoveDialogIntents}. It is a pure
 * <em>view</em>: it holds no move logic and never mutates the model. A driver
 * (a Phase&nbsp;5 controller, a window, or the manual harness) handles each
 * intent, mutates the model, and calls {@link #refresh()} to reconcile the UI —
 * exactly mirroring iris's {@code MoveDialog.syncFromState} reacting to store
 * change events.
 *
 * <p>Three keyboard-focus zones (target {@code INPUT}, recent-targets
 * {@code HISTORY}, browse {@code TREE}) are framed by a border that brightens
 * and thickens on the active zone. Tab/Shift-Tab/Esc/Enter and the per-zone
 * arrow keys are intercepted by a single {@code KEY_PRESSED} filter on the root
 * so JavaFX's native focus traversal never interferes (ported from iris's
 * {@code setFocusTraversalKeysEnabled(false)} + {@code handleDialogKey}).
 *
 * <p>All programmatic writes back into the {@link TextField}, {@link ListView},
 * {@link TreeView} and {@link CheckBox} happen under the {@link #syncing} guard
 * so they never re-fire the change listeners that emit intents — the JavaFX
 * equivalent of iris's {@code suppressTargetSync} / {@code suppressQuickMoveSync}
 * / {@code suppressTreeExpansionEvents} flags.
 */
public final class MoveDialog {

    private final Stage stage = new Stage();
    private final MoveDialogModel model;
    private final MoveDialogIntents intents;

    private final Label headerLabel = new Label();
    private final Label parentExcludedLabel = new Label("(parent directory \"..\" excluded)");
    private final TextField targetField = new TextField();
    private final ListView<String> historyList = new ListView<>();
    private final TreeView<TreeNodeData> tree = new TreeView<>();
    private final TreeItem<TreeNodeData> treeRoot = new TreeItem<>(new TreeNodeData("", ""));
    private final CheckBox quickMoveCheckBox =
            new CheckBox("Enable quick-move shortcuts (F1–F4)");
    private final Label errorLabel = new Label();
    private final Button cancelButton = new Button("Cancel");
    private final Button moveButton = new Button("Move");

    private VBox inputZone;
    private VBox historyZone;
    private VBox treeZone;
    private VBox root;

    /**
     * The recent-target list to display. Owned by the driver (it lives in
     * {@code AppSettings}, not the model), so it is pushed in via
     * {@link #setHistory(List)} rather than read off the model — mirroring how
     * iris's view read {@code state.getMoveHistory()} rather than the dialog
     * state. Empty until the driver supplies it.
     */
    private List<String> history = List.of();

    /**
     * True while {@link #refresh()} is pushing model state into the controls, so
     * the controls' own change listeners short-circuit instead of emitting
     * intents back. The suppress-flag discipline from the handoff §2.9.
     */
    private boolean syncing = false;

    /**
     * The highlighted path last pushed into the {@link TreeView}.
     * {@link #rebuildTree()} only scrolls the tree to reveal the highlight when
     * it has actually moved — so a pure expand/collapse (which reconciles the
     * tree in place, preserving scroll) never yanks the view back to the
     * selected node. {@code null} until the first refresh.
     */
    private String lastHighlightedPath = null;

    public MoveDialog(Stage owner, MoveDialogModel model, MoveDialogIntents intents) {
        this.model = model;
        this.intents = intents;

        buildUi();
        wireListeners();

        Scene scene = new Scene(root, 560, 600);
        var css = MoveDialog.class.getResource("move-dialog.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        ThemeManager.get().register(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

        stage.setScene(scene);
        stage.setTitle("Move Files");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setOnCloseRequest(this::onCloseRequest);
    }

    // ---- Layout -------------------------------------------------------------

    private void buildUi() {
        headerLabel.getStyleClass().add("move-header");

        parentExcludedLabel.getStyleClass().add("move-note");
        parentExcludedLabel.setVisible(false);
        parentExcludedLabel.setManaged(false);

        // INPUT zone: "Target directory:" + free-text field.
        Label targetLabel = fieldLabel("Target directory:");
        targetField.setFocusTraversable(false);
        inputZone = zone(targetLabel, targetField);

        // HISTORY zone: "Recent targets:" + recent-target list.
        Label historyLabel = fieldLabel("Recent targets:");
        historyList.getStyleClass().add("move-history");
        historyList.setFocusTraversable(false);
        // Show at least 3 recent targets without scrolling. A fixed cell size
        // makes the row count -> height mapping predictable across platforms.
        double historyCell = 24;
        historyList.setFixedCellSize(historyCell);
        double historyPad = 4; // ListView's own top+bottom inset
        historyList.setMinHeight(historyCell * 3 + historyPad);
        historyList.setPrefHeight(historyCell * 4 + historyPad);
        historyList.setMaxHeight(historyCell * 4 + historyPad);
        historyZone = zone(historyLabel, historyList);

        // TREE zone: "Browse:" + lazy directory tree.
        Label treeLabel = fieldLabel("Browse:");
        tree.getStyleClass().add("move-tree");
        tree.setFocusTraversable(false);
        tree.setShowRoot(false);
        tree.setRoot(treeRoot);
        tree.setCellFactory(t -> new HighlightCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        treeZone = zone(treeLabel, tree);
        VBox.setVgrow(treeZone, Priority.ALWAYS);

        quickMoveCheckBox.setFocusTraversable(false);

        errorLabel.getStyleClass().add("move-error");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        moveButton.setDefaultButton(false);
        HBox buttons = new HBox(8, spacer, cancelButton, moveButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        root = new VBox(8,
                headerLabel, parentExcludedLabel,
                inputZone, historyZone, treeZone,
                quickMoveCheckBox, errorLabel, buttons);
        root.getStyleClass().add("move-dialog");
        root.setPadding(new Insets(16));
        root.setFocusTraversable(true);
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("move-field-label");
        return label;
    }

    private static VBox zone(Region... children) {
        VBox box = new VBox(4, children);
        box.getStyleClass().add("move-zone");
        return box;
    }

    // ---- Listeners ----------------------------------------------------------

    private void wireListeners() {
        cancelButton.setOnAction(e -> intents.cancel());
        moveButton.setOnAction(e -> intents.submit());

        targetField.textProperty().addListener((o, old, text) -> {
            if (!syncing) {
                intents.setTargetPath(text);
            }
        });

        quickMoveCheckBox.selectedProperty().addListener((o, was, now) -> {
            if (!syncing) {
                intents.setQuickMoveShortcutsEnabled(now);
            }
        });

        // History: click-select fills the target field (matches iris mouseClicked).
        historyList.setOnMouseClicked(e -> {
            int index = historyList.getSelectionModel().getSelectedIndex();
            if (!syncing && index >= 0) {
                intents.selectHistoryEntry(index);
            }
        });

        // Tree: click-select a node fills the target field.
        tree.setOnMouseClicked(e -> {
            TreeItem<TreeNodeData> item = tree.getSelectionModel().getSelectedItem();
            if (!syncing && item != null && item.getValue() != null
                    && !item.getValue().path().isEmpty()) {
                intents.selectTreeNode(item.getValue().path());
            }
        });

        // Lazy expand / collapse. Events bubble to the (permanent) root, so a
        // single pair of handlers covers the whole tree; the syncing guard stops
        // the programmatic expansion done during refresh() from re-firing.
        treeRoot.addEventHandler(TreeItem.<TreeNodeData>branchExpandedEvent(), e -> {
            TreeItem<TreeNodeData> item = e.getTreeItem();
            if (!syncing && item != null && item.getValue() != null
                    && !item.getValue().path().isEmpty()) {
                intents.expandTreeNode(item.getValue().path());
            }
        });
        treeRoot.addEventHandler(TreeItem.<TreeNodeData>branchCollapsedEvent(), e -> {
            TreeItem<TreeNodeData> item = e.getTreeItem();
            if (!syncing && item != null && item.getValue() != null
                    && !item.getValue().path().isEmpty()) {
                intents.collapseTreeNode(item.getValue().path());
            }
        });
    }

    /**
     * The single dialog-level key router (ported from iris's
     * {@code handleDialogKey}). Esc cancels and Tab cycles zones from anywhere;
     * Enter submits from anywhere (one handler, so no double submit); arrows
     * route per the active zone. INPUT deliberately leaves arrows alone so the
     * text caret still moves.
     */
    private void handleKey(KeyEvent e) {
        KeyCode code = e.getCode();
        if (code == KeyCode.ESCAPE) {
            e.consume();
            intents.cancel();
            return;
        }
        if (code == KeyCode.TAB) {
            e.consume();
            intents.cycleFocusZone(e.isShiftDown());
            return;
        }
        if (code == KeyCode.ENTER) {
            e.consume();
            intents.submit();
            return;
        }

        switch (model.getFocusZone()) {
            case HISTORY -> {
                if (code == KeyCode.UP) { e.consume(); intents.historyUp(); }
                else if (code == KeyCode.DOWN) { e.consume(); intents.historyDown(); }
            }
            case TREE -> {
                if (code == KeyCode.UP) { e.consume(); intents.treeUp(); }
                else if (code == KeyCode.DOWN) { e.consume(); intents.treeDown(); }
                else if (code == KeyCode.LEFT) { e.consume(); intents.treeLeft(); }
                else if (code == KeyCode.RIGHT) { e.consume(); intents.treeRight(); }
            }
            case INPUT -> { /* caret movement handled natively by the field */ }
        }
    }

    private void onCloseRequest(WindowEvent e) {
        // Route the window-manager close through the same cancel intent so the
        // driver can reset model state, then let the close proceed.
        intents.cancel();
    }

    // ---- Reconcile from model ----------------------------------------------

    /**
     * Reconcile every control from the current {@link MoveDialogModel} — the
     * analog of iris's {@code syncFromState}. Idempotent and re-entrancy-safe:
     * all writes happen under {@link #syncing} so the controls' listeners do not
     * feed intents back while we are pushing state in. Call this after handling
     * any intent that mutated the model.
     */
    public void refresh() {
        syncing = true;
        try {
            int count = model.getSourceFilePaths().size();
            headerLabel.setText("Move " + count + " item" + (count == 1 ? "" : "s"));

            boolean parentExcluded = model.isParentEntryExcluded();
            parentExcludedLabel.setVisible(parentExcluded);
            parentExcludedLabel.setManaged(parentExcluded);

            String target = model.getTargetPath() == null ? "" : model.getTargetPath();
            if (!targetField.getText().equals(target)) {
                targetField.setText(target);
                targetField.positionCaret(target.length());
            }

            // History list + selection. The zone is hidden entirely when empty
            // (Tab cycling skips it too — see MoveDialogLogic.cycleFocusZone).
            if (!historyList.getItems().equals(history)) {
                historyList.getItems().setAll(history);
            }
            boolean hasHistory = !history.isEmpty();
            historyZone.setVisible(hasHistory);
            historyZone.setManaged(hasHistory);
            int histIdx = model.getHistoryHighlightedIndex();
            if (histIdx >= 0 && histIdx < history.size()) {
                historyList.getSelectionModel().select(histIdx);
                historyList.scrollTo(histIdx);
            } else {
                historyList.getSelectionModel().clearSelection();
            }

            rebuildTree();

            if (quickMoveCheckBox.isSelected() != model.isQuickMoveShortcutsEnabled()) {
                quickMoveCheckBox.setSelected(model.isQuickMoveShortcutsEnabled());
            }

            String error = model.getInlineError();
            boolean hasError = error != null && !error.isEmpty();
            errorLabel.setText(hasError ? error : "");
            errorLabel.setVisible(hasError);
            errorLabel.setManaged(hasError);

            boolean moving = model.isMoving();
            moveButton.setDisable(moving);

            applyZoneBorders();
            applyZoneFocus();
        } finally {
            syncing = false;
        }
    }

    /**
     * Provide the recent-target list the dialog should display. Call before
     * {@link #refresh()}.
     */
    public void setHistory(List<String> history) {
        this.history = history == null ? List.of() : List.copyOf(history);
    }

    private void rebuildTree() {
        // Reconcile the TreeItem graph in place (reusing items by path) so an
        // expand/collapse never throws the graph away — the old setAll rebuild
        // reset the TreeView's scroll position on every structural change.
        reconcileChildren(treeRoot, model.getMiniTreeNodes());

        String highlighted = model.getMiniTreeHighlightedPath();
        boolean highlightChanged = !Objects.equals(highlighted, lastHighlightedPath);
        lastHighlightedPath = highlighted;

        if (highlighted != null && !highlighted.isEmpty()) {
            TreeItem<TreeNodeData> item = findByPath(treeRoot, highlighted);
            if (item != null) {
                tree.getSelectionModel().select(item);
                int row = tree.getRow(item);
                // Reveal the highlight only when it actually moved and isn't
                // already on screen. A pure expand/collapse (highlight unchanged)
                // leaves the scroll position alone, so opening an unrelated
                // directory never yanks the view back to the selected node.
                if (row >= 0 && highlightChanged && !isRowVisible(row)) {
                    tree.scrollTo(row);
                }
            } else {
                tree.getSelectionModel().clearSelection();
            }
        } else {
            tree.getSelectionModel().clearSelection();
        }
        tree.refresh();
    }

    /**
     * Whether the cell at {@code row} is currently rendered fully within the
     * tree's viewport. Cells outside the viewport (or in the off-screen render
     * buffer) report not-visible, so the caller scrolls to reveal them; cells
     * already on screen are left untouched to preserve the scroll position.
     */
    private boolean isRowVisible(int row) {
        javafx.geometry.Bounds view = tree.localToScene(tree.getLayoutBounds());
        for (javafx.scene.Node node : tree.lookupAll(".tree-cell")) {
            if (node instanceof TreeCell<?> cell
                    && !cell.isEmpty() && cell.getIndex() == row) {
                if (cell.getHeight() <= 0) {
                    return false;
                }
                javafx.geometry.Bounds cb = cell.localToScene(cell.getLayoutBounds());
                return cb.getMinY() >= view.getMinY() - 0.5
                        && cb.getMaxY() <= view.getMaxY() + 0.5;
            }
        }
        return false;
    }

    /**
     * Reconcile {@code parent}'s child {@link TreeItem}s <em>in place</em> to
     * match {@code nodes}, reusing existing items by path so the {@link TreeView}
     * keeps item identity, expansion state and — crucially — its scroll position
     * across a refresh. (The old wholesale {@code setAll} rebuild reset the
     * scroll on every expand, which is what snapped the view back to the
     * selected node when opening an unrelated directory.)
     */
    private static void reconcileChildren(TreeItem<TreeNodeData> parent, List<TreeNode> nodes) {
        ObservableList<TreeItem<TreeNodeData>> existing = parent.getChildren();

        Map<String, TreeItem<TreeNodeData>> byPath = new HashMap<>();
        for (TreeItem<TreeNodeData> item : existing) {
            TreeNodeData value = item.getValue();
            if (value != null && !value.path().isEmpty()) {
                byPath.put(value.path(), item);
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            TreeNode node = nodes.get(i);
            TreeItem<TreeNodeData> item = byPath.get(node.getPath());
            if (item == null) {
                item = new TreeItem<>(new TreeNodeData(node.getName(), node.getPath()));
            }
            int currentIndex = existing.indexOf(item);
            if (currentIndex != i) {
                if (currentIndex >= 0) {
                    existing.remove(currentIndex);
                }
                existing.add(Math.min(i, existing.size()), item);
            }
            configureItem(item, node);
        }
        while (existing.size() > nodes.size()) {
            existing.remove(existing.size() - 1);
        }
    }

    /** Apply one {@link TreeNode}'s value + expansion to its (reused) item. */
    private static void configureItem(TreeItem<TreeNodeData> item, TreeNode node) {
        TreeNodeData value = item.getValue();
        if (value == null || !node.getName().equals(value.name())
                || !node.getPath().equals(value.path())) {
            item.setValue(new TreeNodeData(node.getName(), node.getPath()));
        }
        if (node.isExpanded()) {
            reconcileChildren(item, node.getChildren());
            if (!item.isExpanded()) {
                item.setExpanded(true);
            }
        } else {
            // Collapsed: keep a disclosure arrow (a placeholder for never-loaded
            // nodes so the first expand fires the lazy load; retained real
            // children for ones expanded earlier) and hide it.
            if (item.getChildren().isEmpty()) {
                item.getChildren().add(new TreeItem<>(new TreeNodeData("", "")));
            }
            if (item.isExpanded()) {
                item.setExpanded(false);
            }
        }
    }

    private static TreeItem<TreeNodeData> findByPath(TreeItem<TreeNodeData> node, String path) {
        for (TreeItem<TreeNodeData> child : node.getChildren()) {
            if (child.getValue() != null && path.equals(child.getValue().path())) {
                return child;
            }
            TreeItem<TreeNodeData> found = findByPath(child, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void applyZoneBorders() {
        MoveDialogFocusZone zone = model.getFocusZone();
        styleZone(inputZone, zone == MoveDialogFocusZone.INPUT);
        styleZone(historyZone, zone == MoveDialogFocusZone.HISTORY);
        styleZone(treeZone, zone == MoveDialogFocusZone.TREE);
    }

    private static void styleZone(Region zone, boolean active) {
        zone.getStyleClass().remove("move-zone-active");
        if (active) {
            zone.getStyleClass().add("move-zone-active");
        }
    }

    private void applyZoneFocus() {
        // Keyboard focus only ever rests on the text field (INPUT). For HISTORY
        // and TREE we keep focus on the root so stray keystrokes can't disturb
        // the list/tree; the bright zone border + selection make the active zone
        // obvious. Mirrors iris, which parked focus on the dialog itself.
        if (model.getFocusZone() == MoveDialogFocusZone.INPUT) {
            targetField.requestFocus();
            targetField.positionCaret(targetField.getText().length());
        } else {
            root.requestFocus();
        }
    }

    // ---- Lifecycle ----------------------------------------------------------

    /** Show the dialog and reconcile it from the model. */
    public void show() {
        refresh();
        stage.show();
        applyZoneFocus();
    }

    /** Show the dialog modally, blocking until it is closed. */
    public void showAndWait() {
        refresh();
        stage.setOnShown(e -> applyZoneFocus());
        stage.showAndWait();
    }

    /** Close the dialog (called by the driver in response to a cancel/submit). */
    public void close() {
        stage.close();
    }

    public Stage stage() {
        return stage;
    }

    // ---- Tree cell ----------------------------------------------------------

    /** A tree value: a directory's display name and its absolute path. */
    private record TreeNodeData(String name, String path) {}

    /**
     * Renders a node's name and tints the highlighted node so it reads even when
     * the tree is unfocused (focus stays off the tree — arrows are routed
     * through the dialog). Placeholder rows render blank.
     */
    private final class HighlightCell extends TreeCell<TreeNodeData> {
        @Override
        protected void updateItem(TreeNodeData item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("move-tree-highlight");
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item.name());
            if (!item.path().isEmpty()
                    && item.path().equals(model.getMiniTreeHighlightedPath())) {
                getStyleClass().add("move-tree-highlight");
            }
        }
    }
}
