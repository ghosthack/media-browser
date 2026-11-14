package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.album.Album;

import javafx.application.Platform;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.ToIntFunction;

/**
 * The album dialogs' per-album member counts, computed off the FX thread.
 * Counting re-reads the album's CSV and resolves every archive member through
 * {@code ArchivePaths.parse}, which mounts its container — done in a cell's
 * {@code updateItem}, an album of ISO or RAR entries froze the dialog on every
 * scroll. Each album is counted once and cached; until its count lands the
 * label shows a placeholder, and {@code onCounted} (the list's refresh) runs
 * when it does. A count that fails says so rather than showing zero. FX thread
 * only, apart from the counting itself.
 */
final class AlbumCounts {

    /** Shown in place of the count while it is being computed. */
    static final String PENDING = "counting…";

    /** One daemon thread: counts run one at a time, never mounting in parallel. */
    private static final Executor COUNTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "album-counts");
        t.setDaemon(true);
        return t;
    });

    private final ToIntFunction<Album> counter;
    private final Executor background;
    private final Executor fx;
    private final Runnable onCounted;

    private final Map<Album, Integer> counts = new HashMap<>();
    private final Set<Album> failed = new HashSet<>();
    /** Albums being counted, to the request that will land; a newer request wins. */
    private final Map<Album, Long> pending = new HashMap<>();
    private long nextRequest;

    AlbumCounts(ToIntFunction<Album> counter, Runnable onCounted) {
        this(counter, COUNTER, Platform::runLater, onCounted);
    }

    AlbumCounts(ToIntFunction<Album> counter, Executor background, Executor fx,
                Runnable onCounted) {
        this.counter = counter;
        this.background = background;
        this.fx = fx;
        this.onCounted = onCounted;
    }

    /**
     * {@code album}'s list label — its name and member count, or
     * {@link #PENDING} while the count is computed (starting it on first sight).
     */
    String label(Album album) {
        Integer count = counts.get(album);
        String suffix;
        if (count != null) {
            suffix = count + " item" + (count == 1 ? "" : "s");
        } else if (failed.contains(album)) {
            suffix = "count unavailable";
        } else {
            request(album);
            suffix = PENDING;
        }
        return album.name() + "  (" + suffix + ")";
    }

    /** Forgets {@code album}'s count (its members changed); the next label recounts. */
    void invalidate(Album album) {
        counts.remove(album);
        failed.remove(album);
        pending.remove(album);
    }

    private void request(Album album) {
        if (pending.containsKey(album)) {
            return;
        }
        long request = ++nextRequest;
        pending.put(album, request);
        background.execute(() -> {
            int count = 0;
            RuntimeException error = null;
            try {
                count = counter.applyAsInt(album);
            } catch (RuntimeException e) {
                error = e;
            }
            int counted = count;
            RuntimeException failure = error;
            fx.execute(() -> land(album, request, counted, failure));
        });
    }

    private void land(Album album, long request, int count, RuntimeException error) {
        Long current = pending.get(album);
        if (current == null || current != request) {
            return; // invalidated while counting: a newer request owns the label
        }
        pending.remove(album);
        if (error == null) {
            counts.put(album, count);
        } else {
            failed.add(album);
            System.err.println("albums: cannot count " + album.name() + ": " + error);
        }
        onCounted.run();
    }
}
