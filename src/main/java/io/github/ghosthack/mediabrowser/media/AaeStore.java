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
 * variant: {@code IMG_E7085.HEIC} resolves to {@code IMG_7085.AAE}.
 * {@link #setCrop} writes only that sidecar, then invalidates the directory so
 * the replacement is visible on the next lookup.
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

    /**
     * Persists a non-destructive crop for {@code image} in its adjacent Apple
     * {@code .AAE}. The original media file is never opened for writing. Calling
     * this again replaces the Crop adjustment in master coordinates, so a crop
     * can be revised outward as well as inward instead of being applied to the
     * already-cropped pixels.
     *
     * @return the sidecar path that was written
     * @throws IOException if an existing sidecar cannot be safely preserved or
     *                     the replacement cannot be written
     */
    public Path setCrop(Path image, int masterWidth, int masterHeight,
                        AaeSidecar.Crop crop) throws IOException {
        Path sidecar = sidecarForWrite(image);
        AaeSidecar.writeCrop(sidecar, masterWidth, masterHeight, crop);
        invalidate(sidecar.getParent());
        return sidecar;
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

    private static Path sidecarForWrite(Path image) throws IOException {
        if (image == null || image.getFileName() == null || image.getParent() == null) {
            throw new IllegalArgumentException("image must have a file name and parent directory");
        }
        Path dir = image.getParent();
        String wantedStem = Sidecars.stem(image);
        String original = Sidecars.originalVariant(wantedStem);
        if (original != null) wantedStem = original;

        final String matchStem = wantedStem;
        try (Stream<Path> children = Files.list(dir)) {
            Optional<Path> existing = children
                    .filter(p -> !Files.isDirectory(p))
                    .filter(Sidecars::isAaeSidecar)
                    .filter(p -> Sidecars.stem(p).equals(matchStem))
                    .findFirst();
            if (existing.isPresent()) return existing.get();
        }

        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (original != null && base.regionMatches(true, 0, "IMG_E", 0, 5)) {
            base = base.substring(0, 4) + base.substring(5);
        }
        return dir.resolve(base + ".AAE");
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
