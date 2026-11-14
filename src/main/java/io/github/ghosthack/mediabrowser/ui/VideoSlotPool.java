package io.github.ghosthack.mediabrowser.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.ImageView;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The {@link VideoPresenter#SLOT_COUNT} pooled {@link VideoPresenter.Slot}s of
 * one presenter and their hand-off to the FX thread, shared by
 * {@link GlVideoPresenter}, {@link GlfwVideoPresenter} and
 * {@link DirectVideoPresenter}. Slot ownership is {@link VideoSlotRing}'s: the
 * producer {@link #acquire}s a slot (or drops the frame), fills it, and
 * {@link #present}s it; the on-screen slot and the one it just replaced are
 * never handed out until the render thread is provably done with them.
 *
 * <p>While slots are retiring a {@link PulseClock} (an {@link AnimationTimer}
 * in production) feeds {@link VideoSlotRing#pulse} at the start of each pulse
 * and posts {@link VideoSlotRing#synced} to the FX executor. A pulse runs as one
 * FX event (animation timers, then the scene pulse listeners and their sync), so
 * that {@code runLater} executes after the pulse's scene sync — the post-sync
 * signal the ring releases on. The clock runs only while something is retiring,
 * and its own pulse requests are what guarantee a held slot is eventually
 * released even when nothing else is animating. The FX executor, display action
 * and clock are injectable for tests.</p>
 */
final class VideoSlotPool {

    /** Calls {@code onPulse} at the start of every FX pulse while started. FX thread only. */
    interface PulseClock {
        void start(Runnable onPulse);
        void stop();
    }

    /** Builds a presenter's pool for its output size; injectable so tests need no FX toolkit. */
    interface Factory {
        VideoSlotPool create(int width, int height);
    }

    private final VideoPresenter.Slot[] slots;
    private final VideoSlotRing ring;
    private final Executor fxThread;
    private final Consumer<VideoPresenter.Slot> display;
    private final PulseClock clock;
    private final AtomicBoolean disposed = new AtomicBoolean();
    /** FX thread only. */
    private boolean clockRunning;

    VideoSlotPool(int width, int height, Executor fxThread,
                  Consumer<VideoPresenter.Slot> display, PulseClock clock) {
        this.fxThread = fxThread;
        this.display = display;
        this.clock = clock;
        ring = new VideoSlotRing(VideoPresenter.SLOT_COUNT);
        slots = new VideoPresenter.Slot[ring.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new VideoPresenter.Slot(i, width, height);
        }
    }

    /** The production pool: presents into {@code target} via {@link Platform#runLater}. */
    static VideoSlotPool forImageView(ImageView target, int width, int height) {
        return new VideoSlotPool(width, height, Platform::runLater, slot -> {
            slot.pixels.updateBuffer(pb -> null); // whole buffer is dirty
            if (target.getImage() != slot.image) {
                target.setImage(slot.image);
            }
        }, new FxPulseClock());
    }

    /** Producer: a slot to write the next frame into, or {@code null} to drop it. */
    VideoPresenter.Slot acquire() {
        int i = ring.acquire();
        return i < 0 ? null : slots[i];
    }

    /** Producer: returns an acquired slot that will not be presented. */
    void abandon(VideoPresenter.Slot slot) {
        ring.abandon(slot.index);
    }

    /** Producer: hands a filled slot to the FX thread to show. */
    void present(VideoPresenter.Slot slot) {
        fxThread.execute(() -> {
            if (disposed.get()) {
                ring.dropped(slot.index);
                return;
            }
            try {
                display.accept(slot);
            } catch (Throwable t) {
                // Not shown (setImage is the display's last step): give the slot
                // back, or each failure would cost the pool a slot for good.
                ring.dropped(slot.index);
                throw t;
            }
            if (ring.shown(slot.index) && !clockRunning) {
                clockRunning = true;
                clock.start(this::onPulse);
            }
        });
    }

    private void onPulse() {
        long pulse = ring.pulse();
        fxThread.execute(() -> {
            if (!ring.synced(pulse) && clockRunning) {
                clockRunning = false;
                clock.stop();
            }
        });
    }

    /**
     * Stops presenting, from any thread: every hand-off still queued for the FX
     * thread, or queued later, gives its slot back without touching the view.
     * Slot release is unaffected: the on-screen slot stays reserved, and the
     * clock keeps running until the slots already retiring are released (at
     * most one more pulse), since the render thread may still be reading them.
     */
    void revoke() {
        disposed.set(true);
    }

    VideoSlotRing ring() { return ring; }

    VideoPresenter.Slot slot(int index) { return slots[index]; }

    /** {@link PulseClock} over an {@link AnimationTimer}: its handle runs before the pulse's scene sync. */
    private static final class FxPulseClock implements PulseClock {
        private AnimationTimer timer;

        @Override
        public void start(Runnable onPulse) {
            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    onPulse.run();
                }
            };
            timer.start();
        }

        @Override
        public void stop() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
        }
    }
}
