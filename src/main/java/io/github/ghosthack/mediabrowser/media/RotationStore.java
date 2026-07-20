package io.github.ghosthack.mediabrowser.media;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The per-directory store of user-owned, <em>non-destructive</em> adjustments: a
 * thread-safe, {@link Path}-based facade over the vendored {@link PicasaIniStore}
 * ({@code .picasa.ini} sidecar engine). It persists user rotation plus the
 * mirror / black&amp;white / invert toggles bundled in {@link Adjustments}. All
 * of these live <em>above</em> the decoders — every backend already converges on
 * an upright BGRA {@link RasterFrame} with EXIF/container orientation baked in,
 * so this stores only the extra user adjustments and composes them on top of
 * that. Reads are O(1) after the directory's sidecar is loaded once into memory,
 * so answering "what are this file's adjustments?" on every mosaic repaint is
 * cheap; writes are synchronous, atomic write-throughs.
 *
 * <p>Rotation is <strong>clockwise</strong> in 90&deg; quarter-turns, wrapping
 * mod&nbsp;4; the other adjustments are simple toggles. Clearing the last one
 * removes its sidecar entry (and the whole sidecar once it holds nothing). The
 * thumbnail cache is never consulted or invalidated by this store — adjustments
 * are applied after decode (draw-time in the mosaic, a one-shot pixel bake in the
 * viewer; see {@link RasterFrames#apply}).
 *
 * <p>The class keeps its historical name (rotation was the first such
 * adjustment) for continuity, but now owns the whole non-destructive set.
 */
public final class RotationStore {

    private final PicasaIniStore delegate = new PicasaIniStore();

    /** The hidden per-directory sidecar filename ({@code .picasa.ini}). */
    public static String sidecarFileName() {
        return PicasaIniStore.INI_FILENAME;
    }

    /**
     * User quarter-turns clockwise ({@code 0..3}) recorded for {@code file};
     * {@code 0} when none (or the path is null / has no parent). Loads and caches
     * the file's directory sidecar on first sight.
     */
    public int quarterTurns(Path file) {
        return file == null ? 0 : delegate.getRotationSteps(file.toString());
    }

    /**
     * Adds {@code deltaQuarterTurnsCw} (mod&nbsp;4) to {@code file}'s user
     * rotation, persists it, and returns the new value in {@code 0..3}. A failed
     * write is logged and leaves the stored value unchanged (the returned value
     * then reflects what remains persisted).
     */
    public int rotate(Path file, int deltaQuarterTurnsCw) {
        if (file == null) {
            return 0;
        }
        try {
            return delegate.rotateBy(file.toString(), deltaQuarterTurnsCw);
        } catch (IOException e) {
            System.err.println("rotation: cannot persist rotation for "
                    + file + ": " + e.getMessage());
            return delegate.getRotationSteps(file.toString());
        }
    }

    /**
     * Sets {@code file}'s user rotation to {@code quarterTurnsCw} (mod&nbsp;4),
     * persists it, and returns the new value. A failed write is logged.
     */
    public int set(Path file, int quarterTurnsCw) {
        if (file == null) {
            return 0;
        }
        try {
            delegate.setRotationSteps(file.toString(), quarterTurnsCw);
            return delegate.getRotationSteps(file.toString());
        } catch (IOException e) {
            System.err.println("rotation: cannot persist rotation for "
                    + file + ": " + e.getMessage());
            return delegate.getRotationSteps(file.toString());
        }
    }

    /**
     * The complete set of non-destructive adjustments recorded for {@code file}
     * (rotation, mirror, grayscale, invert), read as a single consistent
     * snapshot. {@link Adjustments#NONE} when {@code file} is {@code null} or has
     * nothing recorded.
     */
    public Adjustments adjustments(Path file) {
        if (file == null) {
            return Adjustments.NONE;
        }
        int packed = delegate.getPackedAdjustments(file.toString());
        if (packed == 0) {
            return Adjustments.NONE;
        }
        return new Adjustments(
                packed & 0x3,
                (packed & (1 << 2)) != 0,
                (packed & (1 << 3)) != 0,
                (packed & (1 << 4)) != 0,
                (packed & (1 << 5)) != 0);
    }

    /** Toggles {@code file}'s horizontal mirror and returns the new state. */
    public boolean toggleMirrorH(Path file) {
        return toggleFlag(file, PicasaIniStore.MIRROR_H_KEY);
    }

    /** Toggles {@code file}'s vertical mirror and returns the new state. */
    public boolean toggleMirrorV(Path file) {
        return toggleFlag(file, PicasaIniStore.MIRROR_V_KEY);
    }

    /** Toggles {@code file}'s grayscale (black&amp;white) flag; returns the new state. */
    public boolean toggleGrayscale(Path file) {
        return toggleFlag(file, PicasaIniStore.GRAYSCALE_KEY);
    }

    /** Toggles {@code file}'s colour-invert flag and returns the new state. */
    public boolean toggleInvert(Path file) {
        return toggleFlag(file, PicasaIniStore.INVERT_KEY);
    }

    /** Toggle-and-persist a boolean flag; a failed write is logged and the
     * persisted value is returned unchanged. */
    private boolean toggleFlag(Path file, String key) {
        if (file == null) {
            return false;
        }
        try {
            return delegate.toggleFlag(file.toString(), key);
        } catch (IOException e) {
            System.err.println("adjustments: cannot persist " + key + " for "
                    + file + ": " + e.getMessage());
            return delegate.getFlag(file.toString(), key);
        }
    }

    /** Drops the cached sidecar for {@code dir} (e.g. on an external change). */
    public void invalidate(Path dir) {
        if (dir != null) {
            delegate.invalidate(dir.toString());
        }
    }
}
