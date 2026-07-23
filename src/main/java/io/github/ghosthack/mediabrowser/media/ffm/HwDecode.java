package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hardware-decode policy for the bundled-FFmpeg backends (the
 * {@code decode.device} setting): an intra-backend routing axis like the
 * TurboJPEG/LibRaw fast paths, never a separate backend. Playback-only for
 * now — poster frames and tile compose stay software until the loaded-queue
 * benchmarks say otherwise (docs/panama-decoder-candidates-handoff.md §3).
 *
 * <p>Policies: {@code auto} routes a video to the platform's hardware decoder
 * when it is worthwhile — HEVC only, per the 2026-07-23 benchmarks (see
 * {@code FfmpegVideoStream.isHwWorthwhile}) — and the codec advertises support
 * and the device opens: capability routing, decided before the first frame,
 * with per-session counters surfaced in the Diagnostics panel so the choice is
 * always inspectable. {@code software}
 * never requests hardware. {@code hardware} <em>requires</em> it: a codec or
 * device that can't deliver is a loud {@link MediaException}, never a silent
 * software fallback — the backend factory's no-availability-degrade stance.</p>
 */
public final class HwDecode {

    public enum Policy { AUTO, SOFTWARE, HARDWARE }

    /** A playback hw request: which {@code AVHWDeviceType}, and whether software fallback is forbidden. */
    public record Request(int deviceType, boolean require) {}

    /** {@code AVHWDeviceType} values (hwcontext.h; the enum is append-only). */
    public static final int DEVICE_VIDEOTOOLBOX = 6;
    public static final int DEVICE_D3D11VA = 7;

    private static volatile Policy policy = Policy.AUTO;
    private static final AtomicLong HW_SESSIONS = new AtomicLong();
    private static final AtomicLong SW_SESSIONS = new AtomicLong();

    private HwDecode() {}

    /**
     * Applies the persisted {@code decode.device} value. Unrecognized values
     * (hand-edited file) fall back to {@code auto} with a loud stderr note —
     * visible recovery, not silent.
     */
    public static void configure(String settingsValue) {
        String v = settingsValue == null ? "auto" : settingsValue.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "auto" -> policy = Policy.AUTO;
            case "software" -> policy = Policy.SOFTWARE;
            case "hardware" -> policy = Policy.HARDWARE;
            default -> {
                System.err.println("[HwDecode] unrecognized decode.device '" + settingsValue
                        + "'; using auto (valid: auto, software, hardware)");
                policy = Policy.AUTO;
            }
        }
    }

    public static Policy policy() {
        return policy;
    }

    /**
     * The hw request for a new playback session under the current policy, or
     * {@code null} for a plain software decode. Throws under {@code hardware}
     * when this platform has no hw device type at all.
     */
    static Request playbackRequest() {
        Policy p = policy;
        if (p == Policy.SOFTWARE) {
            return null;
        }
        int type = platformDeviceType();
        if (type < 0) {
            if (p == Policy.HARDWARE) {
                throw new MediaException(
                        "hardware decode required but this platform has no hardware decoder");
            }
            return null;
        }
        return new Request(type, p == Policy.HARDWARE);
    }

    /** VideoToolbox on macOS, D3D11VA on Windows, none elsewhere (Linux: Vulkan, when natives exist). */
    private static int platformDeviceType() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return DEVICE_VIDEOTOOLBOX;
        if (os.contains("win")) return DEVICE_D3D11VA;
        return -1;
    }

    /** Called once per playback session, at the first decoded frame. */
    static void recordSession(boolean hardware) {
        (hardware ? HW_SESSIONS : SW_SESSIONS).incrementAndGet();
    }

    public static long hwSessions() {
        return HW_SESSIONS.get();
    }

    public static long swSessions() {
        return SW_SESSIONS.get();
    }
}
