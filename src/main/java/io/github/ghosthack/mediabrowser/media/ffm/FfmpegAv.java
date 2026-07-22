package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.mediabrowser.media.VisualResult;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Ffm;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Rational;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Optional;

/**
 * AV container probing and first-frame extraction through a {@link FfmpegBindings}
 * (libavformat / libavcodec / libswscale). All native access goes through the
 * injected bindings, so this logic is FFmpeg-version-agnostic.
 */
final class FfmpegAv {

    private final FfmpegBindings ff;

    FfmpegAv(FfmpegBindings ff) {
        this.ff = ff;
        ff.init();
    }

    String version() {
        return ff.version();
    }

    MediaProbe probe(Path file, long fileSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxPtr = ff.openInput(arena, file);
            try {
                return describe(ff.derefFormatContext(ctxPtr), file, fileSize);
            } finally {
                ff.closeInput(ctxPtr);
            }
        }
    }

    /** Reads the probe metadata off an already-open format context. */
    private MediaProbe describe(MemorySegment ctx, Path file, long fileSize) {
        String container = null;
        MemorySegment iformat = ff.iformat(ctx);
        if (!iformat.equals(MemorySegment.NULL)) {
            container = ff.iformatLongName(iformat);
            if (container == null) container = ff.iformatName(iformat);
        }

        long duration = ff.duration(ctx);
        long durationMicros = duration == ff.noptsValue() ? -1 : duration;
        long bitRate = ff.bitRate(ctx);
        if (bitRate <= 0) bitRate = -1;

        int vIdx = FfmpegStreamSelector.selectPrimaryVideoStream(
                ff, ctx, ff.findBestStream(ctx, ff.mediaTypeVideo(), MemorySegment.NULL));
        int aIdx = ff.findBestStream(ctx, ff.mediaTypeAudio(), MemorySegment.NULL);

        int width = -1, height = -1;
        String videoCodec = null, audioCodec = null;
        double frameRate = -1;
        int sampleRate = -1, channels = -1;
        boolean attachedPicOnly = false;

        if (vIdx >= 0) {
            MemorySegment vs = ff.stream(ctx, vIdx);
            MemorySegment par = ff.codecpar(vs);
            width = ff.parWidth(par);
            height = ff.parHeight(par);
            // Report display (post-rotation) dimensions: a 90°/270° container
            // rotation swaps them, matching the upright frame the decode bakes.
            if ((ff.videoRotationQuarterTurnsCw(vs) & 1) != 0) {
                int swap = width;
                width = height;
                height = swap;
            }
            videoCodec = ff.codecName(ff.parCodecId(par));
            Rational fr = ff.avgFrameRate(vs);
            if (fr.isPositive()) frameRate = fr.num() / (double) fr.den();
            attachedPicOnly = (ff.disposition(vs) & ff.dispositionAttachedPic()) != 0;
        }
        if (aIdx >= 0) {
            MemorySegment par = ff.codecpar(ff.stream(ctx, aIdx));
            audioCodec = ff.codecName(ff.parCodecId(par));
            sampleRate = ff.parSampleRate(par);
            channels = ff.parChannels(par);
        }

        MediaKind kind;
        if (vIdx >= 0 && !attachedPicOnly) {
            kind = MediaKind.VIDEO;
        } else if (aIdx >= 0) {
            kind = MediaKind.AUDIO;
        } else if (vIdx >= 0) {
            kind = MediaKind.VIDEO;
        } else {
            throw new MediaException("ffmpeg: no audio/video streams in " + file.getFileName());
        }

        return new MediaProbe(file, kind, container, fileSize, durationMicros, bitRate,
                width, height, videoCodec, frameRate, audioCodec, sampleRate, channels, null);
    }

    /**
     * Probes the file and decodes the first frame of the best video stream
     * (which for audio files with cover art is the attached picture) into
     * BGRA — both off a single native open. The frame is empty when the file
     * has no decodable visual.
     */
    VisualResult firstFrameWithProbe(Path file, long fileSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxPtr = ff.openInput(arena, file);
            try {
                MemorySegment ctx = ff.derefFormatContext(ctxPtr);
                MediaProbe probe = describe(ctx, file, fileSize);
                return new VisualResult(probe,
                        Optional.ofNullable(decodeFirstFrame(arena, ctx, -1, ThumbnailMode.FIT)));
            } finally {
                ff.closeInput(ctxPtr);
            }
        }
    }

    /**
     * Probes the file and decodes a downscaled poster from the best video
     * stream (the attached picture for cover-art audio). In {@link ThumbnailMode#FIT}
     * it fits within a {@code maxEdge} box; in {@link ThumbnailMode#FILL} it is
     * centre-cropped to a {@code maxEdge × maxEdge} square. Empty when the file
     * has no decodable visual.
     */
    Thumbnail thumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxPtr = ff.openInput(arena, file);
            try {
                MemorySegment ctx = ff.derefFormatContext(ctxPtr);
                MediaProbe probe = describe(ctx, file, -1);
                RasterFrame poster = decodeFirstFrame(arena, ctx, maxEdge, mode);
                return new Thumbnail(Optional.ofNullable(poster), probe.kind());
            } finally {
                ff.closeInput(ctxPtr);
            }
        }
    }

    /**
     * First frame of the best video stream as BGRA, or null when none. When
     * {@code maxEdge > 0} the frame is downscaled to fit within that box at its
     * coded (stored) size; otherwise it is returned at coded size.
     */
    private RasterFrame decodeFirstFrame(Arena arena, MemorySegment ctx, int maxEdge,
                                         ThumbnailMode mode) {
        MemorySegment cctxPtr = MemorySegment.NULL;
        MemorySegment pktPtr = MemorySegment.NULL;
        MemorySegment framePtr = MemorySegment.NULL;
        try {
            MemorySegment decoderPtr = arena.allocate(ValueLayout.ADDRESS);
            int vIdx = FfmpegStreamSelector.selectPrimaryVideoStream(
                    ff, ctx, ff.findBestStream(ctx, ff.mediaTypeVideo(), decoderPtr));
            if (vIdx < 0) return null;
            MemorySegment codec = decoderPtr.get(ValueLayout.ADDRESS, 0);
            if (codec.equals(MemorySegment.NULL)) return null;

            MemorySegment cctx = ff.allocContext3(codec);
            if (cctx.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: cannot allocate codec context");
            }
            cctxPtr = Ffm.pointerTo(arena, cctx);

            ff.check(ff.parametersToContext(cctx, ff.codecpar(ff.stream(ctx, vIdx))),
                    "avcodec_parameters_to_context");
            ff.check(ff.open2(cctx, codec), "avcodec_open2");

            MemorySegment pkt = ff.packetAlloc();
            pktPtr = Ffm.pointerTo(arena, pkt);
            MemorySegment frame = ff.frameAlloc();
            framePtr = Ffm.pointerTo(arena, frame);

            if (!decodeOneFrame(ctx, cctx, pkt, frame, vIdx)) return null;

            RasterFrame poster = scaleFrameToBgra(arena, frame, maxEdge, mode);
            if (poster == null) return null;
            // Bake the container/display rotation into the poster so it is
            // upright, like the Apple (AVFoundation) backend's frames.
            return RasterFrames.rotateCw(poster, ff.videoRotationQuarterTurnsCw(ff.stream(ctx, vIdx)));
        } finally {
            if (!framePtr.equals(MemorySegment.NULL)) ff.frameFree(framePtr);
            if (!pktPtr.equals(MemorySegment.NULL)) ff.packetFree(pktPtr);
            if (!cctxPtr.equals(MemorySegment.NULL)) ff.freeContext(cctxPtr);
        }
    }

    private boolean decodeOneFrame(MemorySegment ctx, MemorySegment cctx,
                                   MemorySegment pkt, MemorySegment frame, int vIdx) {
        boolean draining = false;
        while (true) {
            int rr = ff.receiveFrame(cctx, frame);
            if (rr == 0) return true;
            if (rr == ff.averrorEof()) return false;
            if (rr != ff.averrorEagain()) {
                throw new MediaException("ffmpeg: decode failed: " + ff.errStr(rr));
            }
            if (draining) return false;

            // decoder wants more input
            int r = ff.readFrame(ctx, pkt);
            if (r < 0) {
                ff.sendPacket(cctx, MemorySegment.NULL);
                draining = true;
                continue;
            }
            if (ff.packetStreamIndex(pkt) == vIdx) {
                int sr = ff.sendPacket(cctx, pkt);
                if (sr < 0 && sr != ff.averrorEagain() && sr != ff.averrorEof()) {
                    ff.packetUnref(pkt);
                    throw new MediaException("ffmpeg: send packet failed: " + ff.errStr(sr));
                }
            }
            ff.packetUnref(pkt);
        }
    }

    /**
     * Scales a decoded frame to BGRA. With {@code maxEdge <= 0} this is the
     * coded size with bilinear scaling (the viewer's full-frame path,
     * unchanged); with {@code maxEdge > 0} it fits within the box at the
     * frame's coded (stored) aspect using area averaging, which downscales
     * cleaner for thumbnails.
     *
     * <p>The thumbnail deliberately does <em>not</em> apply the sample aspect
     * ratio: the viewer ({@link #decodeFirstFrame} at coded size), the probe
     * dimensions, video playback ({@code FfmpegVideoStream}) and the pure-Java
     * backend all present frames at their coded pixels, so SAR-correcting only
     * the poster made anamorphic media (e.g. a QCIF {@code .3gp} with SAR
     * 12:11) look stretched in the mosaic relative to its own viewer.</p>
     */
    private RasterFrame scaleFrameToBgra(Arena arena, MemorySegment frame, int maxEdge,
                                         ThumbnailMode mode) {
        int w = ff.frameWidth(frame);
        int h = ff.frameHeight(frame);
        int fmt = ff.frameFormat(frame);
        if (w <= 0 || h <= 0 || fmt < 0) return null;

        int dstW, dstH, flags;
        int cropSide = 0;   // > 0 means centre-crop the cover-scaled output (FILL)
        if (maxEdge <= 0) {
            dstW = w;
            dstH = h;
            flags = ff.swsBilinear();
        } else {
            // Fit/fill at the coded (stored) pixel aspect, matching the viewer,
            // the probe, video playback and the pure-Java backend; see the
            // method javadoc for why SAR is not applied here.
            flags = ff.swsArea();
            if (mode == ThumbnailMode.FILL) {
                // Cover-scale so the shorter edge == side, then crop the centred
                // side x side square out of the scaled output.
                int shortEdge = Math.min(w, h);
                cropSide = Math.min(maxEdge, shortEdge);
                double scale = cropSide / (double) shortEdge;
                dstW = Math.max(cropSide, (int) Math.round(w * scale));
                dstH = Math.max(cropSide, (int) Math.round(h * scale));
            } else {
                int[] fit = Thumbnails.fittedSize(w, h, maxEdge);
                dstW = fit[0];
                dstH = fit[1];
            }
        }

        MemorySegment sws = ff.swsGetContextToBgra(w, h, fmt, dstW, dstH, flags);
        if (sws.equals(MemorySegment.NULL)) {
            throw new MediaException("ffmpeg: cannot create swscale context for "
                    + ff.pixFmtName(fmt) + " " + w + "x" + h);
        }
        try {
            // Padded so swscale's SIMD tail over-write stays inside the
            // allocation (see Ffm.allocateSwscaleBgraDst); only the exact
            // dstW*dstH*4 prefix is read back into the frame.
            long exactBytes = (long) dstW * dstH * 4;
            MemorySegment dstBuf = Ffm.allocateSwscaleBgraDst(arena, dstW, dstH);
            MemorySegment dstData = arena.allocate(ValueLayout.ADDRESS, 4);
            dstData.setAtIndex(ValueLayout.ADDRESS, 0, dstBuf);
            MemorySegment dstStride = arena.allocate(ValueLayout.JAVA_INT, 4);
            dstStride.setAtIndex(ValueLayout.JAVA_INT, 0, dstW * 4);

            ff.swsScale(sws, ff.frameData(frame), ff.frameLinesize(frame),
                    0, h, dstData, dstStride);

            RasterFrame scaled = new RasterFrame(dstW, dstH,
                    dstBuf.asSlice(0, exactBytes).toArray(ValueLayout.JAVA_BYTE));
            return cropSide > 0 ? Thumbnails.cropCenterSquare(scaled, cropSide) : scaled;
        } finally {
            ff.swsFreeContext(sws);
        }
    }
}
