package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.gl.GlVideoRenderer;
import io.github.ghosthack.mediabrowser.media.VideoPlayer;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Video frame sinks for the new {@code ux} viewer. There are the GL presenters,
 * a direct fallback, and one chooser ({@link VideoPlayer.FrameSink} impls):
 *
 * <ul>
 *   <li>{@link GlVideoPresenter} — the macOS GL path: each decoded BGRA frame is
 *       pushed through the LWJGL offscreen {@link GlVideoRenderer} (windowless
 *       Apple <b>CGL</b> OpenGL context, offscreen FBO), read back into a pooled
 *       {@link PixelBuffer} and presented in an {@link ImageView}.</li>
 *   <li>{@link GlfwVideoPresenter} — the cross-platform GL path (Windows/Linux,
 *       GAP 3): the same offscreen-FBO readback, but the GL context comes from a
 *       <b>GLFW hidden window</b> ({@link GlfwVideoRenderer}) so it works off
 *       macOS where CGL cannot be constructed.</li>
 *   <li>{@link DirectVideoPresenter} — the guaranteed cross-platform fallback that
 *       copies each BGRA frame <em>directly</em> into a pooled
 *       {@code PixelBuffer<ByteBuffer>} (byte-bgra-pre) backed {@link WritableImage}
 *       and updates the {@code ImageView} via {@link Platform#runLater} —
 *       <b>no OpenGL</b>.</li>
 *   <li>{@link FallbackVideoSink} — picks the GL presenter for the host OS
 *       (CGL on macOS, GLFW elsewhere), wraps its construction in a try/catch, and
 *       degrades to the direct presenter when the GL context cannot be created.
 *       This keeps a GL path primary on every platform while guaranteeing video
 *       actually plays everywhere.</li>
 * </ul>
 *
 * <p>All sinks except {@code FallbackVideoSink} are package-private; the viewer
 * wires {@link FallbackVideoSink}.
 * GL work and the frame copy run on the playback thread; presentation hops to
 * the FX thread. A small pool of buffer/image slots is rotated so the producer
 * never overwrites a buffer the FX thread has not finished presenting — when no
 * slot is free the frame is dropped instead.</p>
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
     * {@code media.RasterFrames#applyExifOrientation}.
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

    /** A reusable BGRA buffer + its PixelBuffer-backed image and a busy flag. */
    static final class Slot {
        final ByteBuffer buffer;
        final PixelBuffer<ByteBuffer> pixels;
        final WritableImage image;
        final AtomicBoolean busy = new AtomicBoolean();

        Slot(int width, int height) {
            buffer = ByteBuffer.allocateDirect(width * height * 4);
            pixels = new PixelBuffer<>(width, height, buffer,
                    PixelFormat.getByteBgraPreInstance());
            image = new WritableImage(pixels);
        }
    }
}

/**
 * GL-primary sink: renders each frame through {@link GlVideoRenderer} and
 * presents the readback. Modeled on the original {@code ui.GlFramePresenter}
 * (which is package-private to {@code ui}, hence this {@code ux} copy). The GL
 * context is created in {@link #begin}; if Apple CGL is unavailable the
 * constructor throws and {@link FallbackVideoSink} catches it.
 */
final class GlVideoPresenter implements VideoPlayer.FrameSink {

    private final ImageView target;
    private final int quarterTurns;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private GlVideoRenderer renderer;
    private VideoPresenter.Slot[] slots;

    GlVideoPresenter(ImageView target) {
        this(target, 0);
    }

    GlVideoPresenter(ImageView target, int quarterTurns) {
        this.target = target;
        this.quarterTurns = quarterTurns;
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        // Constructing the renderer creates the CGL OpenGL context; this is the
        // call that throws off macOS and triggers the direct fallback. The user
        // rotation is baked into the quad on the GPU, so the readback (and the
        // pooled slots) carry the rotated dimensions.
        renderer = new GlVideoRenderer(width, height, quarterTurns);
        slots = new VideoPresenter.Slot[VideoPresenter.SLOT_COUNT];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new VideoPresenter.Slot(renderer.outWidth(), renderer.outHeight());
        }
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        VideoPresenter.Slot slot = null;
        for (VideoPresenter.Slot s : slots) {
            if (s.busy.compareAndSet(false, true)) {
                slot = s;
                break;
            }
        }
        if (slot == null) return; // the FX thread is behind: drop this frame

        renderer.render(bgra, slot.buffer);

        VideoPresenter.Slot chosen = slot;
        Platform.runLater(() -> {
            if (!disposed.get()) {
                chosen.pixels.updateBuffer(pb -> null); // whole buffer is dirty
                if (target.getImage() != chosen.image) {
                    target.setImage(chosen.image);
                }
            }
            chosen.busy.set(false);
        });
    }

    @Override
    public void close() {
        disposed.set(true);
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
 * absent). Frames are dropped when every slot is still awaiting presentation.
 */
final class DirectVideoPresenter implements VideoPlayer.FrameSink {

    private final ImageView target;
    private final int quarterTurns;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private VideoPresenter.Slot[] slots;

    DirectVideoPresenter(ImageView target) {
        this(target, 0);
    }

    DirectVideoPresenter(ImageView target, int quarterTurns) {
        this.target = target;
        this.quarterTurns = quarterTurns;
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        boolean swap = (quarterTurns & 1) == 1;
        int ow = swap ? height : width;
        int oh = swap ? width : height;
        slots = new VideoPresenter.Slot[VideoPresenter.SLOT_COUNT];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new VideoPresenter.Slot(ow, oh);
        }
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        VideoPresenter.Slot slot = null;
        for (VideoPresenter.Slot s : slots) {
            if (s.busy.compareAndSet(false, true)) {
                slot = s;
                break;
            }
        }
        if (slot == null) return; // the FX thread is behind: drop this frame

        // The frame buffer is only valid during this call, so copy (rotating, if
        // requested) now — synchronously, on the playback thread — before handing
        // off to FX. With no rotation this is a straight bulk copy as before.
        VideoPresenter.rotateInto(bgra, width, height, quarterTurns, slot.buffer);

        VideoPresenter.Slot chosen = slot;
        Platform.runLater(() -> {
            if (!disposed.get()) {
                chosen.pixels.updateBuffer(pb -> null); // whole buffer is dirty
                if (target.getImage() != chosen.image) {
                    target.setImage(chosen.image);
                }
            }
            chosen.busy.set(false);
        });
    }

    @Override
    public void close() {
        disposed.set(true);
        slots = null;
    }
}

