package io.github.ghosthack.mediabrowser.media;

import java.lang.foreign.MemorySegment;

/**
 * A sequential decoder over the video stream of one file: each {@link #next}
 * call decodes the following frame into an internal native BGRA buffer.
 *
 * <p>The stream is confined to the thread that opened it (see
 * {@link MediaFacade#openVideo}); all methods, including {@code close}, must
 * be called on that thread.</p>
 */
public interface VideoStream extends AutoCloseable {

    /** Frame width in pixels (fixed for the stream). */
    int width();

    /** Frame height in pixels (fixed for the stream). */
    int height();

    /** Stream duration in microseconds, or -1 when unknown. */
    long durationMicros();

    /**
     * Decodes the next frame into the {@link #bgra} buffer. Returns false at
     * the end of the stream.
     */
    boolean next();

    /** Presentation time in microseconds of the last decoded frame. */
    long ptsMicros();

    /**
     * The internal BGRA buffer ({@code width * height * 4} bytes) holding the
     * last decoded frame; overwritten by the next {@link #next} call.
     */
    MemorySegment bgra();

    @Override
    void close();
}
