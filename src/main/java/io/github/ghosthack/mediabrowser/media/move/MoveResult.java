package io.github.ghosthack.mediabrowser.media.move;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of a move operation.
 *
 * @param succeeded   the source paths that were moved successfully
 * @param failures    human-readable {@code "name: reason"} messages, one per
 *                    source that failed to move
 * @param historyPath the destination directory to record in move history (the
 *                    target directory for a normal move, or the parent of the
 *                    target file for a single-file rename); {@code null} when
 *                    nothing should be recorded (e.g. a total failure)
 * @param wasRename   whether this was a single-file move-and-rename
 */
public record MoveResult(List<Path> succeeded, List<String> failures,
                         Path historyPath, boolean wasRename) {

    public MoveResult {
        succeeded = List.copyOf(succeeded);
        failures = List.copyOf(failures);
    }

    /** All sources moved without failure. */
    public boolean allSucceeded() {
        return failures.isEmpty();
    }

    /** Some but not all sources moved. */
    public boolean partial() {
        return !failures.isEmpty() && !succeeded.isEmpty();
    }

    /** Nothing moved. */
    public boolean allFailed() {
        return succeeded.isEmpty();
    }
}
