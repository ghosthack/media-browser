package io.github.ghosthack.mediabrowser.media.move;

import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.Sidecars;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Carries a media file's sidecars along when {@link FileOps} moves or renames
 * it: its {@code .picasa.ini} entry (user adjustments, filtered mark) and its
 * own Apple {@code .AAE} edit. Without this an edit stays filed under the old
 * name — lost to the moved file, and inherited by whatever file takes that name
 * next — and a left-behind {@code .AAE} resurfaces in the listing as an orphan.
 *
 * <p>Called after the media file has landed at its target. A carrier either
 * carries everything or throws having changed nothing, so {@link FileOps} can
 * move the media file back and report one failed move instead of a half-done
 * one.
 */
@FunctionalInterface
public interface SidecarCarrier {

    /** Carries nothing: for callers whose files have no sidecars (tests, tools). */
    SidecarCarrier NONE = (source, target) -> {};

    /**
     * Carry {@code source}'s sidecars to {@code target}, where the media file
     * now lives.
     *
     * @throws IOException if any sidecar cannot follow; every sidecar is then
     *                     as it was before the call
     */
    void carry(Path source, Path target) throws IOException;

    /**
     * The app's carrier, through the shared stores so every view sees the
     * carried adjustments and edits without a re-read. A moved directory
     * carries its own sidecars inside it, and a sidecar moved as a file in its
     * own right (an orphaned {@code .AAE} shows in the listing) has none.
     */
    static SidecarCarrier of(RotationStore rotations, AaeStore edits) {
        return (source, target) -> {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    || Sidecars.isAaeSidecar(target) || Sidecars.isRotationSidecar(target)) {
                return;
            }
            AaeStore.Undo aae = edits.carryOwnSidecar(source, target);
            try {
                rotations.moveEntry(source, target);
            } catch (IOException | RuntimeException e) {
                try {
                    aae.undo();
                } catch (IOException | RuntimeException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        };
    }
}
