package io.github.ghosthack.mediabrowser.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Lazily-populated directory node for the navigation tree. */
public final class DirTreeItem extends TreeItem<Path> {

    private boolean loaded;

    public DirTreeItem(Path dir) {
        super(dir);
    }

    @Override
    public boolean isLeaf() {
        // Cheap answer: directories always get an expand arrow; empty ones
        // simply expand to nothing. Avoids IO during tree rendering.
        return false;
    }

    @Override
    public ObservableList<TreeItem<Path>> getChildren() {
        if (!loaded) {
            loaded = true;
            super.getChildren().setAll(scan());
        }
        return super.getChildren();
    }

    private List<TreeItem<Path>> scan() {
        try (Stream<Path> children = Files.list(getValue())) {
            return children
                    .filter(p -> Files.isDirectory(p) && !isHidden(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .<TreeItem<Path>>map(DirTreeItem::new)
                    .toList();
        } catch (IOException | SecurityException e) {
            return List.of();
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
