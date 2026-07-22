package io.github.ghosthack.mediabrowser.media.ffm.bind.bundled;

import io.github.ghosthack.ffmpegffm.ffmpeg.AVChannelLayout;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVCodecParameters;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVDictionaryEntry;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVFormatContext;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVFrame;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVInputFormat;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVPacket;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVRational;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVStream;
import io.github.ghosthack.ffmpegffm.ffmpeg.FFmpeg;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Ffm;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Rational;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

/**
 * {@link FfmpegBindings} backed by the {@code io.github.ghosthack:ffmpeg-ffm}
 * Maven artifact: FFmpeg 8.x stubs whose natives ship in per-platform
 * classifier jars and self-extract at first use — no user-installed FFmpeg
 * (docs/ffmpeg-bundled-backend.md). Derived from the retired Homebrew-install
 * {@code BrewFfmpegBindings} (see docs/ffm-retirement-handoff.md); the only
 * behavioural delta is {@code AVERROR_EAGAIN}, chosen at runtime because this
 * class runs on every platform.
 */
public final class BundledFfmpegBindings implements FfmpegBindings {

    /** AVERROR(EAGAIN); EAGAIN is 35 on darwin, 11 on Windows/Linux. */
    private static final int AVERROR_EAGAIN =
            System.getProperty("os.name", "").toLowerCase().contains("mac") ? -35 : -11;

    @Override
    public void init() {
        FFmpeg.av_log_set_level(FFmpeg.AV_LOG_ERROR());
    }

    @Override
    public String version() {
        int v = FFmpeg.avformat_version();
        return "avformat " + (v >> 16) + "." + ((v >> 8) & 0xff) + "." + (v & 0xff);
    }

    @Override
    public MemorySegment openInput(Arena arena, Path file) {
        MemorySegment ctxPtr = arena.allocate(ValueLayout.ADDRESS);
        int err = FFmpeg.avformat_open_input(ctxPtr, arena.allocateFrom(file.toString()),
                MemorySegment.NULL, MemorySegment.NULL);
        if (err < 0) {
            throw new MediaException("ffmpeg: cannot open " + file.getFileName()
                    + ": " + errStr(err));
        }
        err = FFmpeg.avformat_find_stream_info(derefFormatContext(ctxPtr), MemorySegment.NULL);
        if (err < 0) {
            FFmpeg.avformat_close_input(ctxPtr);
            throw new MediaException("ffmpeg: no stream info in " + file.getFileName()
                    + ": " + errStr(err));
        }
        return ctxPtr;
    }

    @Override
    public MemorySegment derefFormatContext(MemorySegment ctxPtr) {
        return ctxPtr.get(ValueLayout.ADDRESS, 0)
                .reinterpret(AVFormatContext.layout().byteSize());
    }

    @Override
    public void closeInput(MemorySegment ctxPtr) {
        FFmpeg.avformat_close_input(ctxPtr);
    }

    @Override
    public MemorySegment iformat(MemorySegment ctx) {
        MemorySegment iformat = AVFormatContext.iformat(ctx);
        return iformat.equals(MemorySegment.NULL)
                ? MemorySegment.NULL
                : iformat.reinterpret(AVInputFormat.layout().byteSize());
    }

    @Override
    public String iformatLongName(MemorySegment iformat) {
        return Ffm.cstr(AVInputFormat.long_name(iformat));
    }

    @Override
    public String iformatName(MemorySegment iformat) {
        return Ffm.cstr(AVInputFormat.name(iformat));
    }

    @Override
    public long duration(MemorySegment ctx) {
        return AVFormatContext.duration(ctx);
    }

    @Override
    public long bitRate(MemorySegment ctx) {
        return AVFormatContext.bit_rate(ctx);
    }

    @Override
    public MemorySegment containerMetadata(MemorySegment ctx) {
        return AVFormatContext.metadata(ctx);
    }

    @Override
    public int nbStreams(MemorySegment ctx) {
        return AVFormatContext.nb_streams(ctx);
    }

    @Override
    public MemorySegment stream(MemorySegment ctx, int index) {
        MemorySegment streams = AVFormatContext.streams(ctx)
                .reinterpret((index + 1L) * ValueLayout.ADDRESS.byteSize());
        return streams.getAtIndex(ValueLayout.ADDRESS, index)
                .reinterpret(AVStream.layout().byteSize());
    }

