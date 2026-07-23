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

    // Hardware decode (HwDecode policy): the device reference, the hw pixel
    // format get_format prefers, the readback destination frame, and the
    // shared arena keeping the get_format upcall stub alive (shared because
    // libavcodec may call it from a decoder worker thread).
    private final boolean hwRequired;
    private final int hwDeviceType;
    private MemorySegment hwDeviceRef = MemorySegment.NULL;
    private int hwPixFmt = -1;
    private MemorySegment swFrame = MemorySegment.NULL, swFramePtr = MemorySegment.NULL;
    private Arena stubArena;
    private boolean firstFrameSeen;
    /** False until {@link #bgra()} converts the current frame — the zero-copy
     *  GPU path never pays the readback+swscale, so conversion is on demand. */
    private boolean cpuReady;
    private int containerTurns;

    FfmpegVideoStream(FfmpegBindings ff, Path file, HwDecode.Request hw) {
        this.ff = ff;
        this.hwRequired = hw != null && hw.require();
        this.hwDeviceType = hw != null ? hw.deviceType() : -1;
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
            // Frame threading (libavcodec defaults to one thread): decode
            // throughput for high-res streams at the cost of a few frames of
            // pipeline latency, which the pts-paced player never observes.
            ff.setAutoThreads(cctx);
            if (hw != null) {
                requestHardware(hw, codec, file);
            }
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
            containerTurns = q;
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

    /**
     * Wires the hw device and get_format preference before {@code open2}.
     * Under {@code hardware} policy every miss is loud; under {@code auto} a
     * missing device or codec config just leaves the session software (the
     * capability-routing decision, counted at the first frame).
     */
    private void requestHardware(HwDecode.Request hw, MemorySegment codec, Path file) {
        if (!hw.require() && !isHwWorthwhile()) {
            return;
        }
        int fmt = ff.hwPixFmtFor(codec, hw.deviceType());
        if (fmt < 0) {
            if (hw.require()) {
                throw new MediaException("ffmpeg: hardware decode required but the decoder"
                        + " has no hw config for this codec: " + file.getFileName());
            }
            return;
        }
        hwDeviceRef = ff.hwDeviceCreate(hw.deviceType());
        if (hwDeviceRef.equals(MemorySegment.NULL)) {
            if (hw.require()) {
                throw new MediaException(
                        "ffmpeg: hardware decode required but the hw device failed to open");
            }
            return;
        }
        stubArena = Arena.ofShared();
        ff.requestHwDecode(stubArena, cctx, hwDeviceRef, fmt);
        hwPixFmt = fmt;
    }

    /**
     * Auto-policy codec gate, from the 2026-07-23 solo-stream benchmarks
     * (docs/panama-decoder-candidates-handoff.md §3): hardware slashes CPU
     * >10× on heavy HEVC (38 → 3 ms/frame at 4K/40 Mbps — every iPhone
     * video), but on h264 the readback+swscale tax eats the win (par CPU,
     * one third the wall throughput). So {@code auto} routes only HEVC;
     * {@code hardware} skips this gate and forces any supported codec.
     */
    private boolean isHwWorthwhile() {
        return "hevc".equals(ff.codecName(
                ff.parCodecId(ff.codecpar(ff.stream(ctx, videoIndex)))));
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
        if (!cpuReady) {
            convertCpu();
            cpuReady = true;
        }
        return presented;
    }

    @Override
    public VideoStream.GpuFrame gpuFrame() {
        if (hwPixFmt < 0 || hwDeviceType != HwDecode.DEVICE_VIDEOTOOLBOX
                || ff.frameFormat(frame) != hwPixFmt) {
            return null;
        }
        long pixelBuffer = ff.frameHwHandle(frame);
        if (pixelBuffer == 0) {
            return null;
        }
        return new VideoStream.GpuFrame(pixelBuffer, width, height, containerTurns,
                ff.frameColorspace(frame) == 1 /* AVCOL_SPC_BT709 */,
                ff.frameColorRange(frame) == 2 /* AVCOL_RANGE_JPEG */);
    }

    @Override
    public long ptsMicros() {
        return ptsMicros;
    }

    @Override
    public boolean next() {
        if (ended || closed) return false;
        while (true) {
            // receiveFrame unrefs `frame` internally first, so the previous
            // frame (and its GPU surface handed out via gpuFrame()) stays
            // valid exactly until this call — the documented lifetime.
            int rr = ff.receiveFrame(cctx, frame);
            if (rr == 0) {
                frameDecoded();
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

    /** Per-frame bookkeeping at decode time: policy check, counters, pts. */
    private void frameDecoded() {
        if (!firstFrameSeen) {
            firstFrameSeen = true;
            boolean hwActive = hwPixFmt >= 0 && ff.frameFormat(frame) == hwPixFmt;
            if (hwRequired && !hwActive) {
                throw new MediaException("ffmpeg: hardware decode required but the decoder"
                        + " fell back to software (" + ff.pixFmtName(ff.frameFormat(frame)) + ")");
            }
            HwDecode.recordSession(hwActive);
        }
        cpuReady = false;

        long t = ff.frameBestEffortTimestamp(frame);
        if (t == ff.noptsValue()) t = ff.framePts(frame);
        ptsMicros = t == ff.noptsValue() || tbDen <= 0
                ? (ptsMicros < 0 ? 0 : ptsMicros + fallbackFrameMicros)
                : t * 1_000_000L * tbNum / tbDen;
    }

    private void convertCpu() {
        // A hardware frame is an opaque device handle — read it back to a
        // software frame (NV12 for VideoToolbox) for the swscale path below.
        MemorySegment src = frame;
        if (hwPixFmt >= 0 && ff.frameFormat(frame) == hwPixFmt) {
            if (swFrame.equals(MemorySegment.NULL)) {
                swFrame = ff.frameAlloc();
                swFramePtr = Ffm.pointerTo(arena, swFrame);
            }
            ff.check(ff.hwFrameTransfer(swFrame, frame), "av_hwframe_transfer_data");
            src = swFrame;
        }
        int w = ff.frameWidth(src);
        int h = ff.frameHeight(src);
        int fmt = ff.frameFormat(src);
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
        ff.swsScale(sws, ff.frameData(src), ff.frameLinesize(src),
                0, h, dstData, dstStride);

        // swscale wrote the coded frame into dstView; bake the rotation (if any)
        // into the buffer consumers read via bgra(). The decoded frame itself
        // stays referenced (receiveFrame unrefs it on the next call) so a GPU
        // surface handed out for the same frame remains valid.
        presented = rotation != null ? rotation.rotate(dstView) : dstView;

        if (src != frame) {
            ff.frameUnref(src);   // readback buffers are per-frame allocations
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (rotation != null) rotation.close();
        if (!sws.equals(MemorySegment.NULL)) ff.swsFreeContext(sws);
        if (!swFramePtr.equals(MemorySegment.NULL)) ff.frameFree(swFramePtr);
        if (!framePtr.equals(MemorySegment.NULL)) ff.frameFree(framePtr);
        if (!pktPtr.equals(MemorySegment.NULL)) ff.packetFree(pktPtr);
        if (!cctxPtr.equals(MemorySegment.NULL)) ff.freeContext(cctxPtr);
        if (!ctxPtr.equals(MemorySegment.NULL)) ff.closeInput(ctxPtr);
        if (!hwDeviceRef.equals(MemorySegment.NULL)) ff.hwDeviceUnref(hwDeviceRef);
        if (stubArena != null) stubArena.close();
        arena.close();
    }
}
