package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.gl.GlfwVideoRenderer;

import javafx.scene.image.ImageView;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Windows/Linux transformed-video sink: renders each frame through the GLFW
 * offscreen {@link GlfwVideoRenderer} (LWJGL) — or, when mujgl is the selected
 * GL library, the WGL/EGL {@code MujglVideoRenderer} — and presents the
 * readback in an {@link ImageView}. {@link FallbackVideoSink} selects it when a live GPU
 * transform is required; untransformed frames use direct PixelBuffer
 * presentation. The {@link GlVideoPresenter} (Apple CGL) remains the macOS GL
 * path.
 *
 * <p>Structurally identical to {@link GlVideoPresenter}: it owns the
 * {@link VideoSlotPool} and the renderer; {@link #begin} constructs the renderer
 * (creating the GLFW GL context — the call that throws when no usable context is
 * available, triggering the {@link FallbackVideoSink} degrade); {@link #frame}
 * renders into a free slot and swaps it into the {@code ImageView} on the FX
 * thread (frame-dropping when no slot is free); {@link #close} disposes.</p>
 */
final class GlfwVideoPresenter extends PooledFramePresenter {

    private final int quarterTurns;
    private final VideoPresenter.Renderer.Factory renderers;
    private final VideoSlotPool.Factory pools;
    private VideoPresenter.Renderer renderer;

    GlfwVideoPresenter(ImageView target) {
        this(target, 0);
    }

    GlfwVideoPresenter(ImageView target, int quarterTurns) {
        this(quarterTurns, GlfwVideoPresenter::glfwRenderer,
                (w, h) -> VideoSlotPool.forImageView(target, w, h));
    }

    /** Test seam: renderer and pool are injected, so no GL context or FX toolkit is needed. */
    GlfwVideoPresenter(int quarterTurns, VideoPresenter.Renderer.Factory renderers,
                       VideoSlotPool.Factory pools) {
        this.quarterTurns = quarterTurns;
        this.renderers = renderers;
        this.pools = pools;
    }

    /** The GLFW {@link VideoPresenter.Renderer}: creates the hidden-window context. */
    static VideoPresenter.Renderer glfwRenderer(int width, int height, int quarterTurns) {
        GlfwVideoRenderer gl = new GlfwVideoRenderer(width, height, quarterTurns);
        return new VideoPresenter.Renderer() {
            @Override public int outWidth() { return gl.outWidth(); }
            @Override public int outHeight() { return gl.outHeight(); }
            @Override public void render(MemorySegment bgra, ByteBuffer dst) { gl.render(bgra, dst); }
            @Override public void close() { gl.close(); }
        };
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        // Constructing the renderer creates the GLFW OpenGL context; this is the
        // call that throws when GL is unavailable and triggers the direct fallback.
        // The user rotation is baked into the quad on the GPU, so the readback
        // (and the pooled slots) carry the rotated dimensions.
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
    public void close() {
        revoke();
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }
}
