package io.github.ghosthack.mediabrowser.media;

/** Broad classification of an openable media file. */
public enum MediaKind {
    IMAGE,
    VIDEO,
    AUDIO;

    public String badge() {
        return switch (this) {
            case IMAGE -> "IMG";
            case VIDEO -> "VID";
            case AUDIO -> "AUD";
        };
    }
}
