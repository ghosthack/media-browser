package io.github.ghosthack.mediabrowser.media.color;

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

    private static volatile Mode mode = Mode.MANAGED;
    private static volatile Gamut gamut = Gamut.CLIP;

    private ColorPolicy() {}

    public static Mode mode() {
        return mode;
    }

    public static Gamut gamut() {
        return gamut;
    }

    public static void set(Mode newMode, Gamut newGamut) {
        mode = newMode == null ? Mode.MANAGED : newMode;
        gamut = newGamut == null ? Gamut.CLIP : newGamut;
    }
}
