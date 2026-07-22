package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Rational;

import java.lang.foreign.MemorySegment;

/**
 * Picks the video stream that actually carries the moving picture.
 *
 * <p>Image-sequence containers — notably animated AVIF / HEIC (major brand
 * {@code avis} / {@code hevs}) — expose <em>two</em> video streams: the primary
 * still-image item (a single coded frame, e.g. the AVIF {@code pitm} "Color"
 * picture) <em>and</em> the animation track (the {@code moov} / {@code trak}
 * with the full timeline). FFmpeg's {@code av_find_best_stream(VIDEO)} scores
 * both equally on resolution and frequently returns the lower-indexed
 * <em>still</em>, so a naive player would render a frozen single frame instead
 * of the animation.
 *
 * <p>{@link #selectPrimaryVideoStream} keeps {@code av_find_best_stream}'s
 * choice for ordinary media (single video track, or a real video track plus an
 * attached-picture cover) and only switches to a longer-timeline video stream
 * when the chosen one has no real timeline of its own — i.e. exactly the
 * still-vs-sequence case. The still item and the animation track share a codec
 * (AV1 for AVIF, HEVC for HEIC), so the decoder {@code av_find_best_stream}
 * returned for the still is reused unchanged for the track.
 *
 * <p>Ported from {@code io.github.ghosthack.videoplayer.ffmpeg.FfmpegStreamSelector},
 * rewired onto the project's {@link FfmpegBindings} seam.
 */
final class FfmpegStreamSelector {

    private FfmpegStreamSelector() {}

    /**
     * Returns the index of the video stream to decode, given the index
     * {@code bestIdx} already chosen by {@code av_find_best_stream}.
     *
     * @param ff      the FFmpeg binding
     * @param ctx     open {@code AVFormatContext*}
     * @param bestIdx the {@code av_find_best_stream(VIDEO)} result
     * @return {@code bestIdx} for ordinary media, or the index of the
     *         longest-timeline video stream when {@code bestIdx} is a
     *         single-frame still and a longer video track exists
     */
    static int selectPrimaryVideoStream(FfmpegBindings ff, MemorySegment ctx, int bestIdx) {
        if (bestIdx < 0) {
            return bestIdx;
        }
        long bestFrames = frameTimelineProxy(ff, ctx, bestIdx);
        if (bestFrames > 1) {
            // A genuine moving-picture track — never second-guess FFmpeg here.
            return bestIdx;
        }
        int nb = ff.nbStreams(ctx);
        int chosen = bestIdx;
        long chosenFrames = bestFrames;
        for (int i = 0; i < nb; i++) {
            if (i == bestIdx || !isVideoStream(ff, ctx, i)) {
                continue;
            }
            long frames = frameTimelineProxy(ff, ctx, i);
            if (frames > chosenFrames) {
                chosenFrames = frames;
                chosen = i;
            }
        }
        return chosen;
    }

    private static boolean isVideoStream(FfmpegBindings ff, MemorySegment ctx, int idx) {
        MemorySegment par = ff.codecpar(ff.stream(ctx, idx));
        return ff.parCodecType(par) == ff.mediaTypeVideo();
    }

    /**
     * Cheap proxy for "how many frames does this stream span": the stream's
     * declared duration (in its own {@code time_base}) multiplied by its frame
     * rate. A single-frame still primary item has no usable duration (FFmpeg
     * reports {@code AV_NOPTS_VALUE} / zero), yielding {@code 0}; the animation
     * track reports its true {@code 10 s × 32 fps ≈ 320}. Returns {@code 0}
     * when the timeline cannot be derived rather than guessing.
     */
    private static long frameTimelineProxy(FfmpegBindings ff, MemorySegment ctx, int idx) {
        MemorySegment stream = ff.stream(ctx, idx);
        long durTicks = ff.streamDuration(stream);
        if (durTicks == ff.noptsValue() || durTicks <= 0) {
            return 0;
        }
        Rational tb = ff.timeBase(stream);
        if (!tb.isPositive()) {
            return 0;
        }
        double seconds = durTicks * (double) tb.num() / tb.den();
        Rational fr = ff.avgFrameRate(stream);
        double fps = fr.isPositive() ? fr.num() / (double) fr.den() : 1.0;
        return Math.round(seconds * fps);
    }
}
