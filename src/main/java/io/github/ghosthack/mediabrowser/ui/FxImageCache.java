package io.github.ghosthack.mediabrowser.ui;

import javafx.scene.image.Image;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Access-ordered FX image cache measured by retained pixel bytes rather than
 * entry count. JavaFX does not expose the backing allocation size, so the
 * stable conservative estimate is width × height × four-byte ARGB pixels.
 */
final class FxImageCache<K> extends LinkedHashMap<K, Image> {
    private long maxBytes;
    private int minEntries;
    private long usedBytes;

    FxImageCache(long maxBytes, int minEntries) {
        super(64, 0.75f, true);
        setLimits(maxBytes, minEntries);
    }

    void setLimits(long maxBytes, int minEntries) {
        this.maxBytes = Math.max(1, maxBytes);
        this.minEntries = Math.max(0, minEntries);
        trim();
    }

    long usedBytes() {
        return usedBytes;
    }

    @Override
    public Image put(K key, Image value) {
        Image previous = super.put(key, value);
        if (previous != null) usedBytes -= estimatedBytes(previous);
        usedBytes = saturatedAdd(usedBytes, estimatedBytes(value));
        trim();
        return previous;
    }

    @Override
    public Image remove(Object key) {
        Image removed = super.remove(key);
        if (removed != null) usedBytes -= estimatedBytes(removed);
        return removed;
    }

    @Override
    public void clear() {
        super.clear();
        usedBytes = 0;
    }

    private void trim() {
        Iterator<Map.Entry<K, Image>> entries = entrySet().iterator();
        while (usedBytes > maxBytes && size() > minEntries && entries.hasNext()) {
            Map.Entry<K, Image> eldest = entries.next();
            usedBytes -= estimatedBytes(eldest.getValue());
            entries.remove();
        }
    }

    static long estimatedBytes(Image image) {
        if (image == null) return 0;
        long width = Math.max(0, (long) Math.ceil(image.getWidth()));
        long height = Math.max(0, (long) Math.ceil(image.getHeight()));
        if (width == 0 || height == 0) return 0;
        if (width > Long.MAX_VALUE / height / 4) return Long.MAX_VALUE;
        return width * height * 4;
    }

    private static long saturatedAdd(long first, long second) {
        if (second > Long.MAX_VALUE - first) return Long.MAX_VALUE;
        return first + second;
    }
}
