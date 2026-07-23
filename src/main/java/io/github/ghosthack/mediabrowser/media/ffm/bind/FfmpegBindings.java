package io.github.ghosthack.mediabrowser.media.ffm.bind;

import io.github.ghosthack.mediabrowser.media.MediaException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Semantic seam over the FFmpeg jextract stubs (libavformat / libavcodec /
 * libswscale). Every method either calls one FFmpeg function or reads one
 * struct field; the decode logic ({@code FfmpegAv}, {@code FfmpegVideoStream},
 * {@code FfmpegMetadata}) talks only to this interface, never to a generated
 * {@code FFmpeg}/{@code AV*} class.
 *
 * <p>This is what lets a single copy of the decode logic run against more than
 * one FFmpeg major version: the version-specific bits — the dylib load path,
 * struct offsets, and API drift between FFmpeg majors (e.g. where the channel
 * count lives) — live entirely in an implementation.</p>
 *
 * <p>Pointers are passed through as opaque {@link MemorySegment}s. Where a
 * struct field is itself a struct (e.g. {@code AVRational}) it is lifted to a
 * plain value ({@link Rational}) so callers never see a generated layout.</p>
 */
public interface FfmpegBindings {

    // ---- lifecycle -------------------------------------------------------

    /** Quiets libav logging to errors. Cheap and idempotent. */
    void init();

    /** Human-readable libavformat version, e.g. {@code "avformat 58.76.100"}. */
    String version();

    // ---- open / close ----------------------------------------------------

    /**
     * Opens {@code file} and reads stream info, returning the
     * {@code AVFormatContext**} holder (allocated in {@code arena}). Throws
     * {@link MediaException} on failure (and closes any partially-open context).
     */
    MemorySegment openInput(Arena arena, Path file);

    /** Dereferences and right-sizes the {@code AVFormatContext*} from its holder. */
    MemorySegment derefFormatContext(MemorySegment ctxPtr);

    /** Closes the context held by {@code ctxPtr} (an {@code AVFormatContext**}). */
    void closeInput(MemorySegment ctxPtr);

    // ---- container reads -------------------------------------------------

    /** The right-sized {@code AVInputFormat*}, or {@code MemorySegment.NULL}. */
    MemorySegment iformat(MemorySegment ctx);

    String iformatLongName(MemorySegment iformat);

    String iformatName(MemorySegment iformat);

    long duration(MemorySegment ctx);

    long bitRate(MemorySegment ctx);

    MemorySegment containerMetadata(MemorySegment ctx);

    int nbStreams(MemorySegment ctx);

    /** The right-sized {@code AVStream*} at {@code index}. */
    MemorySegment stream(MemorySegment ctx, int index);

    /**
     * {@code av_find_best_stream} with the usual {@code wanted=-1, related=-1,
     * flags=0}. When {@code decoderRet} is non-NULL it receives the chosen
     * {@code AVCodec*}.
     */
    int findBestStream(MemorySegment ctx, int mediaType, MemorySegment decoderRet);

    // ---- stream groups -----------------------------------------------------

    /** {@code AVFormatContext.nb_stream_groups} (0 before FFmpeg 7.1). */
    int nbStreamGroups(MemorySegment ctx);

    /**
     * The stream group at {@code index} lifted to a {@link TileGrid}, or
     * {@code null} when that group is not an
     * {@code AV_STREAM_GROUP_PARAMS_TILE_GRID} — the demuxed form of a tiled
     * HEIF/AVIF still. Group-local tile indexes are resolved to format-context
     * stream indexes in the lift.
     */
    TileGrid tileGrid(MemorySegment ctx, int index);

    // ---- stream reads ----------------------------------------------------

    /** The right-sized {@code AVCodecParameters*} for the stream. */
    MemorySegment codecpar(MemorySegment stream);

    Rational avgFrameRate(MemorySegment stream);

    int disposition(MemorySegment stream);

    Rational timeBase(MemorySegment stream);

    long streamDuration(MemorySegment stream);

    MemorySegment streamMetadata(MemorySegment stream);

