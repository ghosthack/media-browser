package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.gl.GlfwVideoRenderer;
import io.github.ghosthack.mediabrowser.media.VideoPlayer;

import javafx.application.Platform;
import javafx.scene.image.ImageView;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-platform GL-primary sink (GAP 3): renders each frame through the GLFW
 * offscreen {@link GlfwVideoRenderer} and presents the readback in an
 * {@link ImageView}. This is the GL path used <b>off macOS</b> (Windows/Linux);
 * the {@link GlVideoPresenter} (Apple CGL) stays the GL path on macOS.
 *
 * <p>Structurally identical to {@link GlVideoPresenter}: it owns the
 * {@link VideoPresenter#SLOT_COUNT} pooled {@link VideoPresenter.Slot}s and the
 * renderer; {@link #begin} constructs the renderer (creating the GLFW GL context
 * — the call that throws when no usable context is available, triggering the
 * {@link FallbackVideoSink} degrade); {@link #frame} renders into a free slot
 * and swaps it into the {@code ImageView} on the FX thread (frame-dropping when
 * every slot is still awaiting presentation); {@link #close} disposes.</p>
 */
final class GlfwVideoPresenter implements VideoPlayer.FrameSink {

    private final ImageView target;
    private final int quarterTurns;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private GlfwVideoRenderer renderer;
    private VideoPresenter.Slot[] slots;

    GlfwVideoPresenter(ImageView target) {
        this(target, 0);
    }

    GlfwVideoPresenter(ImageView target, int quarterTurns) {
        this.target = target;
        this.quarterTurns = quarterTurns;
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        // Constructing the renderer creates the GLFW OpenGL context; this is the
        // call that throws when GL is unavailable and triggers the direct fallback.
        // The user rotation is baked into the quad on the GPU, so the readback
        // (and the pooled slots) carry the rotated dimensions.
        renderer = new GlfwVideoRenderer(width, height, quarterTurns);
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
