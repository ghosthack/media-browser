package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.album.Album;
import io.github.ghosthack.mediabrowser.album.AlbumStore;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The "Add to Album" submenu shared by every window's right-click menu. It lists
 * the most-recently-used albums (or a disabled "No recent albums" note) followed
 * by an "Albums…" item that opens the {@link AlbumsDialog}. The owning context
 * menu should call {@link #refresh()} when it is about to show so the recents
 * stay current.
 *
 * <p>The current selection is supplied lazily (read at action time, not when the
 * menu is built) so the same submenu instance can serve a context menu that is
 * popped over a changing selection. Album files and recency are managed by
 * {@link AlbumStore} / {@link AppSettings}; this class is purely the view + the
 * thin glue that wires a menu item to an add.
 */
public final class AlbumMenu {

    /** How many recent albums the submenu surfaces (the rest live in the dialog). */
    private static final int RECENT_SHOWN = 3;

    private final Menu menu = new Menu("Add to Album");
    private final AlbumStore store;
    private final AppSettings settings;
    private final Supplier<List<Path>> selection;
    private final Window owner;
    private final Consumer<String> status;

    public AlbumMenu(AlbumStore store, AppSettings settings,
                     Supplier<List<Path>> selection, Window owner, Consumer<String> status) {
        this.store = store;
        this.settings = settings;
        this.selection = selection;
        this.owner = owner;
        this.status = status;
        refresh();
    }

    /** The submenu node to add to a {@link javafx.scene.control.ContextMenu}. */
    public Menu menu() {
        return menu;
    }

    /** Rebuilds the recent-album items; call when the owning menu is showing. */
    public void refresh() {
        var items = menu.getItems();
        items.clear();
        List<Album> recents = recentAlbums();
        if (recents.isEmpty()) {
            var none = new MenuItem("No recent albums");
            none.setDisable(true);
            items.add(none);
        } else {
            for (Album album : recents) {
                var item = new MenuItem(album.name());
                item.setOnAction(e -> addToAlbum(album));
                items.add(item);
            }
        }
        items.add(new SeparatorMenuItem());
        var manage = new MenuItem("Albums\u2026");
        manage.setOnAction(e -> openDialog());
        items.add(manage);
    }

    /** The recent albums that still exist on disk, most-recent first, capped. */
    private List<Album> recentAlbums() {
        Map<String, Album> byFileName = new HashMap<>();
        for (Album album : store.albums()) {
            byFileName.put(album.fileName(), album);
        }
        List<Album> result = new ArrayList<>();
        for (String fileName : settings.albumRecents()) {
            Album album = byFileName.get(fileName);
            if (album != null) {
                result.add(album);
                if (result.size() >= RECENT_SHOWN) {
                    break;
                }
            }
        }
        return result;
    }

    private void addToAlbum(Album album) {
        List<Path> sources = selection.get();
        if (sources == null || sources.isEmpty()) {
            status.accept("No items selected to add to an album.");
            return;
        }
        try {
            AlbumStore.AddResult result = store.addPaths(album, sources);
            recordUse(album);
            status.accept(addMessage(album, result));
        } catch (IOException | UncheckedIOException e) {
            status.accept("Cannot add to " + album.name() + ": " + message(e));
        }
    }

    private void openDialog() {
        List<Path> sources = selection.get();
        new AlbumsDialog(owner, store, settings,
                sources == null ? List.of() : sources, status).showAndWait();
    }

    private void recordUse(Album album) {
        settings.recordAlbumUse(album.fileName());
        try {
            settings.save();
        } catch (IOException e) {
            // Recency is a convenience; a failed persist is not worth surfacing.
            System.err.println("Cannot save album recents: " + message(e));
        }
    }

    /** The status-line message describing an add (shared with the dialog). */
    static String addMessage(Album album, AlbumStore.AddResult result) {
        if (result.added() == 0) {
            return "All " + result.skipped() + " already in " + album.name();
        }
        String message = "Added " + result.added() + " item"
                + (result.added() == 1 ? "" : "s") + " to " + album.name();
        if (result.skipped() > 0) {
            message += " (" + result.skipped() + " already present)";
        }
        return message;
    }

    private static String message(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
