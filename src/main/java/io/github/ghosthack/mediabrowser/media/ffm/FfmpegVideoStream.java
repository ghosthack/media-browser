package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.VideoRotation;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Ffm;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Rational;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

/**
 * {@link VideoStream} over the best video stream of an AV container:
 * demux/decode loop kept open between {@link #next} calls, each decoded frame
 * swscaled into one reusable native BGRA buffer.
 *
 * <p>All native access goes through an injected {@link FfmpegBindings}.
 * Confined to the opening thread (backed by a confined {@link Arena}).</p>
 */
final class FfmpegVideoStream implements VideoStream {

    private final FfmpegBindings ff;
    private final Arena arena = Arena.ofConfined();

    private MemorySegment ctxPtr = MemorySegment.NULL;   // AVFormatContext**
    private MemorySegment cctxPtr = MemorySegment.NULL;  // AVCodecContext**
    private MemorySegment pktPtr = MemorySegment.NULL;   // AVPacket**
    private MemorySegment framePtr = MemorySegment.NULL; // AVFrame**
    private MemorySegment ctx, cctx, pkt, frame;
    private MemorySegment sws = MemorySegment.NULL;
    /**
     * {@code dstBuf} is the padded swscale destination (its base is what
     * {@code sws_scale} writes into); {@code dstView} is the exact
     * {@code width*height*4} prefix handed to consumers via {@link #bgra()} so
     * the swscale tail-padding (see {@link Ffm#allocateSwscaleBgraDst}) never
     * leaks into a presented frame.
     */
    private MemorySegment dstBuf, dstView, dstData, dstStride;

    private int videoIndex;
    private int width, height;          // coded (swscale destination) size
    private int displayWidth, displayHeight; // post-rotation size reported to consumers
    private VideoRotation rotation;     // null unless the container is rotated
    private MemorySegment presented;    // dstView, or the rotated buffer
    private long durationMicros;
    private long tbNum, tbDen;            // video stream time base
    private long fallbackFrameMicros;     // from avg_frame_rate, for NOPTS frames

    private long ptsMicros = -1;
    private int srcW = -1, srcH = -1, srcFmt = -1;
    private boolean draining, ended, closed;

    FfmpegVideoStream(FfmpegBindings ff, Path file) {
        this.ff = ff;
        boolean ok = false;
        try {
            ctxPtr = ff.openInput(arena, file);
            ctx = ff.derefFormatContext(ctxPtr);

            MemorySegment decoderPtr = arena.allocate(ValueLayout.ADDRESS);
            videoIndex = FfmpegStreamSelector.selectPrimaryVideoStream(
                    ff, ctx, ff.findBestStream(ctx, ff.mediaTypeVideo(), decoderPtr));
            if (videoIndex < 0) {
                throw new MediaException("ffmpeg: no video stream in " + file.getFileName());
            }
            MemorySegment codec = decoderPtr.get(ValueLayout.ADDRESS, 0);
            if (codec.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: no decoder for " + file.getFileName());
            }

            cctx = ff.allocContext3(codec);
            if (cctx.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: cannot allocate codec context");
            }
            cctxPtr = Ffm.pointerTo(arena, cctx);

            MemorySegment vs = ff.stream(ctx, videoIndex);
            ff.check(ff.parametersToContext(cctx, ff.codecpar(vs)),
                    "avcodec_parameters_to_context");
            ff.check(ff.open2(cctx, codec), "avcodec_open2");

            pkt = ff.packetAlloc();
            pktPtr = Ffm.pointerTo(arena, pkt);
            frame = ff.frameAlloc();
            framePtr = Ffm.pointerTo(arena, frame);

            MemorySegment par = ff.codecpar(vs);
            width = ff.parWidth(par);
            height = ff.parHeight(par);
            if (width <= 0 || height <= 0) {
                throw new MediaException("ffmpeg: unknown video dimensions in " + file.getFileName());
            }

            Rational tb = ff.timeBase(vs);
            tbNum = tb.num();
            tbDen = tb.den();

            long streamDuration = ff.streamDuration(vs);
            long containerDuration = ff.duration(ctx);
            if (streamDuration != ff.noptsValue() && streamDuration > 0 && tbDen > 0) {
                durationMicros = streamDuration * 1_000_000L * tbNum / tbDen;
            } else if (containerDuration != ff.noptsValue() && containerDuration > 0) {
                durationMicros = containerDuration; // AV_TIME_BASE is microseconds
            } else {
                durationMicros = -1;
            }

            Rational fr = ff.avgFrameRate(vs);
            fallbackFrameMicros = fr.isPositive()
                    ? 1_000_000L * fr.den() / fr.num()
                    : 40_000; // assume 25 fps when the container does not say

            dstBuf = Ffm.allocateSwscaleBgraDst(arena, width, height);
            dstView = dstBuf.asSlice(0, (long) width * height * 4);
            dstData = arena.allocate(ValueLayout.ADDRESS, 4);
            dstData.setAtIndex(ValueLayout.ADDRESS, 0, dstBuf);
            dstStride = arena.allocate(ValueLayout.JAVA_INT, 4);
            dstStride.setAtIndex(ValueLayout.JAVA_INT, 0, width * 4);

            // Bake any container/display rotation so presented frames are
            // upright (display-oriented), as the Apple backend's frames are.
            int q = ff.videoRotationQuarterTurnsCw(vs);
            if (q != 0) {
                rotation = new VideoRotation(width, height, q);
                displayWidth = rotation.displayWidth();
                displayHeight = rotation.displayHeight();
            } else {
                displayWidth = width;
                displayHeight = height;
            }
            presented = dstView;

            ok = true;
        } finally {
            if (!ok) close();
        }
    }

