package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.VideoPresentation;
import io.github.ghosthack.mediabrowser.gl.GlVideoRenderer;
import io.github.ghosthack.mediabrowser.gl.MujglVideoRenderer;
import io.github.ghosthack.mediabrowser.media.VideoPlayer;
import io.github.ghosthack.mediabrowser.media.VideoStream;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

/**
 * Video frame sinks for the new {@code ux} viewer. There are the GL presenters,
 * a direct fallback, and one chooser ({@link VideoPlayer.FrameSink} impls):
 *
 * <ul>
 *   <li>{@link GlVideoPresenter} — the macOS GL path: each decoded BGRA frame is
 *       pushed through the LWJGL offscreen {@link GlVideoRenderer} (windowless
 *       Apple <b>CGL</b> OpenGL context, offscreen FBO), read back into a pooled
 *       {@link PixelBuffer} and presented in an {@link ImageView}.</li>
 *   <li>{@link GlfwVideoPresenter} — the transformed-video GL path on
 *       Windows/Linux: the same offscreen-FBO readback, but the GL context comes
 *       from a <b>GLFW hidden window</b> ({@link GlfwVideoRenderer}).</li>
 *   <li>Either GL presenter can instead render through {@link MujglVideoRenderer}
 *       (the FFM {@code mujgl} binding: CGL, WGL or EGL) when
 *       {@link VideoPresentation.GlLibrary#MUJGL} is selected.</li>
 *   <li>{@link DirectVideoPresenter} — the normal Windows/Linux path for
 *       untransformed BGRA video, as well as the guaranteed cross-platform fallback. It
 *       copies each BGRA frame <em>directly</em> into a pooled
 *       {@code PixelBuffer<ByteBuffer>} (byte-bgra-pre) backed {@link WritableImage}
 *       and updates the {@code ImageView} via {@link Platform#runLater} —
 *       <b>no OpenGL</b>.</li>
 *   <li>{@link FallbackVideoSink} — routes per {@link VideoPresentation}: under
 *       Auto it keeps CGL primary on macOS for VideoToolbox IOSurface ingestion,
 *       uses GL on Windows/Linux only when a user rotation requires a GPU
 *       transform, and otherwise selects the direct presenter; a GL path that
 *       cannot create its context degrades to direct, visibly. GPU and CPU are
 *       explicit and never substituted.</li>
 * </ul>
 *
 * <p>All sinks except {@code FallbackVideoSink} are package-private; the viewer
 * wires {@link FallbackVideoSink}.
 * GL work and the frame copy run on the playback thread; presentation hops to
 * the FX thread. A small pool of buffer/image slots ({@link VideoSlotPool}) is
 * rotated so the producer never overwrites a buffer that is on screen or still
 * being uploaded by the render thread — when no slot is free the frame is
 * dropped instead.</p>
 */
final class VideoPresenter {
    private VideoPresenter() {}

    static final int SLOT_COUNT = 3;

