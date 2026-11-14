package io.github.ghosthack.mediabrowser.media.color;

import java.util.function.Supplier;

/**
 * Process-global still-image color policy read by the color-managed decode
 * path on every operation.
 *
 * <p>Deliberately static: the facade is constructed reflectively and decodes
 * run on service-owned threads, so per-call plumbing would have to cross every
 * facade seam for a knob that is conceptually one user preference. The
 * trade-off is that a change applies to <em>all</em> subsequent decodes
 * (viewer and thumbnail pipeline alike) until changed back; the viewer owns
 * scoping semantics (per-image vs persisted) and re-decodes what it shows.</p>
 */
public final class ColorPolicy {

    /** Whether embedded-ICC conversion runs at all. */
    public enum Mode {
        /** Convert tagged stills to sRGB (default). */
        MANAGED,
        /** Pass decoded bytes through untouched; profile still probed for display. */
        UNMANAGED;

        public static Mode fromSettings(String value, Mode fallback) {
            if (value == null) return fallback;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return fallback;
            }
        }
    }

    /** What happens to colors the sRGB destination cannot represent. */
    public enum Gamut {
        /** Per-channel clip at the gamut edge (LittleCMS relative-colorimetric default). */
        CLIP,
        /**
         * OKLCH chroma compression (CSS Color 4 gamut mapping): hold hue and
         * lightness, walk chroma inward until the color fits. Matrix-shaper
         * source profiles only; others fall back to CLIP, loudly, in the trace.
         */
        COMPRESS;

        public static Gamut fromSettings(String value, Gamut fallback) {
            if (value == null) return fallback;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return fallback;
            }
        }
    }

    /**
     * One consistent reading of the policy. {@link #signature()} names only
     * what shapes the pixels (gamut is moot when unmanaged), so it can serve
     * as part of a cached rendition's identity.
     */
    public record Snapshot(Mode mode, Gamut gamut) {
        public Snapshot {
            if (mode == null) mode = Mode.MANAGED;
            if (gamut == null) gamut = Gamut.CLIP;
        }

        public String signature() {
            return mode == Mode.UNMANAGED ? "unmanaged" : "managed/" + gamut;
        }
    }

    private static volatile Snapshot current = new Snapshot(Mode.MANAGED, Gamut.CLIP);
    /** A policy pinned for the current thread's operation; wins over {@link #current}. */
    private static final ThreadLocal<Snapshot> PINNED = new ThreadLocal<>();

    private ColorPolicy() {}

    public static Mode mode() {
        return snapshot().mode();
    }

    public static Gamut gamut() {
        return snapshot().gamut();
    }

    /** The policy in effect on this thread: its pinned one, else the global one. */
    public static Snapshot snapshot() {
        Snapshot pinned = PINNED.get();
        return pinned != null ? pinned : current;
    }

    public static void set(Mode newMode, Gamut newGamut) {
        current = new Snapshot(newMode, newGamut);
    }

    /**
     * Runs {@code work} on this thread under {@code policy}, regardless of any
     * concurrent {@link #set}: a cached rendition keyed by a snapshot's
     * signature is then rendered under exactly that snapshot.
     */
    public static <T> T pinned(Snapshot policy, Supplier<T> work) {
        Snapshot previous = PINNED.get();
        PINNED.set(policy);
        try {
            return work.get();
        } finally {
            if (previous == null) PINNED.remove(); else PINNED.set(previous);
        }
    }
}
