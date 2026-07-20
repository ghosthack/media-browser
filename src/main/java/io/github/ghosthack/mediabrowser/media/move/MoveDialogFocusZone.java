package io.github.ghosthack.mediabrowser.media.move;

/**
 * The three keyboard-focusable zones of the move dialog: the target text field
 * ({@code INPUT}), the recent-targets list ({@code HISTORY}) and the lazy
 * directory tree ({@code TREE}). Tab cycles forward
 * ({@code INPUT → HISTORY → TREE}); Shift+Tab cycles in reverse; {@code HISTORY}
 * is skipped when the move history is empty.
 */
public enum MoveDialogFocusZone {
    INPUT, HISTORY, TREE
}
