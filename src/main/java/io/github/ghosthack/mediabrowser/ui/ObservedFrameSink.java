package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.VideoPlayer;
import io.github.ghosthack.mediabrowser.media.VideoStream;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * The viewer's playback sink: a presenter ({@link FallbackVideoSink}) plus an
 * observer that follows the session for the status bar. Every
 * {@link VideoPlayer.FrameSink} call reaches the presenter here, in one tested
 * place, so the viewer cannot drop one — above all {@link #revoke}: a wrapper
 * that forgot to forward it would let a closed player's queued frame land
 * over the next item — and {@link #gpuFrame}, without which the CGL
 * presenter's zero-copy VideoToolbox path would silently never run.
 */
final class ObservedFrameSink implements VideoPlayer.FrameSink {

    /** Follows the session on the playback thread; hops to FX itself. */
    interface Observer {
        /** After the presenter's {@code begin} returned. */
        void begun(int width, int height, long durationMicros);

        /** After a frame (BGRA, or a GPU frame it accepted) was handed to the presenter. */
        void presented(long positionMicros);
    }

    private final VideoPlayer.FrameSink presenter;
    private final Observer observer;

    ObservedFrameSink(VideoPlayer.FrameSink presenter, Observer observer) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        presenter.begin(width, height, durationMicros);
        observer.begun(width, height, durationMicros);
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        presenter.frame(bgra, width, height, positionMicros);
        observer.presented(positionMicros);
    }

    /**
     * Offers the GPU-resident frame to the presenter; one it declines goes
     * back to the player for the {@link #frame} path, which then reports it.
     */
    @Override
    public boolean gpuFrame(VideoStream.GpuFrame frame, long positionMicros) {
        if (!presenter.gpuFrame(frame, positionMicros)) return false;
        observer.presented(positionMicros);
        return true;
    }

    @Override
    public void revoke() {
        presenter.revoke();
    }

    @Override
    public void close() {
        presenter.close();
    }
}
