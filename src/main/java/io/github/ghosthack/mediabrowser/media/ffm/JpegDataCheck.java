package io.github.ghosthack.mediabrowser.media.ffm;

/**
 * Whether the {@code -turbojpeg} viewer runs its extra libjpeg-turbo pass on
 * a JPEG FFmpeg decoded (the {@code media.jpegDataCheck} setting, "Check
 * JPEGs for damaged data"; default on). FFmpeg's mjpeg decoder conceals
 * damaged scans silently, so that 1/8-scale pass is the only way the viewer
 * learns libjpeg's damaged-data verdict; it costs one more read and an
 * entropy decode of the file. Off skips it entirely — the trace says so —
 * and a thumbnail decoded by TurboJPEG still reports its own warning, which
 * costs nothing extra.
 *
 * <p>Like {@link UntaggedMatrix} a process-wide switch: a Settings save
 * applies to the next viewer load.</p>
 */
public final class JpegDataCheck {

    /** Trace label of the extra pass, also used when it is skipped. */
    static final String LABEL = "TurboJPEG data check";
    /** Trace detail when the setting turned the pass off. */
    static final String SKIPPED = "skipped: \"Check JPEGs for damaged data\" is off "
            + "(media.jpegDataCheck)";

    private static volatile boolean enabled = true;

    private JpegDataCheck() {}

    /** Applies the persisted {@code media.jpegDataCheck} value. */
    public static void configure(boolean enabled) {
        JpegDataCheck.enabled = enabled;
    }

    public static boolean enabled() {
        return enabled;
    }
}
