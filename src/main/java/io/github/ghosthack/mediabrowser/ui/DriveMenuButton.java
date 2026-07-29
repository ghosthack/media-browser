package io.github.ghosthack.mediabrowser.ui;

import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Location-bar menu for jumping between filesystem roots ({@code C:\},
 * {@code D:\}, and so on on Windows, plus mounted volumes on macOS).
 *
 * <p>The roots are read whenever the menu opens rather than once at startup,
 * so a removable drive attached while the application is running appears on
 * the next click.</p>
 */
final class DriveMenuButton extends MenuButton {

    private final Consumer<Path> navigate;

    DriveMenuButton(Consumer<Path> navigate) {
        super("Drives");
        this.navigate = Objects.requireNonNull(navigate);
        setTooltip(new Tooltip("Go to a filesystem drive or root"));
        setOnShowing(event -> refresh());
        refresh();
    }

    private void refresh() {
        List<Path> roots = FileSystemRoots.discover();

        List<MenuItem> items = new ArrayList<>();
        for (Path root : roots) {
            var item = new MenuItem(root.toString());
            item.setOnAction(event -> navigate.accept(root));
            items.add(item);
        }
        if (items.isEmpty()) {
            var unavailable = new MenuItem("No drives available");
            unavailable.setDisable(true);
            items.add(unavailable);
        }
        getItems().setAll(items);
    }
}
