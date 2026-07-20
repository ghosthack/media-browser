package io.github.ghosthack.mediabrowser.media.twelvemonkeys;

import io.github.ghosthack.mediabrowser.media.BufferedImageRaster;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * A {@link VideoStream} that plays an animated GIF frame-by-frame over a
 * {@link GifFrames} compositor. Each {@link #next()} advances to the following
 * composited frame and fills a reusable native BGRA buffer; presentation times
 * are the running sum of the GIF's per-frame delays (frame&nbsp;0 at pts&nbsp;0,
 * monotonically non-decreasing).
 *
 * <p><b>Looping.</b> The stream repeats the animation like a real GIF viewer,
 * driven by {@link GifFrames#loopCount()}: a GIF with no NETSCAPE2.0/ANIMEXTS1.0
 * extension ({@link GifFrames#LOOP_UNSPECIFIED}) plays exactly once; a count of
 * {@link GifFrames#LOOP_INFINITE} (0) loops forever (so {@link #next()} never
 * reports the end — the player stops it via {@link #close()}); a positive count
 * plays that many passes. Across a loop boundary {@link #ptsMicros()} keeps
 * increasing monotonically (the last frame's delay paces the wrap), while
 * {@link #durationMicros()} stays the one-pass total, so positions during later
 * passes legitimately exceed it.
 *
 * <p>Confined to the opening thread per the {@link VideoStream} contract: it
 * owns a single confined {@link Arena} and one reusable BGRA
 * {@link MemorySegment} of {@code width * height * 4} bytes. Closing it disposes
 * the arena and the underlying {@link GifFrames}.</p>
 *
 * <p>Frames are pulled from a forward {@link GifFrames.Cursor}, which composites
 * incrementally and decodes each source frame exactly once — so a full playback
 * pass is O(n) source decodes, not the O(n²) of calling
 * {@code GifFrames.frame(i)} for each {@code i}.</p>
 *
 * <p>Self-contained: imports only {@code io.github.ghosthack.mediabrowser.media.*},
 * {@code java.lang.foreign.*}, {@code java.awt.image.BufferedImage} and the JDK.</p>
 */
public final class GifVideoStream implements VideoStream {

    /** {@link #maxPasses} sentinel for a GIF that loops forever. */
    private static final long INFINITE_PASSES = -1;

    private final GifFrames frames;
    private final long[] delayMicros;
    private final int width;
    private final int height;
    private final long durationMicros;
    private final long maxPasses;
    private final Arena arena;
    private final MemorySegment bgra;
    private final BufferedImageRaster.RowScratch scratch;

    /** Current forward cursor; replaced with a fresh one at each loop boundary. */
    private GifFrames.Cursor cursor;
    /** Within-pass index of the last decoded frame (-1 before the first). */
    private int frameInPass = -1;
    /** Passes fully played out so far (a pass ends when the cursor is exhausted). */
    private long passesCompleted;
    /** Whether any frame has been emitted yet (frame 0 of pass 0 is at pts 0). */
    private boolean everShown;
    private long ptsMicros;
    private boolean closed;

    /**
     * Wraps an already-opened {@link GifFrames}; this stream takes ownership and
     * closes it in {@link #close()}.
     */
    public GifVideoStream(GifFrames frames) {
        this.frames = Objects.requireNonNull(frames, "frames");
        this.cursor = frames.cursor();
        this.delayMicros = frames.delayMicros();
        this.width = frames.width();
        this.height = frames.height();
        long total = 0;
        for (long d : delayMicros) {
            total += d;
        }
        this.durationMicros = total;
        // Map the parsed loop count onto a pass budget: absent → 1 (play once),
        // 0 → infinite, N>0 → N passes.
        int loop = frames.loopCount();
        this.maxPasses = loop < 0 ? 1 : (loop == 0 ? INFINITE_PASSES : loop);
        this.arena = Arena.ofConfined();
        this.bgra = arena.allocate((long) width * height * 4);
        this.scratch = new BufferedImageRaster.RowScratch(width);
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public boolean next() {
        if (closed) {
            return false;
        }
        BufferedImage img = advanceCursor();
        int prevShownIndex;
        if (img == null) {
            // The current pass is exhausted; loop if the budget allows.
            passesCompleted++;
            if (maxPasses != INFINITE_PASSES && passesCompleted >= maxPasses) {
                return false;
            }
            cursor = frames.cursor();
            img = advanceCursor();
            if (img == null) {
                return false; // empty GIF: nothing to replay
            }
            prevShownIndex = frameInPass; // last frame of the pass that just ended
            frameInPass = 0;
        } else {
            prevShownIndex = frameInPass; // -1 on the very first frame
            frameInPass++;
        }
        // Presentation time advances by the delay of the previously shown frame,
        // so the sequence is monotonic non-decreasing and the loop wrap is paced
        // by the last frame's delay (frame 0 of pass 0 stays at pts 0).
        if (everShown) {
            ptsMicros += delayMicros[prevShownIndex];
        } else {
            ptsMicros = 0;
            everShown = true;
        }
        BufferedImageRaster.writeBgra(img, bgra, scratch);
        return true;
    }

    private BufferedImage advanceCursor() {
        try {
            return cursor.next();
        } catch (IOException e) {
            throw new UncheckedIOException("decoding GIF frame", e);
        }
    }

    @Override
    public long ptsMicros() {
        return ptsMicros;
    }

    @Override
    public MemorySegment bgra() {
        return bgra;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            arena.close();
        } finally {
            frames.close();
        }
    }
}
