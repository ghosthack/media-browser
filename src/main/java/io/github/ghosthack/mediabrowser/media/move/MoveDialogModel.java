package io.github.ghosthack.mediabrowser.media.move;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable state bag for the move dialog — the single source of truth the JavaFX
 * view reconciles itself from. A plain Java object (no JavaFX) so the move
 * logic stays unit-testable headless.
 *
 * <p>Ported from {@code iris94.core.state.MoveDialogState}, with two
 * deliberate omissions: the {@code requiresCreateConfirmation} /
 * {@code pendingCreateTargetPath} pair is dropped — in this port the
 * create-if-missing confirmation is an inline JavaFX {@code Alert} raised during
 * submit (Phase 5), so it never needs to live in the model. The UI-only flags
 * iris kept in {@code AppState} ({@code showMoveDialog}, {@code focusedPanel})
 * are likewise the dialog's concern, not the model's.
 *
 * <p>Move history itself is <b>not</b> stored here: it lives in
 * {@link io.github.ghosthack.mediabrowser.AppSettings} and is threaded into
 * {@link MoveDialogLogic} methods that need it, mirroring how iris kept it in
 * {@code AppState} rather than {@code MoveDialogState}.
 */
public final class MoveDialogModel {

    private String targetPath = "";
    private MoveDialogFocusZone focusZone = MoveDialogFocusZone.INPUT;
    private List<String> sourceFilePaths = new ArrayList<>();
    private boolean parentEntryExcluded = false;
    private boolean isMoving = false;
    private String inlineError = null;
    private List<TreeNode> miniTreeNodes = new ArrayList<>();
    private String miniTreeHighlightedPath = "";
    private boolean miniTreeInitialized = false;
    private int historyHighlightedIndex = -1;

    /**
     * Transient: enables the {@code F1}/{@code F2}/{@code F3} keys to quick-move
     * the current selection to {@code moveHistory[0/1/2]} (function keys so they
     * never collide with the list/grid typeahead). Toggled by a checkbox
     * in the dialog. <b>NOT</b> reset by {@link MoveDialogLogic#open} /
     * {@link MoveDialogLogic#close} (so it survives dialog open/close), and
     * <b>NOT</b> persisted (so it does not survive an app restart).
     */
    private boolean quickMoveShortcutsEnabled = false;

    public MoveDialogModel() {}

    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }

    public MoveDialogFocusZone getFocusZone() { return focusZone; }
    public void setFocusZone(MoveDialogFocusZone focusZone) { this.focusZone = focusZone; }

    public List<String> getSourceFilePaths() { return sourceFilePaths; }
    public void setSourceFilePaths(List<String> sourceFilePaths) { this.sourceFilePaths = sourceFilePaths; }

    public boolean isParentEntryExcluded() { return parentEntryExcluded; }
    public void setParentEntryExcluded(boolean parentEntryExcluded) { this.parentEntryExcluded = parentEntryExcluded; }

    public boolean isMoving() { return isMoving; }
    public void setMoving(boolean moving) { this.isMoving = moving; }

    public String getInlineError() { return inlineError; }
    public void setInlineError(String inlineError) { this.inlineError = inlineError; }

    public List<TreeNode> getMiniTreeNodes() { return miniTreeNodes; }
    public void setMiniTreeNodes(List<TreeNode> miniTreeNodes) { this.miniTreeNodes = miniTreeNodes; }

    public String getMiniTreeHighlightedPath() { return miniTreeHighlightedPath; }
    public void setMiniTreeHighlightedPath(String miniTreeHighlightedPath) { this.miniTreeHighlightedPath = miniTreeHighlightedPath; }

    public boolean isMiniTreeInitialized() { return miniTreeInitialized; }
    public void setMiniTreeInitialized(boolean miniTreeInitialized) { this.miniTreeInitialized = miniTreeInitialized; }

    public int getHistoryHighlightedIndex() { return historyHighlightedIndex; }
    public void setHistoryHighlightedIndex(int historyHighlightedIndex) { this.historyHighlightedIndex = historyHighlightedIndex; }

    public boolean isQuickMoveShortcutsEnabled() { return quickMoveShortcutsEnabled; }
    public void setQuickMoveShortcutsEnabled(boolean enabled) { this.quickMoveShortcutsEnabled = enabled; }
}
