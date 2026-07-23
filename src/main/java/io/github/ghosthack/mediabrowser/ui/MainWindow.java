package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.LoadingIndicator;
import io.github.ghosthack.mediabrowser.MosaicFolderGlyph;
import io.github.ghosthack.mediabrowser.MosaicSelectionAnimation;
import io.github.ghosthack.mediabrowser.album.AlbumStore;
import io.github.ghosthack.mediabrowser.StartupLayout;
import io.github.ghosthack.mediabrowser.Theme;
import io.github.ghosthack.mediabrowser.WindowMode;
import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.DetectionMode;
import io.github.ghosthack.mediabrowser.media.DirEntry;
import io.github.ghosthack.mediabrowser.media.MediaBackend;
import io.github.ghosthack.mediabrowser.media.MediaItem;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.ffm.HwDecode;

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
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    /** Shared album store (numbered {@code album-NNN.csv} files in the app dir). */
    private final AlbumStore albumStore = new AlbumStore();

    private final TextField addressField = new TextField();
    private final HBox addressBar;
    private final TreeView<Path> tree;
    private final ListView<DirEntry> listView = new ListView<>();
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
    private final Label statusLabel = new Label("Ready");
    private final Label countLabel = new Label();
    private final HBox statusBar;
    private final ActionLogPanel actionLogPanel;
    private final VBox treePanel;

    private final Deque<Path> backStack = new ArrayDeque<>();
    private final Deque<Path> forwardStack = new ArrayDeque<>();
    private boolean traversingHistory;
    private Path pendingSelection;
    /** True for one event tick after a click in a focus-bouncing panel; see armFocusBounce(). */
    private boolean bounceArmed;

    private final Typeahead typeahead = new Typeahead();

    private Path currentDir;
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
    private enum SortKey { NAME, EXTENSION, SIZE, DATE }

    // --- shared menu-bar state -------------------------------------------------
    // The application has one menu bar, built once for this window and once for
    // the mosaic (each its own instance, so macOS scopes accelerators to the
    // focused window). These observable properties are the single source of
    // truth both bars bind to, so a change in either takes effect once.
    private final BooleanProperty showViewableProp = new SimpleBooleanProperty(true);
    private final BooleanProperty showNonViewableProp = new SimpleBooleanProperty(true);
    private final BooleanProperty showDirsProp = new SimpleBooleanProperty(true);
    private final ObjectProperty<DetectionMode> detectionProp = new SimpleObjectProperty<>();
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
        this.diagnosticsPanel = new DiagnosticsPanel(service::thumbnailStats);
        this.mosaic = new MosaicWindow(shell, service, viewer, settings, rotationStore, aaeStore);
        // Navigating a folder / .. tile in the mosaic drives the shared location
        // here; the new listing then flows back into the mosaic's grid.
        mosaic.setNavigator(this::navigateAndSync);
        // Cmd+Left / Cmd+Right in the mosaic step the shared history.
        mosaic.setHistoryHandlers(this::goBack, this::goForward);

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
        });

        // --- navigation tree -------------------------------------------------
        Path fsRoot = Path.of(System.getProperty("user.home")).getRoot();
        var rootItem = new DirTreeItem(fsRoot);
        rootItem.setExpanded(true);
        tree = new TreeView<>(rootItem);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                setText(empty || path == null ? null : displayName(path));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            // skip when mirroring list-driven navigation back into the tree
            if (sel != null && !sel.getValue().equals(currentDir)) navigate(sel.getValue());
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
        var treeTitle = new Label("Folders");
        treeTitle.setStyle("-fx-font-weight: bold; -fx-padding: 6 8 6 8;");
        treePanel = new VBox(treeTitle, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);
        treePanel.setPrefWidth(240);
        treePanel.setMinWidth(140);

        // --- file list ---------------------------------------------------------
        listView.setItems(visibleEntries);
        // Multi-select so Move (and future bulk ops) can act on several entries;
        // Shift-click / Shift-arrow / Cmd-click come from the ListView for free.
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        listView.setPlaceholder(new Label("Nothing to show in this folder"));
        listView.setCellFactory(lv -> new ListCell<>() {
            private final Label icon = new Label();
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
                menu.getItems().addAll(copyPath, moveItem, albumMenu.menu());
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
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                } else {
                    icon.setText(iconFor(entry));
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
                if (moveController.quickMove(0)) e.consume();
            } else if (e.getCode() == KeyCode.F2) {
                if (moveController.quickMove(1)) e.consume();
            } else if (e.getCode() == KeyCode.F3) {
                if (moveController.quickMove(2)) e.consume();
            } else if (e.getCode() == KeyCode.F4) {
                if (moveController.quickMove(3)) e.consume();
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
        var goButton = new Button("Go");
        goButton.setOnAction(e -> navigateFromAddress());
        addressBar = new HBox(6, new Label("Location:"), addressField, goButton);
        addressBar.setPadding(new Insets(4, 8, 4, 8));
        addressBar.setStyle("-fx-alignment: center-left;");

        // --- view toggles (toolbar buttons + menu items, kept in sync) -------
        var addressToggle = panelToggle("Address Bar", addressVisibleProp);
        var treeToggle = panelToggle("Nav Tree", treeVisibleProp);
        var infoToggle = panelToggle("Info Panel", infoVisibleProp);
        var metadataToggle = panelToggle("Metadata", metadataVisibleProp);
        var diagnosticsToggle = panelToggle("Diagnostics", diagnosticsVisibleProp);
        var statusToggle = panelToggle("Status Bar", statusVisibleProp);
        var actionLogToggle = panelToggle("Action Log", actionLogVisibleProp);

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
        keepFocusToggle.setTooltip(new Tooltip(shell.singleWindow()
                ? "Inert in the single-window mode: opening media always "
                        + "switches to the viewer (see Settings ▸ Window mode)"
                : "Keep this window focused when opening media, so browsing "
                        + "continues while the viewer sits on another screen"));
        keepFocusToggle.selectedProperty().bindBidirectional(keepFocusProp);

        this.toolBar = new ToolBar(addressToggle, treeToggle, infoToggle,
                metadataToggle, diagnosticsToggle, statusToggle, actionLogToggle,
                new Separator(), keepFocusToggle);
        // Hidden by default; View ▸ Toolbar (and the other bars' copies) toggle it.
        // When undecorated, the in-window menu bar still serves as a drag handle.
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

        sortKeyProp.addListener((o, a, v) -> applySort());
        sortDescendingProp.addListener((o, a, v) -> applySort());
        applySort();

        folderPreviewGridProp.set(settings.mosaicFolderPreviewGrid());
        folderPreviewGridProp.addListener((o, a, v) -> { if (v != null) setFolderPreviewGrid(v); });

        this.menuBar = buildMenuBar(MenuOwner.MAIN);
        mosaic.installMenuBar(buildMenuBar(MenuOwner.MOSAIC), keys);
        viewer.installMenuBar(buildMenuBar(MenuOwner.VIEWER), keys);

        // --- layout ----------------------------------------------------------
        splitPane.getItems().addAll(treePanel, listView);
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

        // Backspace anywhere (except text inputs, which consume it first)
        // goes to the parent folder. On the view root, so it only sees events
        // while the browser is the shell's active view.
        root.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.BACK_SPACE
                    && !(e.getTarget() instanceof TextInputControl)) {
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
        actionLogVisibleProp.set(settings.browserActionLogVisible());
        treeVisibleProp.set(settings.browserNavTreeVisible());
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
        expandAndSelect(startDir);
        if (currentDir == null) navigate(startDir);
        if (settings.startupLayout().opensMosaic()) {
            mosaic.open(visibleEntries, () -> currentDir);
            return AppShell.AppView.MOSAIC;
        }
        listView.requestFocus(); // expandAndSelect leaves focus on the tree
        return AppShell.AppView.BROWSER;
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
    private static ToggleButton panelToggle(String text, BooleanProperty prop) {
        var button = new ToggleButton(text);
        button.setTooltip(new Tooltip("Show/hide the " + text.toLowerCase()));
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

    /** @return whether the target was accepted (the listing itself is async) */
    private boolean navigate(Path dir) {
        Path target = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            statusLabel.setText("Not a folder: " + target);
            return false;
        }
        Path select = pendingSelection; // entry to re-select once listed
        pendingSelection = null;
        typeahead.reset();
        if (!traversingHistory && currentDir != null && !target.equals(currentDir)) {
            backStack.push(currentDir);
            forwardStack.clear();
        }
        updateHistoryItems();
        currentDir = target;
        addressField.setText(target.toString());
        statusLabel.setText("Scanning " + target + "…");
        countLabel.setText("");
        // Immediate feedback in the mosaic while the scan runs off the FX thread
        // (the content-sniffing classify of every file is the slow part): name
        // the target and arm its loading indicator now, so an open into a slow
        // folder isn't a frozen-looking gap until the listing lands. A no-op
        // when the mosaic is hidden.
        mosaic.onDirectoryScanStarted(target);
        int seq = ++scanSequence;
        reclassQueue.clear();   // abandon any corrections still queued for the old folder
        // Under content-sniff detection the per-file classify is the slow part of
        // a scan, so paint the folder structure at once from a fast,
        // extension-classified listing (placeholder tiles), then stream the
        // content-sniff corrections in progressively as each file is classified
        // (see streamReclassification) rather than waiting for the whole scan. In
        // extension mode the single listing is already instant and accurate, so
        // it is shown directly.
        if (service.detectionMode() == DetectionMode.CONTENT_SNIFF) {
            service.listEntriesFast(target).whenComplete((fast, error) ->
                    Platform.runLater(() -> {
                        if (seq != scanSequence) return; // user already navigated elsewhere
                        if (error != null) {
                            entries.clear();
                            statusLabel.setText(rootMessage(error));
                            return;
                        }
                        showListing(fast, select);
                        statusLabel.setText("Scanning " + target + "…"); // sniff still streaming
                        streamReclassification(target, fast, seq);
                    }));
        } else {
            service.listEntries(target).whenComplete((listing, error) ->
                    Platform.runLater(() -> {
                        if (seq != scanSequence) return;
                        if (error != null) {
                            entries.clear();
                            statusLabel.setText(rootMessage(error));
                            return;
                        }
                        showListing(listing, select);
                        statusLabel.setText(target.toString());
                    }));
        }
        return true;
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
            if (seq == scanSequence) statusLabel.setText(target.toString());
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
                    ? new DirEntry(cur.path(), DirEntry.Type.MEDIA, kind.get(), cur.size(), cur.lastModifiedMillis())
                    : new DirEntry(cur.path(), DirEntry.Type.OTHER, null, cur.size(), cur.lastModifiedMillis());
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
    }

    /**
     * Address bar Go/Enter: navigates, and on a valid target hands focus to
     * the file list; an invalid path leaves the field focused for fixing.
     */
    private void navigateFromAddress() {
        if (navigate(Path.of(addressField.getText().trim()))) {
            listView.requestFocus();
        }
    }

    /** Navigates and mirrors the new location into the folder tree. */
    private void navigateAndSync(Path dir) {
        navigate(dir);
        expandAndSelect(dir.toAbsolutePath().normalize());
    }

    /** Backspace / Enclosing Folder, and the viewer relaying parent navigation:
     * navigates the listing to the current folder's parent. */
    @Override
    public void navigateToParent() {
        Path parent = currentDir == null ? null : currentDir.getParent();
        if (parent == null) return;
        pendingSelection = currentDir; // land on the folder we came from
        navigateAndSync(parent);
    }

    private void goBack() {
        if (backStack.isEmpty()) return;
        Path target = backStack.pop();
        if (currentDir != null) forwardStack.push(currentDir);
        traverseHistory(target);
    }

    private void goForward() {
        if (forwardStack.isEmpty()) return;
        Path target = forwardStack.pop();
        if (currentDir != null) backStack.push(currentDir);
        traverseHistory(target);
    }

    private void traverseHistory(Path target) {
        traversingHistory = true;
        try {
            navigateAndSync(target);
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
            case DIRECTORY -> showDirsProp.get();
            case MEDIA -> showViewableProp.get();
            case OTHER -> showNonViewableProp.get();
        });
        updateCount();
    }

    private void updateCount() {
        long dirs = 0, media = 0, other = 0;
        for (DirEntry e : visibleEntries) {
            switch (e.type()) {
                case DIRECTORY -> dirs++;
                case MEDIA -> media++;
                case OTHER -> other++;
                case PARENT -> { }
            }
        }
        countLabel.setText(dirs + " folders · " + (media + other) + " files · "
                + media + " viewable");
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
     * properties, keeping all three bars in lock-step. {@code owner} only
     * changes the Mosaic and Viewer menus — their items get accelerators and
     * stay enabled in their own window's bar, while the other bars leave them
     * shortcut-free and disabled until that window is open.
     */
    private MenuBar buildMenuBar(MenuOwner owner) {
        // Accelerators use the logical modifier scheme (Settings ▸ Keys): modifier 1
        // is the platform "shortcut" key by default (Command on macOS, Control
        // elsewhere). Every item also carries an Alt-letter mnemonic (the leading
        // "_"). Each window installs its own bar instance, so the OS scopes
        // accelerators to whichever window is focused.
        var fileMenu = new Menu("_File", null,
                action("_Open Folder\u2026", keys.mod1(KeyCode.O), this::chooseDirectory),
                new SeparatorMenuItem(),
                buildMoveItem(owner),
                new SeparatorMenuItem(),
                action("_Settings\u2026", keys.mod1(KeyCode.COMMA), this::showSettings),
                new SeparatorMenuItem(),
                buildCloseWindowItem(owner),
                new SeparatorMenuItem(),
                action("E_xit", keys.mod1(KeyCode.Q), Platform::exit));

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
                action("_Reload Folder", keys.mod1(KeyCode.R), this::reloadFolder));

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
        // The browser and the mosaic each own an action-log panel; the viewer
        // has none, so its bar's item acts on the browser's (like Address Bar).
        BooleanProperty showActionLogProp = switch (owner) {
            case MAIN, VIEWER -> actionLogVisibleProp;
            case MOSAIC -> mosaic.actionLogVisibleProperty();
        };
        // Menu-bar runtime visibility: per-window, toggled by mod1+\ and the
        // modifier-2 peek (see WindowChrome.bindMenuAutoHide).
        BooleanProperty showMenuBarProp = switch (owner) {
            case MAIN -> menuBarVisibleProp;
            case MOSAIC -> mosaic.menuBarVisibleProperty();
            case VIEWER -> viewer.menuBarVisibleProperty();
        };
        // The mosaic's address bar is its own location strip; the viewer has
        // none, so its bar's item acts on the browser's (like Action Log).
        BooleanProperty showAddressProp = switch (owner) {
            case MAIN, VIEWER -> addressVisibleProp;
            case MOSAIC -> mosaic.locationBarVisibleProperty();
        };
        // The nav tree is browser-only chrome the mosaic never shows, so its
        // item is greyed out there (the viewer keeps it, acting on the
        // browser's, like Address Bar).
        var navTreeItem = boundCheck("_Nav Tree Panel", treeVisibleProp,
                keys.mod1(KeyCode.E));
        navTreeItem.setDisable(owner == MenuOwner.MOSAIC);
        var detectGroup = new ToggleGroup();
        var showMenu = new Menu("_Show", null,
                boundCheck("_Toolbar", showToolbarProp, keys.mod1(KeyCode.T)),
                boundCheck("_Status Bar", showStatusProp, keys.mod1(KeyCode.S)),
                boundCheck("_Info Panel", showInfoProp, keys.mod1(KeyCode.I)),
                boundCheck("_Metadata", showMetadataProp, keys.mod1(KeyCode.D)),
                boundCheck("Menu _Bar", showMenuBarProp, keys.mod1(KeyCode.BACK_SLASH)),
                new SeparatorMenuItem(),
                // modifier1+Shift+L toggles the bar that Go to Location
                // (modifier1+L) focuses — the mosaic's own location strip in
                // the mosaic bar, the browser's address bar everywhere else.
                boundCheck("_Address Bar", showAddressProp, keys.mod1Shift(KeyCode.L)),
                navTreeItem,
                boundCheck("Action _Log", showActionLogProp, keys.mod1(KeyCode.J)),
                boundCheck("Dia_gnostics", showDiagnosticsProp),
                new SeparatorMenuItem(),
                header("Listing filter"),
                boundCheck("_Viewable Media", showViewableProp),
                boundCheck("Non-viewable _Files", showNonViewableProp),
                boundCheck("Fol_ders", showDirsProp),
                new SeparatorMenuItem(),
                header("Detection"),
                objRadio("Detect by _Content", DetectionMode.CONTENT_SNIFF,
                        detectionProp, detectGroup),
                objRadio("Detect by File E_xtension", DetectionMode.FILE_EXTENSION,
                        detectionProp, detectGroup),
                new SeparatorMenuItem(),
                boundCheck("_Keep Focus on Open", keepFocusProp));

        // Order: the sort key and direction (folder placement — Top/Bottom/Natural —
        // is not yet modelled here; see docs/menu-port-handoff.md).
        var keyGroup = new ToggleGroup();
        var dirGroup = new ToggleGroup();
        var orderMenu = new Menu("_Order", null,
                header("Sort"),
                objRadio("By _Name", SortKey.NAME, sortKeyProp, keyGroup),
                objRadio("By E_xtension", SortKey.EXTENSION, sortKeyProp, keyGroup),
                objRadio("By Si_ze", SortKey.SIZE, sortKeyProp, keyGroup),
                objRadio("By _Date", SortKey.DATE, sortKeyProp, keyGroup),
                new SeparatorMenuItem(),
                boolRadio("_Ascending", false, sortDescendingProp, dirGroup),
                boolRadio("_Descending", true, sortDescendingProp, dirGroup));

        var helpMenu = new Menu("_Help", null,
                action("_About Media Browser", null, this::showAbout));

        return new MenuBar(fileMenu, goMenu, showMenu, orderMenu,
                buildMosaicMenu(owner == MenuOwner.MOSAIC),
                buildViewerMenu(owner == MenuOwner.VIEWER),
                buildWindowMenu(owner), helpMenu);
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
                () -> mosaic.open(visibleEntries, () -> currentDir));
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
    private Menu buildMosaicMenu(boolean forMosaic) {
        // No accelerators here: mod1+T lives on Show ▸ Toolbar and mod1+Shift+L
        // on Show ▸ Address Bar, which bind per-window (to the mosaic's toolbar
        // and location strip in the mosaic bar), so each chord is owned in one
        // place and never double-binds. These items stay as click-access
        // duplicates bound to the same properties.
        var showToolbar = boundCheck("Show _Toolbar", mosaic.toolbarVisibleProperty());
        var showLocationBar = boundCheck("Show Location _Bar", mosaic.locationBarVisibleProperty());
        var fpGroup = new ToggleGroup();
        var folderPreviews = new Menu("_Folder Previews", null,
                objRadio("None", 0, folderPreviewGridProp, fpGroup),
                objRadio("2 × 2", 2, folderPreviewGridProp, fpGroup),
                objRadio("3 × 3", 3, folderPreviewGridProp, fpGroup),
                objRadio("4 × 4", 4, folderPreviewGridProp, fpGroup));
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
        var animGroup = new ToggleGroup();
        var selectionAnimation = new Menu("Se_lection Animation", null,
                objRadio(MosaicSelectionAnimation.NONE.label(), MosaicSelectionAnimation.NONE,
                        mosaic.selectionAnimationProperty(), animGroup),
                objRadio(MosaicSelectionAnimation.PULSE.label(), MosaicSelectionAnimation.PULSE,
                        mosaic.selectionAnimationProperty(), animGroup),
                objRadio(MosaicSelectionAnimation.MARCHING_ANTS.label(),
                        MosaicSelectionAnimation.MARCHING_ANTS,
                        mosaic.selectionAnimationProperty(), animGroup));
        var menu = new Menu("_Mosaic", null,
                action("Open _Selected", null, mosaic::openSelectedItem),
                new SeparatorMenuItem(),
                boundCheck("S_eamless", mosaic.seamlessProperty(),
                        forMosaic ? keys.mod1(KeyCode.B) : null),
                action("_Larger Tiles",
                        forMosaic ? keys.mod1(KeyCode.EQUALS) : null,
                        () -> mosaic.nudgeTileSize(32)),
                action("S_maller Tiles",
                        forMosaic ? keys.mod1(KeyCode.MINUS) : null,
                        () -> mosaic.nudgeTileSize(-32)),
                folderPreviews,
                folderGlyph,
                showThumbnails,
                fillTiles,
                tileLabels,
                autoOpen,
                keepFocus,
                selectionAnimation,
                new SeparatorMenuItem(),
                showToolbar,
                showLocationBar,
                new SeparatorMenuItem(),
                action("_Close Mosaic", null, mosaic::closeWindow));
        // In the other views' bars the mosaic actions only apply while it is on
        // screen.
        if (!forMosaic) {
            menu.disableProperty().bind(shell.isShowing(AppShell.AppView.MOSAIC).not());
        }
        return menu;
    }

    /**
     * The Viewer menu, mirroring the viewer's toolbar buttons and scene key
     * filter; see {@link #buildMenuBar}. The viewer centralises its keys
     * (Escape/Enter/arrows/Space/T/S/I/P/F) in a scene event filter that
     * consumes them before any menu accelerator could fire, so these items carry
     * no accelerators and exist for click-access (notably in the macOS system
     * bar) and cross-window state sync.
     */
    private Menu buildViewerMenu(boolean forViewer) {
        var playPause = action("Pla_y / Pause", null, viewer::togglePlayback);
        playPause.disableProperty().bind(viewer.playDisabledProperty());
        var scaleGroup = new ToggleGroup();
        var scaleProp = viewer.scaleModeProperty();
        // The loading indicator is a three-way choice (None / Default / Game
        // Console); the historical single checkbox becomes a radio submenu bound
        // to the viewer's ObjectProperty, mirrored by the toolbar's picker.
        var loadingGroup = new ToggleGroup();
        var loadingProp = viewer.loadingIndicatorProperty();
        var loadingMenu = new Menu("_Loading Indicator", null,
                objRadio("_None", LoadingIndicator.NONE, loadingProp, loadingGroup),
                objRadio("_Default", LoadingIndicator.DEFAULT, loadingProp, loadingGroup),
                objRadio("_Game Console", LoadingIndicator.GAME_CONSOLE, loadingProp, loadingGroup));
        var panelsMenu = new Menu("_Panels", null,
                boundCheck("_Info Panel", viewer.infoPanelVisibleProperty()),
                boundCheck("_Metadata", viewer.metadataPanelVisibleProperty()),
                boundCheck("_Status Bar", viewer.statusBarVisibleProperty()),
                boundCheck("_Toolbar", viewer.toolbarVisibleProperty()),
                boundCheck("P_ins", viewer.pinsVisibleProperty()));
        var menu = new Menu("_Viewer", null,
                playPause,
                boundCheck("_Repeat", viewer.repeatProperty()),
                boundCheck("_Autoplay", viewer.autoplayProperty()),
                new SeparatorMenuItem(),
                boundCheck("Slide_show", viewer.slideshowProperty()),
                action("Slideshow _Settings\u2026", null, viewer::showSlideshowDialog),
                boundCheck("Flip_book", viewer.flipbookProperty()),
                action("Flipbook Settings\u2026", null, viewer::showFlipbookDialog),
                new SeparatorMenuItem(),
                objRadio("_Fit", ViewerWindow.ScaleMode.FIT, scaleProp, scaleGroup),
                objRadio("_1:1", ViewerWindow.ScaleMode.ONE_TO_ONE, scaleProp, scaleGroup),
                objRadio("_Crop to Fill", ViewerWindow.ScaleMode.CROP_TO_FILL, scaleProp, scaleGroup),
                new SeparatorMenuItem(),
                panelsMenu,
                loadingMenu,
                new SeparatorMenuItem(),
                boundCheck("F_ull Screen", viewer.fullScreenProperty()));
        // In the other views' bars the viewer actions only apply once it has
        // content to act on.
        if (!forViewer) menu.disableProperty().bind(viewer.hasContentProperty().not());
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
     * mirroring Escape in the viewer and the {@code Close Mosaic} /
     * {@code Close Viewer} actions. Each window installs its own menu-bar
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

    private void reloadFolder() {
        if (currentDir != null) navigate(currentDir);
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
     * Switches how files are classified, persists the choice, and re-scans the
     * current folder so the listing reflects the new method immediately.
     */
    private void setDetectionMode(DetectionMode mode) {
        service.setDetectionMode(mode);
        settings.setDetectionMode(mode.settingsValue());
        try {
            settings.save();
        } catch (java.io.IOException e) {
            statusLabel.setText("Cannot save settings: " + e.getMessage());
        }
        if (currentDir != null) navigate(currentDir);
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
            case DIRECTORY -> 1;
            case MEDIA, OTHER -> 2;
        };
    }

    /** Puts the entry's absolute path on the system clipboard. */
    private void copyPathToClipboard(DirEntry entry) {
        if (entry == null) return;
        String path = entry.path().toAbsolutePath().normalize().toString();
        var content = new ClipboardContent();
        content.putString(path);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied path: " + path);
    }

    private static String iconFor(DirEntry entry) {
        return switch (entry.type()) {
            case PARENT -> "⬆";
            case DIRECTORY -> "📁";
            case MEDIA -> switch (entry.mediaKind()) {
                case IMAGE -> "🖼";
                case VIDEO -> "🎬";
                case AUDIO -> "🎵";
            };
            case OTHER -> "";
        };
    }

    private void expandAndSelect(Path target) {
        TreeItem<Path> node = tree.getRoot();
        Path root = node.getValue();
        if (!target.startsWith(root)) return;
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
                default -> "Not viewable media";
            });
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
     * Fills the info panel's File section for {@code entry} — name, size and
     * timestamps read straight from an async filesystem stat, independent of
     * the (potentially slow) native probe — or clears it for the parent link
     * and folders. Guarded on {@code probeSequence} like the probe itself, so
     * a superseded selection's facts are dropped.
     */
    private void updateFileFacts(DirEntry entry, int seq) {
        if (entry.type() == DirEntry.Type.PARENT || entry.type() == DirEntry.Type.DIRECTORY) {
            infoPanel.clearFileFacts();
            return;
        }
        service.fileFacts(entry.path()).whenComplete((facts, error) ->
                Platform.runLater(() -> {
                    if (seq != probeSequence) return;
                    if (error != null) {
                        infoPanel.clearFileFacts();
                    } else {
                        infoPanel.showFileFacts(
                                InfoPanel.fileFactRows(entry.displayName(), facts));
                    }
                }));
    }

    /**
     * Starts the on-demand metadata read for the selected item (a manual Load
     * click or the opt-in Auto debounce), mirroring the viewer. A later
     * selection bumps {@code probeSequence}, so a superseded read is dropped.
     */
    private void fireMetadataRead() {
        DirEntry entry = listView.getSelectionModel().getSelectedItem();
        if (entry == null || !entry.viewable()) {
            metadataPanel.showMessage(entry == null ? "No selection" : "Not viewable media");
            return;
        }
        int gen = probeSequence;
        metadataPanel.showMessage("Reading\u2026");
        service.metadata(entry.path()).whenComplete((md, error) ->
                Platform.runLater(() -> {
                    if (gen != probeSequence) return;
                    if (error != null) {
                        metadataPanel.showMessage(rootMessage(error));
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
            case DIRECTORY -> navigateAndSync(entry.path());
            // Keep Focus only means something across separate windows (the
            // viewer opens without stealing focus); the single window always
            // switches, since it can't show a second view.
            case MEDIA -> viewer.open(entry.toMediaItem(), viewableItems(),
                    shell.singleWindow() || !keepFocusProp.get(), this);
            case OTHER -> statusLabel.setText("Not viewable: " + entry.displayName());
        }
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
        moveController.open();
    }

    /**
     * Resolves the move sources from the list selection: every selected entry
     * other than the {@code ..} parent link, with {@code parentExcluded} flagged
     * when {@code ..} was part of the selection (so the dialog never tries to
     * move the parent link). Order follows the listing, not click order.
     */
    private MoveController.Selection currentMoveSelection() {
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
     * re-selects it once the async scan returns (the "refresh → then focus"
     * sequencing from the handoff §2.8); a null path clears the selection.
     */
    private void refreshAfterMove(Path focusPath) {
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
        var backendRow = new HBox(8, new Label("Media decode backend:"), backendCombo);
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

        var themeCombo = new ComboBox<Theme>();
        themeCombo.getItems().setAll(Theme.values());
        themeCombo.setValue(settings.theme());
        // Live preview: apply across all open windows (and this dialog) as the
        // selection changes; reverted below if the user cancels.
        themeCombo.valueProperty().addListener((obs, old, val) ->
                ThemeManager.get().setCurrent(val));
        var themeRow = new HBox(8, new Label("Theme:"), themeCombo);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        var chromeBox = new CheckBox("Remove OS window chrome (title bar, borders)");
        chromeBox.setSelected(settings.undecoratedWindows());
        var resizeBox = new CheckBox("Resizable chromeless windows (drag the edges)");
        resizeBox.setSelected(settings.undecoratedResizable());
        resizeBox.disableProperty().bind(chromeBox.selectedProperty().not());
        var overscanBox = new CheckBox("Overscan maximize (hide window edges on F)");
        overscanBox.setSelected(settings.maximizeOverscan());
        overscanBox.disableProperty().bind(chromeBox.selectedProperty().not());
        var inWindowMenuBox = new CheckBox("In-window menu bar (instead of the macOS menu bar)");
        inWindowMenuBox.setSelected(settings.inWindowMenu());
        var actionLogFileBox = new CheckBox(
                "Write the action log to disk (append-only JSONL, applies immediately)");
        actionLogFileBox.setSelected(settings.actionLogFileEnabled());
        actionLogFileBox.setTooltip(new Tooltip(
                "Appends every completed move/rename to "
                + "~/.media-browser/action-log.jsonl, one JSON object per line. "
                + "The file is never trimmed; at startup the panel is seeded "
                + "with its last few entries."));
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
        hint.setStyle("-fx-text-fill: gray;");
        var generalContent = new VBox(8, themeRow, backendRow, decodeRow, actionLogFileBox,
                new Separator(), loadingIndicatorRow, loadingDelayRow,
                new Separator(),
                chromeBox, resizeBox, overscanBox, inWindowMenuBox, windowModeRow,
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
        var browserActionLogBox = new CheckBox("Show the action log");
        browserActionLogBox.setSelected(settings.browserActionLogVisible());
        var browserHint = new Label("Startup defaults for the file-browser window; "
                + "also applied immediately.");
        browserHint.setStyle("-fx-text-fill: gray;");
        var browserContent = new VBox(8, browserMenuBox, browserToolbarBox,
                browserStatusBox, browserNavTreeBox, browserActionLogBox,
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
        viewerHint.setStyle("-fx-text-fill: gray;");
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
        var mosaicActionLogBox = new CheckBox("Show the action log");
        mosaicActionLogBox.setSelected(settings.mosaicActionLogVisible());

        var layoutTitle = new Label("Grid layout & appearance");
        layoutTitle.setStyle("-fx-font-weight: bold;");
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
        var thumbnailsBox = new CheckBox("Show thumbnails in the mosaic");
        thumbnailsBox.setSelected(settings.mosaicThumbnailsVisible());
        var fillBox = new CheckBox("Crop tiles to fill (squares, no letterbox)");
        fillBox.setSelected(settings.mosaicFillTiles());
        var seamlessBox = new CheckBox("Seamless (no tile margins or borders)");
        seamlessBox.setSelected(settings.mosaicSeamless());

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
        var mosaicHint = new Label("Layout, appearance, selection and behaviour apply "
                + "immediately; the preview max edge applies to newly generated "
                + "previews and the cache budget applies after restart.");
        mosaicHint.setStyle("-fx-text-fill: gray;");
        mosaicHint.setWrapText(true);
        mosaicHint.setMaxWidth(420);

        var mosaicContent = new VBox(8, mosaicMenuBox, mosaicToolbarBox, mosaicStatusBox,
                mosaicLocationBox, mosaicActionLogBox,
                new Separator(), layoutTitle, tileSizeRow, marginRow, borderWidthRow,
                borderColorRow, folderGridRow, folderGlyphRow, thumbnailsBox, fillBox,
                seamlessBox,
                new Separator(), renditionTitle, maxEdgeRow, budgetRow,
                new Separator(), behaviourTitle, autoOpenBox, mosaicDragBox,
                animationRow, animationPeriodRow, mosaicHint);
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
        keysHint.setStyle("-fx-text-fill: gray;");
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

        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Settings");
        dialog.initOwner(stage());
        dialog.getDialogPane().setContent(tabs);
        // Theme the dialog itself so the live preview is visible while choosing.
        ThemeManager.get().register(dialog.getDialogPane());
        var save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        Theme themeBefore = settings.theme();
        if (dialog.showAndWait().filter(b -> b == save).isEmpty()) {
            // Cancelled: revert any live theme preview to the persisted value.
            ThemeManager.get().setCurrent(themeBefore);
            return;
        }

        // --- General -----------------------------------------------------------
        settings.setTheme(themeCombo.getValue());
        ThemeManager.get().setCurrent(themeCombo.getValue());
        settings.setMediaBackend(backendCombo.getValue().settingsValue());
        // Persist + apply live: the next playback session honours the policy.
        String decodeDevice = decodeCombo.getValue().name().toLowerCase(java.util.Locale.ROOT);
        settings.setDecodeDevice(decodeDevice);
        HwDecode.configure(decodeDevice);
        settings.setUndecoratedWindows(chromeBox.isSelected());
        settings.setUndecoratedResizable(resizeBox.isSelected());
        settings.setMaximizeOverscan(overscanBox.isSelected());
        settings.setInWindowMenu(inWindowMenuBox.isSelected());
        settings.setWindowMode(windowModeCombo.getValue());
        settings.setStartupLayout(startupLayoutCombo.getValue());
        settings.setActionLogFileEnabled(actionLogFileBox.isSelected());
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
        settings.setBrowserActionLogVisible(browserActionLogBox.isSelected());
        menuBarVisibleProp.set(browserMenuBox.isSelected());
        toolbarVisibleProp.set(browserToolbarBox.isSelected());
        statusVisibleProp.set(browserStatusBox.isSelected());
        treeVisibleProp.set(browserNavTreeBox.isSelected());
        actionLogVisibleProp.set(browserActionLogBox.isSelected());

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
        settings.setMosaicActionLogVisible(mosaicActionLogBox.isSelected());
        mosaic.menuBarVisibleProperty().set(mosaicMenuBox.isSelected());
        mosaic.toolbarVisibleProperty().set(mosaicToolbarBox.isSelected());
        mosaic.statusBarVisibleProperty().set(mosaicStatusBox.isSelected());
        mosaic.locationBarVisibleProperty().set(mosaicLocationBox.isSelected());
        mosaic.actionLogVisibleProperty().set(mosaicActionLogBox.isSelected());

        settings.setMosaicTileSize(tileSizeSpinner.getValue());
        settings.setMosaicMargin(marginSpinner.getValue());
        settings.setMosaicBorderWidth(borderWidthSpinner.getValue());
        settings.setMosaicBorderColor(webHex(borderColorPicker.getValue()));
        settings.setMosaicFolderPreviewGrid(folderGridSpinner.getValue());
        settings.setMosaicThumbnailsVisible(thumbnailsBox.isSelected());
        settings.setMosaicFillTiles(fillBox.isSelected());
        settings.setMosaicSeamless(seamlessBox.isSelected());
        settings.setThumbnailMaxEdge(maxEdgeSpinner.getValue());
        settings.setThumbnailMemoryBudgetMb(budgetSpinner.getValue());
        settings.setMosaicAutoOpen(autoOpenBox.isSelected());
        // Background drag applies live: the mosaic's drag handler reads this each drag.
        settings.setMosaicDragBackground(mosaicDragBox.isSelected());
        settings.setMosaicSelectionAnimation(animationCombo.getValue());
        settings.setMosaicPulsePeriodMs(animationPeriodSpinner.getValue());
        // Push the layout / appearance / selection changes live into the open
        // mosaic, and sync the Mosaic menu's folder-preview radios to the value.
        // The folder glyph goes through the mosaic's property: its listener
        // persists the choice, and the menu radios are bound to the same property.
        mosaic.applyLayoutSettings();
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
