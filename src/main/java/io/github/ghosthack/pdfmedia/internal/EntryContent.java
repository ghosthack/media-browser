package io.github.ghosthack.pdfmedia.internal;

import java.io.IOException;
import java.io.OutputStream;

/** Lazily writes one indexed entry's attachment or encoded bitstream bytes. */
@FunctionalInterface
public interface EntryContent {
    void writeTo(OutputStream output) throws IOException;
}
