package io.github.ghosthack.epubmedia;

/** Finite resource budgets applied while opening and streaming an EPUB. */
public record EpubOpenOptions(
        long maxSourceBytes,
        int maxZipEntries,
        int maxManifestItems,
        int maxMediaEntries,
        long maxEntryBytes,
        long maxTotalDeclaredBytes,
        int maxXmlBytes,
        int maxPathCharacters,
        int maxConcurrentStreams) {

    public EpubOpenOptions {
        if (maxSourceBytes <= 0
                || maxZipEntries <= 0
                || maxManifestItems <= 0
                || maxMediaEntries <= 0
                || maxEntryBytes <= 0
                || maxTotalDeclaredBytes <= 0
                || maxXmlBytes <= 0
                || maxPathCharacters <= 0
                || maxConcurrentStreams <= 0) {
            throw new IllegalArgumentException("EPUB budgets must be positive");
        }
    }

    /** Defaults suitable for large illustrated publications without making budgets unbounded. */
    public static EpubOpenOptions defaults() {
        return new EpubOpenOptions(
                4L * 1024 * 1024 * 1024,
                100_000,
                100_000,
                50_000,
                512L * 1024 * 1024,
                8L * 1024 * 1024 * 1024,
                8 * 1024 * 1024,
                4096,
                8);
    }
}