    @Override
    public int findBestStream(MemorySegment ctx, int mediaType, MemorySegment decoderRet) {
        return FFmpeg.av_find_best_stream(ctx, mediaType, -1, -1, decoderRet, 0);
    }

    @Override
    public MemorySegment codecpar(MemorySegment stream) {
        return AVStream.codecpar(stream).reinterpret(AVCodecParameters.layout().byteSize());
    }

    @Override
    public Rational avgFrameRate(MemorySegment stream) {
        return rational(AVStream.avg_frame_rate(stream));
    }

    @Override
    public int disposition(MemorySegment stream) {
        return AVStream.disposition(stream);
    }

    @Override
    public Rational timeBase(MemorySegment stream) {
        return rational(AVStream.time_base(stream));
    }

    @Override
    public long streamDuration(MemorySegment stream) {
        return AVStream.duration(stream);
    }

    @Override
    public MemorySegment streamMetadata(MemorySegment stream) {
        return AVStream.metadata(stream);
    }

    @Override
    public int videoRotationQuarterTurnsCw(MemorySegment stream) {
        // FFmpeg 7.x+ moved stream-level side data off AVStream onto
        // AVCodecParameters.coded_side_data (AVStream.side_data was removed).
        MemorySegment par = AVStream.codecpar(stream);
        return Ffm.rotationQuarterTurnsFromSideData(
                AVCodecParameters.coded_side_data(par),
                AVCodecParameters.nb_coded_side_data(par));
    }

    @Override
    public int parWidth(MemorySegment codecpar) {
        return AVCodecParameters.width(codecpar);
    }

    @Override
    public int parHeight(MemorySegment codecpar) {
        return AVCodecParameters.height(codecpar);
    }

    @Override
    public int parCodecId(MemorySegment codecpar) {
        return AVCodecParameters.codec_id(codecpar);
    }

    @Override
    public int parCodecType(MemorySegment codecpar) {
        return AVCodecParameters.codec_type(codecpar);
    }

    @Override
    public int parSampleRate(MemorySegment codecpar) {
        return AVCodecParameters.sample_rate(codecpar);
    }

    @Override
    public int parChannels(MemorySegment codecpar) {
        // jextract emits AVCodecParameters.ch_layout(struct) as a slice and
        // AVChannelLayout.nb_channels(slice) as an int.
        MemorySegment chLayout = AVCodecParameters.ch_layout(codecpar);
        return AVChannelLayout.nb_channels(chLayout);
    }

    @Override
    public String codecName(int codecId) {
        return Ffm.cstr(FFmpeg.avcodec_get_name(codecId));
    }

    @Override
    public MemorySegment allocContext3(MemorySegment codec) {
        return FFmpeg.avcodec_alloc_context3(codec);
    }

    @Override
    public int parametersToContext(MemorySegment cctx, MemorySegment codecpar) {
        return FFmpeg.avcodec_parameters_to_context(cctx, codecpar);
    }

    @Override
    public int open2(MemorySegment cctx, MemorySegment codec) {
        return FFmpeg.avcodec_open2(cctx, codec, MemorySegment.NULL);
    }

    @Override
    public void freeContext(MemorySegment cctxPtr) {
        FFmpeg.avcodec_free_context(cctxPtr);
    }

    @Override
    public MemorySegment packetAlloc() {
        return FFmpeg.av_packet_alloc().reinterpret(AVPacket.layout().byteSize());
    }

    @Override
    public void packetFree(MemorySegment pktPtr) {
        FFmpeg.av_packet_free(pktPtr);
    }

    @Override
    public void packetUnref(MemorySegment pkt) {
        FFmpeg.av_packet_unref(pkt);
    }

    @Override
    public int packetStreamIndex(MemorySegment pkt) {
        return AVPacket.stream_index(pkt);
    }

    @Override
    public MemorySegment frameAlloc() {
        return FFmpeg.av_frame_alloc().reinterpret(AVFrame.layout().byteSize());
    }

    @Override
    public void frameFree(MemorySegment framePtr) {
        FFmpeg.av_frame_free(framePtr);
    }

    @Override
    public void frameUnref(MemorySegment frame) {
        FFmpeg.av_frame_unref(frame);
    }

    @Override
    public int readFrame(MemorySegment ctx, MemorySegment pkt) {
        return FFmpeg.av_read_frame(ctx, pkt);
    }