    /**
     * Rotates a tightly packed native BGRA frame ({@code width × height}) by
     * {@code quarterTurns} 90° clockwise steps into {@code dst} (top-down BGRA
     * rows, sized {@code outWidth*outHeight*4}). Used by the pure-JavaFX
     * {@link DirectVideoPresenter} fallback, which has no GPU to rotate on; the
     * GL presenters bake the same rotation into their quad for free. A zero turn
     * is a straight copy. Mirrors the permutation in
     * {@code media.RasterFrames#applyExifOrientation}. This is
     * {@link VideoPresentation.CpuRotation#SIMPLE}; {@link #rotateIntoTiled} is
     * the default.
     */
    static void rotateInto(MemorySegment src, int width, int height,
                           int quarterTurns, ByteBuffer dst) {
        ByteBuffer in = src.asByteBuffer();
        dst.clear();
        int q = ((quarterTurns % 4) + 4) % 4;
        if (q == 0) {
            dst.put(in);
            dst.rewind();
            return;
        }
        boolean swap = (q & 1) == 1;
        int ow = swap ? height : width;
        int oh = swap ? width : height;
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int sx;
                int sy;
                switch (q) {
                    case 1 -> { sx = oy;             sy = height - 1 - ox; } // 90 CW
                    case 2 -> { sx = width - 1 - ox; sy = height - 1 - oy; } // 180
                    default -> { sx = width - 1 - oy; sy = ox; }            // 270 CW (q == 3)
                }
                int s = (sy * width + sx) * 4;
                int d = (oy * ow + ox) * 4;
                dst.put(d,     in.get(s));
                dst.put(d + 1, in.get(s + 1));
                dst.put(d + 2, in.get(s + 2));
                dst.put(d + 3, in.get(s + 3));
            }
        }
        dst.rewind();
    }

    /** Edge of the square pixel tiles {@link #rotateIntoTiled} walks (64×64×4 B = 16 KiB). */
    static final int ROTATION_TILE = 64;

    /**
     * The same permutation as {@link #rotateInto}, byte for byte, but fast:
     * pixels move as whole ints, and odd turns walk {@link #ROTATION_TILE}-sized
     * tiles so the column-wise side of the transpose stays in cache instead of
     * missing on every pixel. Zero and half turns are already sequential on
     * both sides and need no tiling.
     */
    static void rotateIntoTiled(MemorySegment src, int width, int height,
                                int quarterTurns, ByteBuffer dst) {
        dst.clear();
        MemorySegment out = MemorySegment.ofBuffer(dst);
        int q = Math.floorMod(quarterTurns, 4);
        long pixels = (long) width * height;
        if (q == 0) {
            MemorySegment.copy(src, 0, out, 0, pixels * 4);
            return;
        }
        if (q == 2) {
            for (long d = 0, s = pixels - 1; d < pixels; d++, s--) {
                out.setAtIndex(JAVA_INT_UNALIGNED, d, src.getAtIndex(JAVA_INT_UNALIGNED, s));
            }
            return;
        }
        int ow = height;
        int oh = width;
        for (int ty = 0; ty < oh; ty += ROTATION_TILE) {
            int yEnd = Math.min(ty + ROTATION_TILE, oh);
            for (int tx = 0; tx < ow; tx += ROTATION_TILE) {
                int xEnd = Math.min(tx + ROTATION_TILE, ow);
                for (int oy = ty; oy < yEnd; oy++) {
                    long d = (long) oy * ow;
                    for (int ox = tx; ox < xEnd; ox++) {
                        long s = q == 1
                                ? (long) (height - 1 - ox) * width + oy              // 90 CW
                                : (long) ox * width + (width - 1 - oy);              // 270 CW
                        out.setAtIndex(JAVA_INT_UNALIGNED, d + ox,
                                src.getAtIndex(JAVA_INT_UNALIGNED, s));
                    }
                }
            }
        }
    }

    /**
     * A GL presenter's offscreen renderer ({@link GlVideoRenderer} or
     * {@code GlfwVideoRenderer}) as the presenter uses it — the seam that lets
     * tests drive {@link GlVideoPresenter} and {@link GlfwVideoPresenter}
     * without a GL context.
     */
    interface Renderer {
        int outWidth();
        int outHeight();
        /** Renders one BGRA frame and reads it back into {@code dst}. */
        void render(MemorySegment bgra, ByteBuffer dst);
        /** Zero-copy ingestion of a GPU frame; {@code false} sends it down the BGRA path. */
        default boolean renderSurface(VideoStream.GpuFrame frame, ByteBuffer dst) {
            return false;
        }
        void close();

        /** Builds the renderer (and its GL context) for one session. */
        interface Factory {
            Renderer create(int width, int height, int quarterTurns);
        }
    }

    /** The CGL {@link Renderer}: creates the windowless context, so it throws off macOS. */
    static Renderer cglRenderer(int width, int height, int quarterTurns) {
        GlVideoRenderer gl = new GlVideoRenderer(width, height, quarterTurns);
        return new Renderer() {
            @Override public int outWidth() { return gl.outWidth(); }
            @Override public int outHeight() { return gl.outHeight(); }
            @Override public void render(MemorySegment bgra, ByteBuffer dst) { gl.render(bgra, dst); }
            @Override public boolean renderSurface(VideoStream.GpuFrame frame, ByteBuffer dst) {
                return gl.renderSurface(frame.cvPixelBuffer(),
                        frame.codedWidth(), frame.codedHeight(),
                        frame.containerQuarterTurnsCw(), frame.bt709(), frame.fullRange(),
                        dst);
            }
            @Override public void close() { gl.close(); }
        };
    }

    /**
     * The mujgl {@link Renderer}: CGL (with IOSurface zero-copy) on macOS, WGL
     * on Windows, EGL on Linux; throws where no context can be created.
     */
    static Renderer mujglRenderer(int width, int height, int quarterTurns) {
        return mujglRenderer(width, height, quarterTurns, VideoPresentation.GlReadback.SYNC);
    }

    /** As {@link #mujglRenderer(int, int, int)}, with the given GPU readback. */
    static Renderer mujglRenderer(int width, int height, int quarterTurns,
                                  VideoPresentation.GlReadback readback) {
        MujglVideoRenderer gl = new MujglVideoRenderer(width, height, quarterTurns, readback);
        return new Renderer() {
            @Override public int outWidth() { return gl.outWidth(); }
            @Override public int outHeight() { return gl.outHeight(); }
            @Override public void render(MemorySegment bgra, ByteBuffer dst) { gl.render(bgra, dst); }
            @Override public boolean renderSurface(VideoStream.GpuFrame frame, ByteBuffer dst) {
                return gl.renderSurface(frame.cvPixelBuffer(),
                        frame.codedWidth(), frame.codedHeight(),
                        frame.containerQuarterTurnsCw(), frame.bt709(), frame.fullRange(),
                        dst);
            }
            @Override public void close() { gl.close(); }
        };
    }

    /**
     * Shared by the GL presenters' {@code begin}: once the renderer (and its
     * context) exists, builds the slot pool — three output-sized direct buffers,
     * which at 4K can exhaust direct memory — and on any failure closes the
     * renderer before rethrowing. A failing close is attached as suppressed, so
     * it can never replace the original error (an {@code OutOfMemoryError} must
     * stay one, or the chooser would read it as "GL unavailable").
     */
    static VideoSlotPool poolOrRelease(Renderer renderer, VideoSlotPool.Factory pools) {
        try {
            return pools.create(renderer.outWidth(), renderer.outHeight());
        } catch (Throwable t) {
            try {
                renderer.close();
            } catch (Throwable closeFailure) {
                t.addSuppressed(closeFailure);
            }
            throw t;
        }
    }

    /**
     * A reusable BGRA buffer + its PixelBuffer-backed image; who may write it is
     * {@link VideoSlotRing}'s call, by {@link #index}.
     */
    static final class Slot {
        final int index;
        final ByteBuffer buffer;
        final PixelBuffer<ByteBuffer> pixels;
        final WritableImage image;

        Slot(int index, int width, int height) {
            this.index = index;
            buffer = ByteBuffer.allocateDirect(width * height * 4);
            pixels = new PixelBuffer<>(width, height, buffer,
                    PixelFormat.getByteBgraPreInstance());
            image = new WritableImage(pixels);
        }
    }
}

