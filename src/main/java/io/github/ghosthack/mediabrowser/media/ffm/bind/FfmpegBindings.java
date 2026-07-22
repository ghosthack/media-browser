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
 * struct offsets, and API drift such as {@code channels} (4.x) vs
 * {@code ch_layout.nb_channels} (5.x+) — live entirely in an implementation.</p>
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
     * carries no rotation. Where that side data lives is the version delta the
     * seam exists for ({@code AVStream.side_data} in 4.x,
     * {@code AVCodecParameters.coded_side_data} in 7.x+); the matrix→turns math
     * is shared in {@link Ffm#rotationQuarterTurnsFromSideData}.
     */
    int videoRotationQuarterTurnsCw(MemorySegment stream);

    // ---- codec parameters ------------------------------------------------

    int parWidth(MemorySegment codecpar);

    int parHeight(MemorySegment codecpar);

    int parCodecId(MemorySegment codecpar);

    int parCodecType(MemorySegment codecpar);

    int parSampleRate(MemorySegment codecpar);

    /**
     * Channel count. The whole point of the seam: 4.x reads
     * {@code AVCodecParameters.channels}; 5.x+ reads {@code ch_layout.nb_channels}.
     */
    int parChannels(MemorySegment codecpar);

    /** {@code avcodec_get_name(codecId)} as a String. */
    String codecName(int codecId);

    // ---- decode ----------------------------------------------------------

    MemorySegment allocContext3(MemorySegment codec);

    int parametersToContext(MemorySegment cctx, MemorySegment codecpar);

    int open2(MemorySegment cctx, MemorySegment codec);

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
