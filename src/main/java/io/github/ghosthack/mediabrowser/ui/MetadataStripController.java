package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.metadatastripper.MetadataStripper;
import io.github.ghosthack.metadatastripper.StripResult;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Window-independent driver for exporting zero-metadata sibling copies.
 * Container rewriting is a filesystem transformation, deliberately outside
 * {@code MediaFacade}: its behavior must not vary with the selected decoder.
 */
public final class MetadataStripController {

    /** Window-specific selection, refresh, and notification seam. */
    public interface Host {
        Stage owner();
        MoveController.Selection currentSelection();
        void refreshAfterStrip(Path firstOutput);
        void showStatus(String message);
    }

    private record Failure(Path path, String message) {}
    private record Batch(List<StripResult> successes, List<Failure> failures) {
        Batch {
            successes = List.copyOf(successes);
            failures = List.copyOf(failures);
        }
    }

    private static boolean stripInFlight;

    private final MediaService service;
    private final Host host;
    private final MetadataStripper stripper = new MetadataStripper();

    public MetadataStripController(MediaService service, Host host) {
        this.service = service;
        this.host = host;
    }

    /** Confirms and asynchronously strips the current selection into sibling copies. */
    public void createCopies() {
        if (stripInFlight) {
            host.showStatus("A metadata-stripping operation is already running.");
            return;
        }

        MoveController.Selection selection = host.currentSelection();
        if (selection.sources().isEmpty()) {
            host.showStatus(selection.parentExcluded()
                    ? "Only the parent folder \"..\" is selected; nothing to strip."
                    : "No files selected to strip metadata from.");
            return;
        }
        if (!confirm(selection.sources().size())) return;

        stripInFlight = true;
        host.showStatus("Exporting zero-metadata "
                + (selection.sources().size() == 1 ? "copy\u2026" : "copies\u2026"));
        service.fileOp(() -> stripAll(selection.sources()))
                .whenComplete((batch, error) -> Platform.runLater(() -> finish(batch, error)));
    }

    private boolean confirm(int count) {
        String subject = count == 1 ? "this item" : count + " selected items";
        var create = new ButtonType(count == 1 ? "Create Copy" : "Create Copies",
                ButtonBar.ButtonData.OK_DONE);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, "", create, ButtonType.CANCEL);
        alert.initOwner(host.owner());
        alert.setTitle("Export Zero-Metadata Copy");
        alert.setHeaderText("Export zero-metadata copies of " + subject + "?");
        alert.setContentText("New _0m copies will be written beside the originals; originals and "
                + "existing outputs will not be replaced.\n\n"
                + "Encoded media is not recompressed, but removing EXIF orientation or an "
                + "embedded color profile can change how an image appears.");
        return alert.showAndWait().filter(create::equals).isPresent();
    }

    private Batch stripAll(List<Path> sources) {
        List<StripResult> successes = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (Path source : sources) {
            if (ArchivePaths.inArchive(source)) {
                failures.add(new Failure(source, "Files inside archives are read-only; extract first"));
                continue;
            }
            try {
                successes.add(stripper.stripDetailed(source));
            } catch (IOException | RuntimeException e) {
                failures.add(new Failure(source, usefulMessage(e)));
            }
        }
        return new Batch(successes, failures);
    }

    private void finish(Batch batch, Throwable error) {
        stripInFlight = false;
        if (error != null) {
            host.showStatus("Could not strip metadata: " + usefulMessage(error));
            showFailures(List.of(new Failure(Path.of("metadata stripping"), usefulMessage(error))));
            return;
        }

        if (!batch.successes().isEmpty()) {
            host.refreshAfterStrip(batch.successes().getFirst().output());
        }

        long removed = batch.successes().stream().mapToLong(StripResult::removedBytes).sum();
        int made = batch.successes().size();
        int failed = batch.failures().size();
        String status;
        if (made == 1 && failed == 0) {
            StripResult result = batch.successes().getFirst();
            status = "Exported " + result.output().getFileName()
                    + (result.removedBytes() == 0
                            ? " (no removable bytes found)"
                            : " \u2014 removed " + formatBytes(result.removedBytes()));
        } else if (made == 0) {
            status = "No zero-metadata copies were exported; " + failed + " failed";
        } else {
            status = "Exported " + made + " zero-metadata "
                    + (made == 1 ? "copy" : "copies");
            if (removed > 0) status += " \u2014 removed " + formatBytes(removed);
            if (failed > 0) status += "; " + failed + " failed";
        }
        host.showStatus(status + ".");
        if (failed > 0) showFailures(batch.failures());
    }

    private void showFailures(List<Failure> failures) {
        StringBuilder details = new StringBuilder();
        int shown = Math.min(failures.size(), 8);
        for (int i = 0; i < shown; i++) {
            Failure failure = failures.get(i);
            Path name = failure.path().getFileName();
            details.append(name == null ? failure.path() : name)
                    .append(": ").append(failure.message()).append('\n');
        }
        if (failures.size() > shown) {
            details.append("\u2026and ").append(failures.size() - shown).append(" more");
        }
        var alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(host.owner());
        alert.setTitle("Metadata Stripping");
        alert.setHeaderText(failures.size() == 1
                ? "One item could not be processed"
                : failures.size() + " items could not be processed");
        alert.setContentText(details.toString().stripTrailing());
        alert.show();
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

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + (bytes == 1 ? " byte" : " bytes");
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, value >= 10 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }
}