/**
 * What every presenting sink shares: its {@link VideoSlotPool} (slot ownership
 * and the FX hop) and {@link #revoke}, which keeps any frame still queued for
 * that hop off screen. Revoke lives here once, final, so no presenter can
 * forget it — a stale frame landing over the next item is what a closed player
 * must never cause.
 *
 * <p>The pool is built on the playback thread in {@code begin} and revoked
 * from the closing thread; both sides are volatile, so a revoke racing
 * {@code begin} reaches the pool from one side or the other.</p>
 */
abstract class PooledFramePresenter implements VideoPlayer.FrameSink {

    private volatile VideoSlotPool pool;
    private volatile boolean revoked;

    /** Playback thread, from {@code begin}: installs the session's pool. */
    final void usePool(VideoSlotPool built) {
        pool = built;
        if (revoked) built.revoke();
    }

    /** The pool installed by {@code begin}; {@code null} before it. */
    final VideoSlotPool pool() {
        return pool;
    }

    /**
     * Frames already queued for the FX thread, and any presented later, are
     * dropped; slots are still released as the pool's ring allows. GL stays
     * to {@code close}.
     */
    @Override
    public final void revoke() {
        revoked = true;
        VideoSlotPool installed = pool;
        if (installed != null) installed.revoke();
    }
}

