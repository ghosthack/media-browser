package io.github.ghosthack.mediabrowser.media.move;

import java.io.File;
import java.util.List;

/**
 * The pure decision logic of a move submit: resolve the target, detect a
 * single-file rename, decide whether the target must be created, and reject an
 * empty / non-directory / same-directory target — everything iris's
 * {@code AppStore.submitMoveDialog} computes before it touches the filesystem.
 * Headless and FX-free (it only inspects the filesystem via {@link File}), so
 * the Phase&nbsp;5 submit rules are unit-testable without a UI.
 *
 * <p>The controller drives the side effects the plan implies — the
 * create-directory confirmation, the off-thread move, the refresh and the
 * notifications — but the branching itself lives here.
 */
public final class MovePlanner {

    private MovePlanner() {}

    /**
     * The outcome of planning a submit.
     *
     * @param valid            whether the move may proceed; when {@code false},
     *                         {@code error} carries the inline message
     * @param error            the inline error when {@code !valid}, else null
     * @param resolvedTarget   the absolute target — the directory to move into
     *                         for a normal move, or the destination file path
     *                         for a single-file rename
     * @param singleFileRename whether this is a one-source move-and-rename
     * @param needsCreate      whether {@code resolvedTarget} is a missing
     *                         directory that must be created first (after the
     *                         user confirms)
     * @param renameTarget     the exact destination path for a rename, else null
     * @param historyPath      the directory to record in move history — the
     *                         target dir for a normal move, or the parent of the
     *                         renamed file
     */
    public record Plan(boolean valid, String error, String resolvedTarget,
                       boolean singleFileRename, boolean needsCreate,
                       String renameTarget, String historyPath) {

        static Plan invalid(String error) {
            return new Plan(false, error, null, false, false, null, null);
        }
    }

    /**
     * Plan a submit of {@code sources} to {@code rawTargetPath}, with relative
     * targets resolved against {@code currentDirectory}. Ports the validation
     * and branching of {@code submitMoveDialog} (handoff §2.8 steps&nbsp;2–7,
     * 10): empty check, relative resolve, single-file-rename detection,
     * create-if-missing, not-a-directory, and the same-directory guard (skipped
     * for a rename).
     */
    public static Plan plan(String rawTargetPath, String currentDirectory, List<String> sources) {
        String targetPath = rawTargetPath == null ? "" : rawTargetPath.trim();
        if (targetPath.isEmpty()) {
            return Plan.invalid("Target path cannot be empty");
        }

        // Resolve relative paths against the current directory.
        File targetFile = new File(targetPath);
        if (!targetFile.isAbsolute()) {
            targetFile = new File(currentDirectory, targetPath);
        }
        String resolvedTarget = targetFile.getAbsolutePath();

        // Single-file rename: one source, target absent, no trailing separator,
        // the name has a dot, and the parent directory exists.
        boolean endsWithSeparator = targetPath.endsWith("/") || targetPath.endsWith(File.separator);
        boolean targetLooksLikeFile = targetFile.getName().contains(".");
        boolean singleFileRename = sources.size() == 1
                && !targetFile.exists()
                && !endsWithSeparator
                && targetLooksLikeFile
                && targetFile.getParentFile() != null
                && targetFile.getParentFile().isDirectory();

        // Existence / create-confirm / not-a-directory.
        boolean needsCreate = false;
        if (!targetFile.exists() && !singleFileRename) {
            needsCreate = true;
        } else if (targetFile.exists() && !targetFile.isDirectory()) {
            return Plan.invalid("Target path is not a directory");
        }

        // Same-directory guard (skipped for a rename — same parent, new name).
        // It can never fire while needsCreate is true: a not-yet-created target
        // directory has no source living inside it, so checking it before the
        // create confirmation is equivalent to iris's after-confirm order.
        if (!singleFileRename) {
            for (String source : sources) {
                String sourceParent = new File(source).getParent();
                if (sourceParent != null && sourceParent.equals(resolvedTarget)) {
                    return Plan.invalid("Cannot move files to the same directory");
                }
            }
        }

        // For a rename, history records the parent dir; otherwise the target dir.
        String historyPath = singleFileRename
                ? targetFile.getParentFile().getAbsolutePath() : resolvedTarget;
        String renameTarget = singleFileRename ? targetFile.getAbsolutePath() : null;

        return new Plan(true, null, resolvedTarget, singleFileRename, needsCreate,
                renameTarget, historyPath);
    }
}
