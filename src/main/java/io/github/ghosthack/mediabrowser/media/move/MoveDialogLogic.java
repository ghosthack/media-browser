package io.github.ghosthack.mediabrowser.media.move;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Pure state-machine logic for the move dialog: open/close, focus cycling,
 * history navigation, mini-tree expand/collapse/flatten/highlight,
 * {@code addToMoveHistory}, and path helpers. Static, no UI, no IO beyond the
 * injected {@link LoadChildren} — mirrors {@code iris94.core.coordinators.MoveDialogCoordinator}.
 *
 * <p>Differences from iris: methods mutate a {@link MoveDialogModel} (not the
 * whole {@code AppState}), and the move-history list / current directory are
 * passed in explicitly (iris read them off {@code AppState}). Paths are handled
 * as strings throughout — same as iris and {@link TreeNode} — so highlighted
 * paths can be compared against node paths directly; conversion to
 * {@link Path}/{@link File} happens only at IO and decomposition boundaries.
 */
public final class MoveDialogLogic {

    private static final String DEBUG_PROPERTY = "mediabrowser.debug.moveDialogPaths";

    private MoveDialogLogic() {}

    /** Lazily supplies the subdirectories of a path when a tree node expands. */
    @FunctionalInterface
    public interface LoadChildren {
        List<TreeNode> apply(Path dir);
    }

    // ---- Open / close -------------------------------------------------------

    /**
     * Open the dialog for {@code sources}. The target field is always seeded from
     * {@code currentDir} (normalized and given a trailing separator), regardless
     * of move history, so the input field opens pointing at the directory the
     * user is browsing — typing a bare folder name then creates a subdirectory
     * relative to it. No recent target is pre-selected: the history highlight
     * starts at {@code -1}, and only an explicit pick from the recent-targets
     * list or the browse tree resets the target field. Focus starts in
     * {@code INPUT} and all transient state (moving flag, inline error, mini
     * tree) is reset. (Diverges from {@code MoveDialogCoordinator.open}, which
     * seeded the field from {@code history[0]}.) {@code history} is accepted for
     * call-site symmetry but no longer drives the initial target.
     */
    public static void open(MoveDialogModel model, List<String> sources, boolean parentEntryExcluded,
                            List<String> history, String currentDir) {
        String normalizedTarget = normalizeEscapedForwardSlashes(currentDir);
        model.setTargetPath(appendTrailingSeparator(normalizedTarget));
        model.setFocusZone(MoveDialogFocusZone.INPUT);
        model.setHistoryHighlightedIndex(-1);
        model.setSourceFilePaths(sources);
        model.setParentEntryExcluded(parentEntryExcluded);
        model.setMoving(false);
        model.setInlineError(null);
        model.setMiniTreeNodes(new ArrayList<>());
        model.setMiniTreeHighlightedPath("");
        model.setMiniTreeInitialized(false);
    }

    /**
     * Reset the dialog's transient state on close. Mirrors
     * {@code MoveDialogCoordinator.close} minus the UI-only flags (show-dialog,
     * focused-panel) the dialog itself owns in this port.
     */
    public static void close(MoveDialogModel model) {
        model.setMoving(false);
        model.setMiniTreeNodes(new ArrayList<>());
        model.setMiniTreeHighlightedPath("");
        model.setMiniTreeInitialized(false);
    }

    // ---- Focus zone cycling -------------------------------------------------

    /**
     * Cycle the focus zone. Forward: {@code INPUT → HISTORY → TREE → INPUT};
     * reverse: {@code INPUT → TREE → HISTORY → INPUT}. {@code HISTORY} is skipped
     * in both directions when {@code history} is empty. Mirrors
     * {@code MoveDialogCoordinator.cycleFocusZone}.
     */
    public static void cycleFocusZone(MoveDialogModel model, List<String> history, boolean reverse) {
        boolean hasHistory = history != null && !history.isEmpty();

        if (reverse) {
            switch (model.getFocusZone()) {
                case INPUT:
                    model.setFocusZone(MoveDialogFocusZone.TREE);
                    break;
                case HISTORY:
                    model.setFocusZone(MoveDialogFocusZone.INPUT);
                    break;
                case TREE:
                    model.setFocusZone(hasHistory ? MoveDialogFocusZone.HISTORY : MoveDialogFocusZone.INPUT);
                    break;
            }
        } else {
            switch (model.getFocusZone()) {
                case INPUT:
                    model.setFocusZone(hasHistory ? MoveDialogFocusZone.HISTORY : MoveDialogFocusZone.TREE);
                    break;
                case HISTORY:
                    model.setFocusZone(MoveDialogFocusZone.TREE);
                    break;
                case TREE:
                    model.setFocusZone(MoveDialogFocusZone.INPUT);
                    break;
            }
        }
    }

