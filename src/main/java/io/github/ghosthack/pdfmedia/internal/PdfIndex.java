package io.github.ghosthack.pdfmedia.internal;

import io.github.ghosthack.pdfmedia.PdfEntry;
import io.github.ghosthack.pdfmedia.PdfMrcComposite;
import java.util.List;

/** Immutable parallel public/private index assembled while a PDF is opened. */
public record PdfIndex(
        List<PdfEntry> entries,
        List<EntryContent> contents,
        List<PdfMrcComposite> mrcComposites) {
    public PdfIndex {
        entries = List.copyOf(entries);
        contents = List.copyOf(contents);
        mrcComposites = List.copyOf(mrcComposites);
        if (entries.size() != contents.size()) {
            throw new IllegalArgumentException("entry and content indexes differ");
        }
    }
}
