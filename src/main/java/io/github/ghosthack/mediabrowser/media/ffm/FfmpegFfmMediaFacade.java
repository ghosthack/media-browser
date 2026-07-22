package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.Metadata;
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
 * through the statically linked dav1d), which the vips-paired facade routes to
 * libvips instead.</p>
 */
public final class FfmpegFfmMediaFacade implements MediaFacade {

    /** Extensions FFmpeg should claim as possible stills (kind refined by probe). */
    private static final Set<String> STILL_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff",
            "avif", "heic", "heif");

    /** AV container/stream extensions — shared with the vips-paired facade. */
    private static final Set<String> AV_EXTENSIONS = FfmpegVipsMediaFacade.AV_EXTENSIONS;

    private final FfmpegBindings ffmpeg;
    private final FfmpegAv av;
    private final FfmpegMetadata metadata;

    /** No-arg for the reflective {@code MediaBackend} factories. */
    public FfmpegFfmMediaFacade() {
        this(new BundledFfmpegBindings());
    }

    public FfmpegFfmMediaFacade(FfmpegBindings ffmpeg) {
        this.ffmpeg = ffmpeg;
        this.av = new FfmpegAv(ffmpeg);   // also quiets libav logging
        this.metadata = new FfmpegMetadata(ffmpeg);
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
        return refined == result.probe() ? result
                : new VisualResult(refined, result.frame());
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        Thumbnail thumb = av.thumbnail(file, maxEdge, mode);
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
        return "Bundled FFmpeg " + av.version();
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
