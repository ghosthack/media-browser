package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.ColorProfile;
import io.github.ghosthack.mediabrowser.media.ContentSniffer;
import io.github.ghosthack.mediabrowser.media.CorruptMediaException;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaFailures;
import io.github.ghosthack.mediabrowser.media.MediaEngineTrace;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.Metadata;
import io.github.ghosthack.mediabrowser.media.PhotoCdImagePack;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.VisualResult;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;
import io.github.ghosthack.mediabrowser.media.ffm.bind.bundled.BundledFfmpegBindings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@link MediaFacade} backed <b>solely</b> by the bundled-FFmpeg FFM bindings
 * (the {@code io.github.ghosthack:ffmpeg-ffm} artifact): stills, video, and
 * audio all decode through FFmpeg — no libvips, no other native dependency,
 * nothing user-installed. Works as the solo {@code FFMPEG_FFM} backend
 * <em>and</em> as the video fallback behind the TwelveMonkeys still facade for
 * {@code TWELVEMONKEYS_FFMPEG_FFM}.
 *
 * <p><b>Still vs. video.</b> FFmpeg demuxes a still image as a one-frame video
 * stream, and {@link FfmpegAv} reports it as {@link MediaKind#VIDEO}. This
 * class refines the kind heuristically: a known still
 * extension with no audio stream and no container duration is
 * {@link MediaKind#IMAGE}. Animated GIF/AVIF report a real duration, so they
 * stay {@link MediaKind#VIDEO} and play in the viewer.</p>
 *
 * <p>HEIC/AVIF decode natively here (FFmpeg 8 demuxes HEIF; AV1 stills go
 * through the statically linked dav1d).</p>
 *
 * <p>Camera RAW (CR2/CR3/NEF/ARW/RAF/ORF/DNG/…) — a family FFmpeg does not
 * cover — routes to {@link LibRawStills} (the libraw-ffm artifact) by
 * extension. Where those natives are absent, RAW extensions are not claimed
 * (loud stderr note at first probe), everything else is untouched.</p>
 */
public final class FfmpegFfmMediaFacade implements MediaFacade {

    /** Extensions FFmpeg should claim as possible stills (kind refined by probe). */
    private static final Set<String> STILL_EXTENSIONS = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "webp", "bmp",
            "tif", "tiff",
            "avif", "heic", "heif",
            // JPEG XL decodes through the statically linked libjxl (ffmpeg-ffm
            // >= 0.3.0); animated JXL keeps VIDEO via its duration like GIF.
            "jxl",
            // JPEG 2000 and lossless JPEG-LS: FFmpeg's own jpeg2000/jpegls
            // decoders, no external libopenjpeg needed.
            "jp2", "j2k", "jpf", "jls",
            // Kodak Photo CD image packs (the `photocd` decoder). One file holds
            // a 192x128..3072x2048 pyramid; FFmpeg hands back the top level.
            "pcd",
            // The long tail FFmpeg has always decoded and this facade simply
            // never claimed: Targa, PCX, Netpbm, X11, SGI, Sun raster, DDS,
            // Radiance HDR, OpenEXR, ICO, QOI, DPX, Photoshop.
            "tga", "pcx", "ppm", "pgm", "pbm", "pnm", "pam", "pfm",
            "xpm", "xbm", "xwd", "sgi", "ras", "dds", "hdr", "exr",
            "ico", "qoi", "dpx", "psd");

    /** AV container/stream extensions FFmpeg should claim (video and audio). */
    private static final Set<String> AV_EXTENSIONS = Set.of(
            "mp4", "m4v", "m4s", "mkv", "webm", "avi", "mov", "qt", "wmv", "asf",
            "flv", "f4v", "divx", "mpg", "mpeg", "m2v", "mpv",
            "ts", "m2ts", "mts", "ogv", "ogm", "3gp", "3g2", "vob", "mxf", "nut",
            "dv", "rm",
            "mp3", "mp2", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
            "aiff", "aif", "ape", "mka", "ac3", "dts", "amr", "caf", "au", "ra",
            "mpc", "wv", "tta", "spx", "dsf",
            // GIF: animated GIF plays through the FFmpeg gif demuxer.
            "gif");

    private final FfmpegBindings ffmpeg;
    private final FfmpegAv av;
    private final FfmpegMetadata metadata;
    private final boolean turboJpeg;
    private final boolean captureColorProfiles;

    /** No-arg for the reflective {@code MediaBackend} factories: pure FFmpeg. */
    public FfmpegFfmMediaFacade() {
        this(new BundledFfmpegBindings());
    }

    public FfmpegFfmMediaFacade(FfmpegBindings ffmpeg) {
        this(ffmpeg, false, false);
    }

    private FfmpegFfmMediaFacade(FfmpegBindings ffmpeg, boolean turboJpeg,
                                 boolean captureColorProfiles) {
        this.ffmpeg = ffmpeg;
        // A poster of what plays as video converts an untagged frame like
        // playback does (UntaggedMatrix); a still keeps BT.601. "Plays as
        // video" is the same refined kind the viewer routes playback by.
        this.av = new FfmpegAv(ffmpeg, captureColorProfiles,   // also quiets libav logging
                probe -> refineKind(probe, probe.path()).kind() == MediaKind.VIDEO);
        this.metadata = new FfmpegMetadata(ffmpeg);
        this.turboJpeg = turboJpeg;
        this.captureColorProfiles = captureColorProfiles;
    }

    /**
     * The {@code ffmpeg-ffm-turbojpeg} variant: identical except baseline-JPEG
     * thumbnails decode through libjpeg-turbo's scaled decode
     * ({@link TurboJpegStills}). Fails loudly when the turbojpeg-ffm natives
     * are absent on this platform — choosing this backend is an explicit
     * request for the fast path, not a hint.
     */
    public static FfmpegFfmMediaFacade withTurboJpeg() {
        if (!TurboJpegStills.available()) {
            throw new MediaException(
                    "turbojpeg-ffm natives unavailable on this platform; "
                    + "use the ffmpeg-ffm backend instead");
        }
        return new FfmpegFfmMediaFacade(new BundledFfmpegBindings(), true, false);
    }

    /** TurboJPEG plus a same-read ICC handoff for the color-managed decorator. */
    public static FfmpegFfmMediaFacade withTurboJpegColorProfiles() {
        if (!TurboJpegStills.available()) {
            throw new MediaException(
                    "turbojpeg-ffm natives unavailable on this platform; "
                    + "use the ffmpeg-ffm backend instead");
        }
        return new FfmpegFfmMediaFacade(new BundledFfmpegBindings(), true, true);
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        String ext = extension(file);
        if (PcdOrientation.isPcd(ext) && !PhotoCdImagePack.isImagePack(file)) {
            return Optional.empty();
        }
        if (LibRawStills.isRaw(ext)) {
            // RAW is always a still; decode problems surface at view time,
            // never as a listing-time filter.
            return LibRawStills.available() ? Optional.of(MediaKind.IMAGE) : Optional.empty();
        }
        if (!STILL_EXTENSIONS.contains(ext) && !AV_EXTENSIONS.contains(ext)) {
            return Optional.empty();   // not a format this backend claims
        }
        // A name this backend claims whose probe fails is a broken or
        // unreadable file, not "not media": the failure propagates (the
        // listing keeps the name's verdict and the open shows the error).
        return Optional.of(refineKind(av.probe(file, -1), file).kind());
    }

    @Override
    public MediaProbe probe(Path file) {
        requirePcdImagePack(file);
        if (LibRawStills.isRaw(extension(file)) && LibRawStills.available()) {
            return rawProbe(file);
        }
        return refineKind(av.probe(file, fileSize(file)), file);
    }

    @Override
    public VisualResult loadVisual(Path file) {
        if (LibRawStills.isRaw(extension(file)) && LibRawStills.available()) {
            // JXL-compressed DNGs (DNG 1.7, iPhone 17): LibRaw 0.22 stubs
            // these out (LIBRAW_FILE_UNSUPPORTED), so the tile decode through
            // bundled FFmpeg's libjxl + the pure-Java DNG render is the real
            // decode. The Compression-52546 SubIFD is the same gate that
            // verdict fires on. On a render failure the embedded full-res
            // preview below stays the safety net — visibly logged, not silent.
            // Corrupt tile data is not a render failure: it stays this file's
            // error, never replaced by the camera's preview.
            Optional<RasterFrame> jxlDng = Optional.empty();
            try {
                jxlDng = DngJxlStills.fullDecode(ffmpeg, file);
            } catch (MediaException e) {
                if (MediaFailures.isCorrupt(e)) throw e;
                System.err.println("media-browser: JXL DNG render failed for "
                        + file.getFileName() + " (" + e.getMessage()
                        + "); falling back to the embedded preview");
            }
            if (jxlDng.isPresent()) {
                return new VisualResult(rawProbe(file), jxlDng);
            }
            LibRawStills.Decoded decoded = requireDecoded(file, LibRawStills.fullDecode(file));
            return new VisualResult(rawProbe(file), Optional.of(decoded.frame()),
                    decoded.corruption());
        }
        return ffmpegVisual(file, MediaEngineTrace.Recorder.disabled());
    }

    /** The FFmpeg full decode, past the RAW route. */
    private VisualResult ffmpegVisual(Path file, MediaEngineTrace.Recorder trace) {
        VisualResult result = firstFrameSteppingDownPcd(file, trace);
        MediaProbe refined = refineKind(result.probe(), file);
        if (captureColorProfiles) {
            ColorProfile captured = av.takeCapturedIccProfile();
            if (captured != null) {
                refined = refined.withColorProfile(captured);
            }
        }
        VisualResult out = refined == result.probe() ? result : result.withProbe(refined);
        return jpegDataVerdict(file, bakePcdRotation(file, bakeJpegExif(file, out)), trace);
    }

    /**
     * The libjpeg-turbo verdict on a JPEG the viewer decoded through FFmpeg
     * (the {@code -turbojpeg} variants only): FFmpeg's mjpeg decoder conceals
     * damaged entropy data without saying so, so the same corrupt-data warning
     * that labels the thumbnail ({@link TurboJpegStills#corruptionOf}) is
     * read here from a 1/8-scale decode — entropy decoding in full, almost no
     * IDCT. FFmpeg's pixels stay the viewer image; only the label is added.
     * A JPEG the fast path declines (progressive, CMYK, …) gets no verdict,
     * exactly as its thumbnail gets none. Skipped (and said so in the trace)
     * while {@link JpegDataCheck} is off.
     */
    private VisualResult jpegDataVerdict(Path file, VisualResult result,
                                         MediaEngineTrace.Recorder trace) {
        if (!turboJpeg || !isJpeg(file) || result.frame().isEmpty()) return result;
        long started = trace.beginAttempt();
        String label = JpegDataCheck.LABEL;
        if (!JpegDataCheck.enabled()) {
            trace.declined(label, started, JpegDataCheck.SKIPPED);
            return result;
        }
        try {
            switch (TurboJpegStills.attempt(file, 1, ThumbnailMode.FIT, false)) {
                case TurboJpegStills.Decoded decoded -> {
                    String corruption = decoded.corruption();
                    trace.succeeded(label, started, decoded.warning() == null ? "clean"
                            : (corruption == null ? "harmless" : "corrupt")
                                    + "; libjpeg-turbo warning: " + decoded.warning());
                    return corruption == null ? result : result.withCorruption(corruption);
                }
                case TurboJpegStills.Declined declined -> {
                    trace.declined(label, started, declined.reason());
                    return result;
                }
            }
        } catch (CorruptMediaException e) {
            // libjpeg gives up on data FFmpeg concealed: FFmpeg's image is
            // shown, labelled with libjpeg's verdict.
            trace.failed(label, started, e);
            return result.withCorruption(e.getMessage());
        } catch (MediaException e) {
            trace.failed(label, started, e);
            return result;
        }
    }

    @Override
    public VisualResult loadVisual(Path file, MediaEngineTrace.Recorder trace) {
        if (LibRawStills.isRaw(extension(file)) && LibRawStills.available()) {
            long jxlStarted = trace.beginAttempt();
            try {
                Optional<RasterFrame> jxlDng = DngJxlStills.fullDecode(ffmpeg, file);
                if (jxlDng.isPresent()) {
                    trace.succeeded("JXL-compressed DNG render", jxlStarted,
                            rasterDetail(jxlDng.get(), "pure DNG render"));
                    return new VisualResult(rawProbe(file), jxlDng);
                }
                trace.declined("JXL-compressed DNG render", jxlStarted,
                        "Input is not a JXL-compressed DNG");
            } catch (MediaException e) {
                trace.failed("JXL-compressed DNG render", jxlStarted, e);
                if (MediaFailures.isCorrupt(e)) throw e;
            }
            long rawStarted = trace.beginAttempt();
            try {
                LibRawStills.Decoded decoded =
                        requireDecoded(file, LibRawStills.fullDecode(file));
                trace.succeeded("LibRaw / embedded preview", rawStarted,
                        rasterDetail(decoded.frame(), "camera RAW, " + decoded.route()));
                return new VisualResult(rawProbe(file), Optional.of(decoded.frame()),
                        decoded.corruption());
            } catch (RuntimeException | Error e) {
                trace.failed("LibRaw / embedded preview", rawStarted, e);
                throw e;
            }
        }
        long started = trace.beginAttempt();
        try {
            VisualResult result = ffmpegVisual(file, trace);
            trace.succeeded("Bundled FFmpeg", started, result.frame()
                    .map(f -> rasterDetail(f, "decoded first visual frame"))
                    .orElse("No visual frame"));
            return result;
        } catch (RuntimeException | Error e) {
            trace.failed("Bundled FFmpeg", started, e);
            throw e;
        }
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        if (LibRawStills.isRaw(extension(file)) && LibRawStills.available()) {
            // A RAW that cannot be decoded throws, like an FFmpeg thumbnail
            // failure: an empty frame here would be cached as a blank tile.
            return rawThumbnail(requireDecoded(file,
                    LibRawStills.thumbnail(file, maxEdge, mode, turboJpeg)));
        }
        boolean jpeg = isJpeg(file);
        if (jpeg && turboJpeg) {
            // libjpeg-turbo shrink-on-load fast path (the -turbojpeg backend
            // variant only). Declines — progressive/CMYK/lossless/12-bit, which
            // TurboJPEG handles worse or not at all — fall through to the
            // ordinary FFmpeg decode below: capability routing, not a
            // failure fallback. A native failure throws instead.
            if (TurboJpegStills.attempt(file, maxEdge, mode, captureColorProfiles)
                    instanceof TurboJpegStills.Decoded decoded) {
                return turboThumbnail(decoded);
            }
        }
        return ffmpegThumbnail(file, maxEdge, mode, jpeg, MediaEngineTrace.Recorder.disabled());
    }

    /**
     * LibRaw is the only route for a RAW extension, so a decline there has
     * nowhere to go: it is this file's decode error, with LibRaw's reason.
     */
    private static LibRawStills.Decoded requireDecoded(Path file, LibRawStills.Attempt attempt) {
        return switch (attempt) {
            case LibRawStills.Decoded decoded -> decoded;
            case LibRawStills.Declined declined -> throw new MediaException(
                    "LibRaw cannot decode " + file.getFileName() + ": " + declined.reason());
        };
    }

    /** Shown even when libjpeg-turbo warned the data is damaged — then labelled corrupt. */
    private static Thumbnail turboThumbnail(TurboJpegStills.Decoded decoded) {
        return new Thumbnail(Optional.of(decoded.frame()), MediaKind.IMAGE,
                decoded.colorProfile(), decoded.corruption());
    }

    private static Thumbnail rawThumbnail(LibRawStills.Decoded decoded) {
        return new Thumbnail(Optional.of(decoded.frame()), MediaKind.IMAGE, null,
                decoded.corruption());
    }

    /** The FFmpeg thumbnail decode, past every fast path. */
    private Thumbnail ffmpegThumbnail(Path file, int maxEdge, ThumbnailMode mode, boolean jpeg,
                                      MediaEngineTrace.Recorder trace) {
        Thumbnail thumb = thumbnailSteppingDownPcd(file, maxEdge, mode, trace);
        if (jpeg && thumb.frame().isPresent()) {
            int orientation = TurboJpegStills.exifOrientation(file);
            if (orientation != 1) {
                thumb = thumb.withFrame(thumb.frame()
                        .map(f -> RasterFrames.applyExifOrientation(f, orientation)));
            }
        }
        if (thumb.frame().isPresent() && PcdOrientation.isPcd(extension(file))) {
            int turns = PcdOrientation.quarterTurnsCw(file);
            if (turns != 0) {
                thumb = thumb.withFrame(
                        thumb.frame().map(f -> RasterFrames.rotateCw(f, turns)));
            }
        }
        if (thumb.kind() == MediaKind.VIDEO && isStillCandidate(file)) {
            // The kind labels the result (e.g. the mosaic's video badge), so
            // refine still candidates with one cheap header probe — an
            // animated GIF/AVIF keeps VIDEO via its duration, a plain still
            // becomes IMAGE. AV-extension files never pay the second open.
            return new Thumbnail(thumb.frame(), refineKind(av.probe(file, -1), file).kind(),
                    thumb.colorProfile());
        }
        return thumb;
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode,
                                   MediaEngineTrace.Recorder trace) {
        if (LibRawStills.isRaw(extension(file)) && LibRawStills.available()) {
            long started = trace.beginAttempt();
            try {
                LibRawStills.Decoded decoded = requireDecoded(file,
                        LibRawStills.thumbnail(file, maxEdge, mode, turboJpeg));
                Thumbnail result = rawThumbnail(decoded);
                trace.succeeded("LibRaw thumbnail", started,
                        thumbnailDetail(result, maxEdge, mode) + "; " + decoded.route());
                return result;
            } catch (RuntimeException | Error e) {
                trace.failed("LibRaw thumbnail", started, e);
                throw e;
            }
        }
        boolean jpeg = isJpeg(file);
        if (jpeg && turboJpeg) {
            long turboStarted = trace.beginAttempt();
            TurboJpegStills.Attempt attempt;
            try {
                attempt = TurboJpegStills.attempt(file, maxEdge, mode, captureColorProfiles);
            } catch (RuntimeException | Error e) {
                trace.failed("TurboJPEG scaled decode", turboStarted, e);
                throw e;
            }
            switch (attempt) {
                case TurboJpegStills.Decoded decoded -> {
                    Thumbnail result = turboThumbnail(decoded);
                    trace.succeeded("TurboJPEG scaled decode", turboStarted,
                            thumbnailDetail(result, maxEdge, mode)
                                    + (decoded.warning() == null ? ""
                                            : (decoded.corruption() == null ? "; harmless"
                                                    : "; corrupt")
                                                    + " libjpeg-turbo warning: "
                                                    + decoded.warning()));
                    return result;
                }
                case TurboJpegStills.Declined declined -> trace.declined(
                        "TurboJPEG scaled decode", turboStarted, declined.reason());
            }
        }
        long started = trace.beginAttempt();
        try {
            Thumbnail result = ffmpegThumbnail(file, maxEdge, mode, jpeg, trace);
            trace.succeeded("Bundled FFmpeg thumbnail", started,
                    thumbnailDetail(result, maxEdge, mode));
            return result;
        } catch (RuntimeException | Error e) {
            trace.failed("Bundled FFmpeg thumbnail", started, e);
            throw e;
        }
    }

    private static String rasterDetail(RasterFrame frame, String route) {
        return route + "; " + frame.width() + "×" + frame.height() + " BGRA";
    }

    private static String thumbnailDetail(Thumbnail result, int maxEdge, ThumbnailMode mode) {
        return mode + ", requested ≤" + maxEdge + " px, produced "
                + result.frame().map(f -> f.width() + "×" + f.height() + " BGRA")
                        .orElse("no visual frame");
    }

    @Override
    public Metadata readMetadata(Path file) {
        if (LibRawStills.isRaw(extension(file)) && LibRawStills.available()) {
            return rawMetadata(file);
        }
        return metadata.read(file);
    }

    @Override
    public VideoStream openVideo(Path file) {
        return new FfmpegVideoStream(ffmpeg, file, HwDecode.playbackRequest());
    }

    /**
     * SmokeTest seam: the JXL-DNG tile-decode render alone (no preview
     * fallback), for parity checks against {@link #debugJxlDngPreview}.
     */
    public Optional<RasterFrame> debugJxlDngRender(Path file) {
        return DngJxlStills.fullDecode(ffmpeg, file);
    }

    /**
     * SmokeTest seam: the LibRaw decode / embedded-preview route alone. Empty
     * only when LibRaw declines the file; a decode failure throws.
     */
    public Optional<RasterFrame> debugJxlDngPreview(Path file) {
        return LibRawStills.fullDecode(file) instanceof LibRawStills.Decoded decoded
                ? Optional.of(decoded.frame()) : Optional.empty();
    }

    @Override
    public String nativeVersions() {
        return "Bundled FFmpeg " + av.version()
                + (turboJpeg ? "; TurboJPEG (baseline-JPEG thumbnails)" : "")
                + (LibRawStills.available() ? "; LibRaw " + LibRawStills.version()
                        + " (camera RAW)" : "");
    }

    @Override
    public void close() {
        // native libraries stay loaded for the lifetime of the JVM
    }

    /**
     * Downgrades a {@link MediaKind#VIDEO} probe to {@link MediaKind#IMAGE}
     * when the file is a still: known still extension (or a strong still-image
     * content signature for an unknown name), no audio stream, and at most one
     * frame's worth of container duration — FFmpeg demuxes stills as one-frame
     * video. A real animation (GIF/AVIF) carries multiple frames' duration and
     * stays VIDEO. Returns the original probe unchanged otherwise.
     */
    private static MediaProbe refineKind(MediaProbe probe, Path file) {
        boolean atMostOneFrame = probe.durationMicros() <= 0
                || (probe.frameRate() > 0
                        && probe.durationMicros() * probe.frameRate() <= 1_500_000);
        if (probe.kind() != MediaKind.VIDEO
                || probe.audioCodec() != null
                || !isStillCandidate(file)
                || !atMostOneFrame) {
            return probe;
        }
        return new MediaProbe(probe.path(), MediaKind.IMAGE, probe.container(),
                probe.fileSize(), -1, probe.bitRate(),
                probe.width(), probe.height(),
                probe.videoCodec(), -1, null, -1, -1, probe.pixelDescription(),
                probe.colorProfile());
    }

    /**
     * FFmpeg's mjpeg path surfaces no EXIF display matrix at probe time (the
     * stream-side-data rotation seam only sees container matrices), so JPEG
     * EXIF orientation is baked here — matching the vips/Apple/TwelveMonkeys
     * facades' convention of handing the UI upright frames.
     */
    private static VisualResult bakeJpegExif(Path file, VisualResult result) {
        if (!isJpeg(file) || result.frame().isEmpty()) {
            return result;
        }
        int orientation = TurboJpegStills.exifOrientation(file);
        if (orientation == 1) {
            return result;
        }
        return result.withFrame(
                result.frame().map(f -> RasterFrames.applyExifOrientation(f, orientation)));
    }

    /**
     * The ordinary full-resolution decode, except that a Photo CD whose top
     * pyramid layer FFmpeg rejects steps down the pyramid rather than failing
     * outright. Only {@code .pcd} is retried, and only after a decode error, so
     * nothing else changes its failure behaviour.
     */
    private VisualResult firstFrameSteppingDownPcd(Path file, MediaEngineTrace.Recorder trace) {
        requirePcdImagePack(file);
        long size = fileSize(file);
        return steppingDownPcd(file, trace,
                () -> av.firstFrameWithProbe(file, size),
                lowres -> av.firstFrameWithProbe(file, size, lowres));
    }

    /** {@link #firstFrameSteppingDownPcd} for the thumbnail path. */
    private Thumbnail thumbnailSteppingDownPcd(Path file, int maxEdge, ThumbnailMode mode,
                                               MediaEngineTrace.Recorder trace) {
        requirePcdImagePack(file);
        return steppingDownPcd(file, trace,
                () -> av.thumbnail(file, maxEdge, mode),
                lowres -> av.thumbnail(file, maxEdge, mode, lowres));
    }

    /**
     * Runs {@code full}; for a Photo CD image pack whose full layer FFmpeg
     * rejects, steps down the pyramid. The recovery is visible, never
     * silent: the rejected layer and each level tried are recorded in
     * {@code trace}, a successful step-down is announced on stderr, and when
     * no level decodes the caller sees the full-layer error with every
     * lower level's failure attached as suppressed.
     */
    static <T> T steppingDownPcd(Path file, MediaEngineTrace.Recorder trace,
                                         java.util.function.Supplier<T> full,
                                         java.util.function.IntFunction<T> atLowres) {
        long fullStarted = trace.beginAttempt();
        try {
            return full.get();
        } catch (MediaException first) {
            if (!PcdOrientation.isPcd(extension(file))
                    || !PhotoCdImagePack.isImagePack(file)) {
                throw first;
            }
            trace.failed("FFmpeg Photo CD full-resolution layer", fullStarted, first);
            for (int lowres : PcdOrientation.FALLBACK_LOWRES) {
                String level = "FFmpeg Photo CD 1/" + (1 << lowres) + " layer";
                long started = trace.beginAttempt();
                try {
                    T stepped = atLowres.apply(lowres);
                    trace.succeeded(level, started,
                            "Stepped down: the full-resolution layer was rejected");
                    PcdOrientation.announceStepDown(file, lowres);
                    return stepped;
                } catch (MediaException e) {
                    trace.failed(level, started, e);
                    first.addSuppressed(e);
                }
            }
            throw first;
        }
    }

    private static void requirePcdImagePack(Path file) {
        if (PcdOrientation.isPcd(extension(file))
                && !PhotoCdImagePack.isImagePack(file)) {
            throw new MediaException(file.getFileName()
                    + " is a Photo CD control record, not an ImagePac raster"
                    + " (missing PCD_IPI at offset 0x800)");
        }
    }

    /**
     * Applies the Photo CD image pack's stored orientation. FFmpeg's photocd
     * decoder ignores it, so a portrait original arrives on its side; see
     * {@link PcdOrientation}.
     */
    private static VisualResult bakePcdRotation(Path file, VisualResult result) {
        if (!PcdOrientation.isPcd(extension(file)) || result.frame().isEmpty()) {
            return result;
        }
        int turns = PcdOrientation.quarterTurnsCw(file);
        if (turns == 0) {
            return result;
        }
        return result.withFrame(result.frame().map(f -> RasterFrames.rotateCw(f, turns)));
    }

    /** Probe a RAW file via LibRaw metadata (no pixel decode). */
    private static MediaProbe rawProbe(Path file) {
        LibRawStills.RawInfo info = LibRawStills.info(file);
        String camera = (info.make() + " " + info.model()).trim();
        return new MediaProbe(file, MediaKind.IMAGE, extension(file),
                fileSize(file), -1, -1, info.width(), info.height(),
                null, -1, null, -1, -1,
                camera.isEmpty() ? "camera raw" : "raw, " + camera);
    }

    private static Metadata rawMetadata(Path file) {
        LibRawStills.RawInfo info = LibRawStills.info(file);
        Metadata.Builder b = new Metadata.Builder(file);
        if (!info.make().isBlank()) {
            b.add("Camera", "Make", info.make());
        }
        if (!info.model().isBlank()) {
            b.add("Camera", "Model", info.model());
        }
        b.add("Image", "Dimensions", info.width() + " × " + info.height());
        if (info.timestampSeconds() > 0) {
            b.add("Image", "Captured", java.time.Instant.ofEpochSecond(info.timestampSeconds())
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        if (info.isoSpeed() > 0) {
            b.add("Exposure", "ISO", String.valueOf(Math.round(info.isoSpeed())));
        }
        if (info.shutter() > 0) {
            b.add("Exposure", "Shutter", info.shutter() >= 1
                    ? String.format(Locale.ROOT, "%.1f s", info.shutter())
                    : "1/" + Math.round(1 / info.shutter()) + " s");
        }
        if (info.aperture() > 0) {
            b.add("Exposure", "Aperture", String.format(Locale.ROOT, "f/%.1f", info.aperture()));
        }
        if (info.focalLen() > 0) {
            b.add("Exposure", "Focal length", String.format(Locale.ROOT, "%.0f mm", info.focalLen()));
        }
        return b.build();
    }

    /**
     * JFIF-wrapped JPEG by name, or by strong content signature when the name
     * is unknown — the TurboJPEG fast path and EXIF orientation bake key off
     * this. {@code .jpe}/{@code .jfif} are the same bitstream under older
     * Windows/browser spellings, so they get both.
     */
    private static boolean isJpeg(Path file) {
        String ext = extension(file);
        if ("jpg".equals(ext) || "jpeg".equals(ext)
                || "jpe".equals(ext) || "jfif".equals(ext)) {
            return true;
        }
        return !STILL_EXTENSIONS.contains(ext) && !AV_EXTENSIONS.contains(ext)
                && ContentSniffer.canonicalExtension(file).filter("jpg"::equals).isPresent();
    }

    /** Same promotion-only naming policy as content detection. */
    private static boolean isStillCandidate(Path file) {
        String ext = extension(file);
        if (STILL_EXTENSIONS.contains(ext)) return true;
        if (AV_EXTENSIONS.contains(ext)) return false;
        return ContentSniffer.sniff(file).filter(MediaKind.IMAGE::equals).isPresent();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
