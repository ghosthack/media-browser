package io.github.ghosthack.epubmedia;

import java.io.IOException;

/** Indicates a malformed, unsupported, changed, or budget-violating EPUB package. */
public class EpubArchiveException extends IOException {
    public EpubArchiveException(String message) {
        super(message);
    }

    public EpubArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
