package io.github.ghosthack.cue;

import java.util.Locale;

/** A CUE track mode and its physical-sector geometry. */
public enum CueTrackMode {
    AUDIO(2352, -1, 0, false),
    CDG(2448, -1, 0, false),
    MODE1_2048(2048, 0, 2048, true),
    MODE1_2352(2352, 16, 2048, true),
    MODE2_2336(2336, 8, 2048, true),
    MODE2_2352(2352, 24, 2048, true),
    CDI_2336(2336, -1, 0, false),
    CDI_2352(2352, -1, 0, false),
    UNKNOWN(-1, -1, 0, false);

    private final int storedSectorBytes;
    private final int payloadOffset;
    private final int logicalSectorBytes;
    private final boolean supportedData;

    CueTrackMode(
            int storedSectorBytes,
            int payloadOffset,
            int logicalSectorBytes,
            boolean supportedData) {
        this.storedSectorBytes = storedSectorBytes;
        this.payloadOffset = payloadOffset;
        this.logicalSectorBytes = logicalSectorBytes;
        this.supportedData = supportedData;
    }

    /** Number of bytes stored for each physical sector, or {@code -1} if unknown. */
    public int storedSectorBytes() {
        return storedSectorBytes;
    }

    /** Offset of a 2048-byte data payload within a physical sector. */
    public int payloadOffset() {
        return payloadOffset;
    }

    /** Logical payload bytes exposed per supported data sector. */
    public int logicalSectorBytes() {
        return logicalSectorBytes;
    }

    /** Whether this adapter can expose the track as logical data. */
    public boolean supportedData() {
        return supportedData;
    }

    /** Parses a CUE track-mode token, returning {@link #UNKNOWN} when unrecognized. */
    public static CueTrackMode parse(String value) {
        String normalized =
                value.toUpperCase(Locale.ROOT).replace('/', '_').replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
