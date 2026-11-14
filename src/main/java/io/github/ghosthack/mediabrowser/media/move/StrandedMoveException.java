package io.github.ghosthack.mediabrowser.media.move;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A move that failed <em>after</em> the file had landed: its sidecars could not
 * follow and the file could not be moved back either, so it now lives at
 * {@link #landedAt()} without them. Distinct from every other {@link FileOps}
 * failure, all of which leave the file where it was — a caller must treat this
 * one as a relocation that happened (log it, refresh the listing) as well as a
 * failure to report.
 */
public final class StrandedMoveException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path landedAt;

    StrandedMoveException(String message, Path landedAt, Throwable cause) {
        super(message, cause);
        this.landedAt = landedAt;
    }

    /** Where the file is now. */
    public Path landedAt() {
        return landedAt;
    }
}