/**
 * GL-primary sink: renders each frame through {@link GlVideoRenderer} (behind
 * {@link VideoPresenter.Renderer}) and presents the readback. Modeled on the original {@code ui.GlFramePresenter}
 * (which is package-private to {@code ui}, hence this {@code ux} copy). The GL
 * context is created in {@link #begin}; if Apple CGL is unavailable the
 * constructor throws and {@link FallbackVideoSink} catches it.
 */
final class GlVideoPresenter extends PooledFramePresenter {

    private final int quarterTurns;
    private final VideoPresenter.Renderer.Factory renderers;
    private final VideoSlotPool.Factory pools;
    private VideoPresenter.Renderer renderer;

    GlVideoPresenter(ImageView target) {
        this(target, 0);
    }

    GlVideoPresenter(ImageView target, int quarterTurns) {
        this(quarterTurns, VideoPresenter::cglRenderer,
                (w, h) -> VideoSlotPool.forImageView(target, w, h));
    }

    /** Test seam: renderer and pool are injected, so no GL context or FX toolkit is needed. */
    GlVideoPresenter(int quarterTurns, VideoPresenter.Renderer.Factory renderers,
                     VideoSlotPool.Factory pools) {
        this.quarterTurns = quarterTurns;
        this.renderers = renderers;
        this.pools = pools;
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        // Constructing the renderer creates the CGL OpenGL context; this is the
        // call that throws off macOS and triggers the direct fallback. The user
        // rotation is baked into the quad on the GPU, so the readback (and the
        // pooled slots) carry the rotated dimensions.
        VideoPresenter.Renderer built = renderers.create(width, height, quarterTurns);
        usePool(VideoPresenter.poolOrRelease(built, pools));
        renderer = built;
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        VideoSlotPool pool = pool();
        VideoPresenter.Slot slot = pool.acquire();
        if (slot == null) return; // the FX/render thread is behind: drop this frame
        renderer.render(bgra, slot.buffer);
        pool.present(slot);
    }

    @Override
    public boolean gpuFrame(VideoStream.GpuFrame frame, long positionMicros) {
        VideoSlotPool pool = pool();
        VideoPresenter.Slot slot = pool.acquire();
        if (slot == null) return true; // dropping a frame is handling it
        if (!renderer.renderSurface(frame, slot.buffer)) {
            pool.abandon(slot);
            return false; // zero-copy unavailable for this frame → BGRA path
        }
        pool.present(slot);
        return true;
    }

    @Override
    public void close() {
        revoke();
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }
}

/**
 * Cross-platform fallback sink: copies each decoded BGRA frame straight into a
 * pooled {@code PixelBuffer<ByteBuffer>} (byte-bgra-pre) backed image and swaps
 * it into the {@link ImageView} on the FX thread. No OpenGL is involved, so it
 * works anywhere JavaFX runs (including this Windows box where Apple CGL is
 * absent). Frames are dropped when no slot is free (see {@link VideoSlotPool}).
 */
final class DirectVideoPresenter extends PooledFramePresenter {

    private final int quarterTurns;
    private final VideoPresentation.CpuRotation rotation;
    private final VideoSlotPool.Factory poolFactory;

    DirectVideoPresenter(ImageView target) {
        this(target, 0, VideoPresentation.CpuRotation.TILED);
    }

    DirectVideoPresenter(ImageView target, int quarterTurns, VideoPresentation.CpuRotation rotation) {
        this(quarterTurns, rotation, (w, h) -> VideoSlotPool.forImageView(target, w, h));
    }

    DirectVideoPresenter(int quarterTurns, VideoSlotPool.Factory poolFactory) {
        this(quarterTurns, VideoPresentation.CpuRotation.TILED, poolFactory);
    }

