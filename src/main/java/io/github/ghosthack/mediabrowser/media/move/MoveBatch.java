package io.github.ghosthack.mediabrowser.media.move;

import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The filesystem half of a move batch, with no UI and no threading: every
 * source relocated in turn, each one written to the action log the moment it
 * lands and before the next is tried, and every failure kept per source. The
 * desktop's {@code MoveController} and web mode's move endpoints both run
 * their moves through here, so a move made from a phone is the same move, with
 * the same sidecar carrying, rollback and log line, as one made on the
 * desktop. Blocking: call it on the file-operation thread
 * ({@code MediaService.fileOp}), after the hosts released their handles.
 */
public final class MoveBatch {

    private MoveBatch() {}

    /** One source's relocation, returning the path it landed at. */
    @FunctionalInterface
    public interface Relocation {
        Path apply(Path source) throws IOException;
    }

    /**
     * One completed relocation: the source, its resolved on-disk result, and
     * the action-log entry already persisted for it (classified move-vs-rename
     * by whether the file left its directory).
     */
    public record Moved(Path source, Path finalPath, ActionLogEntry entry) {}

    /**
     * One source that did not move as asked: its text as given, its display
     * name and why. A stranded file ({@link StrandedMoveException}) is both a
     * failure and a {@link Moved}, logged where it now is.
     */
    public record Failure(String source, String name, String reason) {
        /** {@code "name: reason"}, the form the desktop's status and dialog show. */
        public String text() {
            return name + ": " + reason;
        }
    }

    /**
     * Outcome of a batch: what moved, what failed, or — when the target
     * directory could not be created — only why, with nothing tried.
     */
    public record Execution(List<Moved> moved, List<Failure> failures, String createError) {
        public Execution {
            moved = List.copyOf(moved);
            failures = List.copyOf(failures);
        }

        /** Each failure as {@code "name: reason"}. */
        public List<String> failureTexts() {
            return failures.stream().map(Failure::text).toList();
        }
    }

    /**
     * The move dialog's and quick-move's batch: optionally creates
     * {@code resolvedTarget}, then either renames the one source to
     * {@code renameTarget} or moves every source into {@code resolvedTarget}
     * with the {@code " (N)"} collision rename, carrying sidecars.
     *
     * @param journal durably logs one entry ({@code ActionLog.persist} in the app)
     */
    public static Execution execute(List<String> sources, String resolvedTarget, boolean rename,
                                    String renameTarget, boolean createDir,
                                    SidecarCarrier sidecars, Consumer<ActionLogEntry> journal) {
        // The archive-layer counterpart of releasing playback handles: a source
        // may be a container the user has browsed into (or a directory holding
        // one), whose mounted reader keeps the file itself open. Held until the
        // move is done, so an in-flight decode cannot re-mount it meanwhile.
        try (var held = ArchivePaths.releaseForMutation(
                sources.stream().map(Path::of).toList())) {
            if (createDir) {
                try {
                    FileOps.createDirectoriesRecursive(Path.of(resolvedTarget));
                } catch (IOException | RuntimeException e) {
                    return new Execution(List.of(), List.of(),
                            "Failed to create directory: " + message(e));
                }
            }
            if (rename) {
                return relocateEach(sources.subList(0, 1), journal, source ->
                        FileOps.renameMove(source, Path.of(renameTarget), sidecars));
            }
            return relocateEach(sources, journal, source ->
                    FileOps.moveWithAutoRename(source, Path.of(resolvedTarget), sidecars));
        }
    }

    /**
     * Relocates each of {@code sources} with {@code relocation}, holding the
     * archive layer's release over all of them for the whole batch.
     */
    public static Execution run(List<String> sources, Consumer<ActionLogEntry> journal,
                                Relocation relocation) {
        try (var held = ArchivePaths.releaseForMutation(
                sources.stream().map(Path::of).toList())) {
            return relocateEach(sources, journal, relocation);
        }
    }

    private static Execution relocateEach(List<String> sources, Consumer<ActionLogEntry> journal,
                                          Relocation relocation) {
        List<Moved> moved = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String source : sources) {
            moveOne(source, moved, failures, journal, relocation);
        }
        return new Execution(moved, failures, null);
    }

    /**
     * Relocate one source and account for it before the next one starts: a
     * file that landed is written to the on-disk action log here, on the
     * file-operation thread, the moment its move completes — so a crash or
     * forced quit mid-batch leaves a line for every file already moved.
     *
     * <p>Nothing escapes: an unchecked failure (a path the filesystem rejects,
     * a listing dying on a flaky share) is that source's failure alone, since
     * letting it fail the whole batch would discard the record of the sources
     * already moved and skip their refresh. A file stranded at the destination
     * ({@link StrandedMoveException}) is both: logged where it now is, and
     * reported as a failure in the wording that says so.</p>
     */
    private static void moveOne(String source, List<Moved> moved, List<Failure> failures,
                                Consumer<ActionLogEntry> journal, Relocation relocation) {
        String name = new File(source).getName();
        Path sourcePath;
        Path landedAt;
        try {
            sourcePath = Path.of(source);
            landedAt = relocation.apply(sourcePath);
        } catch (StrandedMoveException e) {
            failures.add(new Failure(source, name, e.getMessage()));
            moved.add(landed(Path.of(source), e.landedAt(), journal));
            return;
        } catch (IOException e) {
            failures.add(new Failure(source, name, failureText(e)));
            return;
        } catch (RuntimeException e) {
            // Unexpected, so the trace goes to stderr as well as the status.
            e.printStackTrace();
            failures.add(new Failure(source, name, message(e)));
            return;
        }
        moved.add(landed(sourcePath, landedAt, journal));
    }

    /**
     * Durably logs a relocation that just completed (off the FX thread). The
     * file has moved whatever the log does, so an unexpected logging failure
     * is reported to stderr and the move still counts — like the append
     * failures {@link ActionLogFile} reports itself, it must not turn a
     * finished move into a failed one.
     */
    private static Moved landed(Path source, Path finalPath, Consumer<ActionLogEntry> journal) {
        ActionLogEntry entry = ActionLogEntry.moveOrRename(source, finalPath);
        try {
            journal.accept(entry);
        } catch (RuntimeException e) {
            System.err.println("Cannot write the action log entry for " + source + ": " + e);
            e.printStackTrace();
        }
        return new Moved(source, finalPath, entry);
    }

    /**
     * A per-file move failure's text. {@link FileOps} wraps a sidecar failure
     * in a message of its own that says what became of the file ("nothing was
     * moved"), with the carrier's reason already quoted inside it; unwrapping
     * that to the root cause, as {@link #message} does, would drop the half
     * the user needs. Anything else keeps the root-cause wording.
     */
    private static String failureText(IOException e) {
        return e.getCause() != null && e.getMessage() != null ? e.getMessage() : message(e);
    }

    /** The root cause's message, else its class name. */
    static String message(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
