package io.github.ghosthack.mediabrowser.media.ffm;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Which YCbCr matrix a video frame with <em>no</em> matrix tag is converted
 * with (the {@code video.untaggedMatrix} setting). A tagged frame is always
 * converted by its own tag; this only decides the untagged case, where the
 * file simply does not say.
 *
 * <p>Like {@link HwDecode} this is a per-session routing axis of the
 * bundled-FFmpeg backends: a Settings save applies to the next playback
 * session. It covers video playback (the CPU swscale path and the zero-copy
 * GPU path alike) and that video's poster and thumbnails, so a thumbnail
 * matches the first frame played. Stills are not touched, because an
 * untagged still (WebP, AVIF, JPEG) is BT.601 by its format's own rule.</p>
 */
public final class UntaggedMatrix {

    public enum Policy {
        /**
         * BT.709 for HD frames (width ≥ 1280 or height &gt; 576), BT.601 below.
         * The default: it is what the standards assign to each size class and
         * what players guess, so it is right for most untagged files.
         */
        AUTO,
        /** FFmpeg's own default, and this app's behaviour before the setting existed. */
        BT601,
        /** Treat every untagged video frame as BT.709. */
        BT709
    }

    static final int AVCOL_SPC_BT709 = 1;
    static final int AVCOL_SPC_UNSPECIFIED = 2;

    private static volatile Policy policy = Policy.AUTO;
    /** A policy pinned for the current thread's operation; wins over {@link #policy}. */
    private static final ThreadLocal<Policy> PINNED = new ThreadLocal<>();

    private UntaggedMatrix() {}

    /**
     * Applies the persisted {@code video.untaggedMatrix} value. An
     * unrecognized value (hand-edited file) uses {@code auto} with a loud
     * stderr note — visible recovery, not silent.
     */
    public static void configure(String settingsValue) {
        String v = settingsValue == null ? "auto" : settingsValue.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "bt601" -> policy = Policy.BT601;
            case "bt709" -> policy = Policy.BT709;
            case "auto" -> policy = Policy.AUTO;
            default -> {
                System.err.println("[UntaggedMatrix] unrecognized video.untaggedMatrix '"
                        + settingsValue + "'; using auto (valid: auto, bt601, bt709)");
                policy = Policy.AUTO;
            }
        }
    }

    /** The policy in effect on this thread: its pinned one, else the configured one. */
    public static Policy policy() {
        Policy pinned = PINNED.get();
        return pinned != null ? pinned : policy;
    }

    /**
     * Runs {@code work} on this thread under {@code pinned}, regardless of a
     * concurrent {@link #configure}: a cached thumbnail keyed by a policy is
     * then rendered under exactly that policy.
     */
    public static <T> T pinned(Policy pinned, Supplier<T> work) {
        Policy previous = PINNED.get();
        PINNED.set(pinned);
        try {
            return work.get();
        } finally {
            if (previous == null) PINNED.remove(); else PINNED.set(previous);
        }
    }

    /**
     * The {@code AVColorSpace} to convert a frame with: its own tag when it has
     * one, otherwise what the policy says for a frame of this size.
     * {@code AVCOL_SPC_UNSPECIFIED} back means "leave swscale's BT.601 default".
     */
    static int resolve(int colorspace, int width, int height) {
        if (colorspace != AVCOL_SPC_UNSPECIFIED) {
            return colorspace;
        }
        return switch (policy()) {
            case BT601 -> AVCOL_SPC_UNSPECIFIED;
            case BT709 -> AVCOL_SPC_BT709;
            case AUTO -> width >= 1280 || height > 576 ? AVCOL_SPC_BT709 : AVCOL_SPC_UNSPECIFIED;
        };
    }
}