    /**
     * The video stream's container/display rotation as clockwise quarter-turns
     * (0..3) to apply for an upright frame, read from its display-matrix coded
     * side data ({@code AV_PKT_DATA_DISPLAYMATRIX}). {@code 0} when the stream
     * carries no rotation. Where that side data lives has moved across FFmpeg
     * majors ({@code AVCodecParameters.coded_side_data} in 7.x+), which is why
     * the read sits behind this seam; the matrix→turns math is shared in
     * {@link Ffm#rotationQuarterTurnsFromSideData}.
     */
    int videoRotationQuarterTurnsCw(MemorySegment stream);

    /**
     * The stream's display-matrix orientation as an EXIF code (1..8; 1 when
     * absent) — rotation and mirror, for the stills paths that bake full
     * orientation ({@code Ffm.exifOrientationFromSideData} over the same side
     * data as {@link #videoRotationQuarterTurnsCw}, which stays rotation-only
     * for playback).
     */
    int videoExifOrientation(MemorySegment stream);

    // ---- codec parameters ------------------------------------------------

    int parWidth(MemorySegment codecpar);

    int parHeight(MemorySegment codecpar);

    int parCodecId(MemorySegment codecpar);

    int parCodecType(MemorySegment codecpar);

    int parSampleRate(MemorySegment codecpar);

    /**
     * Channel count — {@code ch_layout.nb_channels} in FFmpeg 5.x+ (the flat
     * {@code channels} field this seam once bridged is gone from the API).
     */
    int parChannels(MemorySegment codecpar);

    /** {@code AVCodecParameters.format} — the pixel format for video streams. */
    int parFormat(MemorySegment codecpar);

    /** {@code avcodec_get_name(codecId)} as a String. */
    String codecName(int codecId);

    /** {@code avcodec_find_decoder(codecId)}, possibly {@code MemorySegment.NULL}. */
    MemorySegment findDecoder(int codecId);

    // ---- decode ----------------------------------------------------------

    MemorySegment allocContext3(MemorySegment codec);

    int parametersToContext(MemorySegment cctx, MemorySegment codecpar);

    int open2(MemorySegment cctx, MemorySegment codec);

    /**
     * Sets {@code AVCodecContext.thread_count = 0} (auto) before
     * {@link #open2}: libavcodec defaults to a single thread, and frame
     * threading pipelines independent intra frames — near-linear speedup on
     * tile-grid composes and multi-frame decode generally.
     */
    void setAutoThreads(MemorySegment cctx);

    void freeContext(MemorySegment cctxPtr);

    /** Allocates and right-sizes an {@code AVPacket}. */
    MemorySegment packetAlloc();

    void packetFree(MemorySegment pktPtr);

    void packetUnref(MemorySegment pkt);

    int packetStreamIndex(MemorySegment pkt);

    /** Allocates and right-sizes an {@code AVFrame}. */
    MemorySegment frameAlloc();

    void frameFree(MemorySegment framePtr);

    void frameUnref(MemorySegment frame);

    int readFrame(MemorySegment ctx, MemorySegment pkt);

    /** {@code avcodec_send_packet}; {@code pkt} may be {@code MemorySegment.NULL} to drain. */
    int sendPacket(MemorySegment cctx, MemorySegment pkt);

    int receiveFrame(MemorySegment cctx, MemorySegment frame);

    // ---- hardware decode -------------------------------------------------

    /**
     * {@code av_hwdevice_ctx_create} for {@code deviceType} (an
     * {@code AVHWDeviceType} value, see {@code HwDecode}) with the default
     * device. Returns the owning {@code AVBufferRef*}, or
     * {@code MemorySegment.NULL} when the device cannot be created here —
     * the caller decides whether that is a loud error (required hardware) or
     * a route-to-software (auto).
     */
    MemorySegment hwDeviceCreate(int deviceType);

    /** Releases a {@link #hwDeviceCreate} reference ({@code av_buffer_unref}). */
    void hwDeviceUnref(MemorySegment deviceRef);

    /**
     * The hardware pixel format {@code codec} decodes to for {@code deviceType}
     * via the {@code hw_device_ctx} method ({@code avcodec_get_hw_config}
     * scan), or {@code -1} when the decoder advertises no such config — the
     * codec-capability gate for auto routing.
     */
    int hwPixFmtFor(MemorySegment codec, int deviceType);

