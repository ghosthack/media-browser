package io.github.ghosthack.cue;

/** Finite parser budgets and companion-path policy for opening a CUE sheet. */
public record CueOpenOptions(
        int maxCueBytes,
        int maxLines,
        int maxLineCharacters,
        int maxFiles,
        int maxTracks,
        int maxIndicesPerTrack,
        int maxCompanionNameCharacters,
        boolean allowExternalCompanions,
        boolean caseInsensitiveFallback) {

    private static final int DEFAULT_MAX_CUE_BYTES = 1 << 20;
    private static final int DEFAULT_MAX_LINES = 100_000;
    private static final int DEFAULT_MAX_LINE_CHARACTERS = 16_384;
    private static final int DEFAULT_MAX_FILES = 256;
    private static final int DEFAULT_MAX_TRACKS = 256;
    private static final int DEFAULT_MAX_INDICES = 100;
    private static final int DEFAULT_MAX_NAME_CHARACTERS = 4_096;

    public CueOpenOptions {
        requirePositive(maxCueBytes, "maxCueBytes");
        requirePositive(maxLines, "maxLines");
        requirePositive(maxLineCharacters, "maxLineCharacters");
        requirePositive(maxFiles, "maxFiles");
        requirePositive(maxTracks, "maxTracks");
        requirePositive(maxIndicesPerTrack, "maxIndicesPerTrack");
        requirePositive(maxCompanionNameCharacters, "maxCompanionNameCharacters");
    }

    /** Returns conservative finite defaults with companion confinement enabled. */
    public static CueOpenOptions defaults() {
        return builder().build();
    }

    /** Returns a builder initialized to the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    /** Mutable builder for {@link CueOpenOptions}. */
    public static final class Builder {
        private int maxCueBytes = DEFAULT_MAX_CUE_BYTES;
        private int maxLines = DEFAULT_MAX_LINES;
        private int maxLineCharacters = DEFAULT_MAX_LINE_CHARACTERS;
        private int maxFiles = DEFAULT_MAX_FILES;
        private int maxTracks = DEFAULT_MAX_TRACKS;
        private int maxIndicesPerTrack = DEFAULT_MAX_INDICES;
        private int maxCompanionNameCharacters = DEFAULT_MAX_NAME_CHARACTERS;
        private boolean allowExternalCompanions;
        private boolean caseInsensitiveFallback = true;

        public Builder maxCueBytes(int value) {
            maxCueBytes = value;
            return this;
        }

        public Builder maxLines(int value) {
            maxLines = value;
            return this;
        }

        public Builder maxLineCharacters(int value) {
            maxLineCharacters = value;
            return this;
        }

        public Builder maxFiles(int value) {
            maxFiles = value;
            return this;
        }

        public Builder maxTracks(int value) {
            maxTracks = value;
            return this;
        }

        public Builder maxIndicesPerTrack(int value) {
            maxIndicesPerTrack = value;
            return this;
        }

        public Builder maxCompanionNameCharacters(int value) {
            maxCompanionNameCharacters = value;
            return this;
        }

        /**
         * Allows absolute companion paths and normalized paths outside the CUE
         * directory. Disabled by default.
         */
        public Builder allowExternalCompanions(boolean value) {
            allowExternalCompanions = value;
            return this;
        }

        /** Enables unique case-insensitive companion lookup after exact lookup fails. */
        public Builder caseInsensitiveFallback(boolean value) {
            caseInsensitiveFallback = value;
            return this;
        }

        public CueOpenOptions build() {
            return new CueOpenOptions(
                    maxCueBytes,
                    maxLines,
                    maxLineCharacters,
                    maxFiles,
                    maxTracks,
                    maxIndicesPerTrack,
                    maxCompanionNameCharacters,
                    allowExternalCompanions,
                    caseInsensitiveFallback);
        }
    }
}
