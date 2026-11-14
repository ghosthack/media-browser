package io.github.ghosthack.mediabrowser.media.ffm;

import java.util.Locale;

/**
 * Which YCbCr matrix a video frame with <em>no</em> matrix tag is converted
 * with (the {@code video.untaggedMatrix} setting). A tagged frame is always
 * converted by its own tag; this only decides the untagged case, where the
 * file simply does not say.
 *
 * <p>Like {@link HwDecode} this is a per-session routing axis of the
 * bundled-FFmpeg backends: a Settings save applies to the next playback
 * session. It covers video playback (the CPU swscale path and the zero-copy
 * GPU path alike); stills and posters are not touched, because an untagged
 * still (WebP, AVIF, JPEG) is BT.601 by its format's own rule.</p>
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

    public static Policy policy() {
        return policy;
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
        return switch (policy) {
            case BT601 -> AVCOL_SPC_UNSPECIFIED;
            case BT709 -> AVCOL_SPC_BT709;
            case AUTO -> width >= 1280 || height > 576 ? AVCOL_SPC_BT709 : AVCOL_SPC_UNSPECIFIED;
        };
    }
}
