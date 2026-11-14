package io.github.ghosthack.mediabrowser.ui;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Ownership state machine for the video presenters' pooled frame slots
 * ({@link VideoSlotPool}), by index — no JavaFX, so it is unit-testable.
 *
 * <p>A slot is {@code FREE} → {@code BUSY} (the producer writes it, then it
 * waits for the FX hop) → {@code SHOWN} (the {@code ImageView}'s image) →
 * {@code RETIRING} (a different slot replaced it) → {@code FREE}. The on-screen
 * slot is never handed out, and a replaced slot stays reserved until the scene
 * sync of the next pulse has completed: Prism uploads the {@code PixelBuffer} on
 * the render thread <em>after</em> the FX update, and a pulse's scene sync waits
 * for the previous render to finish ({@code Scene.ScenePulseListener}:
 * {@code waitForRenderingToComplete}) before it hands the replacement to the
 * render thread. So once the first pulse to start after the replacement has
 * synced, no render reads the old slot. {@link #pulse} marks a pulse start and
 * {@link #synced} that pulse's completion; releasing earlier lets the producer
 * write a buffer the render thread is still uploading (torn frames).</p>
 *
 * <p>Releasing on the post-sync signal rather than a later pulse start is what
 * keeps three slots enough at one frame per pulse: a slot is free again within
 * one pulse of being replaced, so the producer always finds a free slot when
 * its frames reach FX within a frame period, wherever pulses fall between
 * {@code acquire} and {@code shown}.</p>
 *
 * <p>Threads: {@link #acquire}/{@link #abandon} on the producer (playback)
 * thread; {@link #shown}, {@link #dropped}, {@link #pulse} and {@link #synced}
 * on the FX thread.
 * Free slots are picked round-robin, and when none is free the caller drops the
 * frame rather than blocking decode.</p>
 */
final class VideoSlotRing {

    static final int FREE = 0;
    static final int BUSY = 1;
    static final int SHOWN = 2;
    static final int RETIRING = 3;

    private final AtomicIntegerArray state;
    /** Pulses started when each RETIRING slot was replaced (FX thread only). */
    private final long[] retiredAt;
    /** Producer only: where the next round-robin scan starts. */
    private int next;
    /** FX thread only. */
    private int shown = -1;
    private long pulses;
    private int retiring;

    VideoSlotRing(int count) {
        if (count < 2) throw new IllegalArgumentException("need at least two slots: " + count);
        state = new AtomicIntegerArray(count);
        retiredAt = new long[count];
    }

    int size() { return state.length(); }

    int state(int index) { return state.get(index); }

    /** Producer: claims the next free slot round-robin, or returns -1 to drop the frame. */
    int acquire() {
        int n = state.length();
        for (int k = 0; k < n; k++) {
            int i = (next + k) % n;
            if (state.compareAndSet(i, FREE, BUSY)) {
                next = (i + 1) % n;
                return i;
            }
        }
        return -1;
    }

    /** Producer: gives back a claimed slot that was never handed to the FX thread. */
    void abandon(int index) {
        if (!state.compareAndSet(index, BUSY, FREE)) {
            throw new IllegalStateException("slot " + index + " not busy: " + state.get(index));
        }
    }

    /** FX thread: a handed-off slot was not shown (the presenter is disposed). */
    void dropped(int index) {
        abandon(index);
    }

    /**
     * FX thread: {@code index} is now the on-screen image. The slot it replaced
     * starts retiring. Returns {@code true} while retiring slots need
     * {@link #pulse}/{@link #synced} calls.
     */
    boolean shown(int index) {
        if (!state.compareAndSet(index, BUSY, SHOWN)) {
            throw new IllegalStateException("slot " + index + " not busy: " + state.get(index));
        }
        if (shown >= 0 && shown != index) {
            retiredAt[shown] = pulses;
            retiring++;
            state.set(shown, RETIRING);
        }
        shown = index;
        return retiring > 0;
    }

    /**
     * FX thread, at the start of each pulse (before that pulse's scene sync).
     * Returns the pulse's number, to pass to {@link #synced} once the pulse has
     * completed.
     */
    long pulse() {
        return ++pulses;
    }

    /**
     * FX thread, after pulse {@code pulse}'s scene sync: frees the slots
     * replaced before that pulse started. Returns {@code true} while slots are
     * still retiring.
     */
    boolean synced(long pulse) {
        for (int i = 0; i < retiredAt.length; i++) {
            if (state.get(i) == RETIRING && retiredAt[i] < pulse) {
                retiring--;
                state.set(i, FREE);
            }
        }
        return retiring > 0;
    }
}
