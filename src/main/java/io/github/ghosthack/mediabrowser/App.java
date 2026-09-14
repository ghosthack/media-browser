package io.github.ghosthack.mediabrowser;

import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.MediaBackend;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.ffm.HwDecode;
import io.github.ghosthack.mediabrowser.media.move.ActionLogFile;
import io.github.ghosthack.mediabrowser.ui.ActionLog;
import io.github.ghosthack.mediabrowser.ui.AppShell;
import io.github.ghosthack.mediabrowser.ui.FileAssociationsDialog;
import io.github.ghosthack.mediabrowser.ui.MainWindow;
import io.github.ghosthack.mediabrowser.ui.ThemeManager;
import io.github.ghosthack.mediabrowser.ui.ViewerWindow;
import io.github.ghosthack.mediabrowser.ui.icon.IconPackManager;
import io.github.ghosthack.mediabrowser.win.LaunchInvocation;
import io.github.ghosthack.mediabrowser.win.SingleInstanceGuard;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Media Browser entry point. Optional first program argument: either the
 * directory to open initially (defaults to the user home) or a specific
 * media file, which opens straight into the viewer for it — the Windows
 * file-association / "Open with" entry point. A file argument also triggers
 * {@link SingleInstanceGuard}: if the app is already running, the path is
 * forwarded to it and this process exits without ever opening a window.
 */
public final class App extends Application {

    /** Friendly name shown in the macOS application menu. */
    static final String APP_NAME = "Media Browser";

    /** Set in {@link #main} before {@code launch()}, when a file argument acquired the single-instance lock. */
    private static SingleInstanceGuard instanceGuard;

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
        ThemeManager.get().setBorderlessButtons(!settings.buttonBorders());
        // Likewise seed icon artwork before controls and virtualized cells are
        // constructed, so the first frame uses the persisted pack.
        IconPackManager.get().setCurrent(settings.iconPack());
        // Playback decode policy (auto/software/hardware) for the FFM
        // backends — applied before the backend binds so the first playback
        // session already honours it.
        HwDecode.configure(settings.decodeDevice());
        // Bridge the media layer's telemetry seam to the app-wide mosaic
        // counters before the service exists, so -Dmosaic.telemetry reports
        // include the thumbnail pipeline's off-FX stages from the start.
        MosaicTelemetry.installMediaSink();
        // Still-image color policy — before the backend binds, so the very
        // first decode (thumbnails included) already honours the persisted
        // mode/gamut defaults.
        io.github.ghosthack.mediabrowser.media.color.ColorPolicy.set(
                settings.viewerColorMode(), settings.viewerColorGamut());
        String configured = settings.mediaBackend();
        try {
            // fromSettings throws for a recognized-but-unavailable selection
            // (never a silent substitute), so it sits inside the check too.
            var backend = MediaBackend.fromSettings(configured);
            service = new MediaService(backend.label(), backend.create(),
                    settings.thumbnailMemoryBudgetBytes());
        } catch (Throwable t) {
            // Startup backend check: the backend binds only at startup, so a
            // selection that cannot run here — unavailable on this platform/
            // build (fromSettings throws) or failing to initialize (native
            // link failure, corrupt extraction, ...) — is handled here:
            // details to the console, the persisted setting replaced with the
            // pure-Java backend (no natives, cannot link-fail), and one short
            // alert saying so.
            var fallback = MediaBackend.TWELVEMONKEYS_JCODEC;
            System.err.println("[App] media backend '" + configured
                    + "' failed to initialize; replacing the media.backend setting with '"
                    + fallback.settingsValue() + "'");
            t.printStackTrace();
            settings.setMediaBackend(fallback.settingsValue());
            try {
                settings.save();
            } catch (IOException e) {
                System.err.println("[App] could not persist the replacement backend: " + e);
            }
            try {
                service = new MediaService(fallback.label(), fallback.create(),
                        settings.thumbnailMemoryBudgetBytes());
            } catch (Throwable t2) {
                t2.printStackTrace();
                var fatal = new Alert(Alert.AlertType.ERROR);
                fatal.setTitle("Media Browser");
                fatal.setHeaderText("The pure-Java fallback engine failed too");
                fatal.setContentText(String.valueOf(t2));
                fatal.showAndWait();
                Platform.exit();
                return;
            }
            var alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Media Browser");
            alert.setHeaderText("Media engine replaced");
            alert.setContentText("The '" + configured + "' media engine could not be"
                    + " initialized, so the setting was changed to the pure-Java '"
                    + fallback.settingsValue() + "' engine."
                    + "\nSee the console log for details."
                    + "\nPick a different engine in Preferences \u25b8 Media engine.");
            alert.showAndWait();
        }

