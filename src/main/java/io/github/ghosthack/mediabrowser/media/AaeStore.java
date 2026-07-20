package io.github.ghosthack.mediabrowser.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * A thread-safe, {@link Path}-based facade resolving an image to its parsed
 * Apple Photos {@code .AAE} edit sidecar, mirroring {@link RotationStore}: an
 * AAE edit is composed <em>above</em> the decoders, so this just answers "what
 * edit applies to this file?" cheaply on every viewer load and mosaic repaint.
 *
 * <p>Each directory's {@code .AAE} files are listed and parsed <strong>once</strong>
 * (lazily, on first sight) into an in-memory index keyed by the sidecar's stem
 * ({@code IMG_7085.AAE} → {@code img_7085}); lookups are O(1) thereafter, so the
 * mosaic can ask per tile per frame without re-reading or re-parsing. Sidecars
 * that fail to parse (unreadable, or a legacy binary-plist {@code adjustmentData}
 * we don't understand) are simply absent from the index, so the untouched master
 * is shown.
 *
 * <p>Resolution honours both the same-stem pairing and Apple's edited-copy
 * variant: {@code IMG_E7085.HEIC} resolves to {@code IMG_7085.AAE}. The index is
 * never invalidated by an edit we make (we don't write AAEs); {@link #invalidate}
 * exists for external changes.
 */
public final class AaeStore {

    /** Per-directory parsed index, keyed by canonical directory path string. */
    private final ConcurrentMap<String, Map<String, AaeSidecar>> cache = new ConcurrentHashMap<>();

    /**
     * The parsed edit for {@code image}, or empty when the file has no usable
     * AAE sidecar (none present, or one we can't reproduce). Loads and caches the
     * image's directory index on first sight.
     */
    public Optional<AaeSidecar> forImage(Path image) {
        if (image == null) {
            return Optional.empty();
        }
        Path dir = image.getParent();
        if (dir == null) {
            return Optional.empty();
        }
        Map<String, AaeSidecar> index = indexFor(dir);
        if (index.isEmpty()) {
            return Optional.empty();
        }
        String stem = Sidecars.stem(image);
        AaeSidecar hit = index.get(stem);
        if (hit == null) {
            // An edited copy (IMG_E7085.HEIC) is governed by the original's AAE.
            String original = Sidecars.originalVariant(stem);
            if (original != null) {
                hit = index.get(original);
            }
        }
        return Optional.ofNullable(hit);
    }

    /** Drops the cached index for {@code dir} (e.g. on an external change). */
    public void invalidate(Path dir) {
        if (dir != null) {
            cache.remove(dir.toString());
        }
    }

    private Map<String, AaeSidecar> indexFor(Path dir) {
        return cache.computeIfAbsent(dir.toString(), key -> buildIndex(dir));
    }

    /** Lists and parses every {@code .AAE} in {@code dir} once; never throws. */
    private static Map<String, AaeSidecar> buildIndex(Path dir) {
        Map<String, AaeSidecar> index = new HashMap<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(p -> !Files.isDirectory(p))
                    .filter(Sidecars::isAaeSidecar)
                    .forEach(p -> AaeSidecar.read(p)
                            .ifPresent(s -> index.put(Sidecars.stem(p), s)));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return index.isEmpty() ? Map.of() : Map.copyOf(index);
    }
}
