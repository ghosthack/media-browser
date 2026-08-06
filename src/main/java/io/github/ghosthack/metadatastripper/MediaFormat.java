package io.github.ghosthack.metadatastripper;

/** Containers that can be rewritten without decoding their media payload. */
public enum MediaFormat {
    JPEG("JPEG"),
    PNG("PNG/APNG"),
    GIF("GIF"),
    WEBP("WebP"),
    JXL("JPEG XL"),
    MP3("MP3"),
    FLAC("FLAC"),
    WAV("WAV");

    private final String displayName;

    MediaFormat(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

