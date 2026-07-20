package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.RotationStore;

import javafx.application.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One flipbook preload: decodes every image of the directory ring into an
 * in-memory frame buffer so playback can loop at video rates without touching
 * a decoder again.
 *
 * <p>The buffer holds raw BGRA {@code byte[]} frames — exactly
 * {@code frames × width × height × 4} bytes — rather than per-frame JavaFX
 * images, so presentation can blit into a single reused
 * {@link javafx.scene.image.PixelBuffer} (the video path's PixelBuffer trick)
 * instead of churning N textures through Prism's VRAM cache.
 *
 * <p>Decodes run strictly sequentially on the {@code media-facade} thread —
 * each completion enqueues the next — so a preload never floods the decode
 * queue and {@link #cancel()} (checked via the service's freshness gate) takes
 * effect before the next native call. The first decoded frame fixes the
 * reference size; the total buffer requirement is computed from it and checked
 * against the JVM's free headroom before any further decoding, so an
 * impossible folder fails fast instead of dying with an OOM mid-buffer.
 * Frames of any other size (and unreadable files) are skipped, not fatal:
 * the "same size" contract is enforced per frame, tallied for the caller.
 *
 * <p>Apple .AAE edits and the per-directory user adjustments (rotation,
 * mirror, B&amp;W, invert) are baked into each frame on the way in — the same
 * above-the-decoders seam the still viewer uses — so a uniformly rotated
 * directory flips upright, and the size check runs on the adjusted frames.
 *
 * <p>All listener callbacks arrive on the FX thread; none arrive after
 * {@link #cancel()}.
 */
final class FlipbookSession {

    /** Preload progress/outcome callbacks; all delivered on the FX thread. */
    interface Listener {
        /** After each file is dealt with (buffered or skipped). */
        void progress(int processed, int total, long bufferedBytes, long neededBytes);
        /** The whole folder is buffered; the session is ready to present. */
        void ready();
        /** The preload cannot produce a playable buffer; the session is dead. */
        void failed(String message);
    }

    /**
     * Fraction of the JVM's free headroom the frame buffer may claim. Leaves
     * room for the presentation copy, decode scratch and everything else the
     * app allocates while the flipbook runs.
     */
    private static final double MAX_MEMORY_FRACTION = 0.8;

    private final MediaService service;
    private final RotationStore rotationStore;
    private final AaeStore aaeStore;
    private final List<Path> paths;
    private final Listener listener;

    /** Read on the decode thread by the freshness gate; set on the FX thread. */
    private volatile boolean cancelled;

    // Preload state below is written only on the sequential decode chain and
    // published to the FX thread through the Platform.runLater hand-offs.
    private final List<byte[]> frames = new ArrayList<>();
    private int frameWidth = -1;
    private int frameHeight = -1;
    private long bufferedBytes;
    private long neededBytes = -1;
    private int skipped;
    private boolean ready;

    FlipbookSession(MediaService service, RotationStore rotationStore, AaeStore aaeStore,
                    List<Path> paths, Listener listener) {
        this.service = service;
        this.rotationStore = rotationStore;
        this.aaeStore = aaeStore;
        this.paths = List.copyOf(paths);
        this.listener = listener;
    }

    /** Begins the sequential preload; callbacks start arriving on the FX thread. */
    void start() {
        decodeNext(0);
    }

    /**
     * Stops the preload and voids every pending callback. Queued decodes are
     * skipped by the freshness gate before their native call; the buffered
     * frames become garbage as soon as the owner drops the session.
     */
    void cancel() {
        cancelled = true;
    }

    boolean isReady() {
        return ready;
    }

    /** The BGRA frame buffer, in directory order. Only meaningful once ready. */
    List<byte[]> frames() {
        return frames;
    }

    int frameWidth() {
        return frameWidth;
    }

    int frameHeight() {
        return frameHeight;
    }

    long bufferedBytes() {
        return bufferedBytes;
    }

    /** Files that did not make it into the buffer (decode failure or size mismatch). */
    int skipped() {
        return skipped;
    }

    /** The buffer a flipbook of {@code count} frames at {@code w}×{@code h} needs. */
    static long bytesNeeded(int count, int width, int height) {
        return (long) count * width * height * 4;
    }

    /** The JVM's current free headroom: unclaimed heap plus unused claimed heap. */
    static long availableMemoryBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
    }

    private void decodeNext(int i) {
        if (cancelled) return;
        if (i >= paths.size()) {
            finish();
            return;
        }
        Path path = paths.get(i);
        // The whenComplete continuation runs on the media-facade thread; it
        // buffers the frame there (keeping the bake + copy off the FX thread)
        // and only then chains the next decode, so the preload is strictly
        // one-at-a-time and never backs up the decode queue.
        service.loadVisual(path, () -> !cancelled).whenComplete((result, error) -> {
            if (cancelled) return;
            if (result == null && error == null) return;   // gate-skipped: cancelled
            if (error != null || result.frame().isEmpty()) {
                skipped++;
            } else if (!bufferFrame(path, result.frame().get())) {
                return;   // fatal (budget) — bufferFrame already reported
            }
            notifyProgress(i + 1);
            decodeNext(i + 1);
        });
    }

    /**
     * Bakes edits into the decoded frame and appends it to the buffer (or
     * tallies a size-mismatch skip). Returns {@code false} only for the fatal
     * case: the first frame reveals a total buffer the JVM cannot hold.
     */
    private boolean bufferFrame(Path path, RasterFrame decoded) {
        RasterFrame frame = RasterFrames.applyAae(decoded,
                aaeStore.forImage(path).orElse(null));
        frame = RasterFrames.apply(frame, rotationStore.adjustments(path));
        if (frameWidth < 0) {
            frameWidth = frame.width();
            frameHeight = frame.height();
            neededBytes = bytesNeeded(paths.size(), frameWidth, frameHeight);
            long available = availableMemoryBytes();
            if (neededBytes > available * MAX_MEMORY_FRACTION) {
                fail("Flipbook needs " + MediaProbe.humanBytes(neededBytes)
                        + " to buffer " + paths.size() + " frames of "
                        + frameWidth + " × " + frameHeight + ", but only about "
                        + MediaProbe.humanBytes(available) + " of memory is free");
                return false;
            }
        } else if (frame.width() != frameWidth || frame.height() != frameHeight) {
            skipped++;
            return true;
        }
        frames.add(frame.bgra());
        bufferedBytes += frame.bgra().length;
        return true;
    }

    private void finish() {
        if (frames.size() < 2) {
            fail(skipped > 0
                    ? "Flipbook needs at least 2 same-size images ("
                            + skipped + " skipped: unreadable or a different size)"
                    : "Flipbook needs at least 2 images in this directory");
            return;
        }
        ready = true;
        Platform.runLater(() -> {
            if (!cancelled) listener.ready();
        });
    }

    private void fail(String message) {
        Platform.runLater(() -> {
            if (!cancelled) listener.failed(message);
        });
    }

    private void notifyProgress(int processed) {
        Platform.runLater(() -> {
            if (!cancelled) listener.progress(processed, paths.size(), bufferedBytes, neededBytes);
        });
    }
}
