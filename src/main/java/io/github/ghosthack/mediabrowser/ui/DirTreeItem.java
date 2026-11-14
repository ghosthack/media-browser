package io.github.ghosthack.mediabrowser.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Lazily-populated directory node for the navigation tree.
 *
 * <p>The subfolders are listed off the FX thread (a listing plus a stat per
 * child can stall on a slow volume or a huge folder): the first request for
 * children starts the listing and answers with the still-empty list, which
 * fills in when the listing lands. {@link #whenLoaded} lets a caller that needs
 * the children — the browser mirroring its location into the tree — wait for
 * them. {@link #refresh} re-lists a node whose folder changed, keeping the
 * surviving child nodes (and so their expansion and subtrees).</p>
 *
 * <p>FX-thread confined, apart from the listing itself.</p>
 */
public final class DirTreeItem extends TreeItem<Path> {

    /**
     * How a tree's nodes list their folders.
     *
     * @param runner  runs a blocking listing off the FX thread
     * @param fx      gets back onto the FX thread
     * @param applier runs a change to a node's children on the FX thread; the
     *                owner wraps it so a selection the change displaces does
     *                not read as the user picking another folder
     * @param onError told, on the FX thread, of a folder that could not be listed
     */
    record Loading(Function<Supplier<List<Path>>, CompletableFuture<List<Path>>> runner,
                   Executor fx,
                   Consumer<Runnable> applier,
                   BiConsumer<Path, Throwable> onError) {
    }

    private final Loading loading;
    /** Whether a listing has ever landed. */
    private boolean loaded;
    /** Whether a listing is under way whose result has not landed yet. */
    private boolean inFlight;
    /** Latest listing wins: an older one landing late is dropped. */
    private int generation;
    private final List<Runnable> waiters = new ArrayList<>();

    DirTreeItem(Path dir, Loading loading) {
        super(dir);
        this.loading = loading;
    }

    @Override
    public boolean isLeaf() {
        // Cheap answer: directories always get an expand arrow; empty ones
        // simply expand to nothing. Avoids IO during tree rendering.
        return false;
    }

    @Override
    public ObservableList<TreeItem<Path>> getChildren() {
        if (!loaded && !inFlight) load();
        return super.getChildren();
    }

    /** Whether the children are known and no re-listing is pending. */
    boolean isSettled() {
        return loaded && !inFlight;
    }

    /**
     * Runs {@code action} once the children are known and no listing is
     * pending — at once when that is already so, otherwise on the FX thread
     * when the listing lands (starting one if none has been asked for yet).
     */
    void whenLoaded(Runnable action) {
        if (isSettled()) {
            action.run();
            return;
        }
        waiters.add(action);
        if (!inFlight) load();
    }

    /** Re-lists a node already listed; one never asked for children stays lazy. */
    void refresh() {
        if (loaded || inFlight) load();
    }

    /** {@link #refresh} for this node and every listed node below it. */
    void refreshLoaded() {
        if (!loaded && !inFlight) return;
        for (TreeItem<Path> child : super.getChildren()) {
            if (child instanceof DirTreeItem dir) dir.refreshLoaded();
        }
        load();
    }

    private void load() {
        inFlight = true;
        int ticket = ++generation;
        Path dir = getValue();
        CompletableFuture<List<Path>> listing;
        try {
            listing = loading.runner().apply(() -> scan(dir));
        } catch (RuntimeException e) {       // the executor refusing the task
            listing = CompletableFuture.failedFuture(e);
        }
        listing.whenComplete((paths, error) -> loading.fx().execute(() -> {
            if (ticket != generation) return;        // a newer listing is on its way
            inFlight = false;
            loaded = true;
            if (error == null) {
                loading.applier().accept(() -> merge(paths));
            } else if (!vanished(error)) {
                // The children on show stay: a failed re-listing says nothing
                // about them. A folder that is simply gone is not an error
                // here — its parent's listing drops the node.
                loading.onError().accept(dir, error);
            }
            List<Runnable> ready = List.copyOf(waiters);
            waiters.clear();
            ready.forEach(Runnable::run);
        }));
    }

    /**
     * Brings the children in line with {@code paths} (sorted as {@link #scan}
     * sorts) by removing the nodes whose folder is gone and inserting the new
     * ones in place, so untouched nodes — and a selection among them — are left
     * alone.
     */
    private void merge(List<Path> paths) {
        ObservableList<TreeItem<Path>> children = super.getChildren();
        Set<Path> wanted = new HashSet<>(paths);
        children.removeIf(child -> !wanted.contains(child.getValue()));
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            if (i < children.size() && path.equals(children.get(i).getValue())) continue;
            if (containsValue(children, path)) break;   // order differs: rebuild below
            children.add(i, new DirTreeItem(path, loading));
        }
        if (matches(children, paths)) return;
        Map<Path, TreeItem<Path>> existing = new HashMap<>();
        for (TreeItem<Path> child : children) existing.put(child.getValue(), child);
        List<TreeItem<Path>> rebuilt = new ArrayList<>(paths.size());
        for (Path path : paths) {
            TreeItem<Path> kept = existing.get(path);
            rebuilt.add(kept != null ? kept : new DirTreeItem(path, loading));
        }
        children.setAll(rebuilt);
    }

    private static boolean containsValue(List<TreeItem<Path>> children, Path path) {
        for (TreeItem<Path> child : children) {
            if (path.equals(child.getValue())) return true;
        }
        return false;
    }

    private static boolean matches(List<TreeItem<Path>> children, List<Path> paths) {
        if (children.size() != paths.size()) return false;
        for (int i = 0; i < paths.size(); i++) {
            if (!paths.get(i).equals(children.get(i).getValue())) return false;
        }
        return true;
    }

    private static boolean vanished(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof NoSuchFileException || t instanceof NotDirectoryException) return true;
        }
        return false;
    }

    /** The visible subfolders of {@code dir}, sorted by name. Runs off the FX thread. */
    private static List<Path> scan(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children
                    .filter(p -> Files.isDirectory(p) && !isHidden(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isHidden(Path p) {
        try {
            return Files.isHidden(p);
        } catch (IOException e) {
            return true;
        }
    }
}
