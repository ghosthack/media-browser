package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.AaeSidecar;
import io.github.ghosthack.mediabrowser.media.MediaItem;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.RasterFrame;

import java.nio.file.Path;

/**
 * The viewer's retained still: the decoded master and its upright (AAE-edited)
 * frame — the crop / export / enhance / rotate bake base — bound to the item
 * and the load they were decoded for.
 *
 * <p>The decode callback paints any frame newer than the one shown, even a
 * superseded one, so a held arrow key keeps the image moving. Painting is
 * harmless; <em>retaining</em> a superseded frame is not: a crop would write
 * the previous item's master dimensions into the current item's sidecar, an
 * export would save its pixels under the current name, an enhancement would
 * store its upscale as the current item's. A re-decode of the same item (a
 * colour-policy change) is superseded too: its frame carries the old policy.
 * So only the decode of the current load is ever retained.</p>
 *
 * <p>The crop editor relies on this: it opens only on a retained still, so
 * the shown frame is then the current load's, and only a new load (which
 * closes the editor first) can bring a newer one.</p>
 */
final class RetainedStill {

    /** What modifier1+Shift+C does on the retained still. */
    enum CropStart { UNAVAILABLE, NEW, EDIT_EXISTING }

    private RasterFrame master;
    private RasterFrame upright;
    private Path path;

    /**
     * Offers a frame that passed the viewer's monotonic paint guard: decoded
     * for {@code landedPath} by load {@code landedSeq}, while load
     * {@code currentSeq} of {@code currentPath} is the one on screen. Retains
     * it when it is that very load's frame and returns {@code true}; otherwise
     * drops whatever was retained (the frame is painted only) and returns
     * {@code false}.
     */
    boolean land(int landedSeq, int currentSeq, Path landedPath, Path currentPath,
                 RasterFrame master, RasterFrame upright) {
        if (landedSeq != currentSeq || landedPath == null || !landedPath.equals(currentPath)
                || master == null || upright == null) {
            clear();
            return false;
        }
        this.master = master;
        this.upright = upright;
        this.path = landedPath;
        return true;
    }

    /** Drops the retained frames and the path they belong to. */
    void clear() {
        master = null;
        upright = null;
        path = null;
    }

    /**
     * {@code current} when the retained frames are its own decode, else
     * {@code null}.
     */
    MediaItem owner(MediaItem current) {
        if (master == null || upright == null || current == null) return null;
        return current.path().equals(path) ? current : null;
    }

    /** The untouched decoded master, or {@code null}. */
    RasterFrame master() {
        return master;
    }

    /** The upright (AAE-edited, pre-user-adjustment) bake base, or {@code null}. */
    RasterFrame upright() {
        return upright;
    }

    /**
     * Replaces the upright frame re-derived from the retained master (a crop
     * applied or toggled). Refuses loudly when nothing is retained: that
     * caller skipped its {@link #owner} check.
     */
    void reupright(RasterFrame upright) {
        if (master == null || upright == null) {
            throw new IllegalStateException("no retained still to re-derive");
        }
        this.upright = upright;
    }

    /**
     * Whether a crop can start on {@code owner} (the item whose own frame is
     * retained, or {@code null}): the one rule behind the Crop menu items'
     * enablement, the editor itself and the modifier1+Shift+C shortcut. A
     * video's poster frame is retained too, but is no crop target.
     */
    static boolean cropAvailable(MediaItem owner) {
        return owner != null && owner.kind() == MediaKind.IMAGE;
    }

    /** Whether {@code sidecar} holds a crop drawn on this very master's size. */
    boolean hasMatchingCrop(AaeSidecar sidecar) {
        return master != null && sidecar != null && sidecar.crop().isPresent()
                && sidecar.masterWidth() == master.width()
                && sidecar.masterHeight() == master.height();
    }

    /**
     * The shortcut's decision for {@code owner} with its {@code sidecar}: no
     * crop at all where the menu item is disabled, else edit a saved crop
     * that matches the master, else draw a new one.
     */
    CropStart shortcutCrop(MediaItem owner, AaeSidecar sidecar) {
        if (!cropAvailable(owner) || owner(owner) == null) return CropStart.UNAVAILABLE;
        return hasMatchingCrop(sidecar) ? CropStart.EDIT_EXISTING : CropStart.NEW;
    }
}
