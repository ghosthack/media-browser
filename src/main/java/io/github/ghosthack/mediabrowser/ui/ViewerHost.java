package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.MediaItem;

import javafx.scene.input.KeyEvent;

import java.nio.file.Path;

/**
 * A view that owns a {@link ViewerWindow} session: the browser (main window)
 * or the mosaic. The viewer records the host that opened it; when the viewer
 * hands control back (Escape/Enter) the {@link AppShell} back-stack returns to
 * that view, and while the user arrow-browses inside the viewer it calls
 * {@link #mirrorViewerItem} on every item shown, so the host's own selection
 * follows along — the main window's file list and the mosaic's selected tile
 * alike.
 */
public interface ViewerHost {

    /**
     * Reflect the viewer's current item into this host's selection. Called on
     * the FX thread for every item the viewer shows while this host owns the
     * session; a no-op when the item is not in this host's listing.
     */
    void mirrorViewerItem(MediaItem item);

    /**
     * Re-list this host's directory after the viewer moved its on-screen item,
     * landing the host's selection on {@code focusPath} (or clearing it when
     * {@code null}), so the opener window reflects a move the viewer performed.
     * The default does nothing, for hosts that track no listing.
     */
    default void refreshAfterViewerMove(Path focusPath) { }

    /**
     * Relay an Up/Down key the viewer chose not to act on to this host's own
     * navigation, so the underlying list/grid moves its selection (the mosaic a
     * row, the file list an item) while the viewer stays in front. The default
     * does nothing.
     */
    default void forwardNavigationKey(KeyEvent event) { }

    /**
     * Navigate this host's listing to the current folder's parent directory,
     * mirroring its own Backspace / parent navigation. Called on the FX thread
     * when the viewer relays a parent-navigation key (Backspace, modifier1+Left
     * or modifier1+Right) to the window that opened the session, so the user can
     * back out of a folder without leaving the viewer. The default does nothing,
     * for hosts that track no listing.
     */
    default void navigateToParent() { }

    /**
     * Reflect a non-destructive adjustment the viewer just persisted for
     * {@code path} (rotation, mirror, black&amp;white or invert) back into this
     * host's view, so a host drawing that file (the mosaic's tile) updates without
     * waiting for its next redraw. Pixels only — the adjustment is already in the
     * shared {@code RotationStore}; this must not re-list. The default does
     * nothing, for hosts that don't render adjustments (the file list).
     */
    default void mirrorViewerAdjustments(Path path) { }
}
