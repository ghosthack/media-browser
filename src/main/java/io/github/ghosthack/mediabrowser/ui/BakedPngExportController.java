package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Window-independent driver for exporting one fully rendered still as PNG. */
public final class BakedPngExportController {

    /** Window-specific selection, retained-frame, refresh, and notification seam. */
    public interface Host {
        Stage owner();
        Path currentStill();

        /**
         * The already-composed full-resolution frame, when the host has one.
         * The viewer uses this to preserve its visible original/enhanced choice.
         */
        default RasterFrame currentRenderedFrame(Path source) { return null; }

        void refreshAfterExport(Path output);
        void showStatus(String message);
    }

    private static boolean exportInFlight;

    private final MediaService service;
    private final RotationStore rotationStore;
    private final AaeStore aaeStore;
    private final Host host;

    public BakedPngExportController(MediaService service, RotationStore rotationStore,
                                    AaeStore aaeStore, Host host) {
        this.service = service;
        this.rotationStore = rotationStore;
        this.aaeStore = aaeStore;
        this.host = host;
    }

    /** Chooses an output and asynchronously exports the current still. */
    public void exportCurrent() {
        if (exportInFlight) {
            host.showStatus("A baked PNG export is already running.");
            return;
        }

        Path source = host.currentStill();
        if (source == null) {
            host.showStatus("Select one still image to export as PNG.");
            return;
        }

        Path output = chooseOutput(source);
        if (output == null) return;
        if (samePath(source, output)) {
            host.showStatus("Choose a different file; export never replaces the original.");
            return;
        }

        RasterFrame retained = host.currentRenderedFrame(source);
        CompletableFuture<RasterFrame> rendered = retained == null
                ? decodeAndCompose(source)
                : CompletableFuture.completedFuture(retained);

        exportInFlight = true;
        host.showStatus("Exporting " + output.getFileName() + "…");
        rendered.thenCompose(frame -> service.fileOp(() -> {
            try {
                BakedPngExporter.write(frame, output);
                return output;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        })).whenComplete((written, error) -> Platform.runLater(() -> finish(written, error)));
    }

    private CompletableFuture<RasterFrame> decodeAndCompose(Path source) {
        return service.loadVisual(source).thenApply(result -> {
            if (result.probe().kind() != MediaKind.IMAGE || result.frame().isEmpty()) {
                throw new IllegalArgumentException("Baked PNG export supports single-frame still images only");
            }
            RasterFrame upright = RasterFrames.applyAae(result.frame().get(),
                    aaeStore.forImage(source).orElse(null));
            return RasterFrames.apply(upright, rotationStore.adjustments(source));
        });
    }

    private Path chooseOutput(Path source) {
        var chooser = new FileChooser();
        chooser.setTitle("Export Baked PNG");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG image (*.png)", "*.png"));
        chooser.setInitialFileName(BakedPngExporter.suggestedFileName(source));

        if (!ArchivePaths.inArchive(source)) {
            Path parent = source.toAbsolutePath().normalize().getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try {
                    chooser.setInitialDirectory(parent.toFile());
                } catch (UnsupportedOperationException ignored) {
                    // A non-default filesystem cannot seed a native FileChooser.
                }
            }
        }

        File selected = chooser.showSaveDialog(host.owner());
        return selected == null ? null : selected.toPath();
    }

    private void finish(Path output, Throwable error) {
        exportInFlight = false;
        if (error != null) {
            host.showStatus("Could not export baked PNG: " + usefulMessage(error));
            return;
        }
        host.refreshAfterExport(output);
        host.showStatus("Exported baked PNG: " + output.getFileName());
    }

    private static boolean samePath(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException | RuntimeException e) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    private static String usefulMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null
                && (cause.getMessage() == null || cause.getMessage().isBlank())) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }
}
