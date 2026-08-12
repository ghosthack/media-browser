package io.github.ghosthack.mediabrowser.ui;

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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Modal album picker used by File ▸ Open Album…. */
public final class OpenAlbumDialog {

    private final Stage stage = new Stage();
    private final ListView<Album> list = new ListView<>();
    private Album chosen;

    public OpenAlbumDialog(Window owner, AlbumStore store) {
        var albums = store.albums();
        Map<Album, Integer> counts = new HashMap<>();
        for (Album album : albums) counts.put(album, store.count(album));

        list.getItems().setAll(albums);
        list.setPlaceholder(new Label("No albums yet"));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Album album, boolean empty) {
                super.updateItem(album, empty);
                if (empty || album == null) {
                    setText(null);
                } else {
                    int count = counts.getOrDefault(album, 0);
                    setText(album.name() + "  (" + count + " item"
                            + (count == 1 ? "" : "s") + ")");
                }
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        var openButton = new Button("Open");
        openButton.setDefaultButton(true);
        openButton.disableProperty().bind(
                list.getSelectionModel().selectedItemProperty().isNull());
        openButton.setOnAction(e -> choose());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2
                    && list.getSelectionModel().getSelectedItem() != null) {
                choose();
            }
        });

        var cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var buttons = new HBox(8, spacer, openButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        var header = new Label("Open an album as a flat media listing");
        header.getStyleClass().add("move-header");
        var root = new VBox(10, header, list, buttons);
        root.getStyleClass().add("move-dialog");
        root.setPadding(new Insets(16));

        var scene = new Scene(root, 440, 480);
        var css = MoveDialog.class.getResource("move-dialog.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        ThemeManager.get().register(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                stage.close();
            }
        });

        stage.setScene(scene);
        stage.setTitle("Open Album");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        if (!albums.isEmpty()) list.getSelectionModel().selectFirst();
    }

    private void choose() {
        chosen = list.getSelectionModel().getSelectedItem();
        if (chosen != null) stage.close();
    }

    /** Shows the picker and returns the selected album, or empty on cancellation. */
    public Optional<Album> showAndWait() {
        stage.showAndWait();
        return Optional.ofNullable(chosen);
    }
}
