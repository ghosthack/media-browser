package io.github.ghosthack.mediabrowser.media.move;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable node for the move dialog's lazy directory tree. Children are loaded
 * lazily on first expand. {@code equals}/{@code hashCode} are based on
 * {@link #getPath() path} only, so a node can be looked up by its absolute path
 * regardless of expansion/loading state.
 *
 * <p>Ported from {@code iris94.core.models.TreeNode}; kept String-based (paths
 * as absolute path strings) so the {@code MoveDialogLogic} mini-tree algorithms
 * can compare highlighted paths to node paths directly.
 */
public final class TreeNode {

    private final String name;
    private final String path;
    private boolean expanded;
    private List<TreeNode> children;
    private boolean loading;

    public TreeNode(String name, String path, boolean expanded,
                    List<TreeNode> children, boolean loading) {
        this.name = name;
        this.path = path;
        this.expanded = expanded;
        this.children = children != null ? children : new ArrayList<>();
        this.loading = loading;
    }

    public String getName() { return name; }
    public String getPath() { return path; }

    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }

    public List<TreeNode> getChildren() { return children; }
    public void setChildren(List<TreeNode> children) { this.children = children; }

    public boolean isLoading() { return loading; }
    public void setLoading(boolean loading) { this.loading = loading; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TreeNode other = (TreeNode) o;
        return path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return "TreeNode{name='" + name + "', path='" + path + "', expanded=" + expanded
                + ", children=" + children.size() + ", loading=" + loading + "}";
    }
}
