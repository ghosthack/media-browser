package io.github.ghosthack.mediabrowser.media;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Plays one video file on a dedicated daemon thread: opens a
 * {@link VideoStream}, paces decoded frames by their presentation timestamps
 * and hands each one to a {@link FrameSink}. No audio.
 *
 * <p>One instance plays one file once; create a new player to replay.
 * {@link #play}, {@link #pause} and {@link #close} may be called from any
 * thread. All sink and callback invocations happen on the playback thread.</p>
 */
public final class VideoPlayer implements AutoCloseable {

    /** Receives the playback session lifecycle, on the playback thread. */
    public interface FrameSink extends AutoCloseable {

        /** Called once before the first frame. */
        void begin(int width, int height, long durationMicros);

        /**
         * A decoded BGRA frame, due for presentation now. The buffer is only
         * valid during the call. {@code positionMicros} is the position
         * relative to the start of the stream.
         */
        void frame(MemorySegment bgra, int width, int height, long positionMicros);

        /** Called once when the session ends, even on error. */
        @Override
        void close();
    }

    private final Thread thread;
    private final Object lock = new Object();
    private boolean started;        // guarded by lock
    private boolean paused;         // guarded by lock
    private volatile boolean stopped;

    VideoPlayer(MediaFacade facade, Path file, FrameSink sink,
                Runnable onEnded, Consumer<Throwable> onError) {
        thread = new Thread(() -> run(facade, file, sink, onEnded, onError),
                "video-playback");
        thread.setDaemon(true);
    }

    /** Starts (first call) or resumes playback. */
    public void play() {
        synchronized (lock) {
            paused = false;
            if (!started) {
                started = true;
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** Freezes playback on the current frame; {@link #play} resumes. */
    public void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    /**
     * Stops playback and waits until the playback thread has released its
     * native resources and closed the sink. Idempotent; suppresses the
     * end/error callbacks.
     */
    @Override
    public void close() {
        stopped = true;
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
        thread.interrupt();
        if (thread != Thread.currentThread()) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run(MediaFacade facade, Path file, FrameSink sink,
                     Runnable onEnded, Consumer<Throwable> onError) {
        boolean ended = false;
        try (FrameSink s = sink) {
            // A zero-byte file has no stream to open; reject it here (before the
            // native open) so playback of a truncated/failed copy surfaces the
            // same plain "Empty file" note as the info/viewer paths, not a
            // confusing decoder failure.
            MediaService.rejectIfEmpty(file);
            try (VideoStream stream = facade.openVideo(file)) {
                s.begin(stream.width(), stream.height(), stream.durationMicros());
                long startNanos = -1;
                long firstPts = 0;
                while (!stopped) {
                    long pausedNanos = awaitResume();
                    if (stopped) break;
                    if (startNanos >= 0) startNanos += pausedNanos;

                    if (!stream.next()) {
                        ended = true;
                        break;
                    }
                    long pts = stream.ptsMicros();
                    if (startNanos < 0) {
                        startNanos = System.nanoTime();
                        firstPts = pts;
                    }
                    long position = Math.max(0, pts - firstPts);
                    if (!sleepUntil(startNanos + position * 1_000L)) break;
                    s.frame(stream.bgra(), stream.width(), stream.height(), position);
                }
            }
        } catch (Throwable t) {
            if (!stopped) onError.accept(t);
            return;
        }
        if (ended && !stopped) onEnded.run();
    }

    /** Blocks while paused; returns the time spent waiting, in nanoseconds. */
    private long awaitResume() {
        synchronized (lock) {
            if (!paused) return 0;
            long t0 = System.nanoTime();
            while (paused && !stopped) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    break;
                }
            }
            return System.nanoTime() - t0;
        }
    }

    /** Sleeps until the deadline (nanoTime); false when playback should stop. */
    private boolean sleepUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            try {
                Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !stopped;
    }
}