/**
 * Chooser sink: picks the right GL presenter per OS, and if the GL context
 * cannot be created, transparently falls back to the {@link DirectVideoPresenter}.
 *
 * <p>The per-OS choice (GAP 3) is made by the pure, testable
 * {@link #preferCgl(String)}: on macOS the GL path is {@link GlVideoPresenter}
 * (Apple CGL, which needs no GLFW and must run off the AppKit main thread);
 * everywhere else (Windows/Linux) it is {@link GlfwVideoPresenter} (an LWJGL
 * GLFW hidden-window context). Either way the GL context (and thus any failure)
 * is created in {@link #begin}, on the playback thread, so the try/catch lives
 * there and degrades to the pure-JavaFX {@link DirectVideoPresenter} — the
 * guaranteed cross-platform fallback that never uses OpenGL.</p>
 */
final class FallbackVideoSink implements VideoPlayer.FrameSink {

    private final ImageView target;
    /** User rotation (90° clockwise quarter-turns) baked into every presented frame. */
    private final int quarterTurns;
    private VideoPlayer.FrameSink delegate;
    /** True when a GL path (CGL or GLFW) was used; false for the direct fallback. */
    private boolean usedGl;
    /** Human-readable label of the active path: "GL · CGL", "GL · GLFW" or "PixelBuffer". */
    private String activePresenter;

    FallbackVideoSink(ImageView target) {
        this(target, 0);
    }

    FallbackVideoSink(ImageView target, int quarterTurns) {
        this.target = target;
        this.quarterTurns = quarterTurns;
    }

    boolean usedGl() { return usedGl; }

    /**
     * The presenter actually wired by {@link #begin}, as a short status-bar label
     * ("GL · CGL", "GL · GLFW" or "PixelBuffer"); {@code null} before {@code begin}.
     */
    String activePresenter() { return activePresenter; }

    /**
     * Pure platform-selection logic: returns {@code true} iff the Apple CGL GL
     * path should be preferred (macOS), i.e. when {@code osName} (case-insensitive)
     * contains {@code "mac"} or {@code "darwin"}. Off macOS this is {@code false}
     * and the GLFW path is chosen instead. No GL/native work — safe to unit-test.
     */
    static boolean preferCgl(String osName) {
        if (osName == null) return false;
        String os = osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        boolean mac = preferCgl(System.getProperty("os.name", ""));
        try {
            // macOS → CGL (GlVideoPresenter); off macOS → GLFW (GlfwVideoPresenter).
            VideoPlayer.FrameSink gl = mac
                    ? new GlVideoPresenter(target, quarterTurns)
                    : new GlfwVideoPresenter(target, quarterTurns);
            gl.begin(width, height, durationMicros); // creates the GL context — may throw
            delegate = gl;
            usedGl = true;
            activePresenter = mac ? "GL · CGL" : "GL · GLFW";
        } catch (Throwable glUnavailable) {
            // Any GL/native failure (no usable context, FBO incomplete, …) degrades
            // to the pure-JavaFX direct PixelBuffer presenter so video still plays —
            // no crash, no OpenGL. This is the guaranteed cross-platform fallback.
            System.err.println("[VideoPresenter] GL unavailable (" + glUnavailable
                    + "); using PixelBuffer fallback usedGl=false");
            DirectVideoPresenter direct = new DirectVideoPresenter(target, quarterTurns);
            direct.begin(width, height, durationMicros);
            delegate = direct;
            usedGl = false;
            activePresenter = "PixelBuffer";
        }
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        if (delegate != null) delegate.frame(bgra, width, height, positionMicros);
    }

    @Override
    public void close() {
        if (delegate != null) delegate.close();
        delegate = null;
    }
}