    DirectVideoPresenter(int quarterTurns, VideoPresentation.CpuRotation rotation,
                         VideoSlotPool.Factory poolFactory) {
        this.quarterTurns = quarterTurns;
        this.rotation = rotation;
        this.poolFactory = poolFactory;
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        boolean swap = (quarterTurns & 1) == 1;
        int ow = swap ? height : width;
        int oh = swap ? width : height;
        usePool(poolFactory.create(ow, oh));
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        VideoSlotPool pool = pool();
        VideoPresenter.Slot slot = pool.acquire();
        if (slot == null) return; // the FX/render thread is behind: drop this frame

        // The frame buffer is only valid during this call, so copy (rotating, if
        // requested) now — synchronously, on the playback thread — before handing
        // off to FX. With no rotation this is a straight bulk copy as before.
        if (rotation == VideoPresentation.CpuRotation.SIMPLE) {
            VideoPresenter.rotateInto(bgra, width, height, quarterTurns, slot.buffer);
        } else {
            VideoPresenter.rotateIntoTiled(bgra, width, height, quarterTurns, slot.buffer);
        }
        pool.present(slot);
    }

    @Override
    public void close() {
        revoke();
    }
}

/**
 * Chooser sink: picks CGL, GL or direct presentation from the
 * {@link VideoPresentation} setting, the host OS and the transforms this
 * playback session requires.
 *
 * <p>Under {@link VideoPresentation.Presenter#AUTO}, macOS keeps
 * {@link GlVideoPresenter} primary even without user rotation: its CGL context
 * is what lets VideoToolbox NV12 IOSurfaces bypass a CPU readback and
 * conversion. Windows/Linux decoded frames are CPU BGRA, so an identity GL pass
 * would only upload, draw and read back unchanged pixels; those sessions go
 * directly to JavaFX and let Prism perform all display resizing. GL is selected
 * only for a non-zero user rotation. If that GL context cannot be created, the
 * chooser closes what the GL sink built and falls back to the
 * {@link DirectVideoPresenter}, visibly: {@link #activePresenter} says so and
 * {@link #glFailure} carries the reason.</p>
 *
 * <p>{@link VideoPresentation.Presenter#GPU},
 * {@link VideoPresentation.Presenter#CPU} and
 * {@link VideoPresentation.Presenter#VIMAGE} are explicit: GPU always takes the
 * platform's GL route and a GL failure fails playback instead of falling back;
 * CPU never creates a context; vImage (macOS Accelerate, no GL) fails playback
 * off macOS. Errors other than GL unavailability (e.g.
 * {@link OutOfMemoryError}) propagate under every presenter. The route is pure
 * and testable via {@link #preferredRoute}.</p>
 */
final class FallbackVideoSink implements VideoPlayer.FrameSink {

    /**
     * {@code CGL}: macOS. {@code GL}: the Windows/Linux offscreen context — a
     * GLFW hidden window under LWJGL, WGL or EGL under mujgl. {@code VIMAGE}:
     * macOS Accelerate, no GL.
     */
    enum Route { CGL, GL, VIMAGE, DIRECT }

    /** User rotation (90° clockwise quarter-turns) baked into every presented frame. */
    private final int quarterTurns;
    private final String osName;
    private final VideoPresentation.Config config;
    private final Function<Route, VideoPlayer.FrameSink> glSinks;
    private final Supplier<VideoPlayer.FrameSink> directSinks;
    /**
     * Installed on the playback thread in {@link #begin}, read by
     * {@link #revoke} on the closing thread; both volatile, so a revoke racing
     * {@code begin} reaches the presenter from one side or the other.
     */
    private volatile VideoPlayer.FrameSink delegate;
    private volatile boolean revoked;
    /** True when a GL path (CGL or GL) was used; false for the direct presenter. */
    private boolean usedGl;
    /** Human-readable label of the active path; see {@link #activePresenter}. */
    private String activePresenter;
    /** Why a requested GL path degraded to PixelBuffer; {@code null} when it did not. */
    private String glFailure;

