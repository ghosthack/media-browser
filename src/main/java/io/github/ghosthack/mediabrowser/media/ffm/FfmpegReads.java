package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.locks.LockSupport;

/**
 * {@code av_read_frame} with a bounded {@code AVERROR(EAGAIN)} retry, shared by
 * the playback, poster and tile-grid demux loops. Some demuxers report "no
 * packet ready yet" transiently, so EAGAIN is retried — but a source that never
 * delivers (stalled pipe or network AVIO) must not spin a core forever in a
 * loop nothing can cancel: once the budget runs out it is a loud
 * {@link MediaException}, never "ended".
 */
final class FfmpegReads {

    /** Consecutive EAGAIN reads tolerated before giving up (~0.25 s with the backoff). */
    static final int MAX_EAGAIN_READS = 256;
    private static final long BACKOFF_NANOS = 1_000_000L;

    private FfmpegReads() {}

    /**
     * Reads the next packet, retrying EAGAIN up to {@link #MAX_EAGAIN_READS}
     * times with a 1 ms backoff. Returns any other result (0, AVERROR_EOF, or
     * an error code) for the caller's state machine to classify.
     */
    static int readFrame(FfmpegBindings ff, MemorySegment ctx, MemorySegment pkt) {
        for (int attempt = 0; ; attempt++) {
            int r = ff.readFrame(ctx, pkt);
            if (r != ff.averrorEagain()) return r;
            if (attempt + 1 >= MAX_EAGAIN_READS) {
                throw new MediaException("ffmpeg: read stalled: no packet after "
                        + MAX_EAGAIN_READS + " EAGAIN retries");
            }
            LockSupport.parkNanos(BACKOFF_NANOS);
        }
    }
}
