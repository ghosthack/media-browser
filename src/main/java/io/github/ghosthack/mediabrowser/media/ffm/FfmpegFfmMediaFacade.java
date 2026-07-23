package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.Metadata;
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
 * {@code TWELVEMONKEYS_FFMPEG_FFM} (same pairing as the JavaCV twins).
 *
 * <p><b>Still vs. video.</b> FFmpeg demuxes a still image as a one-frame video
 * stream, and {@link FfmpegAv} reports it as {@link MediaKind#VIDEO}. Like the
 * JavaCV facade, this class refines the kind heuristically: a known still
 * extension with no audio stream and no container duration is
 * {@link MediaKind#IMAGE}. Animated GIF/AVIF report a real duration, so they
 * stay {@link MediaKind#VIDEO} and play in the viewer.</p>
 *
 * <p>HEIC/AVIF decode natively here (FFmpeg 8 demuxes HEIF; AV1 stills go
 * through the statically linked dav1d).</p>
 */
public final class FfmpegFfmMediaFacade implements MediaFacade {

    /** Extensions FFmpeg should claim as possible stills (kind refined by probe). */
    private static final Set<String> STILL_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff",
            "avif", "heic", "heif");

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

    /** No-arg for the reflective {@code MediaBackend} factories: pure FFmpeg. */
    public FfmpegFfmMediaFacade() {
        this(new BundledFfmpegBindings());
    }

    public FfmpegFfmMediaFacade(FfmpegBindings ffmpeg) {
        this(ffmpeg, false);
    }

    private FfmpegFfmMediaFacade(FfmpegBindings ffmpeg, boolean turboJpeg) {
        this.ffmpeg = ffmpeg;
        this.av = new FfmpegAv(ffmpeg);   // also quiets libav logging
        this.metadata = new FfmpegMetadata(ffmpeg);
        this.turboJpeg = turboJpeg;
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
        return new FfmpegFfmMediaFacade(new BundledFfmpegBindings(), true);
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        String ext = extension(file);
        if (!STILL_EXTENSIONS.contains(ext) && !AV_EXTENSIONS.contains(ext)) {
            return Optional.empty();
        }
        try {
            return Optional.of(refineKind(av.probe(file, -1), file).kind());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public MediaProbe probe(Path file) {
        return refineKind(av.probe(file, fileSize(file)), file);
    }

    @Override
    public VisualResult loadVisual(Path file) {
        VisualResult result = av.firstFrameWithProbe(file, fileSize(file));
        MediaProbe refined = refineKind(result.probe(), file);
        VisualResult out = refined == result.probe() ? result
                : new VisualResult(refined, result.frame());
        return bakeJpegExif(file, out);
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        boolean jpeg = isJpeg(file);
        if (jpeg && turboJpeg) {
            // libjpeg-turbo shrink-on-load fast path (the -turbojpeg backend
            // variant only). Declines — progressive/CMYK/lossless, which
            // TurboJPEG handles worse or not at all — fall through to the
            // ordinary FFmpeg decode below: capability routing, not a
            // failure fallback.
            var fast = TurboJpegStills.thumbnail(file, maxEdge, mode);
            if (fast.isPresent()) {
                return new Thumbnail(fast, MediaKind.IMAGE);
            }
        }
        Thumbnail thumb = av.thumbnail(file, maxEdge, mode);
        if (jpeg && thumb.frame().isPresent()) {
            int orientation = TurboJpegStills.exifOrientation(file);
            if (orientation != 1) {
                thumb = new Thumbnail(thumb.frame()
                        .map(f -> RasterFrames.applyExifOrientation(f, orientation)), thumb.kind());
            }
        }
        if (thumb.kind() == MediaKind.VIDEO && STILL_EXTENSIONS.contains(extension(file))) {
            // The kind labels the result (e.g. the mosaic's video badge), so
            // refine still-extension files with one cheap header probe — an
            // animated GIF/AVIF keeps VIDEO via its duration, a plain still
            // becomes IMAGE. AV-extension files never pay the second open.
            return new Thumbnail(thumb.frame(), refineKind(av.probe(file, -1), file).kind());
        }
        return thumb;
    }

    @Override
    public Metadata readMetadata(Path file) {
        return metadata.read(file);
    }

    @Override
    public VideoStream openVideo(Path file) {
        return new FfmpegVideoStream(ffmpeg, file);
    }

    @Override
    public String nativeVersions() {
        return "Bundled FFmpeg " + av.version()
                + (turboJpeg ? "; TurboJPEG (baseline-JPEG thumbnails)" : "");
    }

    @Override
    public void close() {
        // native libraries stay loaded for the lifetime of the JVM
    }

    /**
     * Downgrades a {@link MediaKind#VIDEO} probe to {@link MediaKind#IMAGE}
     * when the file is a still: known still extension, no audio stream, and at
     * most one frame's worth of container duration — FFmpeg demuxes stills as
     * one-frame video, and the image2 demuxer (JPEG et al.) reports that one
     * frame as a 1/fps duration rather than none. A real animation (GIF/AVIF)
     * carries multiple frames' duration and stays VIDEO. Returns the original
     * probe unchanged otherwise.
     */
    private static MediaProbe refineKind(MediaProbe probe, Path file) {
        boolean atMostOneFrame = probe.durationMicros() <= 0
                || (probe.frameRate() > 0
                        && probe.durationMicros() * probe.frameRate() <= 1_500_000);
        if (probe.kind() != MediaKind.VIDEO
                || probe.audioCodec() != null
                || !STILL_EXTENSIONS.contains(extension(file))
                || !atMostOneFrame) {
            return probe;
        }
        return new MediaProbe(probe.path(), MediaKind.IMAGE, probe.container(),
                probe.fileSize(), -1, probe.bitRate(),
                probe.width(), probe.height(),
                probe.videoCodec(), -1, null, -1, -1, probe.pixelDescription());
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
        return new VisualResult(result.probe(),
                result.frame().map(f -> RasterFrames.applyExifOrientation(f, orientation)));
    }

    private static boolean isJpeg(Path file) {
        String ext = extension(file);
        return "jpg".equals(ext) || "jpeg".equals(ext);
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