    /**
     * Requests hardware decode on {@code cctx} before {@link #open2}: stores a
     * new reference to {@code deviceRef} in {@code hw_device_ctx} and installs
     * a {@code get_format} callback preferring {@code hwPixFmt} (first
     * software format otherwise — the caller detects that on the first frame
     * and applies its policy). The callback stub must outlive the codec
     * context and may be invoked from a decoder worker thread, so
     * {@code stubArena} must be a shared arena closed after the context.
     */
    void requestHwDecode(Arena stubArena, MemorySegment cctx, MemorySegment deviceRef, int hwPixFmt);

    /**
     * {@code av_hwframe_transfer_data(dst, src, 0)} — GPU→CPU readback of a
     * hardware frame into {@code dst} (format auto-picked when unset; NV12
     * for VideoToolbox). Returns the FFmpeg error code.
     */
    int hwFrameTransfer(MemorySegment dst, MemorySegment src);

    // ---- frame reads -----------------------------------------------------

    int frameWidth(MemorySegment frame);

    int frameHeight(MemorySegment frame);

    int frameFormat(MemorySegment frame);

    Rational frameSampleAspectRatio(MemorySegment frame);

    /** The {@code uint8_t* data[]} plane-pointer array, for {@code sws_scale}. */
    MemorySegment frameData(MemorySegment frame);

    /** The {@code int linesize[]} stride array, for {@code sws_scale}. */
    MemorySegment frameLinesize(MemorySegment frame);

    long frameBestEffortTimestamp(MemorySegment frame);

    long framePts(MemorySegment frame);

    /**
     * {@code AVFrame.data[3]} as a raw address — the hardware handle of a hw
     * frame (a {@code CVPixelBufferRef} for VideoToolbox). {@code 0} when
     * absent.
     */
    long frameHwHandle(MemorySegment frame);

    /** {@code AVFrame.colorspace} ({@code AVCOL_SPC_*}; BT.709 is 1). */
    int frameColorspace(MemorySegment frame);

    /** {@code AVFrame.color_range} ({@code AVCOL_RANGE_*}; full/JPEG is 2). */
    int frameColorRange(MemorySegment frame);

    // ---- swscale ---------------------------------------------------------

    /**
     * {@code sws_getContext} into {@code AV_PIX_FMT_BGRA} with NULL filters.
     * Returns the context (may be {@code MemorySegment.NULL}).
     */
    MemorySegment swsGetContextToBgra(int srcW, int srcH, int srcFmt,
                                      int dstW, int dstH, int flags);

    void swsScale(MemorySegment sws, MemorySegment srcData, MemorySegment srcLinesize,
                  int srcSliceY, int srcSliceH, MemorySegment dstData, MemorySegment dstStride);

    void swsFreeContext(MemorySegment sws);

    int swsBilinear();

    int swsArea();

    /** {@code AV_PIX_FMT_BGRA}, for BGRA-source rescales (tile-grid canvases). */
    int pixFmtBgra();

    // ---- dictionaries ----------------------------------------------------

    /**
     * Iterate-from-{@code prev} dictionary read using the canonical empty-key +
     * {@code AV_DICT_IGNORE_SUFFIX} form. Returns the right-sized entry, or
     * {@code MemorySegment.NULL} at the end. Feed the result back as {@code prev}.
     */
    MemorySegment dictGetFirst(MemorySegment dict, MemorySegment emptyKey, MemorySegment prev);

    String dictEntryKey(MemorySegment entry);

    String dictEntryValue(MemorySegment entry);

    // ---- constants -------------------------------------------------------

    long noptsValue();

    int averrorEof();

    /** {@code AVERROR(EAGAIN)} for this platform. */
    int averrorEagain();

    int dispositionAttachedPic();

    int mediaTypeVideo();

    int mediaTypeAudio();

    int mediaTypeSubtitle();

    // ---- helpers ---------------------------------------------------------

    String pixFmtName(int pixFmt);

    String errStr(int err);

    /** Throws {@link MediaException} when {@code err < 0}. */
    default void check(int err, String what) {
        if (err < 0) {
            throw new MediaException("ffmpeg: " + what + " failed: " + errStr(err));
        }
    }
}
