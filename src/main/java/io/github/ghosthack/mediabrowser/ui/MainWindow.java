package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.IconPack;
import io.github.ghosthack.mediabrowser.LoadingIndicator;
import io.github.ghosthack.mediabrowser.MosaicFolderGlyph;
import io.github.ghosthack.mediabrowser.MosaicReticuleScope;
import io.github.ghosthack.mediabrowser.MosaicSelectionAnimation;
import io.github.ghosthack.mediabrowser.album.Album;
import io.github.ghosthack.mediabrowser.album.AlbumStore;
import io.github.ghosthack.mediabrowser.StartupLayout;
import io.github.ghosthack.mediabrowser.Theme;
import io.github.ghosthack.mediabrowser.UiStyle;
import io.github.ghosthack.mediabrowser.WindowDecorations;
import io.github.ghosthack.mediabrowser.WindowMode;
import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.DetectionMode;
import io.github.ghosthack.mediabrowser.media.DirEntry;
import io.github.ghosthack.mediabrowser.media.MediaBackend;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaItem;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;
import io.github.ghosthack.mediabrowser.media.ffm.HwDecode;
import io.github.ghosthack.mediabrowser.ui.icon.AppIcon;
import io.github.ghosthack.mediabrowser.ui.icon.IconBinding;
import io.github.ghosthack.mediabrowser.ui.icon.IconPackManager;
import io.github.ghosthack.mediabrowser.ui.icon.IconView;
import io.github.ghosthack.mediabrowser.ui.mosaic.MosaicTileSet;
import io.github.ghosthack.mediabrowser.ui.mosaic.MosaicTileSets;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.event.Event;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The browser view: menu, toolbar (panel visibility toggles), address bar,
 * navigation tree, media list, info panel and status bar. One of the three
 * views hosted by {@link AppShell}; it also constructs the mosaic view and
 * builds the shared menu bar for all three.
 */
public final class MainWindow implements AppShell.ShellView, ViewerHost {

    static final String COMPUTER_LOCATION_NAME = "Computer";

    /** The single-window shell hosting this view. */
    private final AppShell shell;
    private final MediaService service;
    private final ViewerWindow viewer;
    private final MosaicWindow mosaic;
    private final AppSettings settings;
    /** Shared user-rotation store, passed on to the mosaic window. */
    private final RotationStore rotationStore;
    /** Shared Apple Photos {@code .AAE} edit store, passed on to the mosaic window. */
    private final AaeStore aaeStore;
    private final MoveController moveController;
    private final MetadataStripController metadataStripController;
    private final BakedPngExportController bakedPngExportController;
    /** Shared album store (numbered {@code album-NNN.csv} files in the app dir). */
    private final AlbumStore albumStore = new AlbumStore();

    private final TextField addressField = new TextField();
    private final HBox addressBar;
    private final TreeView<Path> tree;
    private final ListView<DirEntry> listView = new ListView<>();
    /** The browser's empty state — see {@link #updateEmptyState}. */
    private final Label emptyStateLabel = new Label("Nothing to show in this folder");
    private final ObservableList<DirEntry> entries = FXCollections.observableArrayList();
    private final FilteredList<DirEntry> filteredEntries = new FilteredList<>(entries);
    /**
     * The list as shown: {@code entries} after the Show-menu filter and the
     * Sort-menu ordering. The viewer's arrow-navigation ring is built from
     * this same list (in {@link #activateSelected}), so sorting the list also
     * sorts how arrow browsing steps through the directory.
     */
    private final SortedList<DirEntry> visibleEntries = new SortedList<>(filteredEntries);
    private final InfoPanel infoPanel = new InfoPanel();
    /** On-demand full-metadata panel (below the Info panel in a vertical right split). */
    private final MetadataPanel metadataPanel = new MetadataPanel();
    /** Live thumbnail-pipeline counters (bottom of the right split when shown). */
    private final DiagnosticsPanel diagnosticsPanel;
    /** Right-edge vertical split: Info, Metadata, Diagnostics (membership follows the toggles). */
    private final SplitPane rightPanels = new SplitPane();
    /** Debounce for the opt-in Auto metadata load; (re)armed on each selection change. */
    private final PauseTransition metadataDebounce = new PauseTransition(Duration.millis(180));
    private final SplitPane splitPane = new SplitPane();
    /** Keeps the right stack at the width shared with the viewer and mosaic. */
    private final SidePanelWidth sidePanelWidth;
    private final Label statusLabel = new Label("Ready");
    private final Label countLabel = new Label();
    private final HBox statusBar;
    private final ActionLogPanel actionLogPanel;
    private final VBox treePanel;

    private final Deque<BrowserLocation> backStack = new ArrayDeque<>();
    private final Deque<BrowserLocation> forwardStack = new ArrayDeque<>();
    private boolean traversingHistory;
    private Path pendingSelection;
    /**
     * Set by {@link #openInitialFile}: the file to jump straight into the
     * viewer for once its folder's listing lands in {@link #showListing}.
     * Always paired with {@link #pendingSelection} pointing at the same path.
     */
    private Path pendingViewerTarget;
    /**
     * Set by {@link #openInitial} when the startup layout opens mosaic:
     * true from the moment the initial navigate() is kicked off until its
     * listing actually lands in {@link #showListing}. Mosaic itself opens
     * immediately either way (it tracks the live entries list reactively),
     * but the shell's reveal is held on this so a cold start doesn't briefly
     * show — and then have to visibly refill — an empty grid.
     */
    private boolean pendingInitialMosaicReveal;
    /** True for one event tick after a click in a focus-bouncing panel; see armFocusBounce(). */
    private boolean bounceArmed;

    private final Typeahead typeahead = new Typeahead();

    private Path currentDir;
    /** The flat album location currently shown, mutually exclusive with a directory. */
    private Album currentAlbum;
    /** Album members omitted because their paths no longer exist or cannot be resolved. */
    private int currentAlbumUnavailable;
    /** True while the album CSV and its member attributes are loading off-thread. */
    private boolean currentAlbumLoading;
    /** Windows-only virtual location above the independent drive roots. */
    private boolean atComputerLocation;
    /** The current directory-listing failure, shown without removing its {@code ..} escape. */
    private String listingFailureMessage;
    /**
     * Bumped on every navigation; the latest wins. Read off the FX thread by the
     * streaming reclassification's {@code stillWanted} guard, so {@code volatile}
     * for cross-thread visibility.
     */
    private volatile int scanSequence;
    private int probeSequence;
    /**
     * Content-sniff corrections streamed back from {@link MediaService#reclassify}
     * (each a path and its sniffed kind, empty = not media), drained and applied
     * in coalesced batches by {@link #flushReclassifications}. Filled off the FX
     * thread, drained on it.
     */
    private final ConcurrentLinkedQueue<Map.Entry<Path, Optional<MediaKind>>> reclassQueue =
            new ConcurrentLinkedQueue<>();
    /** Guards against piling up more than one queued reclassification flush per pulse. */
    private final AtomicBoolean reclassFlushScheduled = new AtomicBoolean();
    private boolean mirroringSelection;

    /** What the Sort menu orders entries by, within the folders-then-files grouping. */
    enum SortKey { NAME, EXTENSION, SIZE, DATE }

    // --- shared menu-bar state -------------------------------------------------
    // The application has one menu bar, built once for this window and once for
    // the mosaic (each its own instance, so macOS scopes accelerators to the
    // focused window). These observable properties are the single source of
    // truth both bars bind to, so a change in either takes effect once.
    private final BooleanProperty showViewableProp = new SimpleBooleanProperty(true);
    private final BooleanProperty showNonViewableProp = new SimpleBooleanProperty(true);
    private final BooleanProperty showDirsProp = new SimpleBooleanProperty(true);
    private final ObjectProperty<DetectionMode> detectionProp = new SimpleObjectProperty<>();
    // docs/empty-states.md listing filters. Shared by this window and the
    // mosaic, since both draw from the same MediaService listing.
    private final BooleanProperty showHiddenFilesProp = new SimpleBooleanProperty(true);
    private final BooleanProperty ignoreJunkProp = new SimpleBooleanProperty(false);
    private final BooleanProperty hideEmptyFilesProp = new SimpleBooleanProperty();
    private final BooleanProperty hideEmptyFoldersProp = new SimpleBooleanProperty();
    private final BooleanProperty collapseChainsProp = new SimpleBooleanProperty(true);
    private final ObjectProperty<SortKey> sortKeyProp = new SimpleObjectProperty<>(SortKey.NAME);
    private final BooleanProperty sortDescendingProp = new SimpleBooleanProperty(false);
    private final BooleanProperty toolbarVisibleProp = new SimpleBooleanProperty(false);
    private final BooleanProperty addressVisibleProp = new SimpleBooleanProperty(true);
    private final BooleanProperty treeVisibleProp = new SimpleBooleanProperty(true);
    private final BooleanProperty infoVisibleProp = new SimpleBooleanProperty(true);
    private final BooleanProperty metadataVisibleProp = new SimpleBooleanProperty(false);
    private final BooleanProperty diagnosticsVisibleProp = new SimpleBooleanProperty(false);
    private final BooleanProperty statusVisibleProp = new SimpleBooleanProperty(true);
    private final BooleanProperty actionLogVisibleProp = new SimpleBooleanProperty(false);
    private final BooleanProperty menuBarVisibleProp = new SimpleBooleanProperty(true);
    private final BooleanProperty keepFocusProp = new SimpleBooleanProperty(false);
    private final BooleanProperty backDisabledProp = new SimpleBooleanProperty(true);
    private final BooleanProperty forwardDisabledProp = new SimpleBooleanProperty(true);
    /** Folder-tile preview grid edge (0/2/3/4); the Mosaic menu radios share it. */
    private final ObjectProperty<Integer> folderPreviewGridProp = new SimpleObjectProperty<>();
    private final BooleanProperty folderPreviewSniffProp = new SimpleBooleanProperty();
    /** Logical menu-accelerator modifier scheme (Settings ▸ Keys); read once at start. */
    private final KeyScheme keys;
    /** This view's root, hosted by the shell while the browser is active. */
    private final BorderPane root;
    private final ToolBar toolBar;
    private final MenuBar menuBar;
    /** The stage title while the browser is active. */
    private final javafx.beans.property.SimpleStringProperty title =
            new javafx.beans.property.SimpleStringProperty("Media Browser");