    // ---- History navigation -------------------------------------------------

    /**
     * Move the history highlight by {@code direction} (clamped to
     * {@code [0, size-1]}) and fill the target field with the highlighted entry
     * (normalized, trailing separator added). A negative current index snaps to
     * 0 first. No-op when {@code history} is empty. Mirrors
     * {@code MoveDialogCoordinator.moveHistorySelection}.
     */
    public static void moveHistorySelection(MoveDialogModel model, List<String> history, int direction) {
        if (history == null || history.isEmpty()) {
            return;
        }

        if (model.getHistoryHighlightedIndex() < 0) {
            model.setHistoryHighlightedIndex(0);
        } else {
            int next = model.getHistoryHighlightedIndex() + direction;
            model.setHistoryHighlightedIndex(Math.min(Math.max(0, next), history.size() - 1));
        }

        String selectedPath = history.get(model.getHistoryHighlightedIndex());
        String normalizedPath = normalizeEscapedForwardSlashes(selectedPath);
        model.setTargetPath(appendTrailingSeparator(normalizedPath));
    }

    // ---- Target from tree ---------------------------------------------------

    /** Set the target path from a tree path (normalized, trailing separator). */
    public static void setTargetPathFromTree(MoveDialogModel model, String path) {
        String normalizedPath = normalizeEscapedForwardSlashes(path);
        model.setTargetPath(appendTrailingSeparator(normalizedPath));
    }

    // ---- Move history -------------------------------------------------------

    /**
     * Add {@code path} to the front of {@code history}, in place: the new entry
     * is canonicalized and stored without a trailing separator; any existing
     * duplicate (compared on trailing-separator-trimmed paths) is removed first;
     * the list is then trimmed from the tail to at most {@code limit} (clamped to
     * a minimum of 1) entries.
     *
     * <p>Ported from {@code MoveDialogCoordinator.addToMoveHistory}. {@code null}
     * {@code history} or {@code path} is a no-op.
     */
    public static void addToMoveHistory(List<String> history, String path, int limit) {
        if (history == null || path == null) {
            return;
        }
        String normalized = trimTrailingSeparator(canonicalize(path));
        history.removeIf(entry -> normalized.equals(trimTrailingSeparator(entry)));
        history.add(0, normalized);
        int effectiveLimit = Math.max(1, limit);
        while (history.size() > effectiveLimit) {
            history.remove(history.size() - 1);
        }
    }

    // ---- Mini directory tree ------------------------------------------------

