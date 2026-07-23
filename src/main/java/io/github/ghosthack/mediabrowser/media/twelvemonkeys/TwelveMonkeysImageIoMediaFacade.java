package io.github.ghosthack.mediabrowser.media.twelvemonkeys;

import io.github.ghosthack.mediabrowser.media.BufferedImageRaster;
import io.github.ghosthack.mediabrowser.media.ImageSequences;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.Metadata;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.VisualResult;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * {@link MediaFacade} backed by {@code javax.imageio} — the base JDK readers
 * enhanced by the TwelveMonkeys ImageIO SPI plugins (JPEG incl. CMYK/ICC, PNG,
 * GIF, BMP/ICO, TIFF, WebP-still, PSD, PNM, TGA, ICNS, PCX, SGI, IFF, PICT,
 * HDR, Thumbs.db). It handles <b>still images</b> and <b>animated GIF as
 * video</b>; everything else (other video, audio) is delegated to an optional
 * {@code fallback} facade.
 *
 * <p>The {@code fallback} is the composition seam for the {@code 12M + X}
 * backends (plan §1): {@code null} for the still-only {@code TWELVEMONKEYS}
 * backend, a {@code FfmpegFfmMediaFacade} / {@code JcodecMediaFacade} for the
 * combined backends. When a file is not a 12M-decodable still and not an
 * animated GIF, {@code classify}/{@code probe}/{@code loadVisual}/
 * {@code loadThumbnail}/{@code openVideo}/{@code readMetadata} delegate to the
 * fallback (or report unsupported when it is {@code null}).</p>
 *
 * <p><b>Animated GIF.</b> A GIF sniffs as a still, but a multi-frame one
 * ({@link ImageSequences#isAnimatedImageSequence}) is treated as
 * {@link MediaKind#VIDEO}: classify/probe/loadVisual/loadThumbnail use frame&nbsp;0
 * as the poster (via {@link GifFrames}) and {@link #openVideo} returns a
 * {@link GifVideoStream}. A single-frame GIF stays an {@link MediaKind#IMAGE}.</p>
 *
 * <p><b>Robustness.</b> Where several ImageIO readers claim a file (the classic
 * CMYK/ICC-JPEG case: the JDK reader fails, the TwelveMonkeys reader succeeds),
 * the still path iterates {@link ImageIO#getImageReaders} and uses the first
 * reader that actually decodes.</p>
 *
 * <p>Decoded rasters are tightly packed, straight-alpha BGRA via
 * {@link BufferedImageRaster}, matching {@link RasterFrame} and the GL/JavaFX
 * presentation path. Not thread-safe; callers serialize access (see
 * {@code MediaService}). Self-contained: imports only
 * {@code io.github.ghosthack.mediabrowser.media.*}, {@code javax.imageio.*} /
 * {@code java.awt.*} and the JDK — never {@code media.pure.*} or
 * {@code io.github.ghosthack.*}.</p>
 */
public final class TwelveMonkeysImageIoMediaFacade implements MediaFacade {

    /** Optional cover for non-still, non-GIF media (video/audio); may be null. */
    private final MediaFacade fallback;

    public TwelveMonkeysImageIoMediaFacade(MediaFacade fallback) {
        this.fallback = fallback;
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        if (isAnimatedGif(file)) {
            return Optional.of(MediaKind.VIDEO);
        }
        if (canReadStill(file)) {
            return Optional.of(MediaKind.IMAGE);
        }
        return fallback != null ? fallback.classify(file) : Optional.empty();
    }

    @Override
    public MediaProbe probe(Path file) {
        if (isAnimatedGif(file)) {
            return gifProbe(file);
        }
        Optional<MediaProbe> still = tryReaders(file, r -> stillProbe(file, r));
        if (still.isPresent()) {
            return still.get();
        }
        if (fallback != null) {
            return fallback.probe(file);
        }
        throw new MediaException("twelvemonkeys: cannot probe " + file.getFileName());
    }

    @Override
    public VisualResult loadVisual(Path file) {
        if (isAnimatedGif(file)) {
            return gifVisual(file);
        }
        Optional<VisualResult> still = tryReaders(file, r -> stillVisual(file, r));
        if (still.isPresent()) {
            return still.get();
        }
        if (fallback != null) {
            return fallback.loadVisual(file);
        }
        throw new MediaException("twelvemonkeys: cannot load visual of " + file.getFileName());
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        if (isAnimatedGif(file)) {
            return gifThumbnail(file, maxEdge, mode);
        }
        Optional<Thumbnail> still = tryReaders(file, r -> stillThumbnail(file, r, maxEdge, mode));
        if (still.isPresent()) {
            return still.get();
        }
        if (fallback != null) {
            return fallback.loadThumbnail(file, maxEdge, mode);
        }
        return MediaFacade.super.loadThumbnail(file, maxEdge, mode);
    }

    @Override
    public Metadata readMetadata(Path file) {
        // TwelveMonkeys' own metadata extraction is not wired here; the 12M path
        // (stills + animated GIF) reports an empty snapshot, while non-12M media
        // delegates to the fallback facade (e.g. FFmpeg's container tags).
        if (isAnimatedGif(file) || canReadStill(file)) {
            return Metadata.empty(file);
        }
        return fallback != null ? fallback.readMetadata(file) : Metadata.empty(file);
    }

    @Override
    public VideoStream openVideo(Path file) {
        if (isAnimatedGif(file)) {
            try {
                // GifVideoStream takes ownership of the GifFrames and closes it.
                return new GifVideoStream(GifFrames.open(file));
            } catch (IOException | RuntimeException e) {
                throw new MediaException("twelvemonkeys: cannot open GIF " + file.getFileName()
                        + ": " + e.getMessage(), e);
            }
        }
        if (fallback != null) {
            return fallback.openVideo(file);
        }
        throw new MediaException("twelvemonkeys: video playback is not supported "
                + "(only animated GIF, no fallback) for " + file.getFileName());
    }

    @Override
    public String nativeVersions() {
        String base = "TwelveMonkeys ImageIO (stills + animated GIF)";
        return fallback != null ? base + "; fallback " + fallback.nativeVersions() : base;
    }

    @Override
    public void close() {
        if (fallback != null) {
            fallback.close();
        }
    }

    // --- still images -------------------------------------------------------

    private MediaProbe stillProbe(Path file, ImageReader reader) throws IOException {
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        // Stored pixel dimensions (pre-orientation), matching the convention of
        // the other still backends; the decoded visual/thumbnail are oriented.
        return new MediaProbe(file, MediaKind.IMAGE, formatLabel(reader, file), fileSize(file),
                -1, -1, w, h, null, 0, null, -1, -1, null);
    }

    private VisualResult stillVisual(Path file, ImageReader reader) throws IOException {
        BufferedImage img = reader.read(0);
        RasterFrame frame = BufferedImageRaster.toRaster(img);
        frame = RasterFrames.applyExifOrientation(frame, ImageIoOrientation.read(reader, 0));
        return new VisualResult(stillProbe(file, reader), Optional.of(frame));
    }

    private Thumbnail stillThumbnail(Path file, ImageReader reader, int maxEdge, ThumbnailMode mode)
            throws IOException {
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        BufferedImage img = readSubsampled(reader, w, h, maxEdge);
        RasterFrame frame = BufferedImageRaster.toRaster(img);
        frame = RasterFrames.applyExifOrientation(frame, ImageIoOrientation.read(reader, 0));
        frame = Thumbnails.scale(frame, maxEdge, mode);
        return new Thumbnail(Optional.of(frame), MediaKind.IMAGE);
    }

    /**
     * Decodes image 0, using {@link ImageReadParam#setSourceSubsampling} to skip
     * source pixels for a cheap thumbnail when the image is much larger than
     * {@code maxEdge}. Readers that reject subsampling fall back to a full decode.
     */
    private static BufferedImage readSubsampled(ImageReader reader, int w, int h, int maxEdge)
            throws IOException {
        int sub = subsampling(w, h, maxEdge);
        if (sub > 1) {
            try {
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(sub, sub, 0, 0);
                return reader.read(0, param);
            } catch (IOException | RuntimeException ignore) {
                // Some readers don't support subsampling — fall back to full decode.
            }
        }
        return reader.read(0);
    }

    /**
     * Integer subsampling factor keeping the decoded longest edge &ge;
     * {@code maxEdge} (so the residual {@link Thumbnails#scale} is a downscale).
     * 1 (no subsampling) when {@code maxEdge <= 0} or the image already fits.
     */
    private static int subsampling(int w, int h, int maxEdge) {
        if (maxEdge <= 0) {
            return 1;
        }
        int longest = Math.max(w, h);
        return Math.max(1, longest / maxEdge);
    }

    /** Upper-cased ImageIO format name (e.g. {@code JPEG}, {@code PNG}), or the extension. */
    private static String formatLabel(ImageReader reader, Path file) {
        try {
            String name = reader.getFormatName();
            if (name != null && !name.isBlank()) {
                return name.toUpperCase(Locale.ROOT);
            }
        } catch (IOException | RuntimeException ignore) {
            // fall through to the extension
        }
        return extension(file);
    }

    // --- animated GIF -------------------------------------------------------

    /** Whether {@code file} is a multi-frame GIF the 12M path plays as video. */
    private static boolean isAnimatedGif(Path file) {
        return ImageSequences.isAnimatedImageSequence(file) && isGif(file);
    }

    private static boolean isGif(Path file) {
        return "GIF".equals(extension(file));
    }

    private MediaProbe gifProbe(Path file) {
        try (GifFrames frames = GifFrames.open(file)) {
            return gifProbe(file, frames);
        } catch (IOException | RuntimeException e) {
            throw new MediaException("twelvemonkeys: cannot probe GIF " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
    }

    private MediaProbe gifProbe(Path file, GifFrames frames) {
        long duration = totalDelay(frames);
        double frameRate = duration > 0
                ? frames.numFrames() / (duration / 1_000_000.0)
                : 0;
        return new MediaProbe(file, MediaKind.VIDEO, "GIF", fileSize(file),
                duration, -1, frames.width(), frames.height(),
                "gif", frameRate, null, -1, -1, null);
    }

    private VisualResult gifVisual(Path file) {
        try (GifFrames frames = GifFrames.open(file)) {
            BufferedImage poster = frames.frame(0);
            return new VisualResult(gifProbe(file, frames),
                    Optional.of(BufferedImageRaster.toRaster(poster)));
        } catch (IOException | RuntimeException e) {
            throw new MediaException("twelvemonkeys: cannot load visual of " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
    }

    private Thumbnail gifThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        try (GifFrames frames = GifFrames.open(file)) {
            BufferedImage poster = frames.frame(0);
            RasterFrame frame = Thumbnails.scale(BufferedImageRaster.toRaster(poster), maxEdge, mode);
            return new Thumbnail(Optional.of(frame), MediaKind.VIDEO);
        } catch (IOException | RuntimeException e) {
            throw new MediaException("twelvemonkeys: cannot thumbnail GIF " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
    }

    private static long totalDelay(GifFrames frames) {
        long total = 0;
        for (long d : frames.delayMicros()) {
            total += d;
        }
        return total;
    }

    // --- ImageIO reader plumbing -------------------------------------------

    /** A task run against a working {@link ImageReader} bound to image 0. */
    @FunctionalInterface
    private interface ReaderTask<T> {
        T run(ImageReader reader) throws IOException;
    }

    /** Whether any ImageIO reader can actually parse {@code file} as a still. */
    private boolean canReadStill(Path file) {
        return tryReaders(file, r -> {
            r.getWidth(0);
            return Boolean.TRUE;
        }).isPresent();
    }

    /**
     * Runs {@code task} against the first {@link ImageReader} that decodes
     * {@code file}, iterating all candidate readers so a CMYK/ICC JPEG that the
     * JDK reader rejects is recovered by the TwelveMonkeys reader. Returns empty
     * when no reader can parse the file (it is not a 12M-decodable still).
     */
    private <T> Optional<T> tryReaders(Path file, ReaderTask<T> task) {
        ImageInputStream iis;
        try {
            iis = ImageIO.createImageInputStream(file.toFile());
        } catch (IOException e) {
            return Optional.empty();
        }
        if (iis == null) {
            return Optional.empty();
        }
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            while (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    iis.seek(0);
                    reader.setInput(iis, false, false);
                    return Optional.of(task.run(reader));
                } catch (IOException | RuntimeException ignore) {
                    // This reader couldn't decode it — try the next candidate.
                } finally {
                    reader.dispose();
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        } finally {
            try {
                iis.close();
            } catch (IOException ignore) {
                // best effort
            }
        }
    }

    private static String extension(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(dot + 1).toUpperCase(Locale.ROOT) : "";
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
