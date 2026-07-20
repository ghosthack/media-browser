package io.github.ghosthack.mediabrowser.media.move;

/**
 * The intents a {@code MoveDialog} view emits in response to user input. A
 * framework-free seam (no JavaFX types) so the view stays decoupled from
 * whatever drives it — a Phase&nbsp;5 {@code MoveController}, a window, or the
 * manual harness used to exercise the view in isolation.
 *
 * <p>Mirrors the {@code AppStore.*moveDialog*} glue iris's Swing dialog called.
 * Every method mutates the shared {@link MoveDialogModel} (and, for the
 * filesystem-touching ones, the move history / listing) and is expected to call
 * the view's {@code refresh()} when the model settles — synchronously for the
 * pure navigation intents, and after the async move completes for
 * {@link #submit()}. The view never reconciles itself; the driver decides when,
 * exactly as iris's store→change-listener→{@code syncFromState} flow did. This
 * keeps the "synchronous refresh before focus" sequencing (see the handoff §2.8)
 * in the driver's hands.
 */
public interface MoveDialogIntents {

    /** The target text changed (free-text edit in the INPUT zone). */
    void setTargetPath(String text);

    /** Cycle the focus zone forward (Tab) or {@code reverse} (Shift+Tab). */
    void cycleFocusZone(boolean reverse);

    /** Move the history highlight up one row (and fill the target field). */
    void historyUp();

    /** Move the history highlight down one row (and fill the target field). */
    void historyDown();

    /** Click-select the recent-target at {@code index} (fills the target field). */
    void selectHistoryEntry(int index);

    /** Move the tree highlight up over the flattened visible nodes. */
    void treeUp();

    /** Move the tree highlight down over the flattened visible nodes. */
    void treeDown();

    /** Left arrow in the tree: collapse if expanded, else jump to the parent. */
    void treeLeft();

    /** Right arrow in the tree: expand the highlighted node (lazy-load). */
    void treeRight();

    /** Expand the tree node at {@code path} (user toggled its disclosure). */
    void expandTreeNode(String path);

    /** Collapse the tree node at {@code path} (user toggled its disclosure). */
    void collapseTreeNode(String path);

    /** Click-select the tree node at {@code path} (fills the target field). */
    void selectTreeNode(String path);

    /** Toggle the transient quick-move {@code F1}/{@code F2}/{@code F3} shortcuts. */
    void setQuickMoveShortcutsEnabled(boolean enabled);

    /** Submit the move (Enter or the Move button). */
    void submit();

    /** Cancel/close the dialog (Esc, the Cancel button, or window close). */
    void cancel();
}
