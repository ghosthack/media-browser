package io.github.ghosthack.mediabrowser.media.apple;

import io.github.ghosthack.mediabrowser.media.VideoStream;

import io.github.ghosthack.panama.media.avfoundation.AVFoundation;

import java.lang.foreign.MemorySegment;

/**
 * Adapts the vendored Apple {@link AVFoundation.FrameStream} (an
 * {@code AVAssetReader}-backed BGRA frame pull) to the app's
 * {@link VideoStream} contract. Thin delegate: like the underlying reader it
 * is confined to the thread that opened it.
 */
final class AppleVideoStream implements VideoStream {

    private final AVFoundation.FrameStream stream;

    AppleVideoStream(AVFoundation.FrameStream stream) {
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
