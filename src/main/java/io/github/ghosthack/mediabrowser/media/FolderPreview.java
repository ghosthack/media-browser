package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.List;

/**
 * The result of one folder-preview scan: the child images that fill the tile's
 * collage, plus what that same walk revealed about the folder.
 *
 * <p>The verdict is a <em>by-product</em>, not a second scan. The children were
 * being listed anyway to build the collage, so knowing the folder is empty
 * costs nothing beyond what the tile already paid — which is why the flat
 * verdicts can be always-on while the subtree check
 * ({@link MediaService#barren}) cannot.</p>
 *
 * @param previews child media for the N×N collage, in name order, capped at the
 *                 requested limit; empty when there is nothing to show
 * @param verdict  what the folder holds
 * @param junkFiles how many children were junk or zero-byte — carried so a
 *                 {@link FolderVerdict#JUNK_ONLY} folder can say
 *                 "Empty folder (2 system files)" instead of a bare "Empty"
 *                 that a Finder window would contradict. Counted from the same
 *                 walk, so it costs nothing.
 */
public record FolderPreview(List<Path> previews, FolderVerdict verdict, int junkFiles) {

    public FolderPreview {
        previews = List.copyOf(previews);
    }

    static FolderPreview of(List<Path> previews, FolderVerdict verdict, int junkFiles) {
        return new FolderPreview(previews, verdict, junkFiles);
    }

    /** An unreadable directory: nothing to draw, and we must not claim it is empty. */
    public static final FolderPreview UNREADABLE =
            new FolderPreview(List.of(), FolderVerdict.UNREADABLE, 0);

    /** Whether the tile should carry a dead-end reticule. */
    public boolean isDeadEnd() {
        return verdict.isDeadEnd();
    }
}
