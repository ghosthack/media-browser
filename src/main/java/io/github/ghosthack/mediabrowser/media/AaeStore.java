package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * variant: {@code IMG_E7085.HEIC} resolves to its own {@code IMG_E7085.AAE}
 * when one exists, otherwise to the original's {@code IMG_7085.AAE}.
 * {@link #setCrop} always writes the image's <em>own</em> same-stem sidecar,
 * never the original's: {@code IMG_7085.AAE} is Apple's edit of the
 * {@code IMG_7085} master (its master dimensions and crop), and an
 * {@code IMG_E7085} copy is a separate, already-rendered master, so cropping it
 * must not clobber the original's Photos edit. The directory is then
 * invalidated so the replacement is visible on the next lookup.
 *
 * <p>The index is keyed by {@link ArchivePaths#format} of the directory (the
 * {@code archive.zip!/DCIM} locator), so identically-named inner directories of
 * two different archives never share an index — the same rule as
 * {@link RotationStore}'s overlay.
 */
public final class AaeStore {

    /** Per-directory parsed index, keyed by the directory's {@link ArchivePaths#format} locator. */
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
     * The parsed edit in {@code image}'s <em>own</em> same-stem sidecar — the
     * one {@link #setCrop} writes — without {@link #forImage}'s edited-copy
     * fallback to the original's {@code IMG_7085.AAE}. A crop editor must seed
     * its preview (straighten) and starting rectangle from this, so that what
     * the user draws is in the coordinate space the written sidecar applies to.
     */
    public Optional<AaeSidecar> ownSidecar(Path image) {
        if (image == null || image.getParent() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(indexFor(image.getParent()).get(Sidecars.stem(image)));
    }

    /**
     * Persists a non-destructive crop for {@code image} in its own adjacent
     * same-stem Apple {@code .AAE} ({@code IMG_E7085.HEIC} → {@code IMG_E7085.AAE},
     * never the original's {@code IMG_7085.AAE}). The original media file is
     * never opened for writing. Calling
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

    /** Reverses a {@link #carryOwnSidecar}. */
    @FunctionalInterface
    public interface Undo {
        void undo() throws IOException;
    }

    /**
     * Carries {@code source}'s <em>own</em> same-stem {@code .AAE} along once
     * the image itself has been moved or renamed to {@code target}, renamed to
     * the target's stem ({@code IMG_1234.AAE} → {@code IMG_1234 (1).AAE}) with
     * its extension's case kept. Never the original's sidecar on an edited
     * copy's behalf: when only {@code IMG_E1234.HEIC} moves, {@code IMG_1234.AAE}
     * stays (the same rule as {@link #setCrop}). A sidecar some file left behind
     * still pairs with (a same-stem sibling such as a Live Photo's
     * {@code IMG_1234.MOV}, or an {@code IMG_E1234} edited copy — the
     * {@link Sidecars#isMatchedAaeSidecar} rule) is copied rather than moved,
     * so neither file loses its edit.
     *
     * <p>Moving every partner of a shared sidecar in one batch (both halves of
     * a Live Photo) carries it once: the first carry copies it, and a later
     * partner finding a byte-identical sidecar already at its destination
     * counts it as carried. Once no file left behind pairs with the source
     * copy, that copy is removed, so the batch leaves no orphan; the same goes
     * for an original's {@code IMG_1234.AAE} when its last partner, an
     * {@code IMG_E1234} copy, lands beside the already-carried copy of the
     * same name.
     *
     * <p>A sidecar is never carried <em>onto a stranger</em>: when the target
     * folder already holds a different file the carried sidecar would pair
     * with (same stem, or its {@code IMG_E} copy) and no sidecar of that name
     * is there yet, the carry is refused. That file is not a partner sharing
     * this edit — a partner would have brought its copy — so the edit would
     * silently apply to an unrelated photo, whose next crop would then
     * overwrite it.
     *
     * @return how to reverse the carry (a no-op when there was nothing to carry)
     * @throws IOException if the sidecar cannot be carried, including when the
     *                     target name is already taken by a different file or
     *                     the sidecar would attach to an unrelated file there;
     *                     nothing is then changed
     */
    public Undo carryOwnSidecar(Path source, Path target) throws IOException {
        Path sourceDir = source.getParent();
        Path targetDir = target.getParent();
        if (sourceDir == null || targetDir == null || target.getFileName() == null
                || ArchivePaths.inArchive(source) || ArchivePaths.inArchive(target)) {
            return () -> {};
        }
        String sourceStem = Sidecars.stem(source);
        String originalStem = Sidecars.originalVariant(sourceStem);
        Path movedFile = target.toAbsolutePath().normalize();
        Path own = null;
        Path original = null;
        Set<String> remainingStems = new HashSet<>();
        try (Stream<Path> children = Files.list(sourceDir)) {
            List<Path> files = children.filter(p -> !Files.isDirectory(p)).toList();
            for (Path p : files) {
                if (Sidecars.isAaeSidecar(p)) {
                    String stem = Sidecars.stem(p);
                    if (own == null && stem.equals(sourceStem)) {
                        own = p;
                    } else if (original == null && stem.equals(originalStem)) {
                        original = p;
                    }
                } else if (!Sidecars.isRotationSidecar(p)
                        && !p.toAbsolutePath().normalize().equals(movedFile)) {
                    remainingStems.add(Sidecars.stem(p));
                }
            }
        }
        if (own == null) {
            // An edited copy never takes its original's sidecar, but when it
            // was that sidecar's last partner and this batch already copied it
            // alongside, the copy left at the source would only be an orphan.
            return original == null ? () -> {}
                    : retireLeftCopy(original, targetDir.resolve(original.getFileName()),
                            remainingStems, sourceDir, targetDir);
        }
        Path aae = own;
        String aaeName = aae.getFileName().toString();
        String targetName = target.getFileName().toString();
        int dot = targetName.lastIndexOf('.');
        Path destination = targetDir.resolve((dot > 0 ? targetName.substring(0, dot) : targetName)
                + aaeName.substring(aaeName.lastIndexOf('.')));
        if (destination.equals(aae)) {
            return () -> {}; // a rename that kept the stem (mystery → mystery.jpg)
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!sameBytes(aae, destination)) {
                // The name only: this message reaches the move-failure UI text.
                throw new FileAlreadyExistsException(destination.getFileName().toString(), null,
                        "its edit sidecar " + aaeName + " cannot follow: the name is taken");
            }
            // A partner moved earlier in this batch already carried it.
            return retireLeftCopy(aae, destination, remainingStems, sourceDir, targetDir);
        }
        Path stranger = wouldAlsoPairWith(destination, targetDir, movedFile);
        if (stranger != null) {
            // The names only: this message reaches the move-failure UI text.
            throw new IOException("its edit sidecar " + aaeName + " cannot follow: as "
                    + destination.getFileName() + " it would also apply to "
                    + stranger.getFileName() + ", a different file already there"
                    + " — rename one of them first");
        }
        boolean shared = Sidecars.isMatchedAaeSidecar(aae, remainingStems);
        if (shared) {
            Files.copy(aae, destination, StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            Files.move(aae, destination);
        }
        invalidate(sourceDir);
        invalidate(targetDir);
        return () -> {
            if (shared) {
                Files.delete(destination);
            } else {
                Files.move(destination, aae);
            }
            invalidate(sourceDir);
            invalidate(targetDir);
        };
    }

    /**
     * Removes {@code left}, a sidecar already carried to {@code carried} with
     * identical bytes, once no file in {@code remainingStems} still pairs with
     * it; a no-op otherwise. The undo puts it back from the carried copy.
     *
     * <p>Never when {@code carried} is {@code left} itself: an in-place
     * rename or extension fix resolves the "carried" copy into the source
     * folder, and a case-only rename on a case-insensitive volume names the
     * same file in another case. Deleting it then would lose the only copy,
     * with nothing left for the undo to restore from.</p>
     */
    private Undo retireLeftCopy(Path left, Path carried, Set<String> remainingStems,
                                Path sourceDir, Path targetDir) throws IOException {
        if (Sidecars.isMatchedAaeSidecar(left, remainingStems)
                || !Files.exists(carried, LinkOption.NOFOLLOW_LINKS)
                || Files.isSameFile(left, carried)
                || !sameBytes(left, carried)) {
            return () -> {};
        }
        Files.delete(left);
        invalidate(sourceDir);
        return () -> {
            Files.copy(carried, left, StandardCopyOption.COPY_ATTRIBUTES);
            invalidate(sourceDir);
            invalidate(targetDir);
        };
    }

    /**
     * A file already in {@code targetDir}, other than the one just moved
     * there, that a sidecar newly created at {@code destination} would pair
     * with — by the same rule every reader uses ({@link #forImage},
     * {@link Sidecars#isMatchedAaeSidecar}): the same stem, or the stem's
     * {@code IMG_E} edited copy. Null when there is none.
     *
     * <p>Such a file had no edit under that name (the caller has established
     * {@code destination} does not exist), so it is not a partner that already
     * shares this one: carrying the sidecar in would silently apply a
     * stranger's edit to it, and a later crop of it would rewrite that
     * sidecar. A genuine partner that moved earlier took a copy along, which
     * the caller finds at {@code destination} and never asks here.</p>
     */
    private static Path wouldAlsoPairWith(Path destination, Path targetDir, Path movedFile)
            throws IOException {
        String stem = Sidecars.stem(destination);
        String edited = Sidecars.editedVariant(stem);
        try (Stream<Path> children = Files.list(targetDir)) {
            for (Path p : children.filter(p -> !Files.isDirectory(p)).toList()) {
                if (Sidecars.isAaeSidecar(p) || Sidecars.isRotationSidecar(p)
                        || p.toAbsolutePath().normalize().equals(movedFile)) {
                    continue;
                }
                String other = Sidecars.stem(p);
                if (other.equals(stem) || other.equals(edited)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static boolean sameBytes(Path a, Path b) throws IOException {
        return Files.isRegularFile(b, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(a, b) == -1L;
    }

    /** Drops the cached index for {@code dir} (e.g. on an external change). */
    public void invalidate(Path dir) {
        if (dir != null) {
            cache.remove(dirKey(dir));
        }
    }

    private Map<String, AaeSidecar> indexFor(Path dir) {
        return cache.computeIfAbsent(dirKey(dir), key -> buildIndex(dir));
    }

    /**
     * The locator form, so {@code a.zip!/DCIM} and {@code b.zip!/DCIM} (both
     * {@code /DCIM} by {@code Path.toString}) never share an index.
     */
    private static String dirKey(Path dir) {
        return ArchivePaths.format(dir);
    }

    private static Path sidecarForWrite(Path image) throws IOException {
        if (image == null || image.getFileName() == null || image.getParent() == null) {
            throw new IllegalArgumentException("image must have a file name and parent directory");
        }
        Path dir = image.getParent();
        // Deliberately the image's own stem, even for an IMG_E edited copy:
        // the original's IMG_####.AAE describes the original master and must
        // never be rewritten on the copy's behalf.
        final String matchStem = Sidecars.stem(image);
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
