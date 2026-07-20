package io.github.ghosthack.mediabrowser.media;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * In-memory, byte-budgeted LRU cache of preview renditions. Eviction is by
 * total retained pixel bytes ({@link Thumbnail#byteSize()}), not entry count,
 * since a {@code maxEdge}-bounded tile has a bounded size and a byte budget
 * maps directly to the RAM the mosaic costs.
 *
 * <p>A miss runs its {@code loader} outside the lock, so decodes for distinct
 * keys proceed in parallel; only the short map updates are synchronized. The
 * service de-duplicates concurrent misses for the <em>same</em> key, so the
 * loader here is effectively called once per key.</p>
 */
public final class LruThumbnailCache implements ThumbnailCache {

    private final long budgetBytes;
    private long usedBytes;

    /** Access-ordered so the eldest entry is the least recently used. */
    private final LinkedHashMap<ThumbnailKey, Thumbnail> map =
            new LinkedHashMap<>(64, 0.75f, true);

    public LruThumbnailCache(long budgetBytes) {
        this.budgetBytes = Math.max(0, budgetBytes);
    }

    @Override
    public synchronized Thumbnail peek(ThumbnailKey key) {
        return map.get(key);
    }

    @Override
    public Thumbnail get(ThumbnailKey key, Supplier<Thumbnail> loader) {
        synchronized (this) {
            Thumbnail hit = map.get(key);
            if (hit != null) return hit;
        }
        Thumbnail loaded = loader.get();
        synchronized (this) {
            // Another thread may have stored the same key meanwhile; keep the
            // existing entry (preserves its LRU position) and drop ours.
            Thumbnail existing = map.get(key);
            if (existing != null) return existing;
            map.put(key, loaded);
            usedBytes += loaded.byteSize();
            evictToBudget();
        }
        return loaded;
    }

    @Override
    public synchronized void clear() {
        map.clear();
        usedBytes = 0;
    }

    /** Current retained pixel bytes; intended for tests/diagnostics. */
    @Override
    public synchronized long usedBytes() {
        return usedBytes;
    }

    @Override
    public synchronized int entryCount() {
        return map.size();
    }

    /** Evicts least-recently-used entries until within budget (keeps ≥1 entry). */
    private void evictToBudget() {
        Iterator<Map.Entry<ThumbnailKey, Thumbnail>> it = map.entrySet().iterator();
        while (usedBytes > budgetBytes && map.size() > 1 && it.hasNext()) {
            Map.Entry<ThumbnailKey, Thumbnail> eldest = it.next();
            usedBytes -= eldest.getValue().byteSize();
            it.remove();
        }
    }
}
