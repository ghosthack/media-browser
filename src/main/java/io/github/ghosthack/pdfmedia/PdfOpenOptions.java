package io.github.ghosthack.pdfmedia;

/**
 * Finite resource budgets applied while indexing and reading a PDF.
 *
 * <p>PDF parsing and inline-image tokenization can still consume substantial temporary memory.
 * Applications processing adversarial files should additionally impose wall-clock,
 * process-memory, and isolation limits.</p>
 */
public record PdfOpenOptions(
        long maxSourceBytes,
        int maxObjects,
        int maxEntries,
        long maxTotalDeclaredBytes,
        long maxEntryBytes,
        long maxImagePixels,
        int maxConcurrentStreams,
        int pipeBufferBytes,
        boolean honorExtractionPermission) {

    private static final long DEFAULT_MAX_SOURCE_BYTES = 8L << 30; // 8 GiB
    private static final int DEFAULT_MAX_OBJECTS = 1_000_000;
    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final long DEFAULT_MAX_TOTAL_DECLARED_BYTES = 16L << 40; // 16 TiB
    private static final long DEFAULT_MAX_ENTRY_BYTES = 8L << 30; // 8 GiB
    private static final long DEFAULT_MAX_IMAGE_PIXELS = 268_435_456L; // 16K x 16K
    private static final int DEFAULT_MAX_CONCURRENT_STREAMS = 4;
    private static final int DEFAULT_PIPE_BUFFER_BYTES = 64 * 1024;

    public PdfOpenOptions {
        requirePositive(maxSourceBytes, "maxSourceBytes");
        if (maxObjects <= 0) {
            throw new IllegalArgumentException("maxObjects must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        requirePositive(maxTotalDeclaredBytes, "maxTotalDeclaredBytes");
        requirePositive(maxEntryBytes, "maxEntryBytes");
        requirePositive(maxImagePixels, "maxImagePixels");
        if (maxConcurrentStreams <= 0) {
            throw new IllegalArgumentException("maxConcurrentStreams must be positive");
        }
        if (pipeBufferBytes < 1024) {
            throw new IllegalArgumentException("pipeBufferBytes must be at least 1024");
        }
    }

    /** Returns the finite default budgets. */
    public static PdfOpenOptions defaults() {
        return new PdfOpenOptions(
                DEFAULT_MAX_SOURCE_BYTES,
                DEFAULT_MAX_OBJECTS,
                DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_TOTAL_DECLARED_BYTES,
                DEFAULT_MAX_ENTRY_BYTES,
                DEFAULT_MAX_IMAGE_PIXELS,
                DEFAULT_MAX_CONCURRENT_STREAMS,
                DEFAULT_PIPE_BUFFER_BYTES,
                true);
    }

    /** Returns a builder initialized with the default budgets. */
    public static Builder builder() {
        return new Builder();
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Mutable builder for {@link PdfOpenOptions}. */
    public static final class Builder {
        private long maxSourceBytes = DEFAULT_MAX_SOURCE_BYTES;
        private int maxObjects = DEFAULT_MAX_OBJECTS;
        private int maxEntries = DEFAULT_MAX_ENTRIES;
        private long maxTotalDeclaredBytes = DEFAULT_MAX_TOTAL_DECLARED_BYTES;
        private long maxEntryBytes = DEFAULT_MAX_ENTRY_BYTES;
        private long maxImagePixels = DEFAULT_MAX_IMAGE_PIXELS;
        private int maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
        private int pipeBufferBytes = DEFAULT_PIPE_BUFFER_BYTES;
        private boolean honorExtractionPermission = true;

        private Builder() {}

        public Builder maxSourceBytes(long value) {
            maxSourceBytes = value;
            return this;
        }

        public Builder maxObjects(int value) {
            maxObjects = value;
            return this;
        }

        public Builder maxEntries(int value) {
            maxEntries = value;
            return this;
        }

        public Builder maxTotalDeclaredBytes(long value) {
            maxTotalDeclaredBytes = value;
            return this;
        }

        public Builder maxEntryBytes(long value) {
            maxEntryBytes = value;
            return this;
        }

        public Builder maxImagePixels(long value) {
            maxImagePixels = value;
            return this;
        }

        public Builder maxConcurrentStreams(int value) {
            maxConcurrentStreams = value;
            return this;
        }

        public Builder pipeBufferBytes(int value) {
            pipeBufferBytes = value;
            return this;
        }

        /**
         * Controls whether the PDF extraction permission bit is enforced. The default is
         * {@code true}; disabling it is a consumer policy decision.
         */
        public Builder honorExtractionPermission(boolean value) {
            honorExtractionPermission = value;
            return this;
        }

        public PdfOpenOptions build() {
            return new PdfOpenOptions(
                    maxSourceBytes,
                    maxObjects,
                    maxEntries,
                    maxTotalDeclaredBytes,
                    maxEntryBytes,
                    maxImagePixels,
                    maxConcurrentStreams,
                    pipeBufferBytes,
                    honorExtractionPermission);
        }
    }
}
