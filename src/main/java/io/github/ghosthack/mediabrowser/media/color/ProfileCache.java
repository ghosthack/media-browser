package io.github.ghosthack.mediabrowser.media.color;

import io.github.ghosthack.mediabrowser.media.ColorProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Bounded LRU over {@link EmbeddedProfileReader}, so probe, full view, and
 * thumbnail of the same item share one metadata scan (absence is cached too).
 * Keys carry size and mtime, so a replaced file re-scans; the Path object
 * itself is part of the key, which keeps archive-entry paths distinct without
 * flattening them to strings.
 */
final class ProfileCache {

    private static final int MAX_ENTRIES = 4096;

    private record Key(Path path, long size, long modifiedMillis) {}

    private final Function<Path, Optional<ColorProfile>> loader;
    private final Map<Key, Optional<ColorProfile>> entries =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Optional<ColorProfile>> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    ProfileCache(Function<Path, Optional<ColorProfile>> loader) {
        this.loader = loader;
    }

    Optional<ColorProfile> profile(Path file) {
        Key key = key(file);
        if (key == null) {
            return loader.apply(file);
        }
        Optional<ColorProfile> cached;
        synchronized (entries) {
            cached = entries.get(key);
        }
        if (cached != null) {
            return cached;
        }
        Optional<ColorProfile> loaded = loader.apply(file);
        synchronized (entries) {
            entries.put(key, loaded);
        }
        return loaded;
    }

    private static Key key(Path file) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(file, BasicFileAttributes.class);
            return new Key(file, attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (IOException | RuntimeException ex) {
            return null; // unkeyable (gone, or an exotic provider) — scan uncached
        }
    }
}
