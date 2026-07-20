package io.github.ghosthack.mediabrowser.media;

import java.util.function.Supplier;

/**
 * Storage seam for preview renditions, keyed by {@link ThumbnailKey}. The
 * only implementation today is the in-memory {@link LruThumbnailCache}; a
 * future disk tier slots in behind this same interface (e.g. a layered
 * cache that checks memory, then disk, then generates), so nothing above the
 * facade — the service, the mosaic — has to change.
 *
 * <p>Implementations must be safe for concurrent use: the thumbnail pool
 * calls {@link #get} from several threads at once, for distinct keys.</p>
 */
public interface ThumbnailCache {

    /**
     * Non-blocking lookup of already-resident tiers (in-memory only); returns
     * {@code null} on a miss. Used from the thumbnail worker path before the
     * potentially expensive {@link #get}; never performs I/O.
     */
    Thumbnail peek(ThumbnailKey key);

    /**
     * Returns the cached rendition for {@code key}, or, on a miss, invokes
     * {@code loader} (potentially expensive native decode), stores the result
     * and returns it. Called on a worker thread.
     */
    Thumbnail get(ThumbnailKey key, Supplier<Thumbnail> loader);

    /** Drops all cached renditions. */
    void clear();

    /** Number of renditions currently resident, for diagnostics. */
    int entryCount();

    /** Total retained pixel bytes of resident renditions, for diagnostics. */
    long usedBytes();
}
