package io.github.ghosthack.mediabrowser;

import java.util.Locale;

/**
 * How decoded video frames reach the screen: which presenter, which OpenGL
 * binding for the GPU presenters, and which CPU rotation for the direct one
 * (docs/video-presentation-routing.md). Persisted as {@code video.presenter},
 * {@code video.glLibrary}, {@code video.cpuRotation} and {@code video.glReadback}; like
 * {@code decode.device}, a Settings save applies to the next playback session.
 *
 * <p>An explicit choice is honoured or fails loudly: {@link Presenter#GPU}
 * never degrades to the CPU presenter, and {@link Presenter#VIMAGE} fails off
 * macOS. Only {@link Presenter#AUTO} keeps the
 * visible fallback (status bar "PixelBuffer · GL failed").</p>
 */
public final class VideoPresentation {

    /** Which presenter a playback session uses. */
    public enum Presenter {
        /**
         * CGL on macOS (for VideoToolbox zero-copy); on Windows/Linux the CPU
         * presenter unless the video is rotated, then GPU. A GL failure falls
         * back to the CPU presenter, visibly.
         */
        AUTO("Auto"),
        /** Always the GPU (OpenGL) presenter; a GL failure fails playback. */
        GPU("GPU (OpenGL)"),
        /** Always the CPU presenter: no OpenGL context at all. */
        CPU("CPU"),
        /**
         * macOS only: Accelerate vImage converts VideoToolbox NV12 frames and
         * rotates CPU frames, with no OpenGL context. Fails playback elsewhere.
         */
        VIMAGE("vImage (macOS)");

        private final String label;

        Presenter(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The OpenGL binding behind the GPU presenters. */
    public enum GlLibrary {
        /** LWJGL 3: CGL on macOS, a GLFW hidden window on Windows/Linux. */
        LWJGL("LWJGL"),
        /** The FFM binding (iris panama-media-opengl, vendored): CGL on macOS, WGL on Windows, EGL on Linux. */
        MUJGL("mujgl");

        private final String label;

        GlLibrary(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How the CPU presenter applies a user rotation. */
    public enum CpuRotation {
        /** Whole-pixel moves in cache-sized tiles. */
        TILED("Tiled"),
        /** The original per-byte, per-pixel permutation. */
        SIMPLE("Simple");

        private final String label;

        CpuRotation(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How the mujgl renderer reads a frame back from the GPU (LWJGL is always sync). */
    public enum GlReadback {
        /** {@code glReadPixels} into the frame buffer, waiting for the GPU. */
        SYNC("Sync"),
        /**
         * {@code glReadPixels} into a pixel buffer object, then mapped and
         * copied out at once: no delay, a different driver transfer path
         * (faster on Intel GPUs in docs/video-presentation-routing.md).
         */
        PBO_SAME_FRAME("PBO, same frame"),
        /**
         * {@code glReadPixels} into one of two pixel buffer objects, copying
         * out the other — the previous frame — instead of waiting: each frame
         * is shown one frame late (the first is shown at once), and a video's
         * last frame is never shown. Faster on AMD GPUs.
         */
        PBO_PREVIOUS_FRAME("PBO, previous frame (1-frame delay)");

        private final String label;

        GlReadback(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One complete choice. */
    public record Config(Presenter presenter, GlLibrary glLibrary, CpuRotation cpuRotation,
                         GlReadback glReadback) {
        public static final Config DEFAULT =
                new Config(Presenter.AUTO, GlLibrary.LWJGL, CpuRotation.TILED, GlReadback.SYNC);

        public Config {
            if (presenter == null) presenter = Presenter.AUTO;
            if (glLibrary == null) glLibrary = GlLibrary.LWJGL;
            if (cpuRotation == null) cpuRotation = CpuRotation.TILED;
            if (glReadback == null) glReadback = GlReadback.SYNC;
        }

        /** A choice with the default (sync) readback. */
        public Config(Presenter presenter, GlLibrary glLibrary, CpuRotation cpuRotation) {
            this(presenter, glLibrary, cpuRotation, GlReadback.SYNC);
        }

        /** The readback the GL renderer actually uses: always sync under LWJGL. */
        public GlReadback effectiveReadback() {
            return glLibrary == GlLibrary.MUJGL ? glReadback : GlReadback.SYNC;
        }
    }

    private static volatile Config current = Config.DEFAULT;

    private VideoPresentation() {}

    /** The configuration new playback sessions use. */
    public static Config current() {
        return current;
    }

    /** Applies {@code config} from the next playback session on. */
    public static void configure(Config config) {
        current = config == null ? Config.DEFAULT : config;
    }

    /**
     * Parses a persisted enum constant name, case-insensitively; {@code null}
     * or unrecognised input yields {@code fallback} (a fixed default, never a
     * host-dependent one), so a hand-edited file cannot break startup.
     */
    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
