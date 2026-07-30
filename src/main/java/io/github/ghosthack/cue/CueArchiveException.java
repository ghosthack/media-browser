package io.github.ghosthack.cue;

import java.io.IOException;

/** A malformed, unsupported, changed, or resource-budget-exceeding CUE/BIN set. */
public class CueArchiveException extends IOException {
    public CueArchiveException(String message) {
        super(message);
    }

    public CueArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
