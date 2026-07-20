package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.album.Album;
import io.github.ghosthack.mediabrowser.album.AlbumStore;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Albums…" dialog — a modal list of every album with the controls to add
 * the current selection to one or to create a new (next-numbered) album. Opened
 * from the {@link AlbumMenu}; when launched with an empty selection it is a
 * pure manager (the Add button stays disabled, so you can still browse and
 * create albums).
 */
public final class AlbumsDialog {

    private final Stage stage = new Stage();
    private final AlbumStore store;
    private final AppSettings settings;
    private final List<Path> selection;
    private final Consumer<String> status;

    private final ListView<Album> list = new ListView<>();
    private final Label feedback = new Label();
    private final Button addButton = new Button("Add to Album");

    public AlbumsDialog(Window owner, AlbumStore store, AppSettings settings,
                        List<Path> selection, Consumer<String> status) {
        this.store = store;
        this.settings = settings;
        this.selection = List.copyOf(selection);
        this.status = status;
        buildUi(owner);
        reload(null);
    }

    private void buildUi(Window owner) {
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Album album, boolean empty) {
                super.updateItem(album, empty);
                if (empty || album == null) {
                    setText(null);
                } else {
                    int count = store.count(album);
                    setText(album.name() + "  (" + count + " item"
                            + (count == 1 ? "" : "s") + ")");
                }
            }
        });
        list.setPlaceholder(new Label("No albums yet — create one"));
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                addSelection();
            }
        });
        list.getSelectionModel().selectedItemProperty()
                .addListener((o, was, now) -> updateButtons());

        String headerText = selection.isEmpty()
                ? "Albums"
                : "Add " + selection.size() + " item" + (selection.size() == 1 ? "" : "s")
                        + " to an album";
        Label header = new Label(headerText);
        header.getStyleClass().add("move-header");

        feedback.getStyleClass().add("move-note");
        feedback.setWrapText(true);
        feedback.setVisible(false);
        feedback.setManaged(false);

        var newButton = new Button("New Album");
        newButton.setOnAction(e -> createAlbum());
        addButton.setDefaultButton(true);
        addButton.setOnAction(e -> addSelection());
        var closeButton = new Button("Close");
        closeButton.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, newButton, spacer, addButton, closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, header, list, feedback, buttons);
        root.getStyleClass().add("move-dialog");
        root.setPadding(new Insets(16));

        Scene scene = new Scene(root, 420, 460);
        var css = MoveDialog.class.getResource("move-dialog.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        ThemeManager.get().register(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                stage.close();
            }
        });

        stage.setScene(scene);
        stage.setTitle("Albums");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        updateButtons();
    }

    private void updateButtons() {
        addButton.setDisable(selection.isEmpty()
                || list.getSelectionModel().getSelectedItem() == null);
    }

    /** Re-lists the albums, optionally re-selecting one by its backing file. */
    private void reload(Album select) {
        List<Album> albums = store.albums();
        list.getItems().setAll(albums);
        if (select != null) {
            for (Album album : albums) {
                if (album.fileName().equals(select.fileName())) {
                    list.getSelectionModel().select(album);
                    break;
                }
            }
        }
        updateButtons();
    }

    private void createAlbum() {
        try {
            Album album = store.createAlbum();
            reload(album);
            list.scrollTo(list.getSelectionModel().getSelectedIndex());
            showFeedback("Created " + album.name());
        } catch (IOException | UncheckedIOException e) {
            showFeedback("Cannot create album: " + message(e));
        }
    }

    private void addSelection() {
        Album album = list.getSelectionModel().getSelectedItem();
        if (album == null || selection.isEmpty()) {
            return;
        }
        try {
            AlbumStore.AddResult result = store.addPaths(album, selection);
            settings.recordAlbumUse(album.fileName());
            try {
                settings.save();
            } catch (IOException ignored) {
                // Recency is a convenience; don't fail the add over it.
            }
            reload(album);
            String message = AlbumMenu.addMessage(album, result);
            showFeedback(message);
            status.accept(message);
        } catch (IOException | UncheckedIOException e) {
            showFeedback("Cannot add to " + album.name() + ": " + message(e));
        }
    }

    private void showFeedback(String text) {
        feedback.setText(text);
        feedback.setVisible(true);
        feedback.setManaged(true);
    }

    /** Show the dialog modally, blocking until it is closed. */
    public void showAndWait() {
        stage.showAndWait();
    }

    private static String message(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
