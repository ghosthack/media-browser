package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.album.Album;
import io.github.ghosthack.mediabrowser.album.AlbumStore;
import io.github.ghosthack.mediabrowser.media.MediaService;

import javafx.application.Platform;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
 *
 * <p>The album directory listing and the album file rewrite run on the file-op
 * thread, never the FX thread: the menu shows the recents it last knew and
 * updates them when the listing lands, and an add reports its outcome (or its
 * failure) in the status line when the write finishes.</p>
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
    private final MoveController.FileOpRunner fileOps;
    private final Executor fxThread;
    /** Latest-wins ticket for {@link #refresh}: an older listing landing late is dropped. */
    private int refreshSequence;
    /** The recents on show, to leave the submenu alone when a listing changes nothing. */
    private List<Album> shownRecents;

    public AlbumMenu(MediaService service, AlbumStore store, AppSettings settings,
                     Supplier<List<Path>> selection, Window owner, Consumer<String> status) {
        this(service::fileOp, Platform::runLater, store, settings, selection, owner, status);
    }

    AlbumMenu(MoveController.FileOpRunner fileOps, Executor fxThread,
              AlbumStore store, AppSettings settings,
              Supplier<List<Path>> selection, Window owner, Consumer<String> status) {
        this.fileOps = fileOps;
        this.fxThread = fxThread;
        this.store = store;
        this.settings = settings;
        this.selection = selection;
        this.owner = owner;
        this.status = status;
        show(List.of());
        refresh();
    }

    /** The submenu node to add to a {@link javafx.scene.control.ContextMenu}. */
    public Menu menu() {
        return menu;
    }

    /**
     * Brings the recent-album items up to date; call when the owning menu is
     * showing. The album directory is listed off the FX thread, so the items
     * on show stay until the listing lands — a moment later, normally.
     */
    public void refresh() {
        int ticket = ++refreshSequence;
        List<String> recentNames = List.copyOf(settings.albumRecents());   // FX-side state
        CompletableFuture<List<Album>> listing;
        try {
            listing = fileOps.run(() -> recentAlbums(recentNames));
        } catch (RuntimeException e) {       // the executor refusing the task
            listing = CompletableFuture.failedFuture(e);
        }
        listing.whenComplete((recents, error) -> fxThread.execute(() -> {
            if (ticket != refreshSequence) return;
            if (error != null) {
                status.accept("Cannot list albums: " + message(error));
                return;
            }
            if (!recents.equals(shownRecents)) show(recents);
        }));
    }

    private void show(List<Album> recents) {
        shownRecents = recents;
        var items = menu.getItems();
        items.clear();
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

    /** The recent albums that still exist on disk, most-recent first, capped. Off the FX thread. */
    private List<Album> recentAlbums(List<String> recentNames) {
        Map<String, Album> byFileName = new HashMap<>();
        for (Album album : store.albums()) {
            byFileName.put(album.fileName(), album);
        }
        List<Album> result = new ArrayList<>();
        for (String fileName : recentNames) {
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
        List<Path> adding = List.copyOf(sources);
        status.accept("Adding to " + album.name() + "\u2026");
        CompletableFuture<AlbumStore.AddResult> write;
        try {
            write = fileOps.run(() -> {
                try {
                    return store.addPaths(album, adding);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (RuntimeException e) {       // the executor refusing the task
            write = CompletableFuture.failedFuture(e);
        }
        // No staleness guard: the user asked for this add, so its outcome is
        // reported whatever they have done since.
        write.whenComplete((result, error) -> fxThread.execute(() -> {
            if (error != null) {
                status.accept("Cannot add to " + album.name() + ": " + message(error));
                return;
            }
            recordUse(album);
            status.accept(addMessage(album, result));
        }));
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