    @Override
    public int sendPacket(MemorySegment cctx, MemorySegment pkt) {
        return FFmpeg.avcodec_send_packet(cctx, pkt);
    }

    @Override
    public int receiveFrame(MemorySegment cctx, MemorySegment frame) {
        return FFmpeg.avcodec_receive_frame(cctx, frame);
    }

    @Override
    public int frameWidth(MemorySegment frame) {
        return AVFrame.width(frame);
    }

    @Override
    public int frameHeight(MemorySegment frame) {
        return AVFrame.height(frame);
    }

    @Override
    public int frameFormat(MemorySegment frame) {
        return AVFrame.format(frame);
    }

    @Override
    public Rational frameSampleAspectRatio(MemorySegment frame) {
        return rational(AVFrame.sample_aspect_ratio(frame));
    }

    @Override
    public MemorySegment frameData(MemorySegment frame) {
        return AVFrame.data(frame);
    }

    @Override
    public MemorySegment frameLinesize(MemorySegment frame) {
        return AVFrame.linesize(frame);
    }

    @Override
    public long frameBestEffortTimestamp(MemorySegment frame) {
        return AVFrame.best_effort_timestamp(frame);
    }

    @Override
    public long framePts(MemorySegment frame) {
        return AVFrame.pts(frame);
    }

    @Override
    public MemorySegment swsGetContextToBgra(int srcW, int srcH, int srcFmt,
                                             int dstW, int dstH, int flags) {
        return FFmpeg.sws_getContext(srcW, srcH, srcFmt, dstW, dstH,
                FFmpeg.AV_PIX_FMT_BGRA(), flags,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
    }

    @Override
    public void swsScale(MemorySegment sws, MemorySegment srcData, MemorySegment srcLinesize,
                         int srcSliceY, int srcSliceH, MemorySegment dstData, MemorySegment dstStride) {
        FFmpeg.sws_scale(sws, srcData, srcLinesize, srcSliceY, srcSliceH, dstData, dstStride);
    }

    @Override
    public void swsFreeContext(MemorySegment sws) {
        FFmpeg.sws_freeContext(sws);
    }

    @Override
    public int swsBilinear() {
        return FFmpeg.SWS_BILINEAR();
    }

    @Override
    public int swsArea() {
        return FFmpeg.SWS_AREA();
    }

    @Override
    public MemorySegment dictGetFirst(MemorySegment dict, MemorySegment emptyKey, MemorySegment prev) {
        MemorySegment entry = FFmpeg.av_dict_get(dict, emptyKey, prev,
                FFmpeg.AV_DICT_IGNORE_SUFFIX());
        return entry.equals(MemorySegment.NULL)
                ? MemorySegment.NULL
                : entry.reinterpret(AVDictionaryEntry.layout().byteSize());
    }

    @Override
    public String dictEntryKey(MemorySegment entry) {
        return Ffm.cstr(AVDictionaryEntry.key(entry));
    }

    @Override
    public String dictEntryValue(MemorySegment entry) {
        return Ffm.cstr(AVDictionaryEntry.value(entry));
    }

    @Override
    public long noptsValue() {
        return FFmpeg.AV_NOPTS_VALUE();
    }

    @Override
    public int averrorEof() {
        return FFmpeg.AVERROR_EOF();
    }

    @Override
    public int averrorEagain() {
        return AVERROR_EAGAIN;
    }

    @Override
    public int dispositionAttachedPic() {
        return FFmpeg.AV_DISPOSITION_ATTACHED_PIC();
    }

    @Override
    public int mediaTypeVideo() {
        return FFmpeg.AVMEDIA_TYPE_VIDEO();
    }

    @Override
    public int mediaTypeAudio() {
        return FFmpeg.AVMEDIA_TYPE_AUDIO();
    }

    @Override
    public int mediaTypeSubtitle() {
        return FFmpeg.AVMEDIA_TYPE_SUBTITLE();
    }

    @Override
    public String pixFmtName(int pixFmt) {
        return Ffm.cstr(FFmpeg.av_get_pix_fmt_name(pixFmt));
    }

    @Override
    public String errStr(int err) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            if (FFmpeg.av_strerror(err, buf, 256) < 0) {
                return "error " + err;
            }
            return buf.getString(0);
        }
    }

    private static Rational rational(MemorySegment r) {
        return new Rational(AVRational.num(r), AVRational.den(r));
    }
}
