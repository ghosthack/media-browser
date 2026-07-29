package io.github.ghosthack.mediabrowser.media;

/**
 * What a folder holds, judged from one listing of its immediate children — the
 * flat half of {@code docs/empty-states.md}. Ordinal: less content as you go
 * down the list, which is what lets the mosaic render it as a dither density
 * rather than four unrelated marks.
 *
 * <p>Always judged <em>under the junk rule</em>, whether or not junk files are
 * being displayed: a folder holding one {@code .DS_Store} is {@link #JUNK_ONLY}
 * even with junk visible. Otherwise the two toggles fight — "hide empty
 * folders" would leave an obviously-empty folder on screen because of a
 * dropping the user can see but does not count.</p>
 *
 * <p>Deliberately no {@code BARREN}: whether a whole subtree is empty is a
 * different question with a different cost (see {@link MediaService#barren}),
 * and it is not answerable from one listing.</p>
 */
public enum FolderVerdict {

    /** Has visual children, or subfolders that might. The preview collage draws. */
    NORMAL,

    /** Has files, but none of them previewable — twelve PDFs. */
    NO_VISUAL,

    /** Children, but every one is junk or a zero-byte file. */
    JUNK_ONLY,

    /** No entries at all. */
    EMPTY,

    /**
     * The listing threw — permission denied, unmounted volume. Never collapsed
     * into {@link #EMPTY}: a folder we cannot read is not a folder with nothing
     * in it, and saying otherwise is a silent degrade.
     */
    UNREADABLE;

    /** Whether the mosaic marks this tile as a dead end (see the reticule). */
    public boolean isDeadEnd() {
        return this == JUNK_ONLY || this == EMPTY || this == UNREADABLE;
    }
}
