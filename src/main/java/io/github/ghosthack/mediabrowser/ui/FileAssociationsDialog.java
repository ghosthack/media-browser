package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.ExtensionClassifier;
import io.github.ghosthack.mediabrowser.media.FileAssociationCatalog;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.ui.icon.AppIcon;
import io.github.ghosthack.mediabrowser.ui.icon.IconView;
import io.github.ghosthack.mediabrowser.win.FileAssociationRegistry;
import io.github.ghosthack.mediabrowser.win.FileAssociationRegistry.AssocState;
import io.github.ghosthack.mediabrowser.win.FileAssociationRegistry.AssocStatus;
import io.github.ghosthack.mediabrowser.win.LaunchInvocation;
import io.github.ghosthack.panama.media.fileassoc.RegistryException;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The "File Associations…" dialog (Windows only) — a page-rail (the same
 * {@link MainWindow#composeRailItem}/{@link MainWindow#wireSettingsRail}
 * sidebar used by Settings) with one page per media kind (Images/Video/Audio),
 * each a table of every extension {@link ExtensionClassifier} recognizes for
 * that kind (checkbox, icon, extension, format description), with a per-page
 * Select All/None. Registers/removes associations on Confirm via
 * {@link FileAssociationRegistry}; every checkbox always reflects live
 * registry state, never a cached copy, since that state can change from
 * outside the app (Windows Settings, another installer). Associating only
 * ever makes this app <em>available</em> for an extension (Windows won't let
 * a third-party app silently become the default) — "Open Default Apps
 * Settings…" is the one path left to actually get there, deep-linked
 * straight to this app's own page via {@link
 * FileAssociationRegistry#openAppDefaultsSettings}.
 *
 * <p>A page's rows are only queried against the registry the first time that
 * page is shown (the constructor eagerly loads the initially-selected page;
 * the other two load lazily on first selection) — querying all ~125
 * recognized extensions up front on every open would be needless native I/O
 * for pages the user may never look at. A never-visited page's rows stay at
 * their built-in unchecked/unassociated placeholder, and {@link #confirm}
 * skips such pages entirely (not just leaves them unchanged) so a placeholder
 * can never be mistaken for a real, user-reviewed baseline.</p>
 *
 * <p>{@link #firstRunProposal} is the same dialog in its one-time first-launch
 * role (packaged Windows builds only, driven from {@code App}): every page is
 * loaded up front and {@link FileAssociationCatalog#curated} extensions not
 * already associated come pre-checked as a proposal, so Confirm registers the
 * common set in one go and Cancel registers nothing. This replaces any
 * installer-side registration: jpackage's MSI has no per-type confirm step
 * and would write opaque hashed ProgIds this dialog could never recognize,
 * whereas everything registered here shares the app's own
 * {@code MediaBrowser.AssocFile.<ext>} ProgIds.</p>
 */
public final class FileAssociationsDialog {

    /** One table row: mutable so registry-query results can update an already-built table cell in place. */
    private static final class Row {
        final String extension;
        final MediaKind kind;
        final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);
        final SimpleStringProperty description = new SimpleStringProperty("");
        AssocStatus status = new AssocStatus(AssocState.NOT_ASSOCIATED, null);

        Row(String extension, MediaKind kind) {
            this.extension = extension;
            this.kind = kind;
            description.set(FileAssociationCatalog.formatDescription(extension));
        }
    }

    private final Stage stage = new Stage();
    private final Optional<Path> packagedExe = LaunchInvocation.resolvePackagedExe();
    /** First-launch mode: pre-check curated, not-yet-associated extensions as a proposal. */
    private final boolean proposeCurated;
    private final Map<MediaKind, List<Row>> rowsByKind = new EnumMap<>(MediaKind.class);
    private final Map<MediaKind, Boolean> loadedKinds = new EnumMap<>(MediaKind.class);

    private final Text feedback = new Text();
    private final Button openDefaultAppsButton = new Button("Open Default Apps Settings…");
    private final Button confirmButton = new Button("Confirm");

    public FileAssociationsDialog(Window owner) {
        this(owner, false);
    }

    private FileAssociationsDialog(Window owner, boolean proposeCurated) {
        this.proposeCurated = proposeCurated;
        buildUi(owner);
        if (proposeCurated) {
            // Every page is loaded now so Confirm covers curated extensions on
            // pages the user never clicked through — confirm() only ever acts
            // on loaded pages, and a proposal the user can't see, but did
            // accept by pressing Confirm, must still land.
            for (MediaKind kind : MediaKind.values()) refreshKind(kind);
        } else {
            refreshKind(MediaKind.values()[0]);
        }
    }

    /**
     * The one-time first-launch variant: opens with the curated set pre-checked
     * (see the class docs). Only meaningful from a packaged build — a dev run
     * gets the same disabled dialog and banner as always.
     */
    public static FileAssociationsDialog firstRunProposal(Window owner) {
        return new FileAssociationsDialog(owner, true);
    }

    private void buildUi(Window owner) {
        Label header = new Label("File Associations");
        header.getStyleClass().add("move-header");

        // Created before its content so wrapping text can bind to *its*
        // width: the Scene resizes the root to the scene, so that width never
        // depends on the children — see bindWrappingWidth.
        VBox root = new VBox(10);
        root.getStyleClass().add("move-dialog");
        root.setPadding(new Insets(16));
        ObservableValue<Number> wrapWidth = root.widthProperty().subtract(32); // minus the padding

        VBox content = new VBox(10, header);

        if (packagedExe.isEmpty()) {
            // A Text node, not a wrapText Label: Text's height is derived
            // directly from wrappingWidth (see bindWrappingWidth), sidestepping
            // a VBox limitation where a Region's preferred height for a
            // wrapping, HORIZONTAL-content-bias child is queried unconstrained
            // (single-line) rather than at its resolved width whenever a
            // Priority.ALWAYS sibling (the rail body, here) is also present.
            Text banner = new Text("Running from a development build — associations can only be"
                    + " set from the installed Media Browser.exe.");
            banner.setFill(Color.web("#c82828")); // matches .move-error's -fx-text-fill
            bindWrappingWidth(banner, wrapWidth);
            content.getChildren().add(banner);
        } else if (proposeCurated) {
            Text banner = new Text("Choose which file types Media Browser should open from"
                    + " Explorer. Common formats are pre-selected; Confirm registers the"
                    + " checked types, Cancel registers nothing. This can be changed"
                    + " later under File \u25b8 File Associations\u2026");
            banner.setFill(Color.GRAY); // matches .move-note's -fx-text-fill
            bindWrappingWidth(banner, wrapWidth);
            content.getChildren().add(banner);
        }

        var railGroup = new ToggleGroup();
        Map<ToggleButton, MediaKind> railKinds = new HashMap<>();
        Map<ToggleButton, Node> pages = new HashMap<>();
        ToggleButton initial = null;
        List<ToggleButton> railItems = new ArrayList<>();
        for (MediaKind kind : MediaKind.values()) {
            ToggleButton item = MainWindow.composeRailItem(sectionTitle(kind), railGroup);
            railKinds.put(item, kind);
            pages.put(item, buildPage(kind));
            railItems.add(item);
            if (initial == null) initial = item;
        }
        var rail = new VBox(railItems.toArray(ToggleButton[]::new));
        rail.getStyleClass().add("settings-rail");

        var railDivider = new Region();
        railDivider.getStyleClass().add("settings-rail-divider");
        railDivider.setMinWidth(1);
        railDivider.setPrefWidth(1);
        railDivider.setMaxWidth(1);

        var pageScroll = new ScrollPane();
        pageScroll.setFitToWidth(true);
        pageScroll.setFitToHeight(true); // the table is its own scroller; the page should never need a second one
        HBox.setHgrow(pageScroll, Priority.ALWAYS);
        MainWindow.wireSettingsRail(railGroup, initial, pageScroll, pages);
        railGroup.selectedToggleProperty().addListener((obs, was, now) -> {
            MediaKind kind = railKinds.get(now);
            if (kind != null) refreshKind(kind);
        });

        var body = new HBox(rail, railDivider, pageScroll);
        VBox.setVgrow(body, Priority.ALWAYS);
        content.getChildren().add(body);

        feedback.setFill(Color.GRAY); // matches .move-note's -fx-text-fill
        feedback.setStyle("-fx-font-style: italic;"); // matches .move-note's -fx-font-style
        bindWrappingWidth(feedback, wrapWidth);
        feedback.setVisible(false);
        feedback.setManaged(false);
        content.getChildren().add(feedback);

        openDefaultAppsButton.setOnAction(e -> FileAssociationRegistry.openAppDefaultsSettings());
        openDefaultAppsButton.setVisible(false);
        openDefaultAppsButton.setManaged(false);

        var cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> stage.close());
        confirmButton.setDefaultButton(true);
        confirmButton.setOnAction(e -> confirm());
        confirmButton.setDisable(packagedExe.isEmpty());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, openDefaultAppsButton, spacer, cancelButton, confirmButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().add(buttons);

        root.getChildren().add(content);

        Scene scene = new Scene(root, 640, 620);
        var css = MoveDialog.class.getResource("move-dialog.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        ThemeManager.get().register(scene);
        // register(Scene) only manages the theme overlay; a plain Stage+Scene
        // (unlike SingleWindowShell/MultiWindowShell's) never gets window-chrome.css's
        // cross-theme constants otherwise, and .settings-rail/-rail-item/-rail-divider
        // live there (Compose's own theme sheet happens to redefine them too, but
        // every other theme relies on this). Add it directly, after the theme
        // sheet, matching how MainWindow's own Settings dialog does it.
        var chromeCss = MainWindow.class.getResource("window-chrome.css");
        if (chromeCss != null) {
            scene.getStylesheets().add(chromeCss.toExternalForm());
        }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                stage.close();
            }
        });

        stage.setScene(scene);
        stage.setTitle("File Associations");
        stage.setMinWidth(520);
        stage.setMinHeight(420);
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
    }

    private VBox buildPage(MediaKind kind) {
        List<Row> rows = ExtensionClassifier.extensionsFor(kind).stream()
                .sorted()
                .map(ext -> new Row(ext, kind))
                .toList();
        rowsByKind.put(kind, rows);

        TableView<Row> table = new TableView<>(FXCollections.observableArrayList(rows));
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Row, Boolean> checkCol = new TableColumn<>();
        checkCol.setCellValueFactory(cd -> cd.getValue().selected);
        checkCol.setCellFactory(CheckBoxTableCell.forTableColumn(checkCol));
        checkCol.setEditable(true);
        checkCol.setSortable(false);
        fixWidth(checkCol, 32);

        TableColumn<Row, Row> iconCol = new TableColumn<>();
        iconCol.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        AppIcon appIcon = appIconFor(kind);
        iconCol.setCellFactory(c -> new TableCell<>() {
            private final IconView icon = new IconView(appIcon, 16);

            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : icon);
            }
        });
        iconCol.setSortable(false);
        fixWidth(iconCol, 32);

        TableColumn<Row, String> extCol = new TableColumn<>("Extension");
        extCol.setCellValueFactory(cd -> new SimpleStringProperty("." + cd.getValue().extension));
        fixWidth(extCol, 80);

        TableColumn<Row, String> descCol = new TableColumn<>("Format");
        descCol.setCellValueFactory(cd -> cd.getValue().description);

        // descCol must stay last: CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        // flexes whichever column is last, and Format is the one that should.
        table.getColumns().setAll(List.of(checkCol, iconCol, extCol, descCol));
        // CheckBoxTableCell toggles its bound property directly on click,
        // bypassing the normal start/commit-edit cycle setEditable() gates —
        // disabling the whole table is what actually blocks interaction (and
        // greys out every cell) in the dev-run case.
        table.setDisable(packagedExe.isEmpty());
        VBox.setVgrow(table, Priority.ALWAYS);

        var selectAll = new Button("Select All");
        selectAll.setOnAction(e -> rows.forEach(r -> r.selected.set(true)));
        var selectNone = new Button("Select None");
        selectNone.setOnAction(e -> rows.forEach(r -> r.selected.set(false)));
        selectAll.setDisable(packagedExe.isEmpty());
        selectNone.setDisable(packagedExe.isEmpty());
        HBox toolbar = new HBox(8, selectAll, selectNone);

        VBox page = new VBox(6, toolbar, table);
        page.setPadding(new Insets(8, 8, 0, 8));
        return page;
    }

    /** Fixes a column at {@code width}, leaving only the Format column (the table's last) to flex. */
    private static void fixWidth(TableColumn<?, ?> column, double width) {
        column.setPrefWidth(width);
        column.setMinWidth(width);
        column.setMaxWidth(width);
    }

    private static AppIcon appIconFor(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> AppIcon.IMAGE;
            case VIDEO -> AppIcon.VIDEO;
            case AUDIO -> AppIcon.AUDIO;
        };
    }

    /**
     * Keeps a wrapping {@link Text} node's wrap width matched to the live
     * parent width, so it reflows as the dialog resizes. {@code Text}'s
     * height comes straight from its layout bounds once wrappingWidth is
     * set — unlike a wrapText {@code Label} (a {@code Region}), whose
     * preferred-height negotiation with a {@code Priority.ALWAYS} sibling
     * (the rail body, here) is a known VBox trap: VBox is supposed to query a
     * HORIZONTAL-content-bias child's preferred height using its
     * already-resolved width, but with an ALWAYS-growing sibling present it
     * instead uses the child's width-unconstrained (single-line) preferred
     * height when budgeting space, silently truncating every line past the
     * first. {@code Text} sidesteps the whole negotiation.
     *
     * <p>{@code width} must not depend on the text's own layout: binding to
     * the enclosing content VBox's width (as this once did) is a fixpoint
     * trap. A {@code Text}'s min width is its current layout width, so on the
     * first pass (wrappingWidth still 0, text unwrapped) the VBox grows to the
     * text's full single-line width, which then becomes the wrapping width —
     * and the text stays on one line at exactly that width forever, with the
     * whole dialog content wider than the window (buttons pushed off the right
     * edge, table clipped). The scene-sized root's width is safe.</p>
     */
    private static void bindWrappingWidth(Text text, ObservableValue<Number> width) {
        text.wrappingWidthProperty().bind(width);
    }

    private static String sectionTitle(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> "Images";
            case VIDEO -> "Video";
            case AUDIO -> "Audio";
        };
    }

    /** Queries {@code kind}'s rows against the live registry; a no-op once that kind has already been loaded. */
    private void refreshKind(MediaKind kind) {
        if (loadedKinds.getOrDefault(kind, false)) return;
        loadedKinds.put(kind, true);
        for (Row row : rowsByKind.get(kind)) {
            refreshRow(row);
        }
        updateOpenDefaultAppsVisibility();
    }

    private void refreshRow(Row row) {
        AssocStatus status = FileAssociationRegistry.query(row.extension);
        row.status = status;
        boolean associated = status.state() != AssocState.NOT_ASSOCIATED;
        // The proposal only ever adds: an extension already associated stays
        // checked, and one the registry doesn't know is checked iff curated.
        row.selected.set(associated
                || (proposeCurated && FileAssociationCatalog.curated(row.kind).contains(row.extension)));
        row.description.set(withStatusSuffix(row.extension, status));
    }

    private static String withStatusSuffix(String extension, AssocStatus status) {
        String base = FileAssociationCatalog.formatDescription(extension);
        return switch (status.state()) {
            case DEFAULT -> base + " — Default";
            case AVAILABLE_NOT_DEFAULT -> base + " — Available (not default)";
            case NOT_ASSOCIATED -> base;
        };
    }

    private void updateOpenDefaultAppsVisibility() {
        boolean any = rowsByKind.values().stream()
                .flatMap(List::stream)
                .anyMatch(r -> r.status.state() == AssocState.AVAILABLE_NOT_DEFAULT);
        openDefaultAppsButton.setVisible(any);
        openDefaultAppsButton.setManaged(any);
    }

    private void confirm() {
        Path exe = packagedExe.orElse(null);
        if (exe == null) return;
        int associated = 0;
        int removed = 0;
        List<String> failures = new ArrayList<>();
        for (MediaKind kind : MediaKind.values()) {
            // A tab the user never opened was never queried — its rows are
            // still the built-in unchecked placeholder, not a reviewed
            // baseline, so skip it entirely rather than "confirm" nothing
            // the user actually looked at.
            if (!loadedKinds.getOrDefault(kind, false)) continue;
            for (Row row : rowsByKind.get(kind)) {
                boolean wasAssociated = row.status.state() != AssocState.NOT_ASSOCIATED;
                boolean nowChecked = row.selected.get();
                if (wasAssociated == nowChecked) continue;
                try {
                    if (nowChecked) {
                        FileAssociationRegistry.associate(row.extension, row.kind, exe);
                        associated++;
                    } else {
                        FileAssociationRegistry.unassociate(row.extension);
                        removed++;
                    }
                } catch (RegistryException | IllegalStateException e) {
                    failures.add("." + row.extension + " — " + rootMessage(e));
                }
            }
        }
        for (MediaKind kind : MediaKind.values()) {
            if (loadedKinds.getOrDefault(kind, false)) {
                loadedKinds.put(kind, false);
                refreshKind(kind);
            }
        }
        showSummary(associated, removed, failures);
    }

    private void showSummary(int associated, int removed, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Associated ").append(associated).append(", removed ").append(removed).append('.');
        if (!failures.isEmpty()) {
            sb.append(' ').append(failures.size()).append(" failed: ").append(String.join(", ", failures));
        }
        feedback.setText(sb.toString());
        feedback.setVisible(true);
        feedback.setManaged(true);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /** Show the dialog modally, blocking until it is closed. */
    public void showAndWait() {
        stage.showAndWait();
    }
}