    FallbackVideoSink(ImageView target, int quarterTurns, VideoPresentation.Config config) {
        this(System.getProperty("os.name", ""), config,
                route -> route == Route.VIMAGE
                        ? new VImageVideoPresenter(target, quarterTurns)
                        : glSink(route, config, target, quarterTurns),
                () -> new DirectVideoPresenter(target, quarterTurns, config.cpuRotation()),
                quarterTurns);
    }

    /** Test seam under the default configuration: the sinks for each route are injected. */
    FallbackVideoSink(String osName, Function<Route, VideoPlayer.FrameSink> glSinks,
                      Supplier<VideoPlayer.FrameSink> directSinks, int quarterTurns) {
        this(osName, VideoPresentation.Config.DEFAULT, glSinks, directSinks, quarterTurns);
    }

    /** Test seam: the sinks for each route are injected, so no GL/FX is needed. */
    FallbackVideoSink(String osName, VideoPresentation.Config config,
                      Function<Route, VideoPlayer.FrameSink> glSinks,
                      Supplier<VideoPlayer.FrameSink> directSinks, int quarterTurns) {
        this.osName = osName;
        this.config = config;
        this.glSinks = glSinks;
        this.directSinks = directSinks;
        this.quarterTurns = quarterTurns;
    }

    /** The GL presenter for {@code route}, rendering through the configured library. */
    private static VideoPlayer.FrameSink glSink(Route route, VideoPresentation.Config config,
                                                ImageView target, int quarterTurns) {
        VideoSlotPool.Factory pools = (w, h) -> VideoSlotPool.forImageView(target, w, h);
        VideoPresentation.GlReadback readback = config.effectiveReadback();
        VideoPresenter.Renderer.Factory mujgl =
                (w, h, q) -> VideoPresenter.mujglRenderer(w, h, q, readback);
        boolean useMujgl = config.glLibrary() == VideoPresentation.GlLibrary.MUJGL;
        if (route == Route.CGL) {
            return new GlVideoPresenter(quarterTurns,
                    useMujgl ? mujgl : VideoPresenter::cglRenderer, pools);
        }
        return new GlfwVideoPresenter(quarterTurns,
                useMujgl ? mujgl : GlfwVideoPresenter::glfwRenderer, pools);
    }

    boolean usedGl() { return usedGl; }

    /**
     * The presenter actually wired by {@link #begin}, as a short status-bar
     * label naming the route and library ("GL · CGL · LWJGL", "GL · GLFW ·
     * LWJGL", "GL · CGL · mujgl", "GL · WGL · mujgl", "GL · EGL · mujgl", with
     * " · PBO" for mujgl's same-frame PBO readback and " · PBO +1" for its
     * previous-frame one), the
     * CPU rotation when one runs ("PixelBuffer · tiled"), plain "PixelBuffer",
     * "vImage", or "PixelBuffer · GL failed" when an Auto GL path degraded;
     * {@code null} before {@code begin}.
     */
    String activePresenter() { return activePresenter; }

    /**
     * Why the requested GL path was unavailable (the exception, one line), for
     * the status-bar tooltip; {@code null} when no GL path failed.
     */
    String glFailure() { return glFailure; }

