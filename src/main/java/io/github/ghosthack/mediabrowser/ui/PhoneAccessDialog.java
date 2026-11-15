package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.web.QrCodes;
import io.github.ghosthack.mediabrowser.web.WebAddresses;
import io.github.ghosthack.mediabrowser.web.WebMode;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * File ▸ Phone Access… (and the button on Settings ▸ Web): how a phone gets to
 * web mode. Shows the phone link {@code http://<lan-ip>:<port>/#k=<passkey>}
 * as a QR code and as text, the passkey itself for typing, the other
 * addresses this machine has, and a New Passkey button that signs every phone
 * out. Says plainly when web mode is off, still starting, failed to start, or
 * waiting for a restart. See docs/web-mode.md.
 */
public final class PhoneAccessDialog {

    /** Pixels per QR module: a ~45-module code comes out about 270 px square. */
    static final int QR_SCALE = 6;

    private final Stage stage = new Stage();
    private final WebMode web;
    private final Label status = new Label();
    private final Label restartNote = new Label(
            "Settings changed: restart Media Browser to apply web mode on/off or the port.");
    private final ImageView qr = new ImageView();
    private final TextField link = new TextField();
    private final Label passkey = new Label();
    private final ComboBox<WebAddresses.Candidate> addresses = new ComboBox<>();
    private final Label feedback = new Label();
    private final Timeline poll;

    public PhoneAccessDialog(Window owner, WebMode web, Path root) {
        this.web = web;
        poll = new Timeline(new KeyFrame(Duration.millis(400), e -> refresh()));
        poll.setCycleCount(Timeline.INDEFINITE);
        buildUi(owner, root);
        refresh();
        CompletableFuture.supplyAsync(WebAddresses::discover)
                .thenAccept(found -> Platform.runLater(() -> showAddresses(found)));
    }

    private void buildUi(Window owner, Path root) {
        var header = new Label("Phone Access");
        header.getStyleClass().add("move-header");
        var intro = new Label("Scan the code with a phone on the same network, or open the link "
                + "and type the passkey. It shows " + root + " as a photo grid.");
        intro.setWrapText(true);

        status.setWrapText(true);
        restartNote.getStyleClass().add("move-note");
        restartNote.setWrapText(true);

        qr.setSmooth(false);
        var qrBox = new HBox(qr);
        qrBox.setAlignment(Pos.CENTER);

        link.setEditable(false);
        link.setFocusTraversable(false);
        HBox.setHgrow(link, Priority.ALWAYS);
        var copy = new Button("Copy Link");
        copy.setOnAction(e -> {
            var content = new ClipboardContent();
            content.putString(link.getText());
            Clipboard.getSystemClipboard().setContent(content);
            showFeedback("Link copied.");
        });
        var linkRow = new HBox(8, link, copy);
        linkRow.setAlignment(Pos.CENTER_LEFT);

        addresses.setConverter(new StringConverter<>() {
            @Override public String toString(WebAddresses.Candidate c) {
                return c == null ? "" : c.address() + "  (" + c.interfaceName() + ")";
            }
            @Override public WebAddresses.Candidate fromString(String s) {
                return null;
            }
        });
        addresses.valueProperty().addListener((o, was, now) -> refresh());
        var addressRow = new HBox(8, new Label("Address:"), addresses);
        addressRow.setAlignment(Pos.CENTER_LEFT);

        passkey.setFont(Font.font("Monospaced", 22));
        var rotate = new Button("New Passkey");
        rotate.setOnAction(e -> {
            web.rotatePasskey();
            refresh();
            showFeedback("New passkey: every phone must enter it again.");
        });
        var passkeyRow = new HBox(12, new Label("Passkey:"), passkey, rotate);
        passkeyRow.setAlignment(Pos.CENTER_LEFT);

        var note = new Label("Plain HTTP for a home network: anyone on it who has the passkey "
                + "can browse this folder and rotate its pictures. Originals never leave this "
                + "computer; the phone gets re-encoded JPEGs.");
        note.getStyleClass().add("move-note");
        note.setWrapText(true);

        feedback.getStyleClass().add("move-note");
        feedback.setWrapText(true);
        feedback.setVisible(false);
        feedback.setManaged(false);

        var close = new Button("Close");
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var buttons = new HBox(8, spacer, close);

        var rootBox = new VBox(10, header, intro, status, restartNote, qrBox, addressRow, linkRow,
                passkeyRow, note, feedback, buttons);
        rootBox.getStyleClass().add("move-dialog");
        rootBox.setPadding(new Insets(16));

        Scene scene = new Scene(rootBox, 520, 640);
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
        stage.setTitle("Phone Access");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setOnHidden(e -> poll.stop());
    }

    private void showAddresses(List<WebAddresses.Candidate> found) {
        addresses.getItems().setAll(found);
        if (!found.isEmpty()) addresses.setValue(found.getFirst());
        addresses.setDisable(found.size() < 2);
        refresh();
    }

    /** Re-reads web mode's state and redraws the link, code and passkey. */
    private void refresh() {
        WebMode.State state = web.state();
        status.setText(switch (state) {
            case OFF -> "Web mode is off. Turn it on in Settings ▸ Web, then restart "
                    + "Media Browser.";
            case STARTING -> "Starting…";
            case RUNNING -> "Serving on port " + web.port() + ", every network interface.";
            case FAILED -> "Web mode failed to start: " + web.failure();
        });
        status.setStyle(state == WebMode.State.FAILED ? "-fx-text-fill: #d9534f;" : "");
        boolean restart = web.restartPending();
        restartNote.setVisible(restart);
        restartNote.setManaged(restart);
        if (state == WebMode.State.STARTING) poll.play(); else poll.stop();

        String key = web.passkey();
        passkey.setText(key);
        WebAddresses.Candidate chosen = addresses.getValue();
        String host = chosen != null ? chosen.address() : "localhost";
        String url = WebAddresses.phoneLink(host, web.port(), key);
        link.setText(url);
        boolean live = state == WebMode.State.RUNNING || state == WebMode.State.STARTING;
        qr.setImage(live ? qrImage(url) : null);
        qr.setVisible(live);
        qr.setManaged(live);
    }

    static WritableImage qrImage(String text) {
        boolean[][] modules = QrCodes.modules(text);
        int size = modules.length * QR_SCALE;
        var image = new WritableImage(size, size);
        PixelWriter w = image.getPixelWriter();
        for (int y = 0; y < size; y++) {
            boolean[] row = modules[y / QR_SCALE];
            for (int x = 0; x < size; x++) {
                w.setArgb(x, y, row[x / QR_SCALE] ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return image;
    }

    private void showFeedback(String text) {
        feedback.setText(text);
        feedback.setVisible(true);
        feedback.setManaged(true);
    }

    // Read by tests, which build the dialog without showing it.
    String statusText() {
        return status.getText();
    }

    String linkText() {
        return link.getText();
    }

    String passkeyText() {
        return passkey.getText();
    }

    boolean qrShown() {
        return qr.isVisible() && qr.getImage() != null;
    }

    void pressNewPasskey() {
        web.rotatePasskey();
        refresh();
    }

    /** Show the dialog modally, blocking until it is closed. */
    public void showAndWait() {
        stage.showAndWait();
    }
}
