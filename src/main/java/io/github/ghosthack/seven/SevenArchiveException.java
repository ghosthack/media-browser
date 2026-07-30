package io.github.ghosthack.seven;

import java.io.IOException;

/** Indicates that a 7z archive is corrupt, unsupported, or violates a resource budget. */
public final class SevenArchiveException extends IOException {
    public SevenArchiveException(String message) {
        super(message);
    }

    public SevenArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}

