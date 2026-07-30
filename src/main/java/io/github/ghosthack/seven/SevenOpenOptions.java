package io.github.ghosthack.seven;

/**
 * Resource budgets applied while opening and reading a 7z archive.
 *
 * <p>The decoder-memory limit covers LZMA/LZMA2 dictionaries and archive
 * metadata. Other codecs do not all expose a memory-accounting hook, so callers
 * handling adversarial input should still use process-level memory and time
 * limits.
 */
public record SevenOpenOptions(
        int maxEntries,
        long maxTotalUncompressedBytes,
        long maxEntryUncompressedBytes,
        long maxDecoderMemoryBytes,
        double maxCompressionRatio,
        int maxDecoderSessions) {

    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final long DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES = 16L << 40; // 16 TiB
    private static final long DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES = 8L << 30; // 8 GiB
    private static final long DEFAULT_MAX_DECODER_MEMORY_BYTES = 1L << 30; // 1 GiB
    private static final double DEFAULT_MAX_COMPRESSION_RATIO = 10_000.0;
    private static final int DEFAULT_MAX_DECODER_SESSIONS = 4;

    public SevenOpenOptions {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        requirePositive(maxTotalUncompressedBytes, "maxTotalUncompressedBytes");
        requirePositive(maxEntryUncompressedBytes, "maxEntryUncompressedBytes");
        requirePositive(maxDecoderMemoryBytes, "maxDecoderMemoryBytes");
        if (maxDecoderMemoryBytes > (long) Integer.MAX_VALUE * 1024L) {
            throw new IllegalArgumentException(
                    "maxDecoderMemoryBytes cannot exceed the decoder's 2 TiB limit");
        }
        if (!Double.isFinite(maxCompressionRatio) || maxCompressionRatio < 1.0) {
            throw new IllegalArgumentException(
                    "maxCompressionRatio must be finite and at least 1.0");
        }
        if (maxDecoderSessions <= 0) {
            throw new IllegalArgumentException("maxDecoderSessions must be positive");
        }
    }

    /** Returns the finite default budgets. */
    public static SevenOpenOptions defaults() {
        return new SevenOpenOptions(
                DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
                DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
                DEFAULT_MAX_DECODER_MEMORY_BYTES,
                DEFAULT_MAX_COMPRESSION_RATIO,
                DEFAULT_MAX_DECODER_SESSIONS);
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

    /** Mutable builder for {@link SevenOpenOptions}. */
    public static final class Builder {
        private int maxEntries = DEFAULT_MAX_ENTRIES;
        private long maxTotalUncompressedBytes = DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES;
        private long maxEntryUncompressedBytes = DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES;
        private long maxDecoderMemoryBytes = DEFAULT_MAX_DECODER_MEMORY_BYTES;
        private double maxCompressionRatio = DEFAULT_MAX_COMPRESSION_RATIO;
        private int maxDecoderSessions = DEFAULT_MAX_DECODER_SESSIONS;

        private Builder() {}

        public Builder maxEntries(int value) {
            maxEntries = value;
            return this;
        }

        public Builder maxTotalUncompressedBytes(long value) {
            maxTotalUncompressedBytes = value;
            return this;
        }

        public Builder maxEntryUncompressedBytes(long value) {
            maxEntryUncompressedBytes = value;
            return this;
        }

        public Builder maxDecoderMemoryBytes(long value) {
            maxDecoderMemoryBytes = value;
            return this;
        }

        public Builder maxCompressionRatio(double value) {
            maxCompressionRatio = value;
            return this;
        }

        /**
         * Limits concurrently open member streams and their independent decoder
         * state for one archive.
         */
        public Builder maxDecoderSessions(int value) {
            maxDecoderSessions = value;
            return this;
        }

        public SevenOpenOptions build() {
            return new SevenOpenOptions(
                    maxEntries,
                    maxTotalUncompressedBytes,
                    maxEntryUncompressedBytes,
                    maxDecoderMemoryBytes,
                    maxCompressionRatio,
                    maxDecoderSessions);
        }
    }
}
