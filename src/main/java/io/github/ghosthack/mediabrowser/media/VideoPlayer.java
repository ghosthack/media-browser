package io.github.ghosthack.mediabrowser.media;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Plays one video file on a dedicated daemon thread: opens a
 * {@link VideoStream}, paces decoded frames by their presentation timestamps
 * and hands each one to a {@link FrameSink}. No audio.
 *
 * <p>One instance plays one file once; create a new player to replay.
 * {@link #play}, {@link #pause} and {@link #close} may be called from any
 * thread. All sink and callback invocations happen on the playback thread,
 * except {@link FrameSink#revoke}, which runs on the closing thread.</p>
 *
 * <p>{@link #close} does not wait for the playback thread: a decoder stuck in
 * a native call cannot be interrupted, and the caller is usually the FX
 * thread. It revokes the sink instead, so nothing is presented after it
 * returns; a caller that needs the file itself let go (a move, a rename)
 * waits on {@link #released}.</p>
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

        /**
         * A decoded frame still on the GPU (see {@link VideoStream#gpuFrame()}),
         * due for presentation now; only valid during the call. Return
         * {@code true} when presented; {@code false} to have the caller fall
         * back to {@link #frame} with the CPU-converted pixels — the default
         * for sinks with no zero-copy path.
         */
        default boolean gpuFrame(VideoStream.GpuFrame frame, long positionMicros) {
            return false;
        }

        /**
         * Stop presenting, at once: called by {@link VideoPlayer#close} on the
         * closing thread before it returns, while the playback thread may still
         * be winding down. A frame already handed off for presentation must not
         * land after this. Must be cheap and thread-safe, and must not touch
         * playback-thread-confined resources (GL contexts) — {@link #close}
         * still releases those on the playback thread. No default: a wrapping
         * sink that forgot to forward it would silently let stale frames land,
         * so every sink states its revoke (an empty one when nothing it hands
         * off outlives the call).
         */
        void revoke();

        /** Called once when the session ends, even on error. */
        @Override
        void close();
    }

    /** How long after {@link #close} a still-running playback thread is reported. */
    static final long RELEASE_WARNING_MILLIS = 3000;

    /**
     * The still-running report: after {@link #releaseWarningMillis}, to
     * {@link #releaseWarning} (stderr). Both replaced by tests.
     */
    long releaseWarningMillis = RELEASE_WARNING_MILLIS;
    Consumer<String> releaseWarning = System.err::println;

    private final Thread thread;
    private final FrameSink sink;
    private final Path sourceFile;
    /** Completes once the playback thread has returned (stream and sink closed). */
    private final CompletableFuture<Void> released = new CompletableFuture<>();
    private final Object lock = new Object();
    private boolean started;        // guarded by lock
    private boolean paused;         // guarded by lock
    private volatile boolean stopped;

    VideoPlayer(MediaFacade facade, Path file, FrameSink sink,
                Runnable onEnded, Consumer<Throwable> onError) {
        this(facade, facade.getClass().getSimpleName(), file, file, System.nanoTime(),
                0L, sink, onEnded, onError, ignored -> {});
    }

    VideoPlayer(MediaFacade facade, String engine, Path sourceFile, Path decoderFile,
                long requestedNanos, long inputElapsedNanos,
                FrameSink sink, Runnable onEnded,
                Consumer<Throwable> onError, Consumer<MediaEngineTrace> onDiagnostics) {
        this.sink = sink;
        this.sourceFile = sourceFile;
        thread = new Thread(() -> {
            try {
                run(facade, engine, sourceFile, decoderFile, requestedNanos,
                        inputElapsedNanos, sink, onEnded, onError, onDiagnostics);
            } finally {
                released.complete(null);
            }
        }, "video-playback");
        thread.setDaemon(true);
    }

    /** Starts (first call) or resumes playback; a no-op once closed. */
    public void play() {
        synchronized (lock) {
            if (stopped) return;
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
     * Stops playback without waiting for the playback thread: revokes the sink
     * (no frame is presented after this returns), then signals the thread,
     * which closes the stream and the sink on its way out — see
     * {@link #released}. A thread still running
     * {@value #RELEASE_WARNING_MILLIS} ms later is reported on stderr, since
     * it still holds the file open. Idempotent; suppresses the end/error
     * callbacks.
     */
    @Override
    public void close() {
        sink.revoke();
        boolean neverStarted;
        synchronized (lock) {
            stopped = true;
            paused = false;
            neverStarted = !started;
            started = true; // a never-started thread stays unstarted
            lock.notifyAll();
        }
        if (neverStarted) {
            released.complete(null);
            return;
        }
        thread.interrupt();
        released.copy()
                .orTimeout(releaseWarningMillis, TimeUnit.MILLISECONDS)
                .exceptionally(timeout -> {
                    releaseWarning.accept("[VideoPlayer] playback thread still running "
                            + releaseWarningMillis + " ms after close; "
                            + sourceFile + " is still open");
                    return null;
                });
    }

    /**
     * Completes once the playback thread has returned, having closed the
     * stream (and with it the file) and the sink; at once for a player
     * closed before it ever started. Never completes exceptionally. Each call
     * returns a fresh dependent copy, so a caller's timeout cannot complete
     * it for everyone.
     */
    public CompletableFuture<Void> released() {
        return released.copy();
    }

    /**
     * Whether the playback thread was ever started — so a test can tell, at
     * once and without waiting on the thread, that a closed player never
     * started decoding.
     */
    boolean threadStarted() {
        return thread.getState() != Thread.State.NEW;
    }

    private void run(MediaFacade facade, String engine, Path sourceFile, Path file,
                     long requestedNanos, long inputElapsedNanos,
                     FrameSink sink, Runnable onEnded,
                     Consumer<Throwable> onError,
                     Consumer<MediaEngineTrace> onDiagnostics) {
        boolean ended = false;
        MediaEngineTrace.Recorder trace = MediaEngineTrace.Recorder.recording(
                engine, sourceFile, "Playback session", requestedNanos);
        try (FrameSink s = sink) {
            // A zero-byte file has no stream to open; reject it here (before the
            // native open) so playback of a truncated/failed copy surfaces the
            // same plain "Empty file" note as the info/viewer paths, not a
            // confusing decoder failure.
            MediaService.rejectIfEmpty(file);
            trace.record(sourceFile.equals(file)
                            ? "Direct filesystem input" : "Archive materialization",
                    MediaEngineTrace.Outcome.SUCCEEDED, inputElapsedNanos,
                    sourceFile.equals(file)
                            ? "No materialization required" : "Materialized archive entry");
            long engineStarted = System.nanoTime();
            VideoStream opened;
            try {
                opened = facade.openVideo(file, trace);
            } catch (Throwable failure) {
                trace.engineWork(System.nanoTime() - engineStarted);
                throw failure;
            }
            trace.engineWork(System.nanoTime() - engineStarted);
            try (VideoStream stream = opened) {
                onDiagnostics.accept(trace.finish(null));
                // Closed while the native open ran: begin would only build a
                // GL context and slot buffers to tear them down again, and the
                // loop below never runs.
                if (!stopped) s.begin(stream.width(), stream.height(), stream.durationMicros());
                long startNanos = -1;
                long firstPts = 0;
                boolean diagnosticsReported = false;
                while (!stopped) {
                    long pausedNanos = awaitResume();
                    if (stopped) break;
                    if (startNanos >= 0) startNanos += pausedNanos;

                    long firstFrameStarted = !diagnosticsReported ? trace.beginAttempt() : 0L;
                    if (!stream.next()) {
                        ended = true;
                        break;
                    }
                    if (!diagnosticsReported) {
                        trace.succeeded("First decoded frame", firstFrameStarted,
                                stream.diagnostics());
                    }
                    long pts = stream.ptsMicros();
                    if (startNanos < 0) {
                        startNanos = System.nanoTime();
                        firstPts = pts;
                    }
                    long position = Math.max(0, pts - firstPts);
                    if (!sleepUntil(startNanos + position * 1_000L)) break;
                    // Zero-copy first: a GPU-resident frame goes straight to a
                    // sink that can bind it; otherwise (or when the sink
                    // declines) the stream converts to BGRA on demand.
                    VideoStream.GpuFrame gpu = stream.gpuFrame();
                    long presentationStarted = !diagnosticsReported ? trace.beginAttempt() : 0L;
                    boolean zeroCopy = gpu != null && s.gpuFrame(gpu, position);
                    if (!zeroCopy) {
                        s.frame(stream.bgra(), stream.width(), stream.height(), position);
                    }
                    if (!diagnosticsReported) {
                        trace.succeeded("Presentation path", presentationStarted,
                                zeroCopy ? "GPU zero-copy" : gpu == null
                                        ? "CPU BGRA" : "GPU frame declined; CPU readback/BGRA");
                        onDiagnostics.accept(trace.finish(null));
                        diagnosticsReported = true;
                    }
                }
            }
        } catch (Throwable t) {
            trace.failed("Playback session", trace.beginAttempt(), t);
            onDiagnostics.accept(trace.finish(t));
            if (!stopped) onError.accept(t);
            return;
        }
        trace.succeeded("Playback session", trace.beginAttempt(),
                ended ? "Reached end of stream" : "Stopped");
        onDiagnostics.accept(trace.finish(null));
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
