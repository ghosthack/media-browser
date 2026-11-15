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
    THUMBNAIL_FAILED,
    /**
     * The decoder reported the file's data corrupt (see
     * {@code media.CorruptData}): on a media tile it refines
     * {@link #THUMBNAIL_FAILED}, which stays set alongside it so a tile set
     * unaware of this state still draws a failure; on an archive tile it marks
     * a container that would not open because its structure is damaged. Set
     * <em>without</em> {@link #THUMBNAIL_FAILED} on a media tile whose
     * thumbnail decoded but whose decoder warned the data is damaged
     * ({@link MosaicCorruptMark#decodedButCorrupt}): the picture is drawn and
     * the sign goes on as a corner badge. Drawn with {@link MosaicCorruptMark}'s
     * warning sign, never the failure cross.
     */
    CORRUPT;

    public int mask() {
        return 1 << ordinal();
    }
}