    @Override
    public int width() {
        return displayWidth;
    }

    @Override
    public int height() {
        return displayHeight;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public MemorySegment bgra() {
        return presented;
    }

    @Override
    public long ptsMicros() {
        return ptsMicros;
    }

    @Override
    public boolean next() {
        if (ended || closed) return false;
        while (true) {
            int rr = ff.receiveFrame(cctx, frame);
            if (rr == 0) {
                convert();
                return true;
            }
            if (rr == ff.averrorEof()) {
                ended = true;
                return false;
            }
            if (rr != ff.averrorEagain()) {
                throw new MediaException("ffmpeg: decode failed: " + ff.errStr(rr));
            }
            if (draining) {
                ended = true;
                return false;
            }

            // decoder wants more input
            int r = ff.readFrame(ctx, pkt);
            if (r < 0) {
                ff.sendPacket(cctx, MemorySegment.NULL);
                draining = true;
                continue;
            }
            if (ff.packetStreamIndex(pkt) == videoIndex) {
                int sr = ff.sendPacket(cctx, pkt);
                if (sr < 0 && sr != ff.averrorEagain() && sr != ff.averrorEof()) {
                    ff.packetUnref(pkt);
                    throw new MediaException("ffmpeg: send packet failed: " + ff.errStr(sr));
                }
            }
            ff.packetUnref(pkt);
        }
    }

    private void convert() {
        int w = ff.frameWidth(frame);
        int h = ff.frameHeight(frame);
        int fmt = ff.frameFormat(frame);
        if (w <= 0 || h <= 0 || fmt < 0) {
            throw new MediaException("ffmpeg: decoder produced an unusable frame");
        }
        if (w != srcW || h != srcH || fmt != srcFmt) {
            if (!sws.equals(MemorySegment.NULL)) ff.swsFreeContext(sws);
            sws = ff.swsGetContextToBgra(w, h, fmt, width, height, ff.swsBilinear());
            if (sws.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: cannot create swscale context for "
                        + ff.pixFmtName(fmt) + " " + w + "x" + h);
            }
            srcW = w;
            srcH = h;
            srcFmt = fmt;
        }
        ff.swsScale(sws, ff.frameData(frame), ff.frameLinesize(frame),
                0, h, dstData, dstStride);

        // swscale wrote the coded frame into dstView; bake the rotation (if any)
        // into the buffer consumers read via bgra().
        presented = rotation != null ? rotation.rotate(dstView) : dstView;

        long t = ff.frameBestEffortTimestamp(frame);
        if (t == ff.noptsValue()) t = ff.framePts(frame);
        ptsMicros = t == ff.noptsValue() || tbDen <= 0
                ? (ptsMicros < 0 ? 0 : ptsMicros + fallbackFrameMicros)
                : t * 1_000_000L * tbNum / tbDen;

        ff.frameUnref(frame);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (rotation != null) rotation.close();
        if (!sws.equals(MemorySegment.NULL)) ff.swsFreeContext(sws);
        if (!framePtr.equals(MemorySegment.NULL)) ff.frameFree(framePtr);
        if (!pktPtr.equals(MemorySegment.NULL)) ff.packetFree(pktPtr);
        if (!cctxPtr.equals(MemorySegment.NULL)) ff.freeContext(cctxPtr);
        if (!ctxPtr.equals(MemorySegment.NULL)) ff.closeInput(ctxPtr);
        arena.close();
    }
}
