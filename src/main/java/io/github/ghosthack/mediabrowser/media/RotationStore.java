package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
 * <h2>Read-only volumes: the session overlay</h2>
 *
 * <p>The filesystem is the source of truth, but it is not always writable — a
 * locked SD card, a read-only mount, a share without write access. Rather than
 * let the adjustment simply not happen (the image refusing to turn, with nothing
 * said), a failed sidecar write falls back to an in-memory overlay that mirrors
 * the sidecar's shape: per directory, a map of file name to the same bit-packed
 * adjustments {@link PicasaIniStore#getPackedAdjustments} returns. Overlay
 * entries win over disk on read, so the UX is identical — the picture turns,
 * every view agrees, and the value survives a re-listing.
 *
 * <p>What it does <em>not</em> survive is the session, so the fallback is
 * announced rather than hidden: {@link #pollSessionOnlyNotice} hands the UI a
 * one-time, non-blocking message the first time a directory goes overlay-only.
 * The overlay is bounded to the {@code adjustments.volatile.maxDirs} most
 * recently used directories (default 10, {@code 0} disables the fallback
 * entirely); evicting a directory silently reverts it to what is on disk.
 *
 * <p>Recovery is automatic: every adjustment attempts the real sidecar first, so
 * a volume that becomes writable mid-session starts persisting again, and the
 * directory's pending overlay entries are flushed to disk on the first write
 * that succeeds.
 *
 * <p>The class keeps its historical name (rotation was the first such
 * adjustment) for continuity, but now owns the whole non-destructive set.
 */
public final class RotationStore {

    /** Default {@code adjustments.volatile.maxDirs} when none is configured. */
    public static final int DEFAULT_MAX_VOLATILE_DIRS = 10;

    /** The status-bar notice shown once per directory that falls back to memory. */
    public static final String SESSION_ONLY_NOTICE =
            "Read-only folder — adjustments kept for this session only";

    // Bit layout of a packed adjustment, matching PicasaIniStore.getPackedAdjustments.
    private static final int ROTATION_MASK = 0x3;
    private static final int MIRROR_H_BIT = 1 << 2;
    private static final int MIRROR_V_BIT = 1 << 3;
    private static final int GRAYSCALE_BIT = 1 << 4;
    private static final int INVERT_BIT = 1 << 5;

    private final PicasaIniStore delegate = new PicasaIniStore();

    /** How many directories may hold session-only adjustments; {@code 0} disables. */
    private final int maxVolatileDirs;

    /**
     * Directory path to (file name to packed adjustments), in access order so the
     * eldest entry is the least recently touched directory, not the oldest one.
     */
    private final LinkedHashMap<String, LinkedHashMap<String, Integer>> overlay;

    /** Directories whose one-time session-only notice has already been handed out. */
    private final Set<String> announced = new HashSet<>();

    /** Uses the default overlay bound ({@link #DEFAULT_MAX_VOLATILE_DIRS}). */
    public RotationStore() {
        this(DEFAULT_MAX_VOLATILE_DIRS);
    }

    /**
     * @param maxVolatileDirs how many directories may hold session-only
     *                        adjustments when their sidecar cannot be written;
     *                        {@code 0} disables the fallback, so an unwritable
     *                        sidecar means the adjustment does not apply
     */
    public RotationStore(int maxVolatileDirs) {
        this.maxVolatileDirs = Math.max(0, maxVolatileDirs);
        this.overlay = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<String, LinkedHashMap<String, Integer>> eldest) {
                if (size() <= RotationStore.this.maxVolatileDirs) {
                    return false;
                }
                // Evicted adjustments revert to whatever the sidecar holds.
                announced.remove(eldest.getKey());
                System.err.println("adjustments: dropping session-only adjustments for "
                        + eldest.getKey() + " (over adjustments.volatile.maxDirs="
                        + RotationStore.this.maxVolatileDirs + ")");
                return true;
            }
        };
    }

    /** The hidden per-directory sidecar filename ({@code .picasa.ini}). */
    public static String sidecarFileName() {
        return PicasaIniStore.INI_FILENAME;
    }

    /**
     * User quarter-turns clockwise ({@code 0..3}) recorded for {@code file};
     * {@code 0} when none (or the path is null / has no parent). Loads and caches
     * the file's directory sidecar on first sight.
     */
    public synchronized int quarterTurns(Path file) {
        return file == null ? 0 : effectivePacked(file) & ROTATION_MASK;
    }

    /**
     * Adds {@code deltaQuarterTurnsCw} (mod&nbsp;4) to {@code file}'s user
     * rotation, persists it, and returns the new value in {@code 0..3}. When the
     * sidecar cannot be written the value is kept in the session overlay instead
     * (see the class notes), so the returned value is what now applies either way.
     */
    public synchronized int rotate(Path file, int deltaQuarterTurnsCw) {
        if (file == null) {
            return 0;
        }
        int current = effectivePacked(file);
        int target = Math.floorMod((current & ROTATION_MASK) + deltaQuarterTurnsCw, 4);
        return apply(file, (current & ~ROTATION_MASK) | target) & ROTATION_MASK;
    }

    /**
     * Sets {@code file}'s user rotation to {@code quarterTurnsCw} (mod&nbsp;4),
     * persists it (or keeps it in the session overlay), and returns the new value.
     */
    public synchronized int set(Path file, int quarterTurnsCw) {
        if (file == null) {
            return 0;
        }
        int target = Math.floorMod(quarterTurnsCw, 4);
        int current = effectivePacked(file);
        return apply(file, (current & ~ROTATION_MASK) | target) & ROTATION_MASK;
    }

    /**
     * The complete set of non-destructive adjustments recorded for {@code file}
     * (rotation, mirror, grayscale, invert), read as a single consistent
     * snapshot. {@link Adjustments#NONE} when {@code file} is {@code null} or has
     * nothing recorded.
     */
    public synchronized Adjustments adjustments(Path file) {
        if (file == null) {
            return Adjustments.NONE;
        }
        int packed = effectivePacked(file);
        if (packed == 0) {
            return Adjustments.NONE;
        }
        return new Adjustments(
                packed & ROTATION_MASK,
                (packed & MIRROR_H_BIT) != 0,
                (packed & MIRROR_V_BIT) != 0,
                (packed & GRAYSCALE_BIT) != 0,
                (packed & INVERT_BIT) != 0);
    }

    /** Toggles {@code file}'s horizontal mirror and returns the new state. */
    public synchronized boolean toggleMirrorH(Path file) {
        return toggleBit(file, MIRROR_H_BIT);
    }

    /** Toggles {@code file}'s vertical mirror and returns the new state. */
    public synchronized boolean toggleMirrorV(Path file) {
        return toggleBit(file, MIRROR_V_BIT);
    }

    /** Toggles {@code file}'s grayscale (black&amp;white) flag; returns the new state. */
    public synchronized boolean toggleGrayscale(Path file) {
        return toggleBit(file, GRAYSCALE_BIT);
    }

    /** Toggles {@code file}'s colour-invert flag and returns the new state. */
    public synchronized boolean toggleInvert(Path file) {
        return toggleBit(file, INVERT_BIT);
    }

    /**
     * True when {@code file}'s directory currently holds session-only adjustments
     * — its sidecar could not be written and the values live in the overlay.
     */
    public synchronized boolean isSessionOnly(Path file) {
        String dir = dirKey(file);
        return dir != null && overlay.containsKey(dir);
    }

    /**
     * The one-time {@link #SESSION_ONLY_NOTICE} for {@code file}'s directory, or
     * {@code null} when there is nothing to announce (the sidecar is writable, or
     * this directory's notice was already handed out). Meant to be polled by the
     * UI right after an adjustment so the fallback is stated once, without a
     * dialog and without failing the action. A directory evicted from the overlay
     * announces itself again if it comes back.
     */
    public synchronized String pollSessionOnlyNotice(Path file) {
        String dir = dirKey(file);
        if (dir == null || !overlay.containsKey(dir) || !announced.add(dir)) {
            return null;
        }
        return SESSION_ONLY_NOTICE;
    }

    /**
     * Drops the cached sidecar for {@code dir} (e.g. on an external change). Any
     * session-only adjustments are kept: they are unsaved user work, not a stale
     * read of the file.
     */
    public synchronized void invalidate(Path dir) {
        if (dir != null) {
            delegate.invalidate(dir.toString());
        }
    }

    // --- internals -----------------------------------------------------------

    /** Toggles one packed flag bit and returns its new state. */
    private boolean toggleBit(Path file, int bit) {
        if (file == null) {
            return false;
        }
        int current = effectivePacked(file);
        return (apply(file, current ^ bit) & bit) != 0;
    }

    /**
     * The adjustments in force for {@code file}: the session overlay when it holds
     * an entry (it is seeded from disk, so it never loses information), else the
     * sidecar.
     */
    private int effectivePacked(Path file) {
        String dir = dirKey(file);
        if (dir != null) {
            LinkedHashMap<String, Integer> pending = overlay.get(dir);
            if (pending != null) {
                Integer packed = pending.get(file.getFileName().toString());
                if (packed != null) {
                    return packed;
                }
            }
        }
        // Inside an archive the overlay is the only store there is: the
        // delegate keys on real paths, and an entry's path ("/DCIM/IMG.JPG")
        // would send it looking for a sidecar at that location on the actual
        // filesystem — a wrong read, and a wrong write if it ever succeeded.
        if (ArchivePaths.inArchive(file)) return 0;
        return delegate.getPackedAdjustments(file.toString());
    }

    /**
     * Writes {@code desired} for {@code file}, falling back to the session overlay
     * when the sidecar cannot be written. Returns the packed value now in force —
     * {@code desired} unless the fallback is disabled and the write failed.
     */
    private int apply(Path file, int desired) {
        String dir = dirKey(file);
        // An archive is immutable by definition, so skip straight to the
        // session overlay instead of attempting — and reporting — a write that
        // could never succeed. The user still gets the rotation for this
        // session, with the same one-time notice a read-only volume gives.
        if (ArchivePaths.inArchive(file)) {
            if (maxVolatileDirs == 0 || dir == null) return 0;
            overlay.computeIfAbsent(dir, unused -> new LinkedHashMap<>())
                    .put(file.getFileName().toString(), desired);
            return desired;
        }
        try {
            persist(file.toString(), desired);
            if (dir != null) {
                LinkedHashMap<String, Integer> pending = overlay.get(dir);
                if (pending != null) {
                    // This directory is writable again — persist what it still owes.
                    pending.remove(file.getFileName().toString());
                    flush(dir, pending);
                }
            }
            return desired;
        } catch (IOException e) {
            if (maxVolatileDirs == 0 || dir == null) {
                // e.toString(): AccessDeniedException's message is only a path.
                System.err.println("adjustments: cannot persist adjustments for "
                        + file + ": " + e);
                return delegate.getPackedAdjustments(file.toString());
            }
            boolean firstForDir = !overlay.containsKey(dir);
            overlay.computeIfAbsent(dir, unused -> new LinkedHashMap<>())
                    .put(file.getFileName().toString(), desired);
            if (firstForDir) {
                System.err.println("adjustments: " + dir + " is not writable (" + e
                        + ") — keeping adjustments in memory for this session only");
            }
            return desired;
        }
    }

    /**
     * Writes the full packed adjustment for one file through the delegate. Each
     * setter no-ops when the stored value already matches, so an ordinary rotate
     * still rewrites the sidecar exactly once.
     */
    private void persist(String path, int packed) throws IOException {
        delegate.setRotationSteps(path, packed & ROTATION_MASK);
        delegate.setFlag(path, PicasaIniStore.MIRROR_H_KEY, (packed & MIRROR_H_BIT) != 0);
        delegate.setFlag(path, PicasaIniStore.MIRROR_V_KEY, (packed & MIRROR_V_BIT) != 0);
        delegate.setFlag(path, PicasaIniStore.GRAYSCALE_KEY, (packed & GRAYSCALE_BIT) != 0);
        delegate.setFlag(path, PicasaIniStore.INVERT_KEY, (packed & INVERT_BIT) != 0);
    }

    /**
     * Best-effort write-back of a directory's remaining session-only adjustments,
     * called once a write to it has succeeded. Stops at the first entry that still
     * fails (the volume went away again) and keeps the rest in the overlay.
     */
    private void flush(String dir, LinkedHashMap<String, Integer> pending) {
        for (Iterator<Map.Entry<String, Integer>> it = pending.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<String, Integer> entry = it.next();
            try {
                persist(new File(dir, entry.getKey()).getPath(), entry.getValue());
                it.remove();
            } catch (IOException e) {
                return;
            }
        }
        overlay.remove(dir);
        announced.remove(dir);
    }

    /** {@code file}'s parent directory as an overlay key, or {@code null}. */
    private static String dirKey(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        Path parent = file.getParent();
        // The locator form, so two archives with identically-named inner
        // directories never share an overlay bucket.
        return parent == null ? null : ArchivePaths.format(parent);
    }
}