    /**
     * Pure platform-selection logic: returns {@code true} iff the Apple CGL GL
     * path should be used for GL (macOS), i.e. when {@code osName}
     * (case-insensitive) contains {@code "mac"} or {@code "darwin"}. No
     * GL/native work — safe to unit-test.
     */
    static boolean preferCgl(String osName) {
        if (osName == null) return false;
        String os = osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    /**
     * Selects the presentation path without touching JavaFX or native code.
     * Display resizing is deliberately absent: Prism owns both upscaling and
     * downscaling and therefore never makes an Auto Windows/Linux session use GL.
     */
    static Route preferredRoute(String osName, int quarterTurns, VideoPresentation.Presenter presenter) {
        Route gl = preferCgl(osName) ? Route.CGL : Route.GL;
        return switch (presenter) {
            case CPU -> Route.DIRECT;
            case VIMAGE -> Route.VIMAGE;
            case GPU -> gl;
            case AUTO -> gl == Route.CGL || Math.floorMod(quarterTurns, 4) != 0 ? gl : Route.DIRECT;
        };
    }

    /** The status-bar name of a GL route under {@code library}. */
    static String glLabel(Route route, VideoPresentation.GlLibrary library, String osName) {
        if (library == VideoPresentation.GlLibrary.LWJGL) {
            return route == Route.CGL ? "GL · CGL · LWJGL" : "GL · GLFW · LWJGL";
        }
        if (route == Route.CGL) return "GL · CGL · mujgl";
        boolean windows = osName != null && osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        return windows ? "GL · WGL · mujgl" : "GL · EGL · mujgl";
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        Route route = preferredRoute(osName, quarterTurns, config.presenter());
        if (route == Route.DIRECT) {
            useDirect(width, height, durationMicros);
            activePresenter = Math.floorMod(quarterTurns, 4) == 0 ? "PixelBuffer"
                    : "PixelBuffer · " + config.cpuRotation().toString().toLowerCase(java.util.Locale.ROOT);
            return;
        }
        VideoPlayer.FrameSink gl = null;
        try {
            gl = glSinks.apply(route);
            gl.begin(width, height, durationMicros); // creates the GL context — may throw
            install(gl);
            if (route == Route.VIMAGE) {
                usedGl = false;
                activePresenter = "vImage";
                return;
            }
            usedGl = true;
            activePresenter = glLabel(route, config.glLibrary(), osName)
                    + switch (config.effectiveReadback()) {
                        case SYNC -> "";
                        case PBO_SAME_FRAME -> " · PBO";
                        case PBO_PREVIOUS_FRAME -> " · PBO +1";
                    };
        } catch (RuntimeException | LinkageError glUnavailable) {
            // The failures the fallback exists for: no usable context, FBO
            // incomplete, … (RuntimeException) and missing GLFW/LWJGL JARs or
            // natives (NoClassDefFoundError / UnsatisfiedLinkError). Release
            // whatever the GL sink built first.
            closeAfterFailedBegin(gl, glUnavailable);
            if (config.presenter() != VideoPresentation.Presenter.AUTO) {
                // An explicit GPU choice is honoured or fails playback: never a
                // silent substitute for the path under test.
                throw glUnavailable;
            }
            // Auto degrades visibly to the pure-JavaFX direct PixelBuffer
            // presenter: the status bar names the fallback and its tooltip
            // carries the reason.
            glFailure = glUnavailable.toString();
            System.err.println("[VideoPresenter] GL unavailable (" + glUnavailable
                    + "); using PixelBuffer fallback usedGl=false");
            useDirect(width, height, durationMicros);
            activePresenter = "PixelBuffer · GL failed";
        } catch (Throwable fatal) {
            // OutOfMemoryError and other VM errors are not "GL unavailable": the
            // direct presenter would need the same buffers. Release and rethrow
            // so playback fails loudly.
            closeAfterFailedBegin(gl, fatal);
            throw fatal;
        }
    }

    private static void closeAfterFailedBegin(VideoPlayer.FrameSink sink, Throwable cause) {
        if (sink == null) return;
        try {
            sink.close();
        } catch (Throwable closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    private void useDirect(int width, int height, long durationMicros) {
        VideoPlayer.FrameSink direct = directSinks.get();
        direct.begin(width, height, durationMicros);
        install(direct);
        usedGl = false;
    }

    private void install(VideoPlayer.FrameSink presenter) {
        delegate = presenter;
        if (revoked) presenter.revoke();
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        if (delegate != null) delegate.frame(bgra, width, height, positionMicros);
    }

    @Override
    public boolean gpuFrame(VideoStream.GpuFrame frame, long positionMicros) {
        return delegate != null && delegate.gpuFrame(frame, positionMicros);
    }

    @Override
    public void revoke() {
        revoked = true;
        VideoPlayer.FrameSink installed = delegate;
        if (installed != null) installed.revoke();
    }

    @Override
    public void close() {
        if (delegate != null) delegate.close();
        delegate = null;
    }
}
