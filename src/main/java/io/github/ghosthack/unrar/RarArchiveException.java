package io.github.ghosthack.unrar;

import java.io.IOException;

/** Indicates that a RAR archive is corrupt, unsupported, or violates a resource budget. */
public final class RarArchiveException extends IOException {
    public RarArchiveException(String message) {
        super(message);
    }

    public RarArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
