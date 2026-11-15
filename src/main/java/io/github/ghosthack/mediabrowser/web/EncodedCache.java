package io.github.ghosthack.mediabrowser.web;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encoded tiles and full views, least recently used first out, bounded by
 * their total size in bytes. Keys carry everything that changes the bytes
 * (path, size, mtime, adjustments, quality, encoder), so a stale entry is
 * never served — it just ages out.
 */
final class EncodedCache {

    record Entry(byte[] bytes, String etag, String corruption) {}

    private final long budgetBytes;
    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;

    EncodedCache(long budgetBytes) {
        this.budgetBytes = budgetBytes;
    }

    synchronized Entry get(String key) {
        return map.get(key);
    }

    synchronized void put(String key, Entry entry) {
        if (entry.bytes().length > budgetBytes) return;
        Entry old = map.put(key, entry);
        if (old != null) bytes -= old.bytes().length;
        bytes += entry.bytes().length;
        for (Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
                bytes > budgetBytes && it.hasNext(); ) {
            bytes -= it.next().getValue().bytes().length;
            it.remove();
        }
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized int size() {
        return map.size();
    }

    synchronized void clear() {
        map.clear();
        bytes = 0;
    }
}