        // One shared rotation store backs all views (mirrors how the single
        // MediaService is shared), so a rotate in any is seen by the others.
        // Its bound caps how many read-only directories may keep adjustments in
        // memory for the session when their sidecar cannot be written.
        var rotationStore = new RotationStore(settings.adjustmentsVolatileMaxDirs());
        // Likewise one shared AAE edit store: resolves an image to its Apple
        // Photos .AAE edit (crop/straighten), composed above the decoders.
        var aaeStore = new AaeStore();
        service.setAaeStore(aaeStore);
        // The window shell hosting the browser, mosaic and viewer views: one
        // window whose root swaps between them, or the classic separate
        // windows, per the persisted window-mode setting (see AppShell).
        var shell = AppShell.create(primaryStage, settings);
        var viewer = new ViewerWindow(shell, service, settings, rotationStore, aaeStore);
        var main = new MainWindow(shell, service, viewer, settings, rotationStore, aaeStore);
        shell.register(AppShell.AppView.BROWSER, main);
        shell.register(AppShell.AppView.MOSAIC, main.mosaic());
        shell.register(AppShell.AppView.VIEWER, viewer);

        Path argPath = launchArgPath();
        AppShell.AppView initial = (argPath != null && Files.isRegularFile(argPath))
                ? main.openInitialFile(argPath)
                : main.openInitial(startDir(argPath));
        shell.start(initial);
        maybePromptFileAssociations(shell, settings);

        // A file argument that forwarded to an already-running instance never
        // reaches here (main() returns before launch()); this instance is
        // either the one that just acquired the lock, or an ordinary launch
        // that never touched the guard at all.
        if (instanceGuard != null) {
            instanceGuard.onFileReceived(forwarded -> Platform.runLater(() -> {
                Path abs = forwarded.toAbsolutePath().normalize();
                if (Files.isRegularFile(abs)) {
                    main.openInitialFile(abs);
                } else if (Files.isDirectory(abs)) {
                    main.openInitial(abs);
                } else {
                    return;
                }
                Stage front = shell.stageFor(AppShell.AppView.BROWSER);
                if (front != null) {
                    front.setIconified(false);
                    front.toFront();
                    front.requestFocus();
                }
            }));
        }
        // Retitle the macOS app menu (built during toolkit startup) off the
        // class-name default once the menu exists. Guard on the OS here so the
        // mac-only class (which loads libobjc at init) is never touched elsewhere.
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            Platform.runLater(() ->
                    MacApplicationMenu.setApplicationName(App.class.getName(), APP_NAME));
        }
    }

    /**
     * Windows, packaged builds, first launch only: proposes the curated file
     * associations in the File Associations dialog once the browser stage is
     * actually on screen (the shell may defer its reveal). The installer
     * registers nothing itself — see {@link FileAssociationsDialog} for why —
     * so this is where a fresh install gets its "open with Media Browser"
     * offer. Recorded as prompted before the dialog opens, whatever the user
     * decides, so it can never nag; File ▸ File Associations… stays the way
     * back in. A dev run is never prompted: there is no stable executable to
     * register, and the dialog would only show its disabled state.
     */
    private static void maybePromptFileAssociations(AppShell shell, AppSettings settings) {
        if (!io.github.ghosthack.panama.media.core.Platform.IS_WINDOWS) return;
        if (settings.fileAssociationsPrompted()) return;
        Path exe = LaunchInvocation.resolvePackagedExe().orElse(null);
        if (exe == null) return;
        Stage front = shell.stageFor(AppShell.AppView.BROWSER);
        if (front == null) return;
        Runnable prompt = () -> {
            // One stderr line so a redirected launch can prove the prompt fired
            // (a Windows GUI-subsystem exe shows nothing on a console otherwise).
            System.err.println("[App] first-launch file-associations prompt: " + exe);
            settings.setFileAssociationsPrompted(true);
            try {
                settings.save();
            } catch (IOException e) {
                System.err.println("[App] could not persist the file-associations prompt flag: " + e);
            }
            FileAssociationsDialog.firstRunProposal(front).showAndWait();
        };
        if (front.isShowing()) {
            Platform.runLater(prompt);
        } else {
            front.showingProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Boolean> obs,
                                    Boolean was, Boolean showing) {
                    if (!showing) return;
                    front.showingProperty().removeListener(this);
                    Platform.runLater(prompt);
                }
            });
        }
    }

    @Override
    public void stop() {
        if (service != null) service.close();
        if (instanceGuard != null) instanceGuard.close();
    }

    /** The launch argument, normalized — a directory, a file, or {@code null} if none was given. */
    private Path launchArgPath() {
        var args = getParameters().getRaw();
        if (args.isEmpty()) return null;
        return Path.of(args.getFirst()).toAbsolutePath().normalize();
    }

    /** {@code argPath} itself if it's a directory, else the user home. */
    private static Path startDir(Path argPath) {
        if (argPath != null && Files.isDirectory(argPath)) return argPath;
        return Path.of(System.getProperty("user.home"));
    }

    public static void main(String[] args) {
        // Scoped to a *file* argument only — the file-association entry
        // point. A directory argument (the documented "-Djavafx.args=/dir"
        // usage, including opening several directories in separate windows)
        // must keep opening its own window, not get forwarded and dropped.
        String rawArg = args.length > 0 ? args[0] : null;
        boolean isFileArg = rawArg != null
                && Files.isRegularFile(Path.of(rawArg).toAbsolutePath().normalize());
        if (isFileArg) {
            var guard = SingleInstanceGuard.acquireOrForward(rawArg);
            if (guard.isEmpty()) return; // forwarded to the already-running instance
            instanceGuard = guard.get();
        }
        launch(args);
    }
}
