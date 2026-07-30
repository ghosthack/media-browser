package io.github.ghosthack.mediabrowser.ui.mosaic;

/** Orthogonal states that a tile set may layer over its content identity. */
public enum MosaicTileModifier {
    HIDDEN,
    SYSTEM,
    JUNK,
    EXECUTABLE,
    SYMLINK,
    BROKEN_LINK,
    ZERO_BYTE,
    EMPTY,
    JUNK_ONLY,
    UNREADABLE,
    THUMBNAIL_PENDING,
    THUMBNAIL_FAILED;

    public int mask() {
        return 1 << ordinal();
    }
}