    /**
     * Initialize the mini tree from {@link File#listRoots()} (each root expanded
     * one level via {@code loadChildren}), then expand down to the current
     * target path and highlight it. No-op if already initialized. Mirrors
     * {@code MoveDialogCoordinator.initializeMiniTree}.
     */
    public static void initializeMiniTree(MoveDialogModel model, LoadChildren loadChildren) {
        if (model.isMiniTreeInitialized()) {
            return;
        }

        List<TreeNode> nodes = new ArrayList<>();
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                String rootPath = root.getAbsolutePath();
                List<TreeNode> rootChildren = loadChildren.apply(Paths.get(rootPath));
                nodes.add(new TreeNode(rootPath, rootPath, true, rootChildren, false));
            }
        }
        model.setMiniTreeNodes(nodes);
        model.setMiniTreeInitialized(true);

        String targetPath = trimTrailingSeparator(model.getTargetPath());
        if (targetPath != null && !targetPath.isEmpty()) {
            expandMiniTreeToPath(model, targetPath, loadChildren);
            model.setMiniTreeHighlightedPath(targetPath);
        }
    }

    /** Expand the mini tree to reveal every ancestor of {@code path}. */
    public static void expandMiniTreeToPath(MoveDialogModel model, String path, LoadChildren loadChildren) {
        String normalized = trimTrailingSeparator(path);
        if (normalized == null || normalized.isEmpty()) {
            return;
        }

        // Platform-safe ancestor decomposition (handles "/" and "C:\" roots).
        // A target that isn't a parseable path (e.g. a stale, corrupt history
        // entry) simply can't be revealed in the tree — skip it rather than let
        // Paths.get throw an uncaught InvalidPathException on the FX thread.
        Path nioPath;
        try {
            nioPath = Paths.get(normalized);
        } catch (InvalidPathException e) {
            return;
        }
        List<String> segments = new ArrayList<>();
        Path root = nioPath.getRoot();
        if (root != null) {
            segments.add(root.toString());
        }
        for (int i = 1; i <= nioPath.getNameCount(); i++) {
            Path sub = nioPath.subpath(0, i);
            segments.add(root != null ? root.resolve(sub).toString() : sub.toString());
        }

        for (String segment : segments) {
            model.setMiniTreeNodes(mutateMiniTree(model.getMiniTreeNodes(), segment, node -> {
                List<TreeNode> children = node.getChildren().isEmpty()
                        ? loadChildren.apply(Paths.get(node.getPath())) : node.getChildren();
                return new TreeNode(node.getName(), node.getPath(), true, children, false);
            }));
        }
    }

    /** Toggle a node's expansion (lazy-loading children on first expand). */
    public static void toggleMiniTreeExpansion(MoveDialogModel model, String path, LoadChildren loadChildren) {
        model.setMiniTreeNodes(mutateMiniTree(model.getMiniTreeNodes(), path, node -> {
            if (node.isExpanded()) {
                return new TreeNode(node.getName(), node.getPath(), false, node.getChildren(), false);
            }
            List<TreeNode> children = node.getChildren().isEmpty()
                    ? loadChildren.apply(Paths.get(node.getPath())) : node.getChildren();
            return new TreeNode(node.getName(), node.getPath(), true, children, false);
        }));
    }

    /** Expand a node if collapsed (lazy-loading children); no-op if expanded. */
    public static void expandMiniTreeNode(MoveDialogModel model, String path, LoadChildren loadChildren) {
        model.setMiniTreeNodes(mutateMiniTree(model.getMiniTreeNodes(), path, node -> {
            if (node.isExpanded()) {
                return node;
            }
            List<TreeNode> children = node.getChildren().isEmpty()
                    ? loadChildren.apply(Paths.get(node.getPath())) : node.getChildren();
            return new TreeNode(node.getName(), node.getPath(), true, children, false);
        }));
    }

    /** Collapse a node if expanded (retaining its children); no-op otherwise. */
    public static void collapseMiniTreeNode(MoveDialogModel model, String path) {
        model.setMiniTreeNodes(mutateMiniTree(model.getMiniTreeNodes(), path, node -> {
            if (!node.isExpanded()) {
                return node;
            }
            return new TreeNode(node.getName(), node.getPath(), false, node.getChildren(), false);
        }));
    }

    /** Flatten the mini tree to its currently visible nodes (depth-first, only
     * descending into expanded nodes). */
    public static List<TreeNode> flattenedMiniTreeNodes(MoveDialogModel model) {
        List<TreeNode> result = new ArrayList<>();
        flattenNodes(model.getMiniTreeNodes(), result);
        return result;
    }

    /**
     * Move the tree highlight by {@code direction} over the flattened visible
     * list (clamped) and fill the target field with the highlighted path. An
     * unmatched current highlight snaps to the first/last node depending on
     * direction. Mirrors {@code MoveDialogCoordinator.moveMiniTreeHighlight}.
     */
    public static void moveMiniTreeHighlight(MoveDialogModel model, int direction) {
        List<TreeNode> flat = flattenedMiniTreeNodes(model);
        if (flat.isEmpty()) {
            return;
        }

        int currentIndex = indexOfPath(flat, model.getMiniTreeHighlightedPath());

        int newIndex;
        if (currentIndex < 0) {
            newIndex = direction > 0 ? 0 : flat.size() - 1;
        } else {
            newIndex = Math.min(Math.max(0, currentIndex + direction), flat.size() - 1);
        }

        String selectedPath = normalizeEscapedForwardSlashes(flat.get(newIndex).getPath());
        model.setMiniTreeHighlightedPath(selectedPath);
        model.setTargetPath(appendTrailingSeparator(selectedPath));
    }

    /**
     * Left arrow in the tree zone: collapse the highlighted node if expanded,
     * else jump to its parent (only when the parent is currently visible).
     * Mirrors {@code MoveDialogCoordinator.miniTreeHandleLeft}.
     */
    public static void miniTreeHandleLeft(MoveDialogModel model) {
        List<TreeNode> flat = flattenedMiniTreeNodes(model);
        TreeNode currentNode = findByPath(flat, model.getMiniTreeHighlightedPath());
        if (currentNode == null) {
            return;
        }

        if (currentNode.isExpanded()) {
            collapseMiniTreeNode(model, currentNode.getPath());
        } else {
            String parentPath = parentPathOf(currentNode.getPath());
            if (findByPath(flat, parentPath) != null) {
                String normalizedParentPath = normalizeEscapedForwardSlashes(parentPath);
                model.setMiniTreeHighlightedPath(normalizedParentPath);
                model.setTargetPath(appendTrailingSeparator(normalizedParentPath));
            }
        }
    }

    /** Right arrow in the tree zone: expand the highlighted node. */
    public static void miniTreeHandleRight(MoveDialogModel model, LoadChildren loadChildren) {
        expandMiniTreeNode(model, model.getMiniTreeHighlightedPath(), loadChildren);
    }

    /** Click-select a tree node: highlight it and fill the target field. */
    public static void selectMiniTreeNode(MoveDialogModel model, String path) {
        String normalizedPath = normalizeEscapedForwardSlashes(path);
        model.setMiniTreeHighlightedPath(normalizedPath);
        model.setTargetPath(appendTrailingSeparator(normalizedPath));
    }

    // ---- Path helpers -------------------------------------------------------

    /**
     * Canonical form of {@code path}: {@code .}/{@code ..} segments resolved and
     * made absolute (relative to the working directory). Purely syntactic —
     * symlinks are NOT resolved — so history entries stay stable and
     * predictable. Mirrors the role of {@code iris94.core.util.PathUtils.normalizePath}
     * (the handoff specifies {@code Path.toAbsolutePath().normalize()} here).
     */
    static String canonicalize(String path) {
        if (path == null) {
            return null;
        }
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            return path; // leave an unparseable path untouched rather than crash
        }
    }

    /**
     * Append a trailing separator if absent (the target-field form, signalling
     * "a directory to move into"). Ported from
     * {@code MoveDialogCoordinator.appendTrailingSeparator}.
     */
    static String appendTrailingSeparator(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.endsWith(File.separator) || path.endsWith("/")) {
            return path;
        }
        return path + File.separator;
    }

    /**
     * Strip a single trailing {@code /} or platform separator (the history form,
     * which stores paths without one). Ported from
     * {@code MoveDialogCoordinator.trimTrailingSeparator}.
     */
    static String trimTrailingSeparator(String path) {
        if (path == null || path.length() <= 1) {
            return path;
        }
        if (path.endsWith("/") || path.endsWith(File.separator)) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Replace escaped forward slashes ({@code "\/"} → {@code "/"}) on every
     * target-affecting path. Ported from
     * {@code MoveDialogCoordinator.normalizeEscapedForwardSlashes}.
     */
    static String normalizeEscapedForwardSlashes(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace("\\/", "/");
        if (!normalized.equals(path) && Boolean.getBoolean(DEBUG_PROPERTY)) {
            System.err.println("[MoveDialogLogic] Normalized escaped forward slash sequence(s) in path value");
        }
        return normalized;
    }

    /**
     * Parent of {@code path}, or {@code path} itself at a filesystem root.
     *
     * <p>Separator-agnostic and string-based so the result matches the
     * forward-slash tree paths regardless of OS. (The old {@code new
     * File(path).getParent()} returned a backslash-separated parent on Windows,
     * so {@code findByPath} never matched and the left-arrow jump-to-parent
     * silently no-opped.) {@code slash <= 0} returns {@code path} unchanged so a
     * root node stays put, matching the old {@code File.getParent() == null}
     * behavior.
     */
    private static String parentPathOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash <= 0 ? path : path.substring(0, slash);
    }

    private static int indexOfPath(List<TreeNode> flat, String path) {
        for (int i = 0; i < flat.size(); i++) {
            if (flat.get(i).getPath().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private static TreeNode findByPath(List<TreeNode> flat, String path) {
        for (TreeNode node : flat) {
            if (node.getPath().equals(path)) {
                return node;
            }
        }
        return null;
    }

    private static void flattenNodes(List<TreeNode> nodes, List<TreeNode> result) {
        for (TreeNode node : nodes) {
            result.add(node);
            if (node.isExpanded()) {
                flattenNodes(node.getChildren(), result);
            }
        }
    }

    /**
     * Rebuild {@code nodes} (immutably, depth-first) applying {@code mutate} to
     * the single node whose path equals {@code targetPath}. Subtrees without the
     * target are reused unchanged; subtrees that may contain it are rebuilt.
     * Mirrors {@code MoveDialogCoordinator.mutateMiniTree}.
     */
    private static List<TreeNode> mutateMiniTree(List<TreeNode> nodes, String targetPath, UnaryOperator<TreeNode> mutate) {
        List<TreeNode> result = new ArrayList<>();
        for (TreeNode node : nodes) {
            if (node.getPath().equals(targetPath)) {
                result.add(mutate.apply(node));
            } else if (node.getChildren().isEmpty()) {
                result.add(node);
            } else {
                result.add(new TreeNode(
                        node.getName(),
                        node.getPath(),
                        node.isExpanded(),
                        mutateMiniTree(node.getChildren(), targetPath, mutate),
                        node.isLoading()));
            }
        }
        return result;
    }
}
