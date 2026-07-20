package io.github.ghosthack.mediabrowser.media.jcodec;

import io.github.ghosthack.mediabrowser.media.BufferedImageRaster;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.VisualResult;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Size;
import org.jcodec.scale.AWTUtil;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@link MediaFacade} backed by jcodec's pure-Java {@link FrameGrab}. It is
 * <b>video-only</b>: it always sits behind the TwelveMonkeys still facade for
 * the {@code TWELVEMONKEYS_JCODEC} backend (plan §1, §3.3), so stills never
 * reach it. It decodes the codecs jcodec supports in pure Java —
 * <b>H.264 / MPEG / ProRes</b> in MP4/MOV/MKV — and declines everything else so
 * unsupported inputs fall through to "unsupported" cleanly.
 *
 * <p>Every visual (video poster, thumbnail) is decoded through {@link FrameGrab}
 * → jcodec {@link org.jcodec.common.model.Picture} → {@link BufferedImage} (via
 * jcodec-javase's {@link AWTUtil}) → tightly packed straight-alpha BGRA
 * ({@link BufferedImageRaster}), matching {@link RasterFrame} and the GL/JavaFX
 * presentation path. Playback opens a {@link JcodecVideoStream}.</p>
 *
 * <p>Not thread-safe; callers serialize access (see {@code MediaService}). Each
 * call opens its own short-lived grabber over a fresh seekable channel.
 * Self-contained: imports only {@code io.github.ghosthack.mediabrowser.media.*},
 * {@code org.jcodec.*}, AWT and the JDK — never {@code media.pure.*} or
 * {@code io.github.ghosthack.*}.</p>
 */
public final class JcodecMediaFacade implements MediaFacade {

    /** Codecs jcodec decodes in pure Java (H.264 / MPEG / ProRes). */
    private static final Set<Codec> SUPPORTED = Set.of(Codec.H264, Codec.MPEG2, Codec.PRORES);

    public JcodecMediaFacade() {
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            return withGrab(file, g -> supportedVideo(g)
                    ? Optional.of(MediaKind.VIDEO)
                    : Optional.<MediaKind>empty());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public MediaProbe probe(Path file) {
        return withGrab(file, g -> {
            if (!supportedVideo(g)) {
                throw new MediaException("jcodec: unsupported or non-video file: "
                        + file.getFileName());
            }
            return probeFrom(file, g);
        });
    }

    @Override
    public VisualResult loadVisual(Path file) {
        return withGrab(file, g -> {
            if (!supportedVideo(g)) {
                throw new MediaException("jcodec: unsupported or non-video file: "
                        + file.getFileName());
            }
            MediaProbe probe = probeFrom(file, g);
            return new VisualResult(probe, Optional.of(decodePoster(g, file)));
        });
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        return withGrab(file, g -> {
            if (!supportedVideo(g)) {
                throw new MediaException("jcodec: unsupported or non-video file: "
                        + file.getFileName());
            }
            RasterFrame frame = Thumbnails.scale(decodePoster(g, file), maxEdge, mode);
            return new Thumbnail(Optional.of(frame), MediaKind.VIDEO);
        });
    }

    @Override
    public VideoStream openVideo(Path file) {
        return new JcodecVideoStream(file);
    }

    @Override
    public String nativeVersions() {
        return "jcodec 0.2.5 (pure Java: H.264/MPEG/ProRes)";
    }

    @Override
    public void close() {
        // Stateless: each call opens and releases its own grabber.
    }

    // --- internals ----------------------------------------------------------

    /** A task run against an open {@link FrameGrab}. */
    @FunctionalInterface
    private interface GrabTask<T> {
        T run(FrameGrab grab) throws IOException, JCodecException;
    }

    /**
     * Opens a {@link FrameGrab} over {@code file} through a fresh seekable
     * channel, runs {@code task}, and always closes the channel. Wraps any
     * failure as a {@link MediaException}.
     */
    private <T> T withGrab(Path file, GrabTask<T> task) {
        FileChannelWrapper ch = null;
        try {
            ch = NIOUtils.readableChannel(file.toFile());
            FrameGrab g = FrameGrab.createFrameGrab(ch);
            return task.run(g);
        } catch (MediaException e) {
            throw e;
        } catch (IOException | JCodecException | RuntimeException e) {
            throw new MediaException("jcodec: cannot read " + file.getFileName()
                    + ": " + e.getMessage(), e);
        } finally {
            NIOUtils.closeQuietly(ch);
        }
    }

    /** Whether the grabber's video track carries a codec jcodec can decode. */
    private static boolean supportedVideo(FrameGrab g) {
        Codec codec = videoCodec(g);
        return codec != null && SUPPORTED.contains(codec);
    }

    private static Codec videoCodec(FrameGrab g) {
        SeekableDemuxerTrack track = g.getVideoTrack();
        if (track == null) {
            return null;
        }
        DemuxerTrackMeta meta = track.getMeta();
        return meta == null ? null : meta.getCodec();
    }

    /**
     * Decodes the first picture of {@code file} into a tightly packed BGRA
     * {@link RasterFrame} (the poster); throws if there is no decodable frame.
     */
    private static RasterFrame decodePoster(FrameGrab g, Path file)
            throws IOException {
        PictureWithMetadata first = g.getNativeFrameWithMetadata();
        if (first == null || first.getPicture() == null) {
            throw new MediaException("jcodec: no decodable frame in " + file.getFileName());
        }
        BufferedImage img = AWTUtil.toBufferedImage(first.getPicture());
        if (img == null) {
            throw new MediaException("jcodec: cannot convert frame of " + file.getFileName());
        }
        return BufferedImageRaster.toRaster(img);
    }

    private static MediaProbe probeFrom(Path file, FrameGrab g) {
        Size dim = g.getMediaInfo().getDim();
        int width = dim != null ? dim.getWidth() : -1;
        int height = dim != null ? dim.getHeight() : -1;

        long duration = -1;
        double frameRate = 0;
        Codec codec = null;
        SeekableDemuxerTrack track = g.getVideoTrack();
        if (track != null) {
            DemuxerTrackMeta meta = track.getMeta();
            if (meta != null) {
                codec = meta.getCodec();
                double durSec = meta.getTotalDuration();
                if (durSec > 0) {
                    duration = Math.round(durSec * 1_000_000.0);
                    if (meta.getTotalFrames() > 0) {
                        frameRate = meta.getTotalFrames() / durSec;
                    }
                }
            }
        }

        return new MediaProbe(file, MediaKind.VIDEO, container(file), fileSize(file),
                duration, -1, width, height,
                codec != null ? codec.name().toLowerCase(Locale.ROOT) : null,
                frameRate, null, -1, -1, null);
    }

    private static String container(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return null;
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(dot + 1).toUpperCase(Locale.ROOT) : null;
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
