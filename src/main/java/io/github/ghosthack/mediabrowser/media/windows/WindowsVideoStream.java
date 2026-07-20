package io.github.ghosthack.mediabrowser.media.windows;

import io.github.ghosthack.mediabrowser.media.VideoStream;

import io.github.ghosthack.panama.media.mediafoundation.MediaFoundation;

import java.lang.foreign.MemorySegment;

/**
 * Adapts the vendored {@link MediaFoundation.FrameStream} (an
 * {@code IMFSourceReader}-as-demuxer plus decoder-MFT BGRA frame pull) to the
 * app's {@link VideoStream} contract. Thin delegate: like the underlying reader
 * it is confined to the thread that opened it.
 */
final class WindowsVideoStream implements VideoStream {

    private final MediaFoundation.FrameStream stream;

    WindowsVideoStream(MediaFoundation.FrameStream stream) {
        this.stream = stream;
    }

    @Override
    public int width() {
        return stream.width();
    }

    @Override
    public int height() {
        return stream.height();
    }

    @Override
    public long durationMicros() {
        return stream.durationMicros();
    }

    @Override
    public boolean next() {
        return stream.next();
    }

    @Override
    public long ptsMicros() {
        return stream.ptsMicros();
    }

    @Override
    public MemorySegment bgra() {
        return stream.bgra();
    }

    @Override
    public void close() {
        stream.close();
    }
}
