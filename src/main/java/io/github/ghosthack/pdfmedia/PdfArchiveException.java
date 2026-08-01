package io.github.ghosthack.pdfmedia;

import java.io.IOException;

/** Indicates that a PDF is corrupt, unsupported, inaccessible, or violates a resource budget. */
public final class PdfArchiveException extends IOException {
    public PdfArchiveException(String message) {
        super(message);
    }

    public PdfArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
