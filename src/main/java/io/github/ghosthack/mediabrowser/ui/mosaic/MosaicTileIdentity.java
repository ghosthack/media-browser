package io.github.ghosthack.mediabrowser.ui.mosaic;

/** Mutually exclusive content identities shared by every mosaic tile set. */
public enum MosaicTileIdentity {
    PARENT,
    FOLDER,
    MEDIA_IMAGE,
    MEDIA_VIDEO,
    MEDIA_AUDIO,
    TEXT,
    DOCUMENT,
    DATA,
    BINARY,
    EXECUTABLE,
    ARCHIVE_BROWSABLE,
    ARCHIVE_SEALED,
    UNKNOWN
}
