package io.github.ghosthack.cue;

/** The storage type declared by a CUE {@code FILE} command. */
public enum CueFileType {
    /** Raw little-endian sector data. */
    BINARY,
    /** Raw big-endian sector data. */
    MOTOROLA,
    /** RIFF WAVE audio. */
    WAVE,
    /** AIFF audio. */
    AIFF,
    /** MPEG audio. */
    MP3,
    /** A syntactically valid but unsupported file type. */
    UNKNOWN;

    /** Parses a CUE file-type token, returning {@link #UNKNOWN} when unrecognized. */
    public static CueFileType parse(String value) {
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
