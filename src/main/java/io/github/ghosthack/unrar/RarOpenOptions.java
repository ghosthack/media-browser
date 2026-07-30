package io.github.ghosthack.unrar;

/**
 * Resource budgets applied while opening and reading an archive.
 *
 * <p>The defaults are intentionally finite. Applications working with unusually
 * large archives should raise only the budget they actually need.
 */
public record RarOpenOptions(
        int maxEntries,
        long maxTotalUncompressedBytes,
        long maxEntryUncompressedBytes,
        long maxDictionaryBytes,
        double maxCompressionRatio,
        int pipeBufferBytes,
        int maxDecoderSessions) {

    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final long DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES = 16L << 40; // 16 TiB
    private static final long DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES = 8L << 30; // 8 GiB
    private static final long DEFAULT_MAX_DICTIONARY_BYTES = 1L << 30; // 1 GiB
    private static final double DEFAULT_MAX_COMPRESSION_RATIO = 10_000.0;
    private static final int DEFAULT_PIPE_BUFFER_BYTES = 64 << 10;
    private static final int DEFAULT_MAX_DECODER_SESSIONS = 4;

    public RarOpenOptions {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        requirePositive(maxTotalUncompressedBytes, "maxTotalUncompressedBytes");
        requirePositive(maxEntryUncompressedBytes, "maxEntryUncompressedBytes");
        requirePositive(maxDictionaryBytes, "maxDictionaryBytes");
        if (!Double.isFinite(maxCompressionRatio) || maxCompressionRatio < 1.0) {
            throw new IllegalArgumentException(
                    "maxCompressionRatio must be finite and at least 1.0");
        }
        if (pipeBufferBytes <= 0) {
            throw new IllegalArgumentException("pipeBufferBytes must be positive");
        }
        if (maxDecoderSessions <= 0) {
            throw new IllegalArgumentException("maxDecoderSessions must be positive");
        }
    }

    /** Returns the conservative default budgets. */
    public static RarOpenOptions defaults() {
        return new RarOpenOptions(
                DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
                DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
                DEFAULT_MAX_DICTIONARY_BYTES,
                DEFAULT_MAX_COMPRESSION_RATIO,
                DEFAULT_PIPE_BUFFER_BYTES,
                DEFAULT_MAX_DECODER_SESSIONS);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Returns a builder initialized with the default budgets. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link RarOpenOptions}. */
    public static final class Builder {
        private int maxEntries = DEFAULT_MAX_ENTRIES;
        private long maxTotalUncompressedBytes = DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES;
        private long maxEntryUncompressedBytes = DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES;
        private long maxDictionaryBytes = DEFAULT_MAX_DICTIONARY_BYTES;
        private double maxCompressionRatio = DEFAULT_MAX_COMPRESSION_RATIO;
        private int pipeBufferBytes = DEFAULT_PIPE_BUFFER_BYTES;
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

        public Builder maxDictionaryBytes(long value) {
            maxDictionaryBytes = value;
            return this;
        }

        public Builder maxCompressionRatio(double value) {
            maxCompressionRatio = value;
            return this;
        }

        public Builder pipeBufferBytes(int value) {
            pipeBufferBytes = value;
            return this;
        }

        /**
         * Limits member streams decoding concurrently for one mounted archive.
         * Idle sessions retain their parsed header index for reuse.
         */
        public Builder maxDecoderSessions(int value) {
            maxDecoderSessions = value;
            return this;
        }

        public RarOpenOptions build() {
            return new RarOpenOptions(
                    maxEntries,
                    maxTotalUncompressedBytes,
                    maxEntryUncompressedBytes,
                    maxDictionaryBytes,
                    maxCompressionRatio,
                    pipeBufferBytes,
                    maxDecoderSessions);
        }
    }
}
