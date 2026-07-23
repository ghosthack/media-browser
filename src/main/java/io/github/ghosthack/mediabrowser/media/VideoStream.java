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
     *
     * <p>May convert lazily: a stream that also offers {@link #gpuFrame} only
     * pays the CPU readback/convert when this is actually called.</p>
     */
    MemorySegment bgra();

    /**
     * The last decoded frame while it is still on the GPU — macOS
     * VideoToolbox only for now: {@code cvPixelBuffer} is the CVPixelBufferRef
     * backing an IOSurface, in <b>coded</b> orientation/dimensions with the
     * container rotation to apply. Valid until the next {@link #next} call.
     * {@code null} whenever the frame is CPU-side (software decode, other
     * platforms, or a non-surface hw frame) — callers then use {@link #bgra()}.
     *
     * @param bt709 the frame is tagged BT.709 (untagged/601 otherwise)
     * @param fullRange the frame is tagged full-range (JPEG levels)
     */
    default GpuFrame gpuFrame() {
        return null;
    }

    /** See {@link #gpuFrame()}. */
    record GpuFrame(long cvPixelBuffer, int codedWidth, int codedHeight,
                    int containerQuarterTurnsCw, boolean bt709, boolean fullRange) {}

    @Override
    void close();
}
