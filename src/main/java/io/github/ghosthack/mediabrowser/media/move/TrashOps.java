package io.github.ghosthack.mediabrowser.media.move;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cross-platform moves into the desktop environment's Trash / Recycle Bin.
 * The JDK delegates the actual operation to the host OS; this class adds
 * capability detection and a deterministic per-source result for bulk UI
 * operations.
 */
public final class TrashOps {

    private TrashOps() {}

    /** One source the desktop environment did not accept, with a user-facing reason. */
    public record Failure(Path path, String reason) {}

    /** The successful and failed portions of one bulk move-to-trash request. */
    public record Result(List<Path> moved, List<Failure> failures) {
        public Result {
            moved = List.copyOf(moved);
            failures = List.copyOf(failures);
        }
    }

    /** Injectable seam used by the pure bulk-result tests. */
    @FunctionalInterface
    interface Mover {
        boolean move(Path path);
    }

    /** Whether this graphical desktop advertises a native trash operation. */
    public static boolean isSupported() {
        try {
            return Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        } catch (UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    /**
     * Moves each source independently so one failure does not prevent the rest
     * of a multi-selection from reaching the Trash / Recycle Bin.
     *
     * <p>{@code onMoved} runs on the calling thread right after the OS accepts
     * each source and before the next one is attempted, so a caller can make
     * that item durable (the action log) while the rest of the batch is still
     * pending — a crash mid-batch then cannot lose the record of what already
     * reached the Trash. It must not throw: an exception escapes and ends the
     * batch.</p>
     */
    public static Result moveToTrash(List<Path> sources, Consumer<Path> onMoved) {
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            throw new UnsupportedOperationException("Move to Trash is not supported");
        }
        return moveToTrash(sources, path -> desktop.moveToTrash(path.toFile()), onMoved);
    }

    static Result moveToTrash(List<Path> sources, Mover mover, Consumer<Path> onMoved) {
        List<Path> moved = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (Path source : List.copyOf(sources)) {
            boolean accepted;
            try {
                accepted = mover.move(source);
                if (!accepted) {
                    failures.add(new Failure(source,
                            "the operating system declined the request"));
                }
            } catch (RuntimeException e) {
                failures.add(new Failure(source, message(e)));
                accepted = false;
            }
            if (accepted) {
                moved.add(source);
                // Outside the try: the item is in the Trash whatever the callback does.
                onMoved.accept(source);
            }
        }
        return new Result(moved, failures);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
