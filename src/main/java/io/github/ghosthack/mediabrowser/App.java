package io.github.ghosthack.mediabrowser;

import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.MediaBackend;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.move.ActionLogFile;
import io.github.ghosthack.mediabrowser.ui.ActionLog;
import io.github.ghosthack.mediabrowser.ui.AppShell;
import io.github.ghosthack.mediabrowser.ui.MainWindow;
import io.github.ghosthack.mediabrowser.ui.ThemeManager;
import io.github.ghosthack.mediabrowser.ui.ViewerWindow;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Media Browser entry point. Optional first program argument: the directory
 * to open initially (defaults to the user home).
 */
public final class App extends Application {

    /** Friendly name shown in the macOS application menu. */
    static final String APP_NAME = "Media Browser";

    private MediaService service;

    @Override
    public void start(Stage primaryStage) {
        var settings = AppSettings.load();
        // Hook the session action log up to its optional on-disk JSONL mirror
        // (and, when enabled, seed the last few persisted entries back in) —
        // before any window exists, so no recorded move can miss the file.
        ActionLog.get().attachFile(settings, new ActionLogFile());
        // Seed the current theme before any window builds its scene, so each
        // registers and is themed from the first frame.
        ThemeManager.get().setCurrent(settings.theme());
        var backend = MediaBackend.fromSettings(settings.mediaBackend());
        try {
            service = new MediaService(backend.create(),
                    settings.thumbnailMemoryBudgetBytes());
        } catch (Throwable t) {
            var alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Media Browser");
            alert.setHeaderText("Cannot load the native media libraries (" + backend + " backend)");
            alert.setContentText(String.valueOf(t));
            alert.showAndWait();
            Platform.exit();
            return;
        }

        // One shared rotation store backs all views (mirrors how the single
        // MediaService is shared), so a rotate in any is seen by the others.
        var rotationStore = new RotationStore();
        // Likewise one shared AAE edit store: resolves an image to its Apple
        // Photos .AAE edit (crop/straighten), composed above the decoders.
        var aaeStore = new AaeStore();
        // The window shell hosting the browser, mosaic and viewer views: one
        // window whose root swaps between them, or the classic separate
        // windows, per the persisted window-mode setting (see AppShell).
        var shell = AppShell.create(primaryStage, settings);
        var viewer = new ViewerWindow(shell, service, settings, rotationStore, aaeStore);
        var main = new MainWindow(shell, service, viewer, settings, rotationStore, aaeStore);
        shell.register(AppShell.AppView.BROWSER, main);
        shell.register(AppShell.AppView.MOSAIC, main.mosaic());
        shell.register(AppShell.AppView.VIEWER, viewer);
        shell.start(main.openInitial(startDir()));
        // Retitle the macOS app menu (built during toolkit startup) off the
        // class-name default once the menu exists. Guard on the OS here so the
        // mac-only class (which loads libobjc at init) is never touched elsewhere.
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            Platform.runLater(() ->
                    MacApplicationMenu.setApplicationName(App.class.getName(), APP_NAME));
        }
    }

    @Override
    public void stop() {
        if (service != null) service.close();
    }

    private Path startDir() {
        var args = getParameters().getRaw();
        if (!args.isEmpty()) {
            Path dir = Path.of(args.getFirst()).toAbsolutePath().normalize();
            if (Files.isDirectory(dir)) return dir;
        }
        return Path.of(System.getProperty("user.home"));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