    public MainWindow(AppShell shell, MediaService service, ViewerWindow viewer,
                      AppSettings settings, RotationStore rotationStore, AaeStore aaeStore) {
        this.shell = shell;
        this.service = service;
        this.viewer = viewer;
        this.settings = settings;
        this.rotationStore = rotationStore;
        this.aaeStore = aaeStore;
        this.keys = KeyScheme.fromSettings(settings);
        this.diagnosticsPanel = new DiagnosticsPanel(service);
        this.mosaic = new MosaicWindow(shell, service, viewer, settings, rotationStore, aaeStore,
                sortKeyProp, sortDescendingProp);
        // Navigating a folder / .. tile in the mosaic drives the shared location
        // here; the new listing then flows back into the mosaic's grid.
        mosaic.setNavigator(this::navigateAndSync);
        if (supportsComputerLocation(System.getProperty("os.name"))) {
            mosaic.setVirtualParentHandler(this::navigateToParent);
        }
        // Cmd+Left / Cmd+Right in the mosaic step the shared history.
        mosaic.setHistoryHandlers(this::goBack, this::goForward);
        // The compose toolbar's Settings gear opens the same dialog Cmd+, does.
        mosaic.setSettingsHandler(this::showSettings);

        // The Move dialog (Cmd+M): a window-agnostic controller driven by a host
        // that exposes this window's selection, current dir, post-move focus and
        // refresh. The mosaic reuses the same controller class in Phase 6.
        this.moveController = new MoveController(service, settings, new MoveController.Host() {
            @Override public Stage owner() { return stage(); }
            @Override public Path currentDirectory() { return currentDir; }
            @Override public MoveController.Selection currentSelection() { return currentMoveSelection(); }
            @Override public Path nextFocusAfterMove(List<Path> moving) { return MainWindow.this.nextFocusAfterMove(moving); }
            @Override public void refreshAfterMove(Path focusPath) { MainWindow.this.refreshAfterMove(focusPath); }
            @Override public void showStatus(String message) { statusLabel.setText(message); }
            @Override public void releaseBeforeMove(List<Path> moving) { viewer.releaseBeforeMove(moving); }
        });
        this.metadataStripController = new MetadataStripController(service,
                new MetadataStripController.Host() {
                    @Override public Stage owner() { return stage(); }
                    @Override public MoveController.Selection currentSelection() {
                        return currentMoveSelection();
                    }
                    @Override public void refreshAfterStrip(Path firstOutput) {
                        refreshAfterMove(firstOutput);
                    }
                    @Override public void showStatus(String message) { statusLabel.setText(message); }
                });
        this.bakedPngExportController = new BakedPngExportController(
                service, rotationStore, aaeStore, new BakedPngExportController.Host() {
                    @Override public Stage owner() { return stage(); }
                    @Override public Path currentStill() { return currentStillSelection(); }
                    @Override public void refreshAfterExport(Path output) {
                        Path outputParent = output.toAbsolutePath().normalize().getParent();
                        if (currentDir != null && outputParent != null
                                && outputParent.equals(currentDir.toAbsolutePath().normalize())) {
                            refreshAfterMove(output);
                        }
                    }
                    @Override public void showStatus(String message) { statusLabel.setText(message); }
                });

        // --- navigation tree -------------------------------------------------
        Path fsRoot = Path.of(System.getProperty("user.home")).getRoot();
        TreeItem<Path> rootItem = navigationTreeRoot(fsRoot);
        rootItem.setExpanded(true);
        tree = new TreeView<>(rootItem);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                setText(empty ? null : path == null ? COMPUTER_LOCATION_NAME : displayName(path));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            // skip when mirroring list-driven navigation back into the tree
            if (sel == null) return;
            Path selected = sel.getValue();
            if (selected == null) {
                if (!atComputerLocation) navigateToComputer();
            } else if (!selected.equals(currentDir)) {
                navigate(selected);
            }
        });
        // Committing to a folder (click on a node, or Enter after arrow-key
        // browsing) hands focus to the file list; the disclosure arrow and
        // empty rows don't, so expanding/collapsing keeps focus here.
        tree.setOnMouseClicked(e -> {
            if (clickedOnPath(e.getTarget())) listView.requestFocus();
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER
                    && tree.getSelectionModel().getSelectedItem() != null) {
                listView.requestFocus();
                e.consume();
            }
        });
        treePanel = new VBox(SidePanelHeader.create("Folders"), tree);
        VBox.setVgrow(tree, Priority.ALWAYS);
        treePanel.setPrefWidth(240);
        treePanel.setMinWidth(140);

        // --- file list ---------------------------------------------------------
        listView.setItems(visibleEntries);
        // Multi-select so Move (and future bulk ops) can act on several entries;
        // Shift-click / Shift-arrow / Cmd-click come from the ListView for free.
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        // A ListView placeholder cannot represent an empty folder here because
        // the navigable ".." row keeps the list non-empty. Overlay the message
        // instead, leaving that parent row usable underneath it.
        listView.setPlaceholder(new Region());
        emptyStateLabel.setMouseTransparent(true);
        emptyStateLabel.setVisible(false);
        emptyStateLabel.setStyle("-fx-text-fill: -fx-mid-text-color;");
        var listPane = new StackPane(listView, emptyStateLabel);
        StackPane.setAlignment(emptyStateLabel, Pos.CENTER);
        listView.setCellFactory(lv -> new ListCell<>() {
            private final Label icon = new Label();
            private final IconBinding iconBinding =
                    IconBinding.install(icon, null, "", "", null);
            private final ContextMenu menu = new ContextMenu();
            {
                icon.setMinWidth(28);
                icon.setAlignment(Pos.CENTER);
                var copyPath = new MenuItem("Copy Path");
                copyPath.setOnAction(e -> copyPathToClipboard(getItem()));
                var moveItem = new MenuItem("Move…");
                moveItem.setOnAction(e -> {
                    // Right-click doesn't change selection. Keep the existing
                    // multi-selection if this cell is part of it; otherwise
                    // target just this cell.
                    targetThisCell();
                    openMove();
                });
                var trashItem = new MenuItem(MoveController.trashActionText());
                trashItem.setAccelerator(keys.moveToTrash());
                trashItem.setOnAction(e -> {
                    targetThisCell();
                    // Let the popup finish hiding before showing confirmation.
                    Platform.runLater(MainWindow.this::moveToTrash);
                });
                var zeroMetadata = new MenuItem("Export as Zero-Metadata Copy\u2026");
                zeroMetadata.setOnAction(e -> {
                    targetThisCell();
                    Platform.runLater(MainWindow.this::exportZeroMetadataCopies);
                });
                var bakedPng = new MenuItem("Export as Baked PNG\u2026");
                bakedPng.setOnAction(e -> {
                    targetThisCell();
                    Platform.runLater(MainWindow.this::exportBakedPng);
                });
                var exportMenu = new Menu("Export");
                exportMenu.getItems().addAll(zeroMetadata, bakedPng);
                var albumMenu = new AlbumMenu(albumStore, settings,
                        () -> currentMoveSelection().sources(), stage(),
                        statusLabel::setText);
                // Right-click doesn't change selection: target the clicked cell
                // (unless it is already part of the selection) and refresh the
                // recents just before the menu shows.
                menu.setOnShowing(e -> {
                    targetThisCell();
                    albumMenu.refresh();
                });
                menu.getItems().addAll(copyPath, moveItem, trashItem, exportMenu, albumMenu.menu());
            }
            /**
             * Make this cell's entry the (sole) selection unless it already
             * belongs to the current multi-selection — a right-click never
             * disturbs an existing block, so the action targets what was clicked.
             */
            private void targetThisCell() {
                if (!listView.getSelectionModel().getSelectedItems().contains(getItem())) {
                    listView.getSelectionModel().clearSelection();
                    listView.getSelectionModel().select(getItem());
                }
            }
            @Override
            protected void updateItem(DirEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    iconBinding.update(null, "", "");
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                } else {
                    AppIcon role = iconFor(entry);
                    iconBinding.update(role, role == null ? "" : role.originalGlyph(), "");
                    setGraphic(icon);
                    setText(entry.displayName());
                    setContextMenu(menu);
                }
            }
        });
        listView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, sel) -> onSelection(sel));
        // A vertical ListView's default behavior maps bare Left/Right to
        // horizontal focus traversal, which yanks focus out to a neighboring
        // panel (redundant with Tab/Shift+Tab, and asymmetric). Swallow them in
        // a filter so they never reach that behavior. Modifier combos still
        // pass through, so Cmd+Left/Right (back/forward) keep working.
        listView.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if ((e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT)
                    && !e.isShortcutDown() && !e.isControlDown()
                    && !e.isAltDown() && !e.isMetaDown()) {
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN && e.isShortcutDown()) {
                // Cmd+Down aliases Enter (open the selected entry); Cmd+Up is the
                // Enclosing Folder accelerator, the Backspace alias. Caught in a
                // filter so the list's own Cmd+Down behavior never kicks in.
                activateSelected();
                e.consume();
            }
        });
        listView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                activateSelected();
                e.consume();
            } else if (e.getCode() == KeyCode.F1) {
                if (currentDir != null && moveController.quickMove(0)) e.consume();
            } else if (e.getCode() == KeyCode.F2) {
                if (currentDir != null && moveController.quickMove(1)) e.consume();
            } else if (e.getCode() == KeyCode.F3) {
                if (currentDir != null && moveController.quickMove(2)) e.consume();
            } else if (e.getCode() == KeyCode.F4) {
                if (currentDir != null && moveController.quickMove(3)) e.consume();
            }
        });
        listView.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            String prefix = typeahead.append(e);
            if (prefix == null) return;
            int i = Typeahead.indexOf(visibleEntries,
                    listView.getSelectionModel().getSelectedIndex(), prefix);
            if (i >= 0) {
                listView.getSelectionModel().clearAndSelect(i);
                listView.scrollTo(i);
            }
            e.consume();
        });
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) activateSelected();
        });

        // --- viewer feedback: mirror arrow-browsing, reuse its probe ---------
        // (the mirror is wired per-session via ViewerHost when this window opens
        // the viewer; see open(..., this) in activateSelected)
        viewer.setOnItemProbed(probe -> {
            DirEntry sel = listView.getSelectionModel().getSelectedItem();
            if (sel != null && sel.path().equals(probe.path())) {
                infoPanel.show(probe);
            }
        });

        // --- address bar -----------------------------------------------------
        addressField.setOnAction(e -> navigateFromAddress());
        HBox.setHgrow(addressField, Priority.ALWAYS);
        var drivesButton = new DriveMenuButton(root -> {
            navigateAndSync(root);
            listView.requestFocus();
        });
        var addressLabel = new Label("Location");
        // Mirrors the mosaic's location bar exactly: the shared .tool-bar class
        // supplies the strip inset and both labels add the same internal left
        // padding, so the strip keeps one look (and one height) when the
        // single-window shell switches views. .location-bar carries the themes'
        // flat treatment for the bar's direct-child buttons, which a plain HBox
        // misses (.tool-bar button rules target ToolBar's .container).
        addressLabel.setPadding(new Insets(0, 0, 0, 10));
        addressBar = new HBox(6, addressLabel, drivesButton, addressField);
        addressBar.getStyleClass().addAll("tool-bar", "location-bar");
        addressBar.setStyle("-fx-alignment: center-left;");
        ToolBars.applyControlBaseline(addressBar);

        // --- view toggles (toolbar buttons + menu items, kept in sync) -------
        boolean composeToolbar = settings.uiStyle() == UiStyle.COMPOSE;
        var upButton = new Button("Up");
        upButton.setTooltip(new Tooltip("Enclosing Folder"));
        if (composeToolbar) {
            ToolBars.iconify(upButton, AppIcon.UP, "Enclosing Folder");
        }
        upButton.setOnAction(e -> navigateToParent());
        var refreshButton = new Button("Refresh");
        refreshButton.setTooltip(new Tooltip("Reload Folder"));
        if (composeToolbar) {
            ToolBars.iconify(refreshButton, AppIcon.REFRESH, "Reload Folder");
        }
        refreshButton.setOnAction(e -> reloadLocation());

        // Keep the Browser's primary listing controls in the same place as the
        // Compose Mosaic toolbar: immediately after Up / Refresh. These bind to
        // the same properties as Show and Sort in the menu bar, so neither
        // surface maintains a second copy of the listing state.
        var filterButton = new MenuButton("Filter");
        filterButton.setTooltip(new Tooltip("Filter listed items"));
        filterButton.getItems().addAll(
                boundCheck("Viewable Media", showViewableProp),
                boundCheck("Non-viewable Files", showNonViewableProp),
                boundCheck("Folders", showDirsProp),
                new SeparatorMenuItem(),
                boundCheck("Hidden Files", showHiddenFilesProp),
                inverseBoundCheck("System Files", ignoreJunkProp),
                inverseBoundCheck("Empty Files", hideEmptyFilesProp),
                inverseBoundCheck("Empty Folders", hideEmptyFoldersProp),
                boundCheck("Collapse Folder Chains", collapseChainsProp));
        if (composeToolbar) {
            ToolBars.iconify(filterButton, AppIcon.FILTER, "Filter listed items");
        }

        var sortButton = new MenuButton("Sort");
        sortButton.setTooltip(new Tooltip("Sort and order items"));
        var toolbarSortKeyGroup = new ToggleGroup();
        var toolbarSortDirectionGroup = new ToggleGroup();
        sortButton.getItems().addAll(
                objRadio("By Name", SortKey.NAME, sortKeyProp, toolbarSortKeyGroup),
                objRadio("By Extension", SortKey.EXTENSION,
                        sortKeyProp, toolbarSortKeyGroup),
                objRadio("By Size", SortKey.SIZE, sortKeyProp, toolbarSortKeyGroup),
                objRadio("By Date", SortKey.DATE, sortKeyProp, toolbarSortKeyGroup),
                new SeparatorMenuItem(),
                boolRadio("Ascending", false,
                        sortDescendingProp, toolbarSortDirectionGroup),
                boolRadio("Descending", true,
                        sortDescendingProp, toolbarSortDirectionGroup));
        if (composeToolbar) {
            ToolBars.iconify(sortButton, AppIcon.SORT, "Sort and order items");
        }

        var addressToggle = panelToggle(
                "Address Bar", AppIcon.ADDRESS_BAR, addressVisibleProp, composeToolbar);
        var treeToggle = panelToggle(
                "Nav Tree", AppIcon.NAV_TREE, treeVisibleProp, composeToolbar);
        var panelToggles = ToolBars.panelToggles(composeToolbar,
                infoVisibleProp, metadataVisibleProp, diagnosticsVisibleProp,
                statusVisibleProp, actionLogVisibleProp);

        // Panel-visibility side-effects, fired by the shared props that these
        // toolbar toggles and the View-menu check items all bind to. The Info,
        // Metadata and Diagnostics panels share a vertical right split, rebuilt
        // by updateRightPanels() as any of their toggles flips.
        addressVisibleProp.addListener((o, a, v) -> setAddressBarVisible(v));
        treeVisibleProp.addListener((o, a, v) -> setSidePanelVisible(treePanel, v));
        infoVisibleProp.addListener((o, a, v) -> updateRightPanels());
        metadataVisibleProp.addListener((o, a, v) -> updateRightPanels());
        diagnosticsVisibleProp.addListener((o, a, v) -> updateRightPanels());
        statusVisibleProp.addListener((o, a, v) -> setStatusBarVisible(v));
        actionLogVisibleProp.addListener((o, a, v) -> setActionLogVisible(v));

        // Metadata panel: manual by default — the read is driven by its Load
        // button or its opt-in Auto toggle (debounced on selection); showing the
        // panel reads nothing until a read lands. Mirrors the viewer.
        rightPanels.setOrientation(javafx.geometry.Orientation.VERTICAL);
        metadataPanel.setOnLoadRequested(this::fireMetadataRead);
        metadataPanel.autoLoadProperty().addListener((o, was, on) -> {
            metadataDebounce.stop();
            if (on && metadataVisibleProp.get()) fireMetadataRead();
        });
        metadataDebounce.setOnFinished(e -> {
            if (metadataVisibleProp.get() && metadataPanel.isAutoLoad()) fireMetadataRead();
        });

        var keepFocusToggle = new ToggleButton("Keep Focus");
        String keepFocusTooltip = shell.singleWindow()
                ? "Inert in the single-window mode: opening media always "
                        + "switches to the viewer (see Settings ▸ Window mode)"
                : "Keep this window focused when opening media, so browsing "
                        + "continues while the viewer sits on another screen";
        keepFocusToggle.setTooltip(new Tooltip(keepFocusTooltip));
        if (composeToolbar) {
            ToolBars.iconify(keepFocusToggle, AppIcon.FOCUS_MODE, keepFocusTooltip);
        }
        keepFocusToggle.selectedProperty().bindBidirectional(keepFocusProp);

        this.toolBar = new ToolBar(upButton, refreshButton, filterButton, sortButton,
                new Separator());
        toolBar.getItems().addAll(panelToggles);
        toolBar.getItems().addAll(new Separator(), addressToggle, treeToggle,
                new Separator(), keepFocusToggle);
        ToolBars.applyControlBaseline(toolBar);
        // View ▸ Toolbar (and the other bars' copies) toggle it. When undecorated,
        // the in-window menu bar still serves as a drag handle.
        toolBar.visibleProperty().bind(toolbarVisibleProp);
        toolBar.managedProperty().bind(toolbarVisibleProp);

        // --- shared application menu bar -------------------------------------
        // One bar for this window, one for the mosaic; both bind to the *Prop
        // fields, so the side-effects below run once whichever bar changes them.
        showViewableProp.addListener((o, a, v) -> applyFilter());
        showNonViewableProp.addListener((o, a, v) -> applyFilter());
        showDirsProp.addListener((o, a, v) -> applyFilter());
        applyFilter();

        // Detection method: native content sniffing vs. filename extension. Set
        // the initial value before wiring the listener so it doesn't re-scan.
        DetectionMode detection = DetectionMode.fromSettings(settings.detectionMode());
        service.setDetectionMode(detection);
        detectionProp.set(detection);
        detectionProp.addListener((o, a, v) -> { if (v != null) setDetectionMode(v); });

        service.setListingShowHidden(settings.listingShowHiddenFiles());
        service.setListingIgnoreJunk(settings.listingIgnoreJunkFiles());
        service.setListingHideEmptyFiles(settings.listingHideEmptyFiles());
        service.setListingHideEmptyFolders(settings.listingHideEmptyFolders());
        showHiddenFilesProp.set(settings.listingShowHiddenFiles());
        ignoreJunkProp.set(settings.listingIgnoreJunkFiles());
        hideEmptyFilesProp.set(settings.listingHideEmptyFiles());
        hideEmptyFoldersProp.set(settings.listingHideEmptyFolders());
        collapseChainsProp.set(settings.listingCollapseFolderChains());
        showHiddenFilesProp.addListener((o, a, v) -> {
            service.setListingShowHidden(v);
            settings.setListingShowHiddenFiles(v);
            relist();
        });
        ignoreJunkProp.addListener((o, a, v) -> {
            service.setListingIgnoreJunk(v);
            settings.setListingIgnoreJunkFiles(v);
            relist();
        });
        hideEmptyFilesProp.addListener((o, a, v) -> {
            service.setListingHideEmptyFiles(v);
            settings.setListingHideEmptyFiles(v);
            relist();
        });
        hideEmptyFoldersProp.addListener((o, a, v) -> {
            service.setListingHideEmptyFolders(v);
            settings.setListingHideEmptyFolders(v);
            relist();
        });
        collapseChainsProp.addListener((o, a, v) -> {
            settings.setListingCollapseFolderChains(v);
            saveSettingsQuietly();
        });

        sortKeyProp.addListener((o, a, v) -> applySort());
        sortDescendingProp.addListener((o, a, v) -> applySort());
        applySort();

        folderPreviewGridProp.set(settings.mosaicFolderPreviewGrid());
        folderPreviewGridProp.addListener((o, a, v) -> { if (v != null) setFolderPreviewGrid(v); });
        service.setFolderPreviewSniff(settings.mosaicFolderPreviewSniff());
        folderPreviewSniffProp.set(settings.mosaicFolderPreviewSniff());
        folderPreviewSniffProp.addListener((o, a, v) -> setFolderPreviewSniff(v));

        this.menuBar = buildMenuBar(MenuOwner.MAIN);
        mosaic.installMenuBar(buildMenuBar(MenuOwner.MOSAIC), keys);
        viewer.installMenuBar(buildMenuBar(MenuOwner.VIEWER), keys);

        // --- layout ----------------------------------------------------------
        splitPane.getItems().addAll(treePanel, listPane);
        // The panel stack's width is the shared, persisted one (the viewer and
        // mosaic keep it too), not a fraction of this window; applyDividers
        // hands it the last divider.
        sidePanelWidth = new SidePanelWidth(splitPane, rightPanels, settings);
        updateRightPanels();   // adds the Info/Metadata right split per the toggles

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusBar = new HBox(8, statusLabel, spacer, countLabel);
        statusBar.setPadding(new Insets(4, 8, 4, 8));

        // Session action log + quick-move targets, directly above the status
        // bar. Hidden until the persisted visibility is seeded below.
        actionLogPanel = new ActionLogPanel(settings);
        setActionLogVisible(actionLogVisibleProp.get());

        this.root = new BorderPane(splitPane);
        root.setTop(new VBox(menuBar, toolBar, addressBar));
        root.setBottom(new VBox(actionLogPanel, statusBar));

        // Borderless panels (see browser.css): the nav tree, file list and
        // Info/Metadata tables lose the themes' 1px component boxes. On the
        // root (not the shared scene): Parent stylesheets sit above the
        // scene's theme overlay, and they leave with the root when another
        // view takes the window, so the viewer's panels stay themed.
        var browserCss = MainWindow.class.getResource("browser.css");
        if (browserCss != null) root.getStylesheets().add(browserCss.toExternalForm());

        // Bare Backspace goes to the parent folder. On macOS, modifier1+Backspace
        // is the native Command+Delete move-to-Trash chord; elsewhere bare Delete
        // performs the same operation. Text inputs retain all editing keys.
        root.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof TextInputControl) return;
            if (keys.isMoveToTrash(e)) {
                moveToTrash();
                e.consume();
            } else if (e.getCode() == KeyCode.BACK_SPACE
                    && !e.isAltDown() && !e.isControlDown() && !e.isMetaDown()
                    && !e.isShiftDown()) {
                navigateToParent();
                e.consume();
            }
        });

        // Seed the menu-bar visibility from the persisted Browser default, then
        // wire its runtime hide/show against the shell's (already live) scene.
        //
        // Runtime hide/show (Show ▸ Menu Bar, mod1+\) plus the modifier-2 peek;
        // accelerators keep firing while it is collapsed. The last arg hoists to
        // the macOS system menu bar (top of the screen) while visible, unless the
        // in-window-menu setting opts out; ignored off macOS. Gated on the
        // browser being the active view so the three views' bars don't race for
        // the system menu bar on the single stage.
        menuBarVisibleProp.set(settings.browserMenuBarVisible());
        WindowChrome.bindMenuAutoHide(menuBar, stage().getScene(), stage(),
                menuBarVisibleProp, shell.isActive(AppShell.AppView.BROWSER), keys,
                !settings.inWindowMenu());

        // Clicking the toolbar, the nav tree, the Info panel or the Metadata
        // panel otherwise parks focus on the clicked control, silently breaking
        // the file list's type-ahead. Return focus to the list — but do it
        // synchronously, the instant the control takes focus (see the scene
        // focus-owner listener below), so the control is never painted in its
        // focused state. That means no focus-border flash and, crucially, no
        // layout change: nothing is added, removed or restyled. Only a mouse
        // click arms the bounce, so Tab-navigating into these panels still works
        // and keeps its normal focus ring.
        for (Node panel : List.of(toolBar, infoPanel, metadataPanel)) {
            panel.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> armFocusBounce());
        }
        // The nav tree only bounces when committing to a folder; clicks on the
        // disclosure arrow or empty rows keep focus here, so expand/collapse
        // doesn't yank it away.
        tree.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (clickedOnPath(e.getTarget())) armFocusBounce();
        });
        // The actual bounce: the moment an armed panel's control takes focus,
        // hand it straight back to the list within the same event dispatch,
        // before the next render — so no focused frame is ever drawn. Text inputs
        // (the Metadata filter) and pop-up owners (the Copy All menu) are left
        // alone so they can be used. Gated on the browser being the active view:
        // all three views' bounce listeners share the shell's scene.
        stage().getScene().focusOwnerProperty().addListener((obs, was, now) -> {
            if (!shell.isActiveNow(AppShell.AppView.BROWSER)) return;
            if (!bounceArmed || now == null || now == listView) return;
            if (now instanceof TextInputControl || now instanceof MenuButton) return;
            listView.requestFocus();
        });

        // Seed the rest of this window's chrome from the persisted startup
        // defaults (Settings ▸ Browser). These shared props drive the toolbar
        // toggles, the View-menu check items and the layout side-effects, so
        // setting them here also syncs those and, e.g., removes the nav tree when
        // hidden. (The menu bar is seeded above, before its auto-hide binding, so
        // the macOS system menu bar honours it from the first frame.)
        toolbarVisibleProp.set(settings.browserToolbarVisible());
        statusVisibleProp.set(settings.browserStatusBarVisible());
        treeVisibleProp.set(settings.browserNavTreeVisible());

        // The action log is one app-wide flag (Settings ▸ General): seed it
        // once, then chain the mosaic's and viewer's panel properties to this
        // one, so toggling it in any view shows/hides it in all three.
        actionLogVisibleProp.set(settings.actionLogVisible());
        mosaic.actionLogVisibleProperty().bindBidirectional(actionLogVisibleProp);
        viewer.actionLogVisibleProperty().bindBidirectional(actionLogVisibleProp);
    }

    /**
     * Navigates to {@code startDir} and returns the view the shell should open
     * on: the mosaic when the persisted startup layout asks for it, else the
     * browser. In the single window the multi-window layouts
     * ({@code MOSAIC_VIEWER}, {@code BROWSER_MOSAIC_VIEWER}) degrade to "start
     * in the mosaic"; the separate-windows shell applies their tiling itself
     * (see {@code MultiWindowShell#start}). The listing is driven here either
     * way so the mosaic mirrors the populated source list.
     */
    public AppShell.AppView openInitial(Path startDir) {
        System.err.println("[flash-diag] openInitial start at " + System.nanoTime()
                + " opensMosaic=" + settings.startupLayout().opensMosaic());
        boolean opensMosaic = settings.startupLayout().opensMosaic();
        if (opensMosaic) {
            // Told before expandAndSelect (below) can possibly trigger
            // navigate() synchronously: hold the reveal until that listing
            // actually lands in showListing, so mosaic — which opens
            // immediately either way, since it tracks entries reactively —
            // never gets revealed still empty only to visibly refill a
            // moment later.
            pendingInitialMosaicReveal = true;
            shell.deferRevealUntil(() -> !pendingInitialMosaicReveal);
        }
        expandAndSelect(startDir);
        if (currentDir == null) navigate(startDir);
        if (opensMosaic) {
            openMosaic();
            System.err.println("[flash-diag] openInitial returning MOSAIC at " + System.nanoTime());
            return AppShell.AppView.MOSAIC;
        }
        listView.requestFocus(); // expandAndSelect leaves focus on the tree
        return AppShell.AppView.BROWSER;
    }

    /**
     * Navigates to {@code file}'s parent folder and, once that listing lands,
     * opens the viewer directly on {@code file} — the file-association /
     * "Open with" entry point (an Explorer double-click, or a path forwarded
     * from an already-running instance by {@code SingleInstanceGuard}).
     * Always lands in the browser+viewer, ignoring the startup-layout mosaic
     * preference {@link #openInitial} honours: the caller asked for one
     * specific file, not the general startup view.
     *
     * @return the view the shell should open on — always
     *         {@link AppShell.AppView#BROWSER}; {@code viewer.open(...)}
     *         switches to the viewer itself, one FX pulse later, once the
     *         listing resolves and the target is found (see {@link #showListing})
     */
    public AppShell.AppView openInitialFile(Path file) {
        System.err.println("[flash-diag] openInitialFile start at " + System.nanoTime());
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            // Deliberately not openInitial(home): that honours the mosaic
            // startup preference, which would break this method's own
            // "always BROWSER" contract.
            Path home = Path.of(System.getProperty("user.home"));
            expandAndSelect(home);
            if (!home.equals(currentDir)) navigate(home);
            listView.requestFocus();
            return AppShell.AppView.BROWSER;
        }
        pendingViewerTarget = target;
        pendingSelection = target;
        // Told before shell.start() (called by our caller right after this
        // method returns) runs: hold the window's initial reveal past the
        // usual first-frame minimum until the pending viewer swap below has
        // actually resolved one way or the other, so that swap's own first
        // paint never has a chance to flash as a separate, visible frame.
        shell.deferRevealUntil(() -> pendingViewerTarget == null);
        // expandAndSelect's tree-selection listener may itself call
        // navigate() synchronously (whenever it lands on a path different
        // from currentDir) — which sets currentDir immediately but only
        // *starts* the real listing asynchronously. An earlier version of
        // this method tried to detect that and skip a "redundant" explicit
        // navigate() by comparing currentDir, but currentDir flips to
        // `parent` synchronously regardless of whether that listing has
        // actually landed yet — on a cold start this meant
        // openPendingViewerTargetIfReady() ran immediately against a still-
        // empty `entries`, concluded "not found", and cleared
        // pendingViewerTarget before the real listing ever arrived: the
        // browser opened but the viewer never did. Always navigating
        // explicitly costs one redundant scan when the listener's own call
        // already covers it (bumping scanSequence discards its now-stale
        // in-flight result), but that's the only way to guarantee this
        // call's own showListing()/flushReclassifications() landing is the
        // one that actually resolves pendingViewerTarget.
        expandAndSelect(parent);
        navigate(parent);
        listView.requestFocus();
        return AppShell.AppView.BROWSER;
    }

    /** Opens Mosaic over the shared listing, including an album's virtual label. */
    private void openMosaic() {
        System.err.println("[flash-diag] openMosaic: visibleEntries.size()=" + visibleEntries.size()
                + " at " + System.nanoTime());
        mosaic.open(visibleEntries, () -> currentDir, this::albumLocationLabel);
    }

    /** The mosaic view this window constructed, for shell registration. */
    public MosaicWindow mosaic() {
        return mosaic;
    }

    // --- ShellView ------------------------------------------------------------

    /** The stage hosting this view: the single window, or the browser's own. */
    private Stage stage() {
        return shell.stageFor(AppShell.AppView.BROWSER);
    }

    @Override
    public javafx.scene.Parent root() {
        return root;
    }

    @Override
    public javafx.beans.property.ReadOnlyStringProperty titleProperty() {
        return title;
    }

    /** Claims keyboard focus for the file list as the browser takes the window. */
    @Override
    public void activate() {
        // On the first-ever attach the file list isn't in the scene graph yet:
        // the split pane parents its items only when its skin is built by the
        // CSS pass, so a bare requestFocus() would be a silent no-op and the
        // next pulse's focus cleanup would hand default focus to the first
        // in-graph node — the address field — swallowing the browser's
        // arrow/navigation/shortcut keys. Build the skins now so the focus
        // request lands deterministically.
        root.applyCss();
        listView.requestFocus();
    }

    /** The browser holds no live resources that need parking; nothing to do. */
    @Override
    public void deactivate() {
    }

    @Override
    public ToolBar toolBar() {
        return toolBar;
    }

    @Override
    public MenuBar menuBar() {
        return menuBar;
    }

    // --- panel visibility ----------------------------------------------------

    /**
     * A toolbar visibility toggle bound to a shared property; the matching
     * View-menu check item (in both menu bars) binds to the same property, so
     * toolbar and menus stay in sync.
     */
    private static ToggleButton panelToggle(
            String text, AppIcon icon, BooleanProperty prop, boolean iconFirst) {
        var button = new ToggleButton(text);
        button.setTooltip(new Tooltip("Show/hide the " + text.toLowerCase()));
        if (iconFirst) ToolBars.iconify(button, icon, text);
        button.selectedProperty().bindBidirectional(prop);
        return button;
    }

    /**
     * Arms the synchronous focus bounce for the current event tick. A click in
     * the toolbar / Info / Metadata panels (or on a nav-tree folder) calls this
     * from a {@code MOUSE_PRESSED} filter — which runs in the capture phase,
     * before the clicked control grabs focus — so the scene's focus-owner
     * listener (wired in the constructor) sees the flag set and hands focus
     * straight back to the file list, within the same dispatch and before any
     * frame is painted. The flag clears on the next pulse, so it never affects a
     * later, genuine focus change (e.g. Tab navigation).
     */
    private void armFocusBounce() {
        bounceArmed = true;
        Platform.runLater(() -> bounceArmed = false);
    }

    private void setAddressBarVisible(boolean visible) {
        addressBar.setVisible(visible);
        addressBar.setManaged(visible);
    }

    private void setStatusBarVisible(boolean visible) {
        statusBar.setVisible(visible);
        statusBar.setManaged(visible);
    }

    private void setActionLogVisible(boolean visible) {
        actionLogPanel.setVisible(visible);
        actionLogPanel.setManaged(visible);
    }

    private void setSidePanelVisible(Node panel, boolean visible) {
        var panes = splitPane.getItems();
        if (visible) {
            if (!panes.contains(panel)) {
                if (panel == treePanel) {
                    panes.add(0, panel);
                } else {
                    panes.add(panel);
                }
            }
        } else {
            panes.remove(panel);
        }
        applyDividers();
    }

    private void applyDividers() {
        var panes = splitPane.getItems();
        boolean hasTree = panes.contains(treePanel);
        boolean hasRight = panes.contains(rightPanels);
        if (hasTree && hasRight) {
            splitPane.setDividerPositions(0.20, 0.76);
        } else if (hasTree) {
            splitPane.setDividerPositions(0.22);
        } else if (hasRight) {
            splitPane.setDividerPositions(0.74);
        }
        // The stack's own divider is the shared width, not a fraction of this
        // window; set last so it wins over the placement above.
        if (hasRight && sidePanelWidth != null) sidePanelWidth.apply();
    }

    /**
     * Rebuilds the right vertical split from whichever of the Info / Metadata /
     * Diagnostics panels are toggled on (in that top-to-bottom order) and adds
     * or removes it from the main horizontal split, so a hidden panel leaves no
     * empty divider and all-hidden hands the list its full width back. Mirrors
     * the viewer's right-panel handling.
     */
    private void updateRightPanels() {
        var panels = new ArrayList<Node>(3);
        if (infoVisibleProp.get()) panels.add(infoPanel);
        if (metadataVisibleProp.get()) panels.add(metadataPanel);
        if (diagnosticsVisibleProp.get()) panels.add(diagnosticsPanel);
        if (!rightPanels.getItems().equals(panels)) {
            rightPanels.getItems().setAll(panels);
            if (panels.size() > 1) {
                double[] positions = new double[panels.size() - 1];
                for (int i = 0; i < positions.length; i++) {
                    positions[i] = (i + 1) / (double) panels.size();
                }
                rightPanels.setDividerPositions(positions);
            }
        }
        setSidePanelVisible(rightPanels, !panels.isEmpty());
    }

    // --- navigation ------------------------------------------------------

    /** A history entry is a directory, an album, or Windows' virtual Computer view. */
    private record BrowserLocation(Path directory, Album album) {
        private static final BrowserLocation COMPUTER = new BrowserLocation(null, null);

        static BrowserLocation directory(Path directory) {
            return new BrowserLocation(Objects.requireNonNull(directory), null);
        }

        static BrowserLocation album(Album album) {
            return new BrowserLocation(null, Objects.requireNonNull(album));
        }

        boolean isComputer() {
            return directory == null && album == null;
        }

        boolean isAlbum() {
            return album != null;
        }
    }

    private BrowserLocation currentLocation() {
        if (currentAlbum != null) return BrowserLocation.album(currentAlbum);
        if (atComputerLocation) return BrowserLocation.COMPUTER;
        return currentDir == null ? null : BrowserLocation.directory(currentDir);
    }

    /** @return whether the target was accepted (the listing itself is async) */
    private boolean navigate(Path dir) {
        // An archive browsed earlier may have been evicted under the mount cap;
        // reviving re-opens it so history and pending selections keep working.
        Path target = ArchivePaths.revive(dir).toAbsolutePath().normalize();
        if (!ArchivePaths.isDirectory(target)) {
            statusLabel.setText("Not a folder: " + ArchivePaths.format(target));
            return false;
        }
        Path select = pendingSelection; // entry to re-select once listed
        pendingSelection = null;
        typeahead.reset();
        BrowserLocation previous = currentLocation();
        BrowserLocation destination = BrowserLocation.directory(target);
        if (!traversingHistory && previous != null && !destination.equals(previous)) {
            backStack.push(previous);
            forwardStack.clear();
        }
        updateHistoryItems();
        currentDir = target;
        currentAlbum = null;
        currentAlbumUnavailable = 0;
        currentAlbumLoading = false;
        atComputerLocation = false;
        listingFailureMessage = null;
        addressField.setText(ArchivePaths.format(target));
        statusLabel.setText("Scanning " + ArchivePaths.format(target) + "…");
        countLabel.setText("");
        // Immediate feedback in the mosaic while the scan runs off the FX thread
        // (a big directory enumeration, or content mode's per-file reads): name
        // the target and arm its loading indicator now, so an open into a slow
        // folder isn't a frozen-looking gap until the listing lands. A no-op
        // when the mosaic is hidden.
        mosaic.onDirectoryScanStarted(target);
        int seq = ++scanSequence;
        reclassQueue.clear();   // abandon any corrections still queued for the old folder
        // Under content-sniff detection classify still reads files (the header
        // sniff of every unknown-extension file, the moov check of every
        // AVIF/HEIC), so paint the folder structure at once from a fast,
        // name-only listing (placeholder tiles), then stream the content
        // corrections — promotions of misnamed media, animated-image upgrades —
        // in progressively as each file is classified (see
        // streamReclassification) rather than waiting for the whole scan. In
        // extension mode the single listing is already instant and accurate, so
        // it is shown directly.
        if (service.detectionMode() == DetectionMode.CONTENT_SNIFF) {
            service.listEntriesFast(target).whenComplete((fast, error) ->
                    Platform.runLater(() -> {
                        if (seq != scanSequence) return; // user already navigated elsewhere
                        if (error != null) {
                            showListingFailure(target, select, error);
                            return;
                        }
                        showListing(withComputerParent(target, fast), select);
                        statusLabel.setText("Scanning " + target + "…"); // sniff still streaming
                        streamReclassification(target, fast, seq);
                    }));
        } else {
            service.listEntries(target).whenComplete((listing, error) ->
                    Platform.runLater(() -> {
                        if (seq != scanSequence) return;
                        if (error != null) {
                            showListingFailure(target, select, error);
                            return;
                        }
                        showListing(withComputerParent(target, listing), select);
                        statusLabel.setText(target.toString());
                    }));
        }
        return true;
    }

    /**
     * Shows the Windows-only virtual location above all drive roots. It has no
     * backing {@link Path}; its rows are the real roots users can enter.
     */
    private void navigateToComputer() {
        if (!supportsComputerLocation(System.getProperty("os.name"))) return;

        BrowserLocation previous = currentLocation();
        if (!traversingHistory && previous != null && !previous.isComputer()) {
            backStack.push(previous);
            forwardStack.clear();
        }
        updateHistoryItems();

        Path select = pendingSelection;
        pendingSelection = null;
        typeahead.reset();
        ++scanSequence;             // discard any real-directory scan still in flight
        reclassQueue.clear();
        currentDir = null;
        currentAlbum = null;
        currentAlbumUnavailable = 0;
        currentAlbumLoading = false;
        atComputerLocation = true;
        listingFailureMessage = null;
        addressField.setText(COMPUTER_LOCATION_NAME);
        statusLabel.setText(COMPUTER_LOCATION_NAME);
        countLabel.setText("");
        List<Path> roots = FileSystemRoots.discover();
        refreshComputerTreeRoots(roots);
        showListing(computerListing(roots), select);
        selectComputerInTree();
    }

    /** File ▸ Open Album…: choose one of the numbered CSV albums. */
    private void chooseAlbum() {
        new OpenAlbumDialog(stage(), albumStore).showAndWait().ifPresent(album -> {
            navigateToAlbum(album);
            shell.showView(AppShell.AppView.BROWSER);
        });
    }

    /** Opens an album as one flat listing and records it as a history location. */
    private void navigateToAlbum(Album album) {
        if (album == null) return;
        BrowserLocation previous = currentLocation();
        BrowserLocation destination = BrowserLocation.album(album);
        if (!traversingHistory && previous != null && !destination.equals(previous)) {
            backStack.push(previous);
            forwardStack.clear();
        }
        updateHistoryItems();

        typeahead.reset();
        int seq = ++scanSequence;
        reclassQueue.clear();
        currentDir = null;
        currentAlbum = album;
        currentAlbumUnavailable = 0;
        currentAlbumLoading = true;
        atComputerLocation = false;
        listingFailureMessage = null;
        String location = albumLocationLabel();
        addressField.setText(location);
        statusLabel.setText("Opening " + album.name() + "…");
        countLabel.setText("");
        showListing(List.of(), null);
        tree.getSelectionModel().clearSelection();

        settings.recordAlbumUse(album.fileName());
        saveSettingsQuietly();

        // Reading a scanner-sized CSV and statting/classifying its members are
        // both background work. The sequence guard makes a superseded open a
        // harmless no-op when the user navigates elsewhere before it finishes.
        service.fileOp(() -> albumStore.entries(album))
                .thenCompose(service::listPaths)
                .whenComplete((listing, error) -> Platform.runLater(() -> {
                    if (seq != scanSequence || !album.equals(currentAlbum)) return;
                    currentAlbumLoading = false;
                    if (error != null) {
                        showListing(List.of(), null);
                        statusLabel.setText("Cannot open " + album.name() + ": "
                                + rootMessage(error));
                        return;
                    }
                    currentAlbumUnavailable = listing.unavailable();
                    showListing(listing.entries(), null);
                    String message = album.name() + " · " + listing.entries().size()
                            + (listing.entries().size() == 1 ? " item" : " items");
                    if (listing.unavailable() > 0) {
                        message += " · " + listing.unavailable() + " unavailable";
                    }
                    statusLabel.setText(message);
                    listView.requestFocus();
                }));
    }

    private String albumLocationLabel() {
        return currentAlbum == null ? null : "Album: " + currentAlbum.name();
    }

    /**
     * Leaves an unreadable folder navigable: its contents are unknown, but the
     * parent link is still valid and must remain available as an escape hatch.
     */
    private void showListingFailure(Path target, Path select, Throwable error) {
        listingFailureMessage = rootMessage(error);
        mosaic.onDirectoryScanFailed(target, listingFailureMessage);
        showListing(navigationOnlyListing(target), select);
        statusLabel.setText(listingFailureMessage);
    }

    /** A listing containing only {@code ..}, or empty at a non-Windows root. */
    static List<DirEntry> navigationOnlyListing(Path dir) {
        Path parent = ArchivePaths.parentOf(dir);
        if (parent != null) {
            return List.of(new DirEntry(parent, DirEntry.Type.PARENT, null, 0, 0));
        }
        return withComputerParent(dir, List.of());
    }

    /** Adds the synthetic {@code ..} row that leads from a Windows drive to Computer. */
    private static List<DirEntry> withComputerParent(Path dir, List<DirEntry> listing) {
        return withComputerParent(dir, listing,
                supportsComputerLocation(System.getProperty("os.name")));
    }

    static List<DirEntry> withComputerParent(Path dir, List<DirEntry> listing,
                                              boolean computerSupported) {
        if (!computerSupported || dir == null || dir.getRoot() == null
                || !dir.equals(dir.getRoot()) || ArchivePaths.parentOf(dir) != null
                || listing.stream().anyMatch(entry -> entry.type() == DirEntry.Type.PARENT)) {
            return listing;
        }
        var result = new ArrayList<DirEntry>(listing.size() + 1);
        // The PARENT action is semantic; its path remains the real drive root
        // because Computer deliberately has no fake filesystem Path.
        result.add(new DirEntry(dir, DirEntry.Type.PARENT, null, 0, 0));
        result.addAll(listing);
        return List.copyOf(result);
    }

    /**
     * Paints a fresh directory listing: replaces the entries, updates the count
     * and lands the selection (the {@code select} target if given, else clears
     * and scrolls to the top — a fresh listing otherwise inherits the previous
     * folder's scroll offset, since the VirtualFlow keeps its pixel position
     * across {@code setAll}). Used for the first paint of a navigation: the fast
     * extension-only listing under content sniff, or the sole listing otherwise.
     */
    private void showListing(List<DirEntry> listing, Path select) {
        entries.setAll(listing);
        updateCount();
        if (select != null) {
            selectEntry(select);
        } else {
            listView.getSelectionModel().clearSelection();
            listView.scrollTo(0);
        }
        if (pendingInitialMosaicReveal) {
            pendingInitialMosaicReveal = false;
            System.err.println("[flash-diag] pendingInitialMosaicReveal resolved at " + System.nanoTime()
                    + " listing.size()=" + listing.size());
        }
        openPendingViewerTargetIfReady();
    }

    /** Opportunistic check: gives up on an unresolved target only once nothing more will correct it. */
    private void openPendingViewerTargetIfReady() {
        openPendingViewerTargetIfReady(false);
    }

    /**
     * Consumes {@link #pendingViewerTarget} once its folder listing has
     * landed: opens the viewer on it directly, the same call
     * {@link #activateSelected} makes for Enter/double-click, but always
     * taking focus regardless of the multi-window "keep focus" toggle — a
     * freshly-opened file (from an Explorer double-click, or forwarded from
     * a running instance) should always take over.
     *
     * <p>Under {@link DetectionMode#CONTENT_SNIFF} the fast/skeleton listing
     * classifies an unrecognized-extension file as {@code OTHER} until the
     * background sniff either upgrades it to {@code MEDIA} or leaves it
     * alone; a still-{@code OTHER} target here may yet be promoted by that
     * scan, so this only reports "not viewable" and gives up once
     * {@code giveUpIfUnresolved} says the scan is done (see the
     * {@link #streamReclassification} completion callback) — otherwise it
     * leaves {@link #pendingViewerTarget} set and quietly returns, to be
     * retried once {@link #flushReclassifications} lands a correction.</p>
     */
    private void openPendingViewerTargetIfReady(boolean giveUpIfUnresolved) {
        if (pendingViewerTarget == null) return;
        Path target = pendingViewerTarget;
        DirEntry found = null;
        for (DirEntry entry : entries) {
            if (entry.path().equals(target)) {
                found = entry;
                break;
            }
        }
        if (found != null && found.viewable()) {
            pendingViewerTarget = null;
            System.err.println("[flash-diag] pendingViewerTarget resolved (viewable) at " + System.nanoTime());
            selectEntry(target);
            viewer.open(found.toMediaItem(), viewableItems(), true, this);
            return;
        }
        boolean stillSniffing = found != null && found.type() == DirEntry.Type.OTHER
                && service.detectionMode() == DetectionMode.CONTENT_SNIFF;
        if (stillSniffing && !giveUpIfUnresolved) {
            return; // may yet be promoted by the in-flight content sniff
        }
        pendingViewerTarget = null;
        System.err.println("[flash-diag] pendingViewerTarget resolved (NOT viewable/found) at "
                + System.nanoTime() + " found=" + (found != null));
        statusLabel.setText(found != null
                ? "Not viewable: " + found.displayName()
                : "Not found: " + target);
    }

    /**
     * Kicks off the background content-sniff of an already-painted skeleton
     * listing: collects its file paths (folders and {@code ..} need no sniff) and
     * hands them to {@link MediaService#reclassify}, which streams back only the
     * corrections. Each lands on the FX thread into {@link #reclassQueue} and is
     * applied in coalesced batches; a final flush on completion catches the tail
     * and marks the scan done.
     */
    private void streamReclassification(Path target, List<DirEntry> skeleton, int seq) {
        List<Path> files = new ArrayList<>();
        for (DirEntry e : skeleton) {
            if (e.type() == DirEntry.Type.MEDIA || e.type() == DirEntry.Type.OTHER) {
                files.add(e.path());
            }
        }
        service.reclassify(files, () -> seq == scanSequence, (path, kind) -> {
            reclassQueue.add(Map.entry(path, kind));
            scheduleReclassFlush();
        }).whenComplete((v, error) -> Platform.runLater(() -> {
            flushReclassifications();              // apply the tail batch
            if (seq == scanSequence) {
                statusLabel.setText(target.toString());
                // The sniff is done for this navigation: a pending viewer
                // target still stuck at OTHER is never getting promoted now,
                // so stop waiting and report it instead of leaving it stuck.
                openPendingViewerTargetIfReady(true);
            }
        }));
    }

    /** Coalesces streamed corrections onto a single FX-pulse flush. */
    private void scheduleReclassFlush() {
        if (reclassFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushReclassifications);
        }
    }

    /**
     * Drains the queued content-sniff corrections and applies them in one batch:
     * swaps the reclassified entries in place (a single {@code setAll}, so the
     * list/grid rebuild once per batch, not per file) while preserving the user's
     * current selection and scroll. Corrections are matched to the live listing
     * by path, so a straggler from a folder we have since left simply finds no
     * match and is dropped — no per-navigation sequence guard needed. A no-op
     * when nothing in the batch actually changed.
     */
    private void flushReclassifications() {
        reclassFlushScheduled.set(false);
        if (reclassQueue.isEmpty()) return;
        Map<Path, Optional<MediaKind>> changes = new HashMap<>();
        for (Map.Entry<Path, Optional<MediaKind>> e; (e = reclassQueue.poll()) != null; ) {
            changes.put(e.getKey(), e.getValue());
        }
        if (changes.isEmpty()) return;
        List<DirEntry> updated = new ArrayList<>(entries);
        boolean any = false;
        for (int i = 0; i < updated.size(); i++) {
            DirEntry cur = updated.get(i);
            Optional<MediaKind> kind = changes.get(cur.path());
            if (kind == null) continue;
            DirEntry refined = kind.isPresent()
                    ? new DirEntry(cur.path(), DirEntry.Type.MEDIA, kind.get(),
                            cur.size(), cur.lastModifiedMillis(), cur.traits())
                    : new DirEntry(cur.path(), DirEntry.Type.OTHER, null,
                            cur.size(), cur.lastModifiedMillis(), cur.traits());
            if (!refined.equals(cur)) {
                updated.set(i, refined);
                any = true;
            }
        }
        if (!any) return;
        DirEntry sel = listView.getSelectionModel().getSelectedItem();
        Path keep = sel != null ? sel.path() : null;
        entries.setAll(updated);
        updateCount();
        if (keep != null) selectEntry(keep);
        // Belt-and-suspenders: a pending viewer target normally resolves in
        // showListing's initial pass, but a content-sniff correction that
        // upgrades an unknown-extension file to MEDIA lands here instead.
        openPendingViewerTargetIfReady();
    }

    /**
     * Address bar Go/Enter: navigates, and on a valid target hands focus to
     * the file list; an invalid path leaves the field focused for fixing.
     */
    private void navigateFromAddress() {
        if (supportsComputerLocation(System.getProperty("os.name"))
                && COMPUTER_LOCATION_NAME.equalsIgnoreCase(addressField.getText().trim())) {
            navigateToComputer();
            listView.requestFocus();
            return;
        }
        // Accepts both a plain path and an archive locator
        // (/discs/photos.iso!/DCIM), mounting the container when needed.
        Path target = ArchivePaths.parse(addressField.getText()).orElse(null);
        if (target == null) {
            statusLabel.setText("Cannot open: " + addressField.getText().trim());
            return;
        }
        if (navigate(target)) {
            listView.requestFocus();
        }
    }

    /**
     * Navigates and mirrors the new location into the folder tree. Locations
     * inside an archive are skipped for the tree, which shows the real
     * filesystem only — the archive itself stays selected there instead.
     */
    private void navigateAndSync(Path dir) {
        navigate(dir);
        if (ArchivePaths.inArchive(dir)) {
            ArchivePaths.hostArchive(dir)
                    .map(archive -> archive.toAbsolutePath().normalize().getParent())
                    .ifPresent(this::expandAndSelect);
            return;
        }
        expandAndSelect(dir.toAbsolutePath().normalize());
    }

    /** Backspace / Enclosing Folder, and the viewer relaying parent navigation:
     * navigates the listing to the current folder's parent. */
    @Override
    public void navigateToParent() {
        if (atComputerLocation || currentDir == null) return;
        // From an archive root this steps back out to the folder holding the
        // container, rather than dead-ending at a filesystem root.
        Path parent = ArchivePaths.parentOf(currentDir);
        if (parent == null) {
            if (supportsComputerLocation(System.getProperty("os.name"))
                    && currentDir.getRoot() != null
                    && currentDir.equals(currentDir.getRoot())) {
                pendingSelection = currentDir;
                navigateToComputer();
            }
            return;
        }
        pendingSelection = ArchivePaths.hostArchive(currentDir)
                .filter(archive -> ArchivePaths.isArchiveRoot(currentDir))
                .orElse(currentDir);   // land on the archive we came out of
        navigateAndSync(parent);
    }

    private void goBack() {
        if (backStack.isEmpty()) return;
        BrowserLocation target = backStack.pop();
        BrowserLocation current = currentLocation();
        if (current != null) forwardStack.push(current);
        traverseHistory(target);
    }

    private void goForward() {
        if (forwardStack.isEmpty()) return;
        BrowserLocation target = forwardStack.pop();
        BrowserLocation current = currentLocation();
        if (current != null) backStack.push(current);
        traverseHistory(target);
    }

    private void traverseHistory(BrowserLocation target) {
        traversingHistory = true;
        try {
            if (target.isComputer()) navigateToComputer();
            else if (target.isAlbum()) navigateToAlbum(target.album());
            else navigateAndSync(target.directory());
        } finally {
            traversingHistory = false;
        }
        updateHistoryItems();
    }

    private void updateHistoryItems() {
        backDisabledProp.set(backStack.isEmpty());
        forwardDisabledProp.set(forwardStack.isEmpty());
    }

    /**
     * Limits the list to the entry types ticked in the Show menu; the
     * {@code ..} entry is always visible.
     */
    private void applyFilter() {
        filteredEntries.setPredicate(e -> switch (e.type()) {
            case PARENT -> true;
            // An archive is browsed as a folder, so it follows the folder
            // toggle rather than the non-viewable-file one it would otherwise
            // hide behind.
            case DIRECTORY -> atComputerLocation || showDirsProp.get();
            case ARCHIVE -> showDirsProp.get();
            case MEDIA -> showViewableProp.get();
            case OTHER -> showNonViewableProp.get();
        });
        updateCount();
    }

    private void updateCount() {
        if (currentAlbumLoading) {
            countLabel.setText("");
            updateEmptyState();
            return;
        }
        long dirs = 0, media = 0, other = 0;
        for (DirEntry e : visibleEntries) {
            switch (e.type()) {
                case DIRECTORY -> dirs++;
                case MEDIA -> media++;
                case OTHER -> other++;
                case PARENT -> { }
            }
        }
        String text = dirs + " folders · " + (media + other) + " files · "
                + media + " viewable";
        if (currentAlbum != null && currentAlbumUnavailable > 0) {
            text += " · " + currentAlbumUnavailable + " unavailable";
        }
        // Disclose what the opt-in filters removed — the user turned them on,
        // so the count is the receipt. Junk is not reported: a dropped
        // .DS_Store changes nothing about the folder, and a suffix on every
        // folder on the disk is noise rather than honesty.
        var hidden = currentDir == null ? null : service.hiddenIn(currentDir);
        if (hidden != null && hidden.anyOptIn()) {
            var parts = new ArrayList<String>(3);
            if (hidden.hiddenEntries() > 0) {
                parts.add(hidden.hiddenEntries()
                        + (hidden.hiddenEntries() == 1 ? " hidden item" : " hidden items"));
            }
            if (hidden.emptyFiles() > 0) {
                parts.add(hidden.emptyFiles()
                        + (hidden.emptyFiles() == 1 ? " empty file" : " empty files"));
            }
            if (hidden.emptyFolders() > 0) {
                parts.add(hidden.emptyFolders()
                        + (hidden.emptyFolders() == 1 ? " empty folder" : " empty folders"));
            }
            text += " · " + String.join(", ", parts);
        }
        countLabel.setText(text);
        updateEmptyState();
    }

    /**
     * Says which nothing an empty listing is, rather than leaving a bare
     * "nothing to show". When a filter caused it the filter is named, since
     * that turns a dead end into a one-key fix — the folder is genuinely full.
     */
    private void updateEmptyState() {
        if (hasNonParentEntry(visibleEntries)) {
            emptyStateLabel.setVisible(false);
            return;
        }
        if (listingFailureMessage != null) {
            emptyStateLabel.setText("Can't read this folder — " + listingFailureMessage);
            emptyStateLabel.setVisible(true);
            return;
        }
        if (atComputerLocation) {
            emptyStateLabel.setText("No drives available");
            emptyStateLabel.setVisible(true);
            return;
        }
        var offFilters = new ArrayList<String>(3);
        if (!showViewableProp.get()) offFilters.add("Viewable Media");
        if (!showNonViewableProp.get()) offFilters.add("Non-viewable Files");
        if (!showDirsProp.get()) offFilters.add("Folders");
        long listed = entries.stream().filter(e -> e.type() != DirEntry.Type.PARENT).count();
        if (listed > 0 && !offFilters.isEmpty()) {
            emptyStateLabel.setText(listed + (listed == 1 ? " item" : " items")
                    + " hidden by " + String.join(", ", offFilters));
            return;
        }
        if (currentAlbum != null) {
            String message;
            if (currentAlbumLoading) message = "Opening album…";
            else if (currentAlbumUnavailable > 0) {
                message = "No available items in " + currentAlbum.name();
            } else message = "Empty album";
            emptyStateLabel.setText(message);
            emptyStateLabel.setVisible(true);
            return;
        }
        var hidden = currentDir == null ? null : service.hiddenIn(currentDir);
        if (hidden != null && hidden.anyOptIn()) {
            var parts = new ArrayList<String>(3);
            if (hidden.hiddenEntries() > 0) {
                parts.add(hidden.hiddenEntries()
                        + (hidden.hiddenEntries() == 1 ? " hidden item" : " hidden items"));
            }
            if (hidden.emptyFiles() > 0) {
                parts.add(hidden.emptyFiles()
                        + (hidden.emptyFiles() == 1 ? " empty file" : " empty files"));
            }
            if (hidden.emptyFolders() > 0) {
                parts.add(hidden.emptyFolders()
                        + (hidden.emptyFolders() == 1 ? " empty folder" : " empty folders"));
            }
            emptyStateLabel.setText("Empty folder (" + String.join(", ", parts) + ")");
        } else if (hidden != null && hidden.junkFiles() > 0) {
            // Never a bare "Empty" that a Finder window would contradict.
            emptyStateLabel.setText("Empty folder (" + hidden.junkFiles()
                    + (hidden.junkFiles() == 1 ? " system file)" : " system files)"));
        } else {
            emptyStateLabel.setText("Empty folder");
        }
        emptyStateLabel.setVisible(true);
    }

    /**
     * Whether a rendered listing contains anything besides the navigational
     * {@code ..} row. Shared with the mosaic so both views agree about when an
     * empty-state message is warranted.
     */
    static boolean hasNonParentEntry(Iterable<DirEntry> listing) {
        for (DirEntry entry : listing) {
            if (entry.type() != DirEntry.Type.PARENT) return true;
        }
        return false;
    }

    // --- sorting -------------------------------------------------------------

    private static final Comparator<DirEntry> BY_NAME =
            Comparator.comparing(DirEntry::displayName, String.CASE_INSENSITIVE_ORDER);

    // --- application menu bar -------------------------------------------------

    /** Which window an installed menu-bar instance belongs to (scopes accelerators). */
    private enum MenuOwner { MAIN, MOSAIC, VIEWER }

    /**
     * Builds an instance of the single application menu bar. The main window,
     * the mosaic and the viewer each install one (each its own instance, so
     * macOS scopes the accelerators to the focused window); every stateful item
     * binds to the shared {@code *Prop} fields and the satellite windows'
     * properties, keeping all three bars in lock-step. {@code owner} selects
     * the location chrome shown in Show and adds only that view's contextual
     * command menu (Mosaic or Viewer).
     */
    private MenuBar buildMenuBar(MenuOwner owner) {
        // Accelerators use the logical modifier scheme (Settings ▸ Keys): modifier 1
        // is the platform "shortcut" key by default (Command on macOS, Control
        // elsewhere). Every item also carries an Alt-letter mnemonic (the leading
        // "_"). Each window installs its own bar instance, so the OS scopes
        // accelerators to whichever window is focused.
        List<MenuItem> fileItems = new ArrayList<>(List.of(
                action("_Open Folder\u2026", keys.mod1(KeyCode.O), this::chooseDirectory),
                action("Open _Album\u2026", keys.mod1Shift(KeyCode.O), this::chooseAlbum),
                new SeparatorMenuItem(),
                buildMoveItem(owner),
                buildTrashItem(owner),
                buildExportMenu(owner),
                new SeparatorMenuItem(),
                action("_Settings\u2026", keys.mod1(KeyCode.COMMA), this::showSettings)));
        if (io.github.ghosthack.panama.media.core.Platform.IS_WINDOWS) {
            fileItems.add(action("File _Associations\u2026", null, this::showFileAssociations));
        }
        fileItems.addAll(List.of(
                new SeparatorMenuItem(),
                buildCloseWindowItem(owner),
                action("E_xit", keys.mod1(KeyCode.Q), Platform::exit)));
        var fileMenu = new Menu("_File", null, fileItems.toArray(MenuItem[]::new));

        var back = action("_Back", keys.mod1(KeyCode.LEFT), this::goBack);
        back.disableProperty().bind(backDisabledProp);
        var forward = action("_Forward", keys.mod1(KeyCode.RIGHT), this::goForward);
        forward.disableProperty().bind(forwardDisabledProp);
        // Go to Location targets the bar's own window: the mosaic's location bar
        // in the mosaic bar, the browser's address bar everywhere else (the
        // viewer has no location field of its own).
        Runnable goToLocation = owner == MenuOwner.MOSAIC
                ? mosaic::focusLocationBar : this::focusAddressBar;
        var goMenu = new Menu("_Go", null, back, forward,
                action("_Enclosing Folder", keys.mod1(KeyCode.UP), this::navigateToParent),
                new SeparatorMenuItem(),
                action("Go to _Location", keys.mod1(KeyCode.L), goToLocation),
                action("_Reload", keys.mod1(KeyCode.R), this::reloadLocation));

        // Show: panel visibility, then the listing filter and the detection mode.
        // (Panel toggles carry modifier-1 accelerators; the listing filter and
        // detection groups are headed by disabled section labels.)
        //
        // The Toolbar / Status Bar / Info Panel toggles bind to the *focused*
        // window's own panel properties — the browser's by default, the mosaic's
        // or the viewer's in those bars — so mod1+T/S/I always acts on the
        // window it is pressed in (each window now owns all three panels). The
        // viewer's bare-key filter still consumes T/S/I first, so its accelerator
        // is shadowed (single toggle) while the check-state stays correct.
        BooleanProperty showToolbarProp = switch (owner) {
            case MAIN -> toolbarVisibleProp;
            case MOSAIC -> mosaic.toolbarVisibleProperty();
            case VIEWER -> viewer.toolbarVisibleProperty();
        };
        BooleanProperty showStatusProp = switch (owner) {
            case MAIN -> statusVisibleProp;
            case MOSAIC -> mosaic.statusBarVisibleProperty();
            case VIEWER -> viewer.statusBarVisibleProperty();
        };
        BooleanProperty showInfoProp = switch (owner) {
            case MAIN -> infoVisibleProp;
            case MOSAIC -> mosaic.infoPanelVisibleProperty();
            case VIEWER -> viewer.infoPanelVisibleProperty();
        };
        BooleanProperty showMetadataProp = switch (owner) {
            case MAIN -> metadataVisibleProp;
            case MOSAIC -> mosaic.metadataPanelVisibleProperty();
            case VIEWER -> viewer.metadataPanelVisibleProperty();
        };
        BooleanProperty showDiagnosticsProp = switch (owner) {
            case MAIN -> diagnosticsVisibleProp;
            case MOSAIC -> mosaic.diagnosticsPanelVisibleProperty();
            case VIEWER -> viewer.diagnosticsPanelVisibleProperty();
        };
        // Every view hosts an action-log panel over the one app-wide flag:
        // the mosaic's and viewer's properties are bound bidirectionally to
        // the browser's, so any bar's item toggles the panel in all three.
        BooleanProperty showActionLogProp = actionLogVisibleProp;
        // Menu-bar runtime visibility: per-window, toggled by mod1+\ and the
        // modifier-2 peek (see WindowChrome.bindMenuAutoHide).
        BooleanProperty showMenuBarProp = switch (owner) {
            case MAIN -> menuBarVisibleProp;
            case MOSAIC -> mosaic.menuBarVisibleProperty();
            case VIEWER -> viewer.menuBarVisibleProperty();
        };
        var detectGroup = new ToggleGroup();
        var showMenu = new Menu("_Show");
        showMenu.getItems().addAll(
                boundCheck("_Toolbar", showToolbarProp, keys.mod1(KeyCode.T)),
                boundCheck("_Status Bar", showStatusProp, keys.mod1(KeyCode.S)),
                boundCheck("_Info Panel", showInfoProp, keys.mod1(KeyCode.I)),
                boundCheck("_Metadata", showMetadataProp, keys.mod1(KeyCode.D)),
                boundCheck("Menu _Bar", showMenuBarProp, keys.mod1(KeyCode.BACK_SLASH)),
                new SeparatorMenuItem());
        // Expose only location chrome the current view actually owns. The
        // viewer has neither a location field nor a navigation tree.
        switch (owner) {
            case MAIN -> showMenu.getItems().addAll(
                    boundCheck("_Address Bar", addressVisibleProp, keys.mod1Shift(KeyCode.L)),
                    boundCheck("_Nav Tree Panel", treeVisibleProp, keys.mod1(KeyCode.E)));
            case MOSAIC -> showMenu.getItems().add(
                    boundCheck("_Location Bar", mosaic.locationBarVisibleProperty(),
                            keys.mod1Shift(KeyCode.L)));
            case VIEWER -> { }
        }
        showMenu.getItems().addAll(
                boundCheck("Action _Log", showActionLogProp, keys.mod1(KeyCode.J)),
                boundCheck("Dia_gnostics", showDiagnosticsProp, keys.mod1(KeyCode.G)),
                new SeparatorMenuItem(),
                header("Listing filter"),
                boundCheck("Show _Viewable Media", showViewableProp),
                boundCheck("Show _Non-viewable Media", showNonViewableProp),
                boundCheck("Show _Folders", showDirsProp),
                boundCheck("Show _Hidden Files", showHiddenFilesProp),
                // docs/empty-states.md. "System files" on screen, "junk" in the
                // code: a menu item should not call a user's disk junk.
                inverseBoundCheck("Show S_ystem Files", ignoreJunkProp),
                inverseBoundCheck("Show _Empty Files", hideEmptyFilesProp),
                inverseBoundCheck("Show Empty F_olders", hideEmptyFoldersProp),
                boundCheck("_Collapse Folder Chains", collapseChainsProp),
                new SeparatorMenuItem(),
                header("Detection"),
                objRadio("Detect by _Content", DetectionMode.CONTENT_SNIFF,
                        detectionProp, detectGroup),
                objRadio("Detect by File E_xtension", DetectionMode.FILE_EXTENSION,
                        detectionProp, detectGroup),
                new SeparatorMenuItem(),
                boundCheck("_Keep Focus on Open", keepFocusProp));

        // Sort key and direction (folder placement — Top/Bottom/Natural —
        // is not yet modelled here).
        var keyGroup = new ToggleGroup();
        var dirGroup = new ToggleGroup();
        var sortMenu = new Menu("S_ort", null,
                objRadio("By _Name", SortKey.NAME, sortKeyProp, keyGroup),
                objRadio("By E_xtension", SortKey.EXTENSION, sortKeyProp, keyGroup),
                objRadio("By Si_ze", SortKey.SIZE, sortKeyProp, keyGroup),
                objRadio("By _Date", SortKey.DATE, sortKeyProp, keyGroup),
                new SeparatorMenuItem(),
                boolRadio("_Ascending", false, sortDescendingProp, dirGroup),
                boolRadio("_Descending", true, sortDescendingProp, dirGroup));

        var helpMenu = new Menu("_Help", null,
                action("_About Media Browser", null, this::showAbout));

        var bar = new MenuBar(fileMenu, goMenu, showMenu, sortMenu);
        // View-specific commands belong only to the view that can execute them;
        // Window remains the shared place for switching views.
        if (owner == MenuOwner.MOSAIC) bar.getMenus().add(buildMosaicMenu());
        if (owner == MenuOwner.VIEWER) bar.getMenus().add(buildViewerMenu());
        bar.getMenus().addAll(buildWindowMenu(owner), helpMenu);
        return bar;
    }

    /**
     * The Window menu: a radio item per view that <em>selects</em> (switches the
     * single window to) that view. The ticked item tracks the shell's active
     * view — every bar binds to the same {@code isActive} bindings, so all the
     * copies stay in lock-step. The viewer needs a media item to show, so its
     * item is disabled (greyed out) until it has shown one.
     *
     * <p>The Maximize / Restore item targets the bar's own view ({@code owner})
     * — only the active view's bar is live, so it always acts on the view on
     * screen; the viewer routes through its focus mode so its menu maximize and
     * F key share one state (chrome hiding included).</p>
     */
    private Menu buildWindowMenu(MenuOwner owner) {
        var group = new ToggleGroup();
        var mainItem = windowSelect("Media _Browser",
                shell.isActive(AppShell.AppView.BROWSER), group,
                () -> shell.showView(AppShell.AppView.BROWSER));
        // Switch to the browser view (modifier-1 + Shift + B).
        mainItem.setAccelerator(keys.mod1Shift(KeyCode.B));
        var mosaicItem = windowSelect("_Mosaic",
                shell.isActive(AppShell.AppView.MOSAIC), group,
                this::openMosaic);
        // Open / switch to the Mosaic (modifier-1 + Shift + M). This is the single
        // home for the former View ▸ Open Mosaic accelerator, so it never double-binds.
        mosaicItem.setAccelerator(keys.mod1Shift(KeyCode.M));
        var viewerItem = windowSelect("_Viewer",
                shell.isActive(AppShell.AppView.VIEWER), group,
                viewer::toFront);
        // Switch to the viewer (modifier-1 + Shift + V), completing the
        // Browser/Mosaic/Viewer trio of window chords.
        viewerItem.setAccelerator(keys.mod1Shift(KeyCode.V));
        // The viewer cannot show without a media item, so its selector is greyed
        // out until it has ever shown one.
        viewerItem.disableProperty().bind(viewer.hasContentProperty().not());
        // The viewer routes through its focus mode so its menu maximize and F key
        // share one state; the browser and mosaic drive the shell's shared
        // maximizer. The accelerator (modifier-1 + Shift + F) mirrors the viewer's
        // bare F maximize, giving every view the same one-chord maximize/restore.
        var maximizeItem = action("Ma_ximize / Restore", keys.mod1Shift(KeyCode.F), () -> {
            switch (owner) {
                case MAIN -> shell.maximizer().toggle(stage());
                case MOSAIC -> mosaic.toggleMaximize();
                case VIEWER -> viewer.toggleFocusMode();
            }
        });
        // Tile the window to a screen half (modifier-1 + Shift + arrow). The
        // mosaic and viewer also implement these chords in their own key filters,
        // which consume the arrows before the accelerators fire; the labels here
        // keep them discoverable and drive the click path.
        var tileLeftItem = action("Tile _Left", keys.mod1Shift(KeyCode.LEFT), () -> {
            switch (owner) {
                case MAIN -> shell.maximizer().snapLeft(stage());
                case MOSAIC -> mosaic.snapLeft();
                case VIEWER -> viewer.snapLeft();
            }
        });
        var tileRightItem = action("Tile _Right", keys.mod1Shift(KeyCode.RIGHT), () -> {
            switch (owner) {
                case MAIN -> shell.maximizer().snapRight(stage());
                case MOSAIC -> mosaic.snapRight();
                case VIEWER -> viewer.snapRight();
            }
        });
        return new Menu("_Window", null, mainItem, mosaicItem, viewerItem,
                new SeparatorMenuItem(), maximizeItem, tileLeftItem, tileRightItem);
    }

    /**
     * A {@link RadioMenuItem} that <em>selects</em> a view: its tick tracks the
     * shell's {@code active} binding for that view (so the group always marks
     * the view on screen), and activating it runs {@code select} to switch to
     * that view.
     */
    private RadioMenuItem windowSelect(String text, ObservableValue<Boolean> active,
                                       ToggleGroup group, Runnable select) {
        var item = new RadioMenuItem(text);
        item.setToggleGroup(group);
        item.setSelected(Boolean.TRUE.equals(active.getValue()));
        active.addListener((o, was, now) -> {
            if (Boolean.TRUE.equals(now)) item.setSelected(true);
        });
        item.setOnAction(e -> select.run());
        return item;
    }

    /** The Mosaic menu, mirroring the mosaic's actions; see {@link #buildMenuBar}. */
    private Menu buildMosaicMenu() {
        var fpGroup = new ToggleGroup();
        var folderPreviews = new Menu("_Folder Previews", null,
                objRadio("None", 0, folderPreviewGridProp, fpGroup),
                objRadio("2 × 2", 2, folderPreviewGridProp, fpGroup),
                objRadio("3 × 3", 3, folderPreviewGridProp, fpGroup),
                objRadio("4 × 4", 4, folderPreviewGridProp, fpGroup),
                new SeparatorMenuItem(),
                // Extends the collage scan to unnamed media (a curated header
                // sniff of extension-less/unknown-suffix children); independent
                // of Show ▸ Detection so it reads the same in both modes.
                boundCheck("Sniff _Unnamed Files", folderPreviewSniffProp));
        var fgGroup = new ToggleGroup();
        var folderGlyph = new Menu("Folder _Glyph", null,
                objRadio(MosaicFolderGlyph.GLYPH.label(), MosaicFolderGlyph.GLYPH,
                        mosaic.folderGlyphProperty(), fgGroup),
                objRadio(MosaicFolderGlyph.ROUNDED.label(), MosaicFolderGlyph.ROUNDED,
                        mosaic.folderGlyphProperty(), fgGroup),
                objRadio(MosaicFolderGlyph.INVERSE.label(), MosaicFolderGlyph.INVERSE,
                        mosaic.folderGlyphProperty(), fgGroup),
                objRadio(MosaicFolderGlyph.IMAGE.label(), MosaicFolderGlyph.IMAGE,
                        mosaic.folderGlyphProperty(), fgGroup));
        var showThumbnails = boundCheck("Show T_humbnails", mosaic.thumbnailsVisibleProperty());
        var fillTiles = boundCheck("Fill T_iles (crop to square)", mosaic.fillTilesProperty());
        var tileLabels = new Menu("Tile _Labels", null,
                boundCheck("_Folders", mosaic.dirLabelsVisibleProperty()),
                boundCheck("Fil_es", mosaic.fileLabelsVisibleProperty()),
                boundCheck("_Media", mosaic.mediaLabelsVisibleProperty()));
        var autoOpen = boundCheck("_Auto-open Selected in Viewer", mosaic.autoOpenProperty());
        var keepFocus = boundCheck("_Keep Focus on Open", mosaic.keepFocusProperty());
        var dragTiles = boundCheck("_Drag Tiles to Other Apps",
                mosaic.dragTilesToOtherAppsProperty(), keys.mod1Shift(KeyCode.D));
        var animGroup = new ToggleGroup();
        var selectionAnimation = new Menu("Se_lection Animation", null,
                objRadio(MosaicSelectionAnimation.NONE.label(), MosaicSelectionAnimation.NONE,
                        mosaic.selectionAnimationProperty(), animGroup),
                objRadio(MosaicSelectionAnimation.PULSE.label(), MosaicSelectionAnimation.PULSE,
                        mosaic.selectionAnimationProperty(), animGroup),
                objRadio(MosaicSelectionAnimation.MARCHING_ANTS.label(),
                        MosaicSelectionAnimation.MARCHING_ANTS,
                        mosaic.selectionAnimationProperty(), animGroup));
        var reticuleScopeGroup = new ToggleGroup();
        var reticuleCoverage = new Menu("Reticule _Coverage", null,
                objRadio("Entire _Tile", MosaicReticuleScope.TILE,
                        mosaic.reticuleScopeProperty(), reticuleScopeGroup),
                objRadio("_Icon Glyph Only", MosaicReticuleScope.GLYPH,
                        mosaic.reticuleScopeProperty(), reticuleScopeGroup),
                objRadio("Icon Glyph Only — _Black", MosaicReticuleScope.GLYPH_BLACK,
                        mosaic.reticuleScopeProperty(), reticuleScopeGroup));
        var menu = new Menu("_Mosaic", null,
                action("Open _Selected", null, mosaic::openSelectedItem),
                new SeparatorMenuItem(),
                boundCheck("S_eamless", mosaic.seamlessProperty(),
                        keys.mod1(KeyCode.B)),
                action("_Larger Tiles",
                        keys.mod1(KeyCode.EQUALS),
                        () -> mosaic.nudgeTileSize(32)),
                action("S_maller Tiles",
                        keys.mod1(KeyCode.MINUS),
                        () -> mosaic.nudgeTileSize(-32)),
                new SeparatorMenuItem(),
                folderPreviews,
                folderGlyph,
                showThumbnails,
                fillTiles,
                tileLabels,
                selectionAnimation,
                new SeparatorMenuItem(),
                boundCheck("Mark _Dead Ends", mosaic.emptyReticuleProperty()),
                boundCheck("Mark Files That _Won't Open", mosaic.wontOpenReticuleProperty()),
                reticuleCoverage,
                boundCheck("Check Whole Su_btree", mosaic.barrenCheckProperty()),
                new SeparatorMenuItem(),
                autoOpen,
                keepFocus,
                dragTiles);
        return menu;
    }

    /**
     * The Viewer menu, mirroring the viewer's toolbar buttons and scene key
     * filter; see {@link #buildMenuBar}. The viewer centralises its keys
     * (Escape/Enter/arrows/Space/T/S/I/P/F) in a scene event filter that
     * consumes them before any menu accelerator could fire, so these items carry
     * no accelerators and exist for click-access (notably in the macOS system
     * bar).
     */
    private Menu buildViewerMenu() {
        var playPause = action("Pla_y / Pause", null, viewer::togglePlayback);
        playPause.disableProperty().bind(viewer.playDisabledProperty());
        var scaleGroup = new ToggleGroup();
        var scaleProp = viewer.scaleModeProperty();
        // The loading-indicator radios bind to the viewer's ObjectProperty and
        // mirror the toolbar picker.
        var loadingGroup = new ToggleGroup();
        var loadingProp = viewer.loadingIndicatorProperty();
        var loadingMenu = new Menu("_Loading Indicator", null,
                objRadio("_None", LoadingIndicator.NONE, loadingProp, loadingGroup),
                objRadio("_Default", LoadingIndicator.DEFAULT, loadingProp, loadingGroup),
                objRadio("_Game Console", LoadingIndicator.GAME_CONSOLE, loadingProp, loadingGroup),
                objRadio("_ASCII Matrix", LoadingIndicator.ASCII_MATRIX, loadingProp, loadingGroup),
                objRadio("ASCII Matrix (_Black Label)",
                        LoadingIndicator.ASCII_MATRIX_BLACK_LABEL,
                        loadingProp, loadingGroup),
                objRadio("_Now Loading", LoadingIndicator.NOW_LOADING,
                        loadingProp, loadingGroup));
        var enhancePhoto = action("_Photo (Real-ESRGAN 4×)", null, viewer::enhancePhoto);
        enhancePhoto.disableProperty().bind(viewer.superResolutionDisabledProperty());
        var enhanceArtwork = action("_Artwork (Real-ESRGAN 4×)", null, viewer::enhanceArtwork);
        enhanceArtwork.disableProperty().bind(viewer.superResolutionDisabledProperty());
        var showEnhanced = boundCheck("Show _Enhanced", viewer.enhancedVisibleProperty());
        showEnhanced.disableProperty().bind(viewer.enhancedAvailableProperty().not());
        var enhanceMenu = new Menu("_Super Resolution", null,
                enhancePhoto, enhanceArtwork, new SeparatorMenuItem(), showEnhanced);
        var menu = new Menu("_Viewer", null,
                playPause,
                boundCheck("_Repeat", viewer.repeatProperty()),
                boundCheck("_Autoplay", viewer.autoplayProperty()),
                new SeparatorMenuItem(),
                boundCheck("Slide_show", viewer.slideshowProperty()),
                action("Slideshow _Settings\u2026", null, viewer::showSlideshowDialog),
                boundCheck("Flip_book", viewer.flipbookProperty()),
                boundCheck("Flipbook _Loop", viewer.flipbookLoopProperty()),
                action("Flipbook Settings\u2026", null, viewer::showFlipbookDialog),
                new SeparatorMenuItem(),
                objRadio("_Fit", ViewerWindow.ScaleMode.FIT, scaleProp, scaleGroup),
                objRadio("_1:1", ViewerWindow.ScaleMode.ONE_TO_ONE, scaleProp, scaleGroup),
                objRadio("_Crop to Fill", ViewerWindow.ScaleMode.CROP_TO_FILL, scaleProp, scaleGroup),
                new SeparatorMenuItem(),
                action("_Crop…", keys.mod1Shift(KeyCode.C), viewer::beginCrop),
                new SeparatorMenuItem(),
                enhanceMenu,
                new SeparatorMenuItem(),
                boundCheck("Show P_ins", viewer.pinsVisibleProperty()),
                loadingMenu,
                new SeparatorMenuItem(),
                boundCheck("F_ull Screen", viewer.fullScreenProperty()));
        return menu;
    }

    /**
     * The {@code Move…} item (Cmd/Ctrl+M). The main window, the mosaic and the
     * viewer each carry the accelerator and a handler driving their own
     * {@link MoveController}; because each window installs its own menu-bar
     * instance, macOS scopes the accelerator to whichever is focused, so the
     * three copies never double-bind. The viewer copy moves the on-screen item
     * and is disabled while the viewer is hidden (it has nothing to move).
     */
    /**
     * The {@code Close Window} item (Cmd/Ctrl+W): hides the bar's own window,
     * mirroring Escape in the viewer. Each window installs its own menu-bar
     * instance, so macOS scopes the accelerator to whichever window is focused
     * and the three copies never double-bind. Hiding the main (primary) window
     * with nothing else showing lets the platform exit, matching its window
     * close button.
     */
    private MenuItem buildCloseWindowItem(MenuOwner owner) {
        return action("_Close Window", keys.mod1(KeyCode.W), () -> {
            switch (owner) {
                // Hiding the single (primary) stage lets the platform exit,
                // matching the window's close button.
                case MAIN -> stage().hide();
                case MOSAIC -> mosaic.closeWindow();
                case VIEWER -> viewer.closeViewer();
            }
        });
    }

    private MenuItem buildMoveItem(MenuOwner owner) {
        var item = action("_Move\u2026", keys.mod1(KeyCode.M), () -> {
            switch (owner) {
                case MAIN -> openMove();
                case MOSAIC -> mosaic.openMove();
                case VIEWER -> viewer.openMove();
            }
        });
        if (owner == MenuOwner.VIEWER) {
            item.disableProperty().bind(viewer.hasContentProperty().not());
        }
        return item;
    }

    private MenuItem buildTrashItem(MenuOwner owner) {
        var item = action("Move to _" + MoveController.trashDestinationName(),
                keys.moveToTrash(), () -> {
            switch (owner) {
                case MAIN -> moveToTrash();
                case MOSAIC -> mosaic.moveToTrash();
                case VIEWER -> viewer.moveToTrash();
            }
        });
        if (owner == MenuOwner.VIEWER) {
            item.disableProperty().bind(viewer.hasContentProperty().not());
        }
        return item;
    }

    /** Extensible export submenu scoped to the window owning the menu bar. */
    private Menu buildExportMenu(MenuOwner owner) {
        Runnable zeroMetadataAction = switch (owner) {
            case MAIN -> this::exportZeroMetadataCopies;
            case MOSAIC -> mosaic::exportZeroMetadataCopies;
            case VIEWER -> viewer::exportZeroMetadataCopies;
        };
        Runnable bakedPngAction = switch (owner) {
            case MAIN -> this::exportBakedPng;
            case MOSAIC -> mosaic::exportBakedPng;
            case VIEWER -> viewer::exportBakedPng;
        };
        var item = action("As _Zero-Metadata Copy\u2026", null, zeroMetadataAction);
        var bakedPng = action("As _Baked PNG\u2026", null, bakedPngAction);
        if (owner == MenuOwner.VIEWER) {
            item.disableProperty().bind(viewer.hasContentProperty().not());
            bakedPng.disableProperty().bind(viewer.hasContentProperty().not());
        }
        return new Menu("_Export", null, item, bakedPng);
    }

    private static MenuItem action(String text, KeyCombination accel, Runnable handler) {
        var item = new MenuItem(text);
        if (accel != null) item.setAccelerator(accel);
        item.setOnAction(e -> handler.run());
        return item;
    }

    private static CheckMenuItem boundCheck(String text, BooleanProperty prop) {
        return boundCheck(text, prop, null);
    }

    private static CheckMenuItem boundCheck(String text, BooleanProperty prop,
                                            KeyCombination accel) {
        var item = new CheckMenuItem(text);
        if (accel != null) item.setAccelerator(accel);
        item.selectedProperty().bindBidirectional(prop);
        return item;
    }

    /**
     * A positive "Show" check item backed by an existing negative hide/ignore
     * property. The inverse binding keeps persisted settings and filter
     * behavior unchanged while making the menu labels read consistently.
     */
    private static CheckMenuItem inverseBoundCheck(String text, BooleanProperty hiddenProp) {
        var item = new CheckMenuItem(text);
        item.setSelected(!hiddenProp.get());
        item.selectedProperty().addListener((o, was, selected) -> hiddenProp.set(!selected));
        hiddenProp.addListener((o, was, hidden) -> item.setSelected(!hidden));
        return item;
    }

    /**
     * A disabled, styled section label inside a menu (e.g. "Listing filter",
     * "Detection", "Sort") — mirrors the source UI's {@code menu-header} rows that
     * group related items without a separate submenu.
     */
    private static MenuItem header(String text) {
        var item = new MenuItem(text);
        item.setDisable(true);
        item.getStyleClass().add("menu-header");
        item.setStyle("-fx-font-weight: bold; -fx-opacity: 1;");
        return item;
    }

    private static <T> RadioMenuItem objRadio(String text, T value,
                                              ObjectProperty<T> prop, ToggleGroup group) {
        var item = new RadioMenuItem(text);
        item.setToggleGroup(group);
        item.setSelected(Objects.equals(prop.get(), value));
        item.setOnAction(e -> prop.set(value));
        prop.addListener((o, a, b) -> item.setSelected(Objects.equals(b, value)));
        return item;
    }

    private static RadioMenuItem boolRadio(String text, boolean value,
                                           BooleanProperty prop, ToggleGroup group) {
        var item = new RadioMenuItem(text);
        item.setToggleGroup(group);
        item.setSelected(prop.get() == value);
        item.setOnAction(e -> prop.set(value));
        prop.addListener((o, a, b) -> item.setSelected(b == value));
        return item;
    }

    /** A combo over the {@link KeyScheme.ModifierChoice} mappings for the Keys tab. */
    private static ComboBox<KeyScheme.ModifierChoice> modifierChoiceCombo(String token) {
        var combo = new ComboBox<KeyScheme.ModifierChoice>();
        combo.getItems().setAll(KeyScheme.ModifierChoice.values());
        combo.setValue(KeyScheme.ModifierChoice.fromToken(token));
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(KeyScheme.ModifierChoice choice) {
                return choice == null ? "" : choice.label;
            }
            @Override public KeyScheme.ModifierChoice fromString(String s) {
                return null; // non-editable combo; never parses back
            }
        });
        return combo;
    }

    private void focusAddressBar() {
        addressVisibleProp.set(true); // unhide the bar if needed
        addressField.requestFocus();
        addressField.selectAll();
    }

    private void reloadLocation() {
        if (currentAlbum != null) navigateToAlbum(currentAlbum);
        else if (atComputerLocation) navigateToComputer();
        else if (currentDir != null) navigate(currentDir);
    }

    /** Sets the mosaic folder-preview grid edge, persists it, and repaints. */
    private void setFolderPreviewGrid(int grid) {
        settings.setMosaicFolderPreviewGrid(grid);
        try {
            settings.save();
        } catch (java.io.IOException e) {
            statusLabel.setText("Cannot save settings: " + e.getMessage());
        }
        mosaic.setFolderPreviewGrid(grid);
    }

    /**
     * Toggles the folder-preview sniff of unnamed files, persists it, and
     * drops the mosaic's cached collage scans so tiles re-scan under the new
     * policy.
     */
    private void setFolderPreviewSniff(boolean sniff) {
        service.setFolderPreviewSniff(sniff);
        settings.setMosaicFolderPreviewSniff(sniff);
        try {
            settings.save();
        } catch (java.io.IOException e) {
            statusLabel.setText("Cannot save settings: " + e.getMessage());
        }
        mosaic.refreshFolderPreviews();
    }

    /**
     * Switches how files are classified, persists the choice, and re-scans the
     * current folder so the listing reflects the new method immediately.
     */
    private void setDetectionMode(DetectionMode mode) {
        service.setDetectionMode(mode);
        settings.setDetectionMode(mode.settingsValue());
        saveSettingsQuietly();
        reloadCurrentListing();
    }

    /** Persists and re-lists after a listing filter changed what the scan keeps. */
    private void relist() {
        saveSettingsQuietly();
        reloadCurrentListing();
    }

    private void reloadCurrentListing() {
        if (currentAlbum != null) navigateToAlbum(currentAlbum);
        else if (currentDir != null) navigate(currentDir);
    }

    private void saveSettingsQuietly() {
        try {
            settings.save();
        } catch (java.io.IOException e) {
            statusLabel.setText("Cannot save settings: " + e.getMessage());
        }
    }

    /**
     * Re-sorts {@link #visibleEntries} for the current key and direction. The
     * parent link always sorts first and folders above files, regardless of
     * direction; the key (with a name tiebreak) orders entries within each
     * group, and only that key ordering reverses when descending.
     */
    private void applySort() {
        Comparator<DirEntry> key = switch (sortKeyProp.get()) {
            case NAME -> BY_NAME;
            case EXTENSION -> Comparator.comparing(DirEntry::extension,
                    String.CASE_INSENSITIVE_ORDER).thenComparing(BY_NAME);
            case SIZE -> Comparator.comparingLong(DirEntry::size).thenComparing(BY_NAME);
            case DATE -> Comparator.comparingLong(DirEntry::lastModifiedMillis)
                    .thenComparing(BY_NAME);
        };
        if (sortDescendingProp.get()) key = key.reversed();
        visibleEntries.setComparator(
                Comparator.<DirEntry>comparingInt(MainWindow::sortGroup).thenComparing(key));
    }

    /** Grouping rank that pins {@code ..} first and keeps folders above files. */
    private static int sortGroup(DirEntry e) {
        return switch (e.type()) {
            case PARENT -> 0;
            case DIRECTORY, ARCHIVE -> 1;
            case MEDIA, OTHER -> 2;
        };
    }

    /** Puts the entry's absolute path on the system clipboard. */
    private void copyPathToClipboard(DirEntry entry) {
        if (entry == null) return;
        // The locator form, so a path copied from inside an archive can be
        // pasted back into the address bar and reach the same entry.
        String path = ArchivePaths.format(entry.path().toAbsolutePath().normalize());
        var content = new ClipboardContent();
        content.putString(path);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied path: " + path);
    }

    private static AppIcon iconFor(DirEntry entry) {
        return switch (entry.type()) {
            case PARENT -> AppIcon.UP;
            case DIRECTORY -> AppIcon.FOLDER;
            case ARCHIVE -> AppIcon.ARCHIVE;
            case MEDIA -> switch (entry.mediaKind()) {
                case IMAGE -> AppIcon.IMAGE;
                case VIDEO -> AppIcon.VIDEO;
                case AUDIO -> AppIcon.AUDIO;
            };
            case OTHER -> null;
        };
    }

    private void expandAndSelect(Path target) {
        TreeItem<Path> node = tree.getRoot();
        Path root = node.getValue();
        if (root == null) {
            Path targetRoot = target.getRoot();
            if (targetRoot == null) return;
            TreeItem<Path> drive = null;
            for (TreeItem<Path> child : node.getChildren()) {
                if (targetRoot.equals(child.getValue())) {
                    drive = child;
                    break;
                }
            }
            if (drive == null) {
                drive = new DirTreeItem(targetRoot);
                node.getChildren().add(drive);
            }
            node = drive;
            root = targetRoot;
        } else if (!target.startsWith(root)) {
            Path targetRoot = target.getRoot();
            if (targetRoot == null) return;
            node = new DirTreeItem(targetRoot);
            node.setExpanded(true);
            tree.setRoot(node);
            root = targetRoot;
        }
        outer:
        for (Path name : root.relativize(target)) {
            for (TreeItem<Path> child : node.getChildren()) {
                Path childName = child.getValue().getFileName();
                if (childName != null && childName.equals(name)) {
                    child.setExpanded(true);
                    node = child;
                    continue outer;
                }
            }
            break; // component not visible in the tree (hidden/missing): stop here
        }
        tree.getSelectionModel().select(node);
        tree.scrollTo(tree.getRow(node));
    }

    /** Selects the virtual navigation-tree root without feeding the listener back. */
    private void selectComputerInTree() {
        TreeItem<Path> root = tree.getRoot();
        if (root != null && root.getValue() == null) {
            tree.getSelectionModel().select(root);
            tree.scrollTo(tree.getRow(root));
        }
    }

    /** Refreshes attached/detached drives whenever Computer is entered. */
    private void refreshComputerTreeRoots(List<Path> roots) {
        TreeItem<Path> computer = tree.getRoot();
        if (computer == null || computer.getValue() != null) return;
        computer.getChildren().setAll(roots.stream()
                .<TreeItem<Path>>map(DirTreeItem::new)
                .toList());
    }

    private void chooseDirectory() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Open Location");
        if (currentDir != null) chooser.setInitialDirectory(currentDir.toFile());
        var dir = chooser.showDialog(stage());
        if (dir != null) navigate(dir.toPath());
    }

    // --- selection / viewer ------------------------------------------------

    /**
     * Mirrors the item the viewer is showing into the file list, so arrow
     * browsing in the viewer is visible here. The probe that selecting
     * normally triggers is suppressed: the viewer follows up with the
     * metadata it extracted while loading the visual, so the facade thread
     * sees no extra work. No-op when the listing has moved on.
     */
    @Override
    public void mirrorViewerItem(MediaItem item) {
        mirroringSelection = true;
        try {
            selectEntry(item.path());
        } finally {
            mirroringSelection = false;
        }
    }

    /** A crop saved from the Browser-hosted viewer also replaces Mosaic's cache. */
    @Override
    public void mirrorViewerCrop(Path path) {
        mosaic.mirrorViewerCrop(path);
    }

    /**
     * Up/Down forwarded from the viewer: replay them on the file list so its
     * native behavior moves the selection an item at a time (Shift still
     * extends the range), even though the viewer holds focus.
     */
    @Override
    public void forwardNavigationKey(KeyEvent event) {
        Event.fireEvent(listView, event.copyFor(listView, listView));
        // If the move landed on a non-viewable entry (folder, .., or other file),
        // hide the viewer — there's nothing left for it to show.
        DirEntry sel = listView.getSelectionModel().getSelectedItem();
        if (sel == null || !sel.viewable()) {
            viewer.hideForNonViewableTarget();
        }
    }

    /** Selects the visible entry with the given path, if any, scrolling only as
     * needed to keep it on screen. */
    private void selectEntry(Path path) {
        for (int i = 0; i < visibleEntries.size(); i++) {
            if (visibleEntries.get(i).path().equals(path)) {
                listView.getSelectionModel().clearAndSelect(i);
                scrollIntoView(i);
                return;
            }
        }
    }

    /**
     * Scrolls the file list so that {@code index} is visible — but only when it
     * is currently off-screen. Plain {@link ListView#scrollTo(int)} always
     * re-aligns the row to the top of the viewport, which would yank the scroll
     * position when re-selecting an already-visible row (e.g. opening the viewer
     * mirrors the current item straight back here). Leaving a visible row where
     * it sits keeps the user's scroll offset intact while still following
     * arrow-browsing that steps off the visible range.
     */
    private void scrollIntoView(int index) {
        if (listView.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
            IndexedCell<?> first = flow.getFirstVisibleCell();
            IndexedCell<?> last = flow.getLastVisibleCell();
            if (first != null && last != null
                    && index >= first.getIndex() && index <= last.getIndex()) {
                return; // already on screen — don't disturb the scroll offset
            }
        }
        listView.scrollTo(index);
    }

    private void onSelection(DirEntry entry) {
        // Metadata panel (manual by default): reset to the new selection's
        // placeholder and re-arm the opt-in Auto debounce; the read itself is
        // driven by Load / Auto. Mirrors the viewer.
        metadataDebounce.stop();
        metadataPanel.resetToPlaceholder();
        if (entry != null && entry.viewable()
                && metadataVisibleProp.get() && metadataPanel.isAutoLoad()) {
            metadataDebounce.playFromStart();
        }
        if (entry == null) {
            probeSequence++; // void in-flight reads for the cleared selection
            infoPanel.clearFileFacts();
            infoPanel.showMessage("No selection");
            return;
        }
        statusLabel.setText(entry.displayName());
        if (!entry.viewable()) {
            int seq = ++probeSequence; // void in-flight probes for an earlier selection
            updateFileFacts(entry, seq);
            infoPanel.showMessage(switch (entry.type()) {
                case PARENT -> "Parent folder";
                case DIRECTORY -> "Folder";
                // Browsable, so "not viewable media" would be actively
                // misleading — it reads as a dead end.
                case ARCHIVE -> "iso".equals(entry.extension())
                        || "cue".equals(entry.extension())
                        ? "Reading disc image…"
                        : "Reading archive…";
                default -> "Not viewable media";
            });
            if (entry.type() == DirEntry.Type.ARCHIVE) showArchiveInfo(entry, seq);
            return;
        }
        if (mirroringSelection) {
            // Void in-flight probes; the viewer supplies the probe (see
            // setOnItemProbed) — the File section is still read here.
            int seq = ++probeSequence;
            updateFileFacts(entry, seq);
            return;
        }
        infoPanel.showMessage("Probing…");
        int seq = ++probeSequence;
        updateFileFacts(entry, seq);
        service.probe(entry.path()).whenComplete((probe, error) ->
                Platform.runLater(() -> {
                    if (seq != probeSequence) return;
                    if (error != null) {
                        infoPanel.showMessage(rootMessage(error));
                    } else {
                        infoPanel.show(probe);
                    }
                }));
    }

    /**
     * Fills the info panel's File section for {@code entry}: files show name,
     * size and timestamps; folders show name, timestamps, and an Items row only
     * when an earlier listing already supplied the direct-child count. The
     * filesystem stat is asynchronous and independent of the potentially slow
     * native probe. Guarded on {@code probeSequence} like the probe itself, so a
     * superseded selection's facts are dropped.
     */
    private void updateFileFacts(DirEntry entry, int seq) {
        if (entry.type() == DirEntry.Type.PARENT) {
            infoPanel.clearFileFacts();
            return;
        }
        boolean folder = entry.type() == DirEntry.Type.DIRECTORY;
        if (folder) {
            // Name (and a free cached count, if known) can appear immediately;
            // the existing async stat fills in the dates without blocking FX.
            infoPanel.showFileFacts(InfoPanel.folderFactRows(
                    entry.displayName(), service.knownFolderItemCount(entry.path())));
        }
        service.fileFacts(entry.path()).whenComplete((facts, error) ->
                Platform.runLater(() -> {
                    if (seq != probeSequence) return;
                    if (error != null) {
                        if (!folder) infoPanel.clearFileFacts();
                    } else {
                        infoPanel.showFileFacts(folder
                                ? InfoPanel.folderFactRows(
                                        entry.displayName(), facts,
                                        service.knownFolderItemCount(entry.path()))
                                : InfoPanel.fileFactRows(entry.displayName(), facts));
                    }
                }));
    }

    /**
     * Fills the Info panel's detail table for a selected archive: the format
     * and naming scheme in force, plus whatever identity the container carries
     * (an ISO's publisher and mastering date, a ZIP's entry count).
     *
     * <p>Only for the container itself — once inside, entries are ordinary
     * files and the panel behaves exactly as it does in a folder. Reads without
     * mounting and off the FX thread, guarded on {@code probeSequence} like the
     * probe, so arrowing quickly through a folder of images neither stalls nor
     * lands stale rows.</p>
     */
    private void showArchiveInfo(DirEntry entry, int seq) {
        service.archiveInfo(entry.path()).whenComplete((info, error) ->
                Platform.runLater(() -> {
                    if (seq != probeSequence) return;
                    if (error != null) {
                        infoPanel.showMessage(rootMessage(error));
                        return;
                    }
                    var rows = new ArrayList<InfoPanel.Row>();
                    rows.add(new InfoPanel.Row("Format", info.summary()));
                    for (var field : info.fields()) {
                        rows.add(new InfoPanel.Row(field.name(), field.value()));
                    }
                    infoPanel.showDetailRows(rows);
                }));
    }

    /**
     * Starts the on-demand metadata read for the selected item (a manual Load
     * click or the opt-in Auto debounce), mirroring the viewer. A later
     * selection bumps {@code probeSequence}, so a superseded read is dropped.
     */
    private void fireMetadataRead() {
        DirEntry entry = listView.getSelectionModel().getSelectedItem();
        // An archive has metadata worth reading — its volume descriptor — even
        // though it is not viewable media.
        if (entry == null || !(entry.viewable() || entry.type() == DirEntry.Type.ARCHIVE)) {
            metadataPanel.showMessage(entry == null ? "No selection" : "Not viewable media");
            return;
        }
        int gen = probeSequence;
        metadataPanel.showMessage("Reading\u2026");
        service.metadata(entry.path()).whenComplete((md, error) ->
                Platform.runLater(() -> {
                    if (gen != probeSequence) return;
                    if (error != null) {
                        metadataPanel.showFailure(rootMessage(error));
                    } else {
                        metadataPanel.show(md);
                    }
                }));
    }

    /** Enter / double-click: folders navigate, viewable media opens the viewer. */
    private void activateSelected() {
        DirEntry entry = listView.getSelectionModel().getSelectedItem();
        if (entry == null) return;
        switch (entry.type()) {
            case PARENT -> navigateToParent();
            case DIRECTORY -> {
                if (atComputerLocation) navigateAndSync(entry.path());
                else enterFolder(entry.path());
            }
            case ARCHIVE -> openArchive(entry.path());
            // Keep Focus only means something across separate windows (the
            // viewer opens without stealing focus); the single window always
            // switches, since it can't show a second view.
            case MEDIA -> viewer.open(entry.toMediaItem(), viewableItems(),
                    shell.singleWindow() || !keepFocusProp.get(), this);
            case OTHER -> statusLabel.setText("Not viewable: " + entry.displayName());
        }
    }


    /**
     * Descent cap for {@link MediaService#collapseChain}, the same bound
     * {@code ArchivePaths.MAX_DESCENT} uses for the identical shape inside an
     * archive. Nothing sane nests this deep with one child per level, and the
     * cap means a pathological tree costs eight listings, not a walk.
     */
    private static final int MAX_CHAIN_DESCENT = 8;

    /**
     * Enters a folder, landing past any run of levels that hold exactly one
     * subfolder and nothing else — the click-click-click descent.
     *
     * <p>Only ever on the way <em>in</em>. Going up must not collapse, or
     * {@code ..} out of a chain would fall straight back down it and the user
     * could never leave. The descent runs off the FX thread (it is up to
     * {@code MAX_CHAIN_DESCENT} listings) and falls back to the folder itself
     * if anything goes wrong, so a slow or unreadable tree still navigates.</p>
     *
     * <p>Nothing is hidden: the location bar shows where you landed, and
     * {@code ..} walks back up through every level that was skipped.</p>
     */
    private void enterFolder(Path dir) {
        if (!collapseChainsProp.get()) {
            navigateAndSync(dir);
            return;
        }
        service.fileOp(() -> MediaService.collapseChain(dir, MAX_CHAIN_DESCENT))
                .whenComplete((target, error) -> Platform.runLater(() ->
                        navigateAndSync(error != null || target == null ? dir : target)));
    }

    /**
     * Enters an archive: mounts it off the FX thread — reading a large zip's
     * central directory or an image's descriptors is real I/O — then navigates
     * to its root. A container that cannot be opened (truncated, UDF-only,
     * unreadable) says so in the status bar and leaves the listing alone,
     * rather than silently doing nothing.
     */
    private void openArchive(Path archive) {
        statusLabel.setText("Opening " + archive.getFileName() + "…");
        service.fileOp(() -> {
            try {
                // Lands inside a solitary top-level folder, the shape almost
                // every "compress this folder" archive has.
                return ArchivePaths.browseRoot(archive);
            } catch (IOException e) {
                throw new MediaException(e.getMessage(), e);
            }
        }).whenComplete((root, error) -> Platform.runLater(() -> {
            if (error != null) {
                statusLabel.setText("Cannot open " + archive.getFileName() + ": "
                        + rootMessage(error));
                return;
            }
            navigateAndSync(root);
        }));
    }

    /** The currently visible viewable media as a viewer/mosaic navigation list. */
    private List<MediaItem> viewableItems() {
        return visibleEntries.stream()
                .filter(DirEntry::viewable)
                .map(DirEntry::toMediaItem)
                .toList();
    }

    // --- move dialog ---------------------------------------------------------

    /** Cmd+M / menu / context-menu: open the Move dialog for the selection. */
    private void openMove() {
        if (currentAlbum != null) {
            statusLabel.setText("Open an item's folder before moving it");
            return;
        }
        if (atComputerLocation) {
            statusLabel.setText("Choose a folder before moving items");
            return;
        }
        moveController.open();
    }

    /** Platform shortcut / menu / context-menu: move the selection to native trash. */
    private void moveToTrash() {
        if (atComputerLocation) {
            statusLabel.setText("Drive roots cannot be moved to Trash");
            return;
        }
        moveController.moveSelectionToTrash();
    }

    /** Exports payload-preserving _0m copies for the selected files. */
    private void exportZeroMetadataCopies() {
        if (atComputerLocation) {
            statusLabel.setText("Choose files before exporting");
            return;
        }
        metadataStripController.createCopies();
    }

    /** Exports the selected still with its visible edits baked into a clean PNG. */
    private void exportBakedPng() {
        if (atComputerLocation) {
            statusLabel.setText("Choose one still image before exporting");
            return;
        }
        bakedPngExportController.exportCurrent();
    }

    /** Exactly one selected image, or {@code null} for mixed/multiple selections. */
    private Path currentStillSelection() {
        var selected = listView.getSelectionModel().getSelectedItems();
        if (selected.size() != 1) return null;
        DirEntry entry = selected.getFirst();
        return entry != null && entry.type() == DirEntry.Type.MEDIA
                && entry.mediaKind() == MediaKind.IMAGE ? entry.path() : null;
    }

    /**
     * Resolves the move sources from the list selection: every selected entry
     * other than the {@code ..} parent link, with {@code parentExcluded} flagged
     * when {@code ..} was part of the selection (so the dialog never tries to
     * move the parent link). Order follows the listing, not click order.
     */
    private MoveController.Selection currentMoveSelection() {
        if (atComputerLocation) return new MoveController.Selection(List.of(), false);
        boolean parentExcluded = false;
        List<Path> sources = new ArrayList<>();
        for (DirEntry sel : listView.getSelectionModel().getSelectedItems()) {
            if (sel == null) continue;
            if (sel.type() == DirEntry.Type.PARENT) {
                parentExcluded = true;
            } else {
                sources.add(sel.path());
            }
        }
        return new MoveController.Selection(sources, parentExcluded);
    }

    /**
     * The path to focus once {@code moving} leaves the listing: the first entry
     * after the selection that is not moving, else the first before it, else
     * null. Read against the current (pre-move) {@link #visibleEntries}; the
     * cursor is the lowest selected row so the scan starts just past the block.
     */
    private Path nextFocusAfterMove(List<Path> moving) {
        Set<Path> movingSet = new HashSet<>(moving);
        int cursor = visibleEntries.size();
        for (int i : listView.getSelectionModel().getSelectedIndices()) {
            if (i >= 0) cursor = Math.min(cursor, i);
        }
        for (int i = cursor + 1; i < visibleEntries.size(); i++) {
            Path p = visibleEntries.get(i).path();
            if (!movingSet.contains(p)) return p;
        }
        for (int i = cursor - 1; i >= 0; i--) {
            Path p = visibleEntries.get(i).path();
            if (!movingSet.contains(p)) return p;
        }
        return null;
    }

    /**
     * Refresh the listing after a move and land selection on {@code focusPath}.
     * Sets {@link #pendingSelection} so the post-listing hook in {@link #navigate}
     * re-selects it once the async scan returns (the ported "refresh → then
     * focus" sequencing); a null path clears the selection.
     */
    private void refreshAfterMove(Path focusPath) {
        if (currentDir == null) return;
        pendingSelection = focusPath;
        navigate(currentDir);
    }

    /**
     * A move the viewer performed: re-list this window and re-select the focus
     * target, exactly like the window's own move refresh. The viewer mirrors
     * the same focus into the list, so both converge on {@code focusPath}.
     */
    @Override
    public void refreshAfterViewerMove(Path focusPath) {
        refreshAfterMove(focusPath);
    }

    /**
     * A pack selector cell with a small, pack-locked gallery. The icon colours
     * follow the cell label so popup selection and all themes remain legible.
     */
    private static ListCell<IconPack> iconPackCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(IconPack pack, boolean empty) {
                super.updateItem(pack, empty);
                if (empty || pack == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                var label = new Label(pack.label());
                label.setMinWidth(62);
                label.textFillProperty().bind(textFillProperty());
                var samples = new HBox(5);
                for (AppIcon role : List.of(
                        AppIcon.FOLDER, AppIcon.IMAGE, AppIcon.PLAY, AppIcon.PIN)) {
                    var icon = new IconView(pack, role, 16);
                    icon.colorProperty().bind(textFillProperty());
                    samples.getChildren().add(icon);
                }
                var row = new HBox(8, label, samples);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
    }

    // --- Compose-style Settings dialog: row/group/rail primitives --------------
    //
    // Transcribes the shape of kotlin-mosaic's SettingsDialog.kt (Group /
    // SettingRow / SettingsRail there) onto this dialog's existing controls.
    // These are purely presentational: they reparent the same ComboBox /
    // CheckBox / Spinner instances showSettings() already builds for
    // UiStyle.CLASSIC's TabPane, so Save-time reads (control.getValue() /
    // isSelected()) are unaffected by which style built the dialog. JavaFX
    // reparents a Node automatically (removing it from its previous parent),
    // which is exactly why the Compose assembly in showSettings() is built
    // ONLY when that style is active: whichever assembly is built last strips
    // the other's rows bare.

    /**
     * One Compose-style settings row: {@code label} (and optional wrapping
     * {@code hint}) on the left, growing to fill; {@code control} right-aligned
     * in a fixed 230px column, so every control on a page begins at the same x
     * (kotlin's {@code DROPDOWN_WIDTH} column — what makes the form read as
     * one thing). The label dims when {@code control} is disabled, mirroring
     * the CLASSIC dialog's existing disable bindings.
     */
    static HBox composeRow(String label, String hint, Node control) {
        var labelNode = new Label(label);
        labelNode.setWrapText(true);
        var labelBox = new VBox(2, labelNode);
        if (hint != null) {
            var hintLabel = new Label(hint);
            hintLabel.getStyleClass().add("settings-hint");
            hintLabel.setWrapText(true);
            labelBox.getChildren().add(hintLabel);
        }
        HBox.setHgrow(labelBox, Priority.ALWAYS);
        labelNode.opacityProperty().bind(
                Bindings.when(control.disableProperty()).then(0.55).otherwise(1.0));

        var controlBox = new HBox(control);
        controlBox.getStyleClass().add("settings-row-control");
        controlBox.setAlignment(Pos.CENTER_RIGHT);
        controlBox.setMinWidth(230);
        controlBox.setPrefWidth(230);
        controlBox.setMaxWidth(230);

        var row = new HBox(16, labelBox, controlBox);
        row.getStyleClass().add("settings-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(38);
        row.setPadding(new Insets(4, 0, 4, 0));
        return row;
    }

    static HBox composeRow(String label, Node control) {
        return composeRow(label, null, control);
    }

    /**
     * A row built from an existing labelled {@link CheckBox}: the checkbox's
     * own text becomes the row label (kotlin's {@code Checkbox(null, ...)} — an
     * unlabelled control, with the label carried by {@code SettingRow}
     * instead), and the checkbox itself — now textless — sits in the control
     * column. Mutates {@code box}'s text, so only call this for the instance
     * that is actually being displayed; UiStyle.COMPOSE never shows the
     * CLASSIC tab that also parents {@code box}, so this is safe.
     */
    static HBox composeCheckRow(CheckBox box, String hint) {
        String text = box.getText();
        box.setText("");
        return composeRow(text, hint, box);
    }

    static HBox composeCheckRow(CheckBox box) {
        return composeCheckRow(box, null);
    }

    /**
     * A titled group of rows: 13px semibold title, a 6px gap, a hairline
     * divider, another 6px gap, then the rows — kotlin's {@code Group}.
     */
    static VBox composeGroup(String title, Node... rows) {
        var titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-group-title");
        var divider = new Region();
        divider.getStyleClass().add("settings-group-divider");
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);

        var rowsBox = new VBox(0, rows);

        var group = new VBox(6, titleLabel, divider, rowsBox);
        group.setPadding(new Insets(0, 0, 24, 0));
        return group;
    }

    /** A page-rail item: a full-width toggle; selected/hover state is CSS-driven. */
    static ToggleButton composeRailItem(String label, ToggleGroup group) {
        var button = new ToggleButton(label);
        button.getStyleClass().add("settings-rail-item");
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        return button;
    }

    /**
     * Wires a settings rail's {@link ToggleGroup} to swap {@code target}'s
     * content by selection, selecting {@code initial} first. Also guards
     * against every rail item going unselected — a {@link ToggleButton} in a
     * {@link ToggleGroup} can otherwise deselect itself on a second click —
     * by re-selecting the page that was already showing rather than leaving
     * the pane blank.
     */
    static void wireSettingsRail(ToggleGroup group, ToggleButton initial,
            ScrollPane target, Map<ToggleButton, Node> pages) {
        group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                if (oldToggle != null) oldToggle.setSelected(true);
                return;
            }
            target.setContent(pages.get(newToggle));
        });
        initial.setSelected(true);
        target.setContent(pages.get(initial));
    }

    /** Windows only: the "File Associations…" dialog, wired from the File menu. */
    private void showFileAssociations() {
        new FileAssociationsDialog(stage()).showAndWait();
    }

    private void showSettings() {
        // --- General tab: cross-window application settings --------------------
        var backendCombo = new ComboBox<MediaBackend>();
        backendCombo.getItems().setAll(MediaBackend.available());
        backendCombo.setConverter(new StringConverter<>() {
            @Override public String toString(MediaBackend b) {
                return b == null ? "" : b.label();
            }
            @Override public MediaBackend fromString(String s) {
                return null; // non-editable combo; never parses back
            }
        });
        backendCombo.setValue(MediaBackend.fromSettings(settings.mediaBackend()));
        var backendRow = new HBox(8, new Label("Media engine:"), backendCombo);
        backendRow.setAlignment(Pos.CENTER_LEFT);

        // Playback decode policy for the bundled-FFmpeg backends (HwDecode):
        // applies to new playback sessions immediately, meaningless (and so
        // disabled) for the other backends.
        var decodeCombo = new ComboBox<HwDecode.Policy>();
        decodeCombo.getItems().setAll(HwDecode.Policy.values());
        decodeCombo.setConverter(new StringConverter<>() {
            @Override public String toString(HwDecode.Policy p) {
                return p == null ? "" : switch (p) {
                    case AUTO -> "Auto (hardware when available)";
                    case SOFTWARE -> "Software";
                    case HARDWARE -> "Hardware (required — fails loudly)";
                };
            }
            @Override public HwDecode.Policy fromString(String s) {
                return null; // non-editable combo; never parses back
            }
        });
        decodeCombo.setValue(switch (settings.decodeDevice().toLowerCase(java.util.Locale.ROOT)) {
            case "software" -> HwDecode.Policy.SOFTWARE;
            case "hardware" -> HwDecode.Policy.HARDWARE;
            default -> HwDecode.Policy.AUTO;
        });
        decodeCombo.disableProperty().bind(Bindings.createBooleanBinding(
                () -> backendCombo.getValue() == null
                        || !backendCombo.getValue().settingsValue().contains("ffmpeg-ffm"),
                backendCombo.valueProperty()));
        var decodeRow = new HBox(8, new Label("Video playback decode:"), decodeCombo);
        decodeRow.setAlignment(Pos.CENTER_LEFT);

        // UI style preset (above Theme: it applies over Theme, Icon pack and
        // Mosaic tile set, see the listener wired below once tileSetCombo
        // exists). Its own value is read once at startup for the mosaic
        // toolbar variant; setting it here only takes effect on Save.
        var uiStyleCombo = new ComboBox<UiStyle>();
        uiStyleCombo.getItems().setAll(UiStyle.values());
        uiStyleCombo.setValue(settings.uiStyle());
        uiStyleCombo.setAccessibleText("UI style");
        var uiStyleRow = new HBox(8, new Label("UI style:"), uiStyleCombo);
        uiStyleRow.setAlignment(Pos.CENTER_LEFT);
        var uiStyleHint = new Label("Compose uses icon-first Browser and Mosaic toolbars; Classic "
                + "keeps their text-labelled versions. Theme, icon pack and tile set are chosen "
                + "independently below. Takes effect on the next start.");
        uiStyleHint.getStyleClass().add("settings-hint");
        uiStyleHint.setWrapText(true);
        uiStyleHint.setMaxWidth(420);

        var themeCombo = new ComboBox<Theme>();
        themeCombo.getItems().setAll(Theme.values());
        themeCombo.setValue(settings.theme());
        // Live preview: apply across all open windows (and this dialog) as the
        // selection changes; reverted below if the user cancels.
        themeCombo.valueProperty().addListener((obs, old, val) ->
                ThemeManager.get().setCurrent(val));
        var themeRow = new HBox(8, new Label("Theme:"), themeCombo);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        var iconPackCombo = new ComboBox<IconPack>();
        iconPackCombo.getItems().setAll(IconPack.values());
        iconPackCombo.setValue(settings.iconPack());
        iconPackCombo.setCellFactory(list -> iconPackCell());
        iconPackCombo.setButtonCell(iconPackCell());
        iconPackCombo.setAccessibleText("Icon pack");
        iconPackCombo.valueProperty().addListener((obs, old, val) ->
                IconPackManager.get().setCurrent(val));
        var iconPackRow = new HBox(8, new Label("Icon pack:"), iconPackCombo);
        iconPackRow.setAlignment(Pos.CENTER_LEFT);

        var buttonBordersBox = new CheckBox("Button borders");
        buttonBordersBox.setSelected(settings.buttonBorders());
        // Live preview like the theme combo; reverted below if the user cancels.
        buttonBordersBox.selectedProperty().addListener((obs, old, val) ->
                ThemeManager.get().setBorderlessButtons(!val));

        var decorationsCombo = new ComboBox<WindowDecorations>();
        decorationsCombo.getItems().setAll(WindowDecorations.values());
        decorationsCombo.setValue(settings.windowDecorations());
        var decorationsRow = new HBox(8,
                new Label("Windows decorations:"), decorationsCombo);
        decorationsRow.setAlignment(Pos.CENTER_LEFT);
        var decorationsHint = new Label(
                "Automatic uses JavaFX themed chrome when supported; Themed requests it; "
                + "Native keeps the Windows title bar. Undecorated keeps the manual "
                + "cross-platform chrome.");
        decorationsHint.getStyleClass().add("settings-hint");
        decorationsHint.setWrapText(true);
        var resizeBox = new CheckBox("Resizable chromeless windows (drag the edges)");
        resizeBox.setSelected(settings.undecoratedResizable());
        resizeBox.disableProperty().bind(decorationsCombo.valueProperty()
                .isNotEqualTo(WindowDecorations.UNDECORATED));
        var overscanBox = new CheckBox("Overscan maximize (hide window edges on F)");
        overscanBox.setSelected(settings.maximizeOverscan());
        overscanBox.disableProperty().bind(decorationsCombo.valueProperty()
                .isNotEqualTo(WindowDecorations.UNDECORATED));
        var inWindowMenuBox = new CheckBox("In-window menu bar (instead of the macOS menu bar)");
        inWindowMenuBox.setSelected(settings.inWindowMenu());
        var actionLogBox = new CheckBox(
                "Show the action log (browser, mosaic and viewer)");
        // Seed from the LIVE visibility, not the persisted startup default:
        // Show ▸ Action Log (mod1+J) toggles only the live prop, so seeding
        // from settings would let an untouched checkbox hide a panel the user
        // just showed the moment Settings is saved.
        actionLogBox.setSelected(actionLogVisibleProp.get());
        var actionLogFileBox = new CheckBox(
                "Write the action log to disk (append-only JSONL, applies immediately)");
        actionLogFileBox.setSelected(settings.actionLogFileEnabled());
        actionLogFileBox.setTooltip(new Tooltip(
                "Appends every completed move/rename to "
                + "~/.media-browser/action-log.jsonl, one JSON object per line. "
                + "The file is never trimmed; at startup the panel is seeded "
                + "with its last few entries."));
        var extensionFixBox = new CheckBox(
                "Fix extensions of sniffed files on open (applies immediately)");
        extensionFixBox.setSelected(settings.extensionFixEnabled());
        extensionFixBox.setTooltip(new Tooltip(
                "A file with no classifying extension that opens without error "
                + "in the viewer is renamed in place to its content's canonical "
                + "extension (mystery → mystery.jpg). Every rename lands in the "
                + "action log as an Extension fix entry; nothing is renamed "
                + "while this is off."));
        var windowModeCombo = new ComboBox<WindowMode>();
        windowModeCombo.getItems().setAll(WindowMode.values());
        windowModeCombo.setValue(settings.windowMode());
        var windowModeRow = new HBox(8, new Label("Window mode:"), windowModeCombo);
        windowModeRow.setAlignment(Pos.CENTER_LEFT);
        var startupLayoutCombo = new ComboBox<StartupLayout>();
        startupLayoutCombo.getItems().setAll(StartupLayout.values());
        startupLayoutCombo.setValue(settings.startupLayout());
        var startupLayoutRow = new HBox(8,
                new Label("Startup window layout:"), startupLayoutCombo);
        startupLayoutRow.setAlignment(Pos.CENTER_LEFT);
        // Loading indicator: shared by the viewer viewport and the mosaic grid,
        // hence a General setting rather than a per-window one.
        var loadingIndicatorCombo = new ComboBox<LoadingIndicator>();
        loadingIndicatorCombo.getItems().setAll(LoadingIndicator.values());
        loadingIndicatorCombo.setValue(settings.viewerLoadingIndicator());
        var loadingIndicatorRow = new HBox(8,
                new Label("Loading indicator (viewer & mosaic):"), loadingIndicatorCombo);
        loadingIndicatorRow.setAlignment(Pos.CENTER_LEFT);
        // Grace period before the indicator appears: a decode landing within it
        // never flashes one up. Pointless when the indicator is None, so disable
        // the spinner there (mirrors the mosaic animation cycle-duration spinner).
        var loadingDelaySpinner = new Spinner<Integer>(0, 5000,
                settings.viewerLoadingIndicatorDelayMs(), 50);
        loadingDelaySpinner.setEditable(true);
        loadingDelaySpinner.setPrefWidth(100);
        loadingDelaySpinner.disableProperty().bind(
                loadingIndicatorCombo.valueProperty().isEqualTo(LoadingIndicator.NONE));
        var loadingDelayRow = new HBox(8,
                new Label("Show after (ms, 0 = at once):"), loadingDelaySpinner);
        loadingDelayRow.setAlignment(Pos.CENTER_LEFT);
        var hint = new Label("Applies after restarting the application.");
        hint.getStyleClass().add("settings-hint");
        var generalContent = new VBox(8, uiStyleRow, uiStyleHint,
                themeRow, iconPackRow, buttonBordersBox, backendRow, decodeRow,
                actionLogBox, actionLogFileBox, extensionFixBox,
                new Separator(), loadingIndicatorRow, loadingDelayRow,
                new Separator(),
                decorationsRow, decorationsHint, resizeBox, overscanBox,
                inWindowMenuBox, windowModeRow,
                startupLayoutRow, hint);
        generalContent.setPadding(new Insets(12));

        // --- Browser (main window) tab: chrome visibility ---------------------
        var browserMenuBox = new CheckBox("Show the menu bar");
        browserMenuBox.setSelected(settings.browserMenuBarVisible());
        var browserToolbarBox = new CheckBox("Show the toolbar");
        browserToolbarBox.setSelected(settings.browserToolbarVisible());
        var browserStatusBox = new CheckBox("Show the status bar");
        browserStatusBox.setSelected(settings.browserStatusBarVisible());
        var browserNavTreeBox = new CheckBox("Show the navigation tree");
        browserNavTreeBox.setSelected(settings.browserNavTreeVisible());
        var browserHint = new Label("Startup defaults for the file-browser window; "
                + "also applied immediately.");
        browserHint.getStyleClass().add("settings-hint");
        var browserContent = new VBox(8, browserMenuBox, browserToolbarBox,
                browserStatusBox, browserNavTreeBox,
                new Separator(), browserHint);
        browserContent.setPadding(new Insets(12));

        // --- Viewer tab: chrome visibility + viewport drag --------------------
        var viewerMenuBox = new CheckBox("Show the menu bar");
        viewerMenuBox.setSelected(settings.viewerMenuBarVisible());
        var viewerToolbarBox = new CheckBox("Show the toolbar");
        viewerToolbarBox.setSelected(settings.viewerToolbarVisible());
        var viewerStatusBox = new CheckBox("Show the status bar");
        viewerStatusBox.setSelected(settings.viewerStatusBarVisible());
        var viewerDragBox = new CheckBox("Drag the viewer window from its viewport (the image area)");
        viewerDragBox.setSelected(settings.viewerDragViewport());
        var viewerHint = new Label("Startup defaults for the viewer window; "
                + "also applied immediately.");
        viewerHint.getStyleClass().add("settings-hint");
        var viewerContent = new VBox(8, viewerMenuBox, viewerToolbarBox,
                viewerStatusBox, new Separator(), viewerDragBox, viewerHint);
        viewerContent.setPadding(new Insets(12));

        // --- Mosaic tab: chrome visibility + grid layout/appearance -----------
        var mosaicMenuBox = new CheckBox("Show the menu bar");
        mosaicMenuBox.setSelected(settings.mosaicMenuBarVisible());
        var mosaicToolbarBox = new CheckBox("Show the toolbar");
        mosaicToolbarBox.setSelected(settings.mosaicToolbarVisible());
        var mosaicStatusBox = new CheckBox("Show the status bar");
        mosaicStatusBox.setSelected(settings.mosaicStatusBarVisible());
        var mosaicLocationBox = new CheckBox("Show the location bar");
        mosaicLocationBox.setSelected(settings.mosaicLocationBarVisible());

        var layoutTitle = new Label("Grid layout & appearance");
        layoutTitle.setStyle("-fx-font-weight: bold;");
        var tileSetCombo = new ComboBox<MosaicTileSet>();
        tileSetCombo.getItems().setAll(MosaicTileSets.values());
        tileSetCombo.setConverter(new StringConverter<>() {
            @Override public String toString(MosaicTileSet set) {
                return set == null ? "" : set.label();
            }
            @Override public MosaicTileSet fromString(String value) {
                return null;
            }
        });
        tileSetCombo.setValue(MosaicTileSets.resolve(settings.mosaicTileSetId()));
        tileSetCombo.setAccessibleText("Mosaic tile set");
        tileSetCombo.setTooltip(new Tooltip(
                "Generated artwork for folders and files without thumbnails. "
                + "Current preserves the original Mosaic rendering."));
        tileSetCombo.valueProperty().addListener((obs, old, value) -> {
            if (value != null) mosaic.tileSetProperty().set(value);
        });
        var tileSetRow = new HBox(8, new Label("Tile set:"), tileSetCombo);
        tileSetRow.setAlignment(Pos.CENTER_LEFT);
        var tileSizeSpinner = new Spinner<Integer>(64, 512, settings.mosaicTileSize(), 8);
        tileSizeSpinner.setEditable(true);
        tileSizeSpinner.setPrefWidth(100);
        var tileSizeRow = new HBox(8, new Label("Tile size (px):"), tileSizeSpinner);
        tileSizeRow.setAlignment(Pos.CENTER_LEFT);
        var marginSpinner = new Spinner<Integer>(0, 128, settings.mosaicMargin(), 1);
        marginSpinner.setEditable(true);
        marginSpinner.setPrefWidth(100);
        var marginRow = new HBox(8, new Label("Tile margin (px):"), marginSpinner);
        marginRow.setAlignment(Pos.CENTER_LEFT);
        var borderWidthSpinner = new Spinner<Integer>(0, 32, settings.mosaicBorderWidth(), 1);
        borderWidthSpinner.setEditable(true);
        borderWidthSpinner.setPrefWidth(100);
        var borderWidthRow = new HBox(8, new Label("Tile border width (px):"), borderWidthSpinner);
        borderWidthRow.setAlignment(Pos.CENTER_LEFT);
        Color initialBorder;
        try {
            initialBorder = Color.web(settings.mosaicBorderColor());
        } catch (RuntimeException ex) {
            initialBorder = Color.web("#3c3c3c");
        }
        var borderColorPicker = new ColorPicker(initialBorder);
        var borderColorRow = new HBox(8, new Label("Tile border colour:"), borderColorPicker);
        borderColorRow.setAlignment(Pos.CENTER_LEFT);
        var folderGridSpinner = new Spinner<Integer>(0, 4, settings.mosaicFolderPreviewGrid(), 1);
        folderGridSpinner.setEditable(true);
        folderGridSpinner.setPrefWidth(100);
        var folderGridRow = new HBox(8,
                new Label("Folder preview grid (N\u00d7N, 0 = folder glyph):"), folderGridSpinner);
        folderGridRow.setAlignment(Pos.CENTER_LEFT);
        var folderGlyphCombo = new ComboBox<MosaicFolderGlyph>();
        folderGlyphCombo.getItems().setAll(MosaicFolderGlyph.values());
        folderGlyphCombo.setValue(settings.mosaicFolderGlyph());
        var folderGlyphRow = new HBox(8, new Label("Folder glyph:"), folderGlyphCombo);
        folderGlyphRow.setAlignment(Pos.CENTER_LEFT);
        folderGlyphCombo.disableProperty().bind(Bindings.createBooleanBinding(
                () -> tileSetCombo.getValue() != null
                        && !tileSetCombo.getValue().usesLegacyFolderGlyph(),
                tileSetCombo.valueProperty()));
        var thumbnailsBox = new CheckBox("Show thumbnails in the mosaic");
        thumbnailsBox.setSelected(settings.mosaicThumbnailsVisible());
        var fillBox = new CheckBox("Crop tiles to fill (squares, no letterbox)");
        fillBox.setSelected(settings.mosaicFillTiles());
        var seamlessBox = new CheckBox("Seamless (no tile margins or borders)");
        seamlessBox.setSelected(settings.mosaicSeamless());
        var wontOpenReticuleBox = new CheckBox(
                "Mark files this media engine won't open");
        wontOpenReticuleBox.setSelected(settings.mosaicWontOpenReticule());
        var reticuleScopeCombo = new ComboBox<MosaicReticuleScope>();
        reticuleScopeCombo.getItems().setAll(MosaicReticuleScope.values());
        reticuleScopeCombo.setValue(settings.mosaicReticuleScope());
        var reticuleScopeRow = new HBox(8,
                new Label("Reticule coverage:"), reticuleScopeCombo);
        reticuleScopeRow.setAlignment(Pos.CENTER_LEFT);

        var renditionTitle = new Label("Preview renditions");
        renditionTitle.setStyle("-fx-font-weight: bold;");
        var maxEdgeSpinner = new Spinner<Integer>(32, 2048, settings.thumbnailMaxEdge(), 10);
        maxEdgeSpinner.setEditable(true);
        maxEdgeSpinner.setPrefWidth(100);
        var maxEdgeRow = new HBox(8, new Label("Preview max edge (px):"), maxEdgeSpinner);
        maxEdgeRow.setAlignment(Pos.CENTER_LEFT);
        var budgetSpinner = new Spinner<Integer>(16, 8192, settings.thumbnailMemoryBudgetMb(), 32);
        budgetSpinner.setEditable(true);
        budgetSpinner.setPrefWidth(100);
        var budgetRow = new HBox(8, new Label("Preview cache budget (MB):"), budgetSpinner);
        budgetRow.setAlignment(Pos.CENTER_LEFT);

        var behaviourTitle = new Label("Selection & behaviour");
        behaviourTitle.setStyle("-fx-font-weight: bold;");
        var autoOpenBox = new CheckBox(
                "Auto-open the selected media in the viewer while browsing the mosaic");
        autoOpenBox.setSelected(settings.mosaicAutoOpen());
        var mosaicDragBox = new CheckBox(
                "Drag the mosaic window from the grid's empty background");
        mosaicDragBox.setSelected(settings.mosaicDragBackground());
        var animationCombo = new ComboBox<MosaicSelectionAnimation>();
        animationCombo.getItems().setAll(MosaicSelectionAnimation.values());
        animationCombo.setValue(settings.mosaicSelectionAnimation());
        var animationRow = new HBox(8,
                new Label("Selected-tile animation:"), animationCombo);
        animationRow.setAlignment(Pos.CENTER_LEFT);
        var animationPeriodSpinner = new Spinner<Integer>(200, 10000, settings.mosaicPulsePeriodMs(), 100);
        animationPeriodSpinner.setEditable(true);
        animationPeriodSpinner.setPrefWidth(100);
        animationPeriodSpinner.disableProperty().bind(
                animationCombo.valueProperty().isEqualTo(MosaicSelectionAnimation.NONE));
        var animationPeriodRow = new HBox(8, new Label("Animation cycle duration (ms, lower = faster):"),
                animationPeriodSpinner);
        animationPeriodRow.setAlignment(Pos.CENTER_LEFT);
        var screensaverBox = new CheckBox(
                "Start the Matrix screensaver automatically while Mosaic is idle");
        screensaverBox.setSelected(settings.mosaicScreensaverEnabled());
        var screensaverDelaySpinner = new Spinner<Integer>(1, 120,
                settings.mosaicScreensaverDelayMinutes(), 1);
        screensaverDelaySpinner.setEditable(true);
        screensaverDelaySpinner.setPrefWidth(100);
        screensaverDelaySpinner.disableProperty().bind(screensaverBox.selectedProperty().not());
        var screensaverDelayRow = new HBox(8,
                new Label("Screensaver idle delay (minutes):"), screensaverDelaySpinner);
        screensaverDelayRow.setAlignment(Pos.CENTER_LEFT);
        var mosaicHint = new Label("Layout, appearance, selection and behaviour apply "
                + "immediately; the preview max edge applies to newly generated "
                + "previews and the cache budget applies after restart.");
        mosaicHint.getStyleClass().add("settings-hint");
        mosaicHint.setWrapText(true);
        mosaicHint.setMaxWidth(420);

        var mosaicContent = new VBox(8, mosaicMenuBox, mosaicToolbarBox, mosaicStatusBox,
                mosaicLocationBox,
                new Separator(), layoutTitle, tileSetRow, tileSizeRow, marginRow, borderWidthRow,
                borderColorRow, folderGridRow, folderGlyphRow, thumbnailsBox, fillBox,
                seamlessBox, wontOpenReticuleBox, reticuleScopeRow,
                new Separator(), renditionTitle, maxEdgeRow, budgetRow,
                new Separator(), behaviourTitle, autoOpenBox, mosaicDragBox,
                animationRow, animationPeriodRow, screensaverBox,
                screensaverDelayRow, mosaicHint);
        mosaicContent.setPadding(new Insets(12));
        var mosaicScroll = new ScrollPane(mosaicContent);
        mosaicScroll.setFitToWidth(true);
        mosaicScroll.setPrefViewportHeight(460);

        // --- Keys tab: the logical menu-accelerator modifier mapping -----------
        var mod1Combo = modifierChoiceCombo(settings.keysModifier1());
        var mod1Row = new HBox(8, new Label("Modifier 1 (primary shortcut):"), mod1Combo);
        mod1Row.setAlignment(Pos.CENTER_LEFT);
        var mod2Combo = modifierChoiceCombo(settings.keysModifier2());
        var mod2Row = new HBox(8, new Label("Modifier 2 (secondary):"), mod2Combo);
        mod2Row.setAlignment(Pos.CENTER_LEFT);
        var keysHint = new Label(
                "Menu accelerators are built from two logical modifiers.\n"
                + "Auto: Modifier 1 = Command on macOS / Control elsewhere; "
                + "Modifier 2 = Option on macOS / Alt elsewhere.\n"
                + "Pick Control, Command/Meta or Alt/Option to override on every "
                + "platform (e.g. map Modifier 1 to Command/Meta on Windows).\n"
                + "Applies after restarting the application.");
        keysHint.getStyleClass().add("settings-hint");
        var keysContent = new VBox(8, mod1Row, mod2Row, new Separator(), keysHint);
        keysContent.setPadding(new Insets(12));

        var generalTab = new Tab("General", generalContent);
        generalTab.setClosable(false);
        var browserTab = new Tab("Browser", browserContent);
        browserTab.setClosable(false);
        var viewerTab = new Tab("Viewer", viewerContent);
        viewerTab.setClosable(false);
        var mosaicTab = new Tab("Mosaic", mosaicScroll);
        mosaicTab.setClosable(false);
        var keysTab = new Tab("Keys", keysContent);
        keysTab.setClosable(false);
        var tabs = new TabPane(generalTab, browserTab, viewerTab, mosaicTab, keysTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // --- UiStyle.COMPOSE: left-rail pages over the same controls above ------
        // Built ONLY when the Compose style is active: the row primitives
        // reparent the very control instances the CLASSIC rows above hold, so
        // building this assembly unconditionally would strip the TabPane's
        // rows bare. Whichever assembly is built last owns the controls;
        // Save-time reads (control.getValue()/isSelected()) work either way.
        boolean compose = settings.uiStyle() == UiStyle.COMPOSE;
        Node settingsContent = tabs;
        if (compose) {
            var generalPageCompose = new VBox(0,
                    composeGroup("Appearance & engine",
                            composeRow("UI style", "Compose uses icon-first Browser and Mosaic "
                                    + "toolbars; Classic keeps their text-labelled versions. "
                                    + "Takes effect on the next start.", uiStyleCombo),
                            composeRow("Theme", "Applies to every window immediately.", themeCombo),
                            composeRow("Icon pack", iconPackCombo),
                            composeCheckRow(buttonBordersBox, "Off strips button, toggle and "
                                    + "menu-button outlines on every theme — the Compose "
                                    + "ghost-button look."),
                            composeRow("Media engine", backendCombo),
                            composeRow("Video playback decode", decodeCombo),
                            composeCheckRow(actionLogBox),
                            composeCheckRow(actionLogFileBox, "Appends every completed move/rename "
                                    + "to ~/.media-browser/action-log.jsonl, one JSON object per line."),
                            composeCheckRow(extensionFixBox, "Renames a sniffed file with no "
                                    + "classifying extension to its content's canonical extension "
                                    + "on open.")),
                    composeGroup("Loading indicator (viewer & mosaic)",
                            composeRow("Indicator", loadingIndicatorCombo),
                            composeRow("Show after (ms)", "0 = at once.", loadingDelaySpinner)),
                    composeGroup("Window & startup",
                            composeRow("Window decorations", "Automatic uses JavaFX themed chrome "
                                    + "when supported; Themed requests it; Native keeps the Windows "
                                    + "title bar. Undecorated keeps the manual cross-platform chrome.",
                                    decorationsCombo),
                            composeCheckRow(resizeBox),
                            composeCheckRow(overscanBox),
                            composeCheckRow(inWindowMenuBox),
                            composeRow("Window mode", windowModeCombo),
                            composeRow("Startup window layout", "Applies after restarting the "
                                    + "application.", startupLayoutCombo)));
            generalPageCompose.setPadding(new Insets(12, 16, 12, 16));

            var browserPageCompose = new VBox(0,
                    composeGroup("Window chrome",
                            composeCheckRow(browserMenuBox),
                            composeCheckRow(browserToolbarBox),
                            composeCheckRow(browserStatusBox),
                            composeCheckRow(browserNavTreeBox, "Startup defaults for the "
                                    + "file-browser window; also applied immediately.")));
            browserPageCompose.setPadding(new Insets(12, 16, 12, 16));

            var viewerPageCompose = new VBox(0,
                    composeGroup("Window chrome",
                            composeCheckRow(viewerMenuBox),
                            composeCheckRow(viewerToolbarBox),
                            composeCheckRow(viewerStatusBox, "Startup defaults for the viewer "
                                    + "window; also applied immediately.")),
                    composeGroup("Viewport",
                            composeCheckRow(viewerDragBox)));
            viewerPageCompose.setPadding(new Insets(12, 16, 12, 16));

            var mosaicPageCompose = new VBox(0,
                    composeGroup("Window chrome",
                            composeCheckRow(mosaicMenuBox),
                            composeCheckRow(mosaicToolbarBox),
                            composeCheckRow(mosaicStatusBox),
                            composeCheckRow(mosaicLocationBox)),
                    composeGroup("Grid layout & appearance",
                            composeRow("Tile set", "Generated artwork for folders and files "
                                    + "without thumbnails. Current preserves the original Mosaic "
                                    + "rendering.", tileSetCombo),
                            composeRow("Tile size (px)", tileSizeSpinner),
                            composeRow("Tile margin (px)", marginSpinner),
                            composeRow("Tile border width (px)", borderWidthSpinner),
                            composeRow("Tile border colour", borderColorPicker),
                            composeRow("Folder preview grid", "N×N collage, 0 = folder glyph.",
                                    folderGridSpinner),
                            composeRow("Folder glyph", folderGlyphCombo),
                            composeCheckRow(thumbnailsBox),
                            composeCheckRow(fillBox),
                            composeCheckRow(seamlessBox),
                            composeCheckRow(wontOpenReticuleBox),
                            composeRow("Reticule coverage", "Applies to both dead-end folders "
                                    + "and files that won't open.", reticuleScopeCombo)),
                    composeGroup("Preview renditions",
                            composeRow("Preview max edge (px)", maxEdgeSpinner),
                            composeRow("Preview cache budget (MB)", budgetSpinner)),
                    composeGroup("Selection & behaviour",
                            composeCheckRow(autoOpenBox),
                            composeCheckRow(mosaicDragBox),
                            composeRow("Selected-tile animation", animationCombo),
                            composeRow("Animation cycle duration (ms)", "Lower = faster.",
                                    animationPeriodSpinner),
                            composeCheckRow(screensaverBox),
                            composeRow("Screensaver idle delay (min)", screensaverDelaySpinner)));
            mosaicPageCompose.setPadding(new Insets(12, 16, 12, 16));

            var keysPageCompose = new VBox(0,
                    composeGroup("Shortcuts",
                            composeRow("Modifier 1 (primary)", mod1Combo),
                            composeRow("Modifier 2 (secondary)", "Auto: Modifier 1 = Command on "
                                    + "macOS / Control elsewhere; Modifier 2 = Option on macOS / "
                                    + "Alt elsewhere. Applies after restarting the application.",
                                    mod2Combo)));
            keysPageCompose.setPadding(new Insets(12, 16, 12, 16));

            var railGroup = new ToggleGroup();
            var generalRailItem = composeRailItem("General", railGroup);
            var browserRailItem = composeRailItem("Browser", railGroup);
            var viewerRailItem = composeRailItem("Viewer", railGroup);
            var mosaicRailItem = composeRailItem("Mosaic", railGroup);
            var keysRailItem = composeRailItem("Keys", railGroup);
            var rail = new VBox(generalRailItem, browserRailItem, viewerRailItem,
                    mosaicRailItem, keysRailItem);
            rail.getStyleClass().add("settings-rail");

            var railDivider = new Region();
            railDivider.getStyleClass().add("settings-rail-divider");
            railDivider.setMinWidth(1);
            railDivider.setPrefWidth(1);
            railDivider.setMaxWidth(1);

            Map<ToggleButton, Node> composePages = new HashMap<>();
            composePages.put(generalRailItem, generalPageCompose);
            composePages.put(browserRailItem, browserPageCompose);
            composePages.put(viewerRailItem, viewerPageCompose);
            composePages.put(mosaicRailItem, mosaicPageCompose);
            composePages.put(keysRailItem, keysPageCompose);

            var composeScroll = new ScrollPane();
            composeScroll.setFitToWidth(true);
            HBox.setHgrow(composeScroll, Priority.ALWAYS);
            wireSettingsRail(railGroup, generalRailItem, composeScroll, composePages);

            var composeBody = new HBox(rail, railDivider, composeScroll);
            VBox.setVgrow(composeBody, Priority.ALWAYS);

            // A dialog-internal title bar, matching kotlin ModalDialog's ChromeBar
            // (13px semibold title). The dialog's own OS-drawn title (dialog.
            // setTitle below) still carries "Settings" for the window/taskbar.
            var composeTitle = new Label("Settings");
            composeTitle.getStyleClass().add("settings-dialog-title");
            var composeTitleBar = new HBox(composeTitle);
            composeTitleBar.getStyleClass().add("settings-titlebar");
            composeTitleBar.setAlignment(Pos.CENTER_LEFT);

            var footerPath = new Label(settings.persistencePath().toString());
            footerPath.getStyleClass().add("settings-footer-path");
            var composeFooter = new HBox(footerPath);
            composeFooter.getStyleClass().add("settings-footer");
            composeFooter.setAlignment(Pos.CENTER_LEFT);

            var composeRoot = new VBox(composeTitleBar, composeBody, composeFooter);
            composeRoot.setPrefWidth(780);
            composeRoot.setPrefHeight(580);
            settingsContent = composeRoot;
        }

        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Settings");
        dialog.initOwner(stage());
        dialog.getDialogPane().setContent(settingsContent);
        // Theme the dialog itself so the live preview is visible while choosing.
        ThemeManager.get().register(dialog.getDialogPane());
        // register(Parent) only manages the theme overlay; the dialog pane is
        // not a Scene, so it never gets window-chrome.css's cross-theme
        // constants (e.g. .settings-hint) the way SingleWindowShell/
        // MultiWindowShell's scenes do. Add it directly, after the theme sheet,
        // matching the same ordering.
        var chromeCss = MainWindow.class.getResource("window-chrome.css");
        if (chromeCss != null) dialog.getDialogPane().getStylesheets().add(chromeCss.toExternalForm());
        var save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        Theme themeBefore = settings.theme();
        IconPack iconPackBefore = settings.iconPack();
        MosaicTileSet tileSetBefore = mosaic.tileSetProperty().get();
        boolean buttonBordersBefore = settings.buttonBorders();
        if (dialog.showAndWait().filter(b -> b == save).isEmpty()) {
            // Cancelled: revert all live appearance previews to their prior values.
            ThemeManager.get().setCurrent(themeBefore);
            IconPackManager.get().setCurrent(iconPackBefore);
            ThemeManager.get().setBorderlessButtons(!buttonBordersBefore);
            mosaic.tileSetProperty().set(tileSetBefore);
            return;
        }

        // --- General -----------------------------------------------------------
        // Read once at startup by the mosaic (see MosaicWindow's constructor);
        // this Save is the only effect until the next start.
        settings.setUiStyle(uiStyleCombo.getValue());
        settings.setTheme(themeCombo.getValue());
        ThemeManager.get().setCurrent(themeCombo.getValue());
        settings.setIconPack(iconPackCombo.getValue());
        IconPackManager.get().setCurrent(iconPackCombo.getValue());
        settings.setButtonBorders(buttonBordersBox.isSelected());
        ThemeManager.get().setBorderlessButtons(!buttonBordersBox.isSelected());
        settings.setMediaBackend(backendCombo.getValue().settingsValue());
        // Persist + apply live: the next playback session honours the policy.
        String decodeDevice = decodeCombo.getValue().name().toLowerCase(java.util.Locale.ROOT);
        settings.setDecodeDevice(decodeDevice);
        HwDecode.configure(decodeDevice);
        settings.setWindowDecorations(decorationsCombo.getValue());
        settings.setUndecoratedResizable(resizeBox.isSelected());
        settings.setMaximizeOverscan(overscanBox.isSelected());
        settings.setInWindowMenu(inWindowMenuBox.isSelected());
        settings.setWindowMode(windowModeCombo.getValue());
        settings.setStartupLayout(startupLayoutCombo.getValue());
        settings.setActionLogFileEnabled(actionLogFileBox.isSelected());
        // Applies immediately: the viewer re-reads this per open. The revision
        // bump keeps every action-log panel's Extension fix checkbox in step.
        settings.setExtensionFixEnabled(extensionFixBox.isSelected());
        ActionLog.get().touchMoveTargets();
        // One flag for all three views: the mosaic's and viewer's panels are
        // bound bidirectionally to this prop, so one set reaches them all.
        settings.setActionLogVisible(actionLogBox.isSelected());
        actionLogVisibleProp.set(actionLogBox.isSelected());
        // Loading indicator: push live into the viewer; its property listener
        // persists the choice (the same value the menu/toolbar picker edits) and
        // the mosaic reads it from settings on each load. The delay gate has no
        // live property — both windows read it from settings — so just store it.
        viewer.loadingIndicatorProperty().set(loadingIndicatorCombo.getValue());
        settings.setViewerLoadingIndicatorDelayMs(loadingDelaySpinner.getValue());

        // --- Browser: persist + drive the shared props this window binds to ----
        settings.setBrowserMenuBarVisible(browserMenuBox.isSelected());
        settings.setBrowserToolbarVisible(browserToolbarBox.isSelected());
        settings.setBrowserStatusBarVisible(browserStatusBox.isSelected());
        settings.setBrowserNavTreeVisible(browserNavTreeBox.isSelected());
        menuBarVisibleProp.set(browserMenuBox.isSelected());
        toolbarVisibleProp.set(browserToolbarBox.isSelected());
        statusVisibleProp.set(browserStatusBox.isSelected());
        treeVisibleProp.set(browserNavTreeBox.isSelected());

        // --- Viewer: persist + push live via the viewer's exposed properties ---
        // Viewport drag applies live: the viewer's drag handler reads this each drag.
        settings.setViewerDragViewport(viewerDragBox.isSelected());
        settings.setViewerMenuBarVisible(viewerMenuBox.isSelected());
        settings.setViewerToolbarVisible(viewerToolbarBox.isSelected());
        settings.setViewerStatusBarVisible(viewerStatusBox.isSelected());
        viewer.menuBarVisibleProperty().set(viewerMenuBox.isSelected());
        viewer.toolbarVisibleProperty().set(viewerToolbarBox.isSelected());
        viewer.statusBarVisibleProperty().set(viewerStatusBox.isSelected());

        // --- Mosaic: persist, push chrome live, then apply layout/appearance ---
        settings.setMosaicMenuBarVisible(mosaicMenuBox.isSelected());
        settings.setMosaicToolbarVisible(mosaicToolbarBox.isSelected());
        settings.setMosaicStatusBarVisible(mosaicStatusBox.isSelected());
        settings.setMosaicLocationBarVisible(mosaicLocationBox.isSelected());
        mosaic.menuBarVisibleProperty().set(mosaicMenuBox.isSelected());
        mosaic.toolbarVisibleProperty().set(mosaicToolbarBox.isSelected());
        mosaic.statusBarVisibleProperty().set(mosaicStatusBox.isSelected());
        mosaic.locationBarVisibleProperty().set(mosaicLocationBox.isSelected());

        settings.setMosaicTileSize(tileSizeSpinner.getValue());
        settings.setMosaicMargin(marginSpinner.getValue());
        settings.setMosaicBorderWidth(borderWidthSpinner.getValue());
        settings.setMosaicBorderColor(webHex(borderColorPicker.getValue()));
        settings.setMosaicFolderPreviewGrid(folderGridSpinner.getValue());
        settings.setMosaicTileSetId(tileSetCombo.getValue().id());
        settings.setMosaicThumbnailsVisible(thumbnailsBox.isSelected());
        settings.setMosaicFillTiles(fillBox.isSelected());
        settings.setMosaicSeamless(seamlessBox.isSelected());
        settings.setMosaicWontOpenReticule(wontOpenReticuleBox.isSelected());
        settings.setMosaicReticuleScope(reticuleScopeCombo.getValue());
        settings.setThumbnailMaxEdge(maxEdgeSpinner.getValue());
        settings.setThumbnailMemoryBudgetMb(budgetSpinner.getValue());
        settings.setMosaicAutoOpen(autoOpenBox.isSelected());
        // Background drag applies live: the mosaic's drag handler reads this each drag.
        settings.setMosaicDragBackground(mosaicDragBox.isSelected());
        settings.setMosaicSelectionAnimation(animationCombo.getValue());
        settings.setMosaicPulsePeriodMs(animationPeriodSpinner.getValue());
        settings.setMosaicScreensaverEnabled(screensaverBox.isSelected());
        settings.setMosaicScreensaverDelayMinutes(screensaverDelaySpinner.getValue());
        // Push the layout / appearance / selection changes live into the open
        // mosaic, and sync the Mosaic menu's folder-preview radios to the value.
        // The folder glyph goes through the mosaic's property: its listener
        // persists the choice, and the menu radios are bound to the same property.
        mosaic.applyLayoutSettings();
        mosaic.applyScreensaverSettings();
        folderPreviewGridProp.set(folderGridSpinner.getValue());
        mosaic.folderGlyphProperty().set(folderGlyphCombo.getValue());

        // --- Keys: applied to the menu bar on the next start -------------------
        settings.setKeysModifier1(mod1Combo.getValue().token());
        settings.setKeysModifier2(mod2Combo.getValue().token());
        try {
            settings.save();
            statusLabel.setText("Settings saved \u2014 some changes apply after restart");
        } catch (java.io.IOException e) {
            statusLabel.setText("Cannot save settings: " + e.getMessage());
        }
    }

    /** Formats a {@link Color} as a {@code #rrggbb} web hex string for persistence. */
    private static String webHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private void showAbout() {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Media Browser");
        alert.setHeaderText("Media Browser");
        alert.setContentText("A pure-Java media browser.\n\n"
                + "Java " + System.getProperty("java.version")
                + ", JavaFX " + System.getProperty("javafx.version") + "\n"
                + service.nativeVersions());
        alert.initOwner(stage());
        alert.showAndWait();
    }

    static boolean supportsComputerLocation(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Builds the virtual Computer listing from real, independently rooted drives. */
    static List<DirEntry> computerListing(Iterable<Path> roots) {
        var result = new ArrayList<DirEntry>();
        for (Path root : roots) {
            if (root != null) result.add(new DirEntry(root, DirEntry.Type.DIRECTORY, null, 0, 0));
        }
        return List.copyOf(result);
    }

    private static TreeItem<Path> navigationTreeRoot(Path initialRoot) {
        if (!supportsComputerLocation(System.getProperty("os.name"))) {
            return new DirTreeItem(initialRoot);
        }
        var computer = new TreeItem<Path>(null);
        for (Path root : FileSystemRoots.discover()) {
            computer.getChildren().add(new DirTreeItem(root));
        }
        return computer;
    }

    /** Whether a tree click landed on a populated cell, excluding its expand arrow. */
    private static boolean clickedOnPath(Object target) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("tree-disclosure-node")) return false;
            if (n instanceof TreeCell<?> cell) return !cell.isEmpty();
        }
        return false;
    }

    private static String displayName(Path p) {
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
