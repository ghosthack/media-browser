package io.github.ghosthack.mediabrowser.media.javacv;

import io.github.ghosthack.mediabrowser.media.BufferedImageRaster;
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
import io.github.ghosthack.mediabrowser.media.VideoRotation;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.VisualResult;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MediaFacade} backed by JavaCV's {@link FFmpegFrameGrabber} (bundled
 * FFmpeg). It handles <b>both</b> still images and video (and audio-only files,
 * classified {@link MediaKind#AUDIO} with no playback), so it works as the solo
 * {@code JAVACV} backend <em>and</em> as the video fallback behind the
 * TwelveMonkeys still facade for the {@code TWELVEMONKEYS_JAVACV} backend
 * (plan §1, §3.2).
 *
 * <p>Every visual (still pixels, video/GIF poster, thumbnail) is decoded through
 * the grabber, converted to a {@link BufferedImage} by {@link Java2DFrameConverter}
 * and written as tightly packed straight-alpha BGRA via
 * {@link BufferedImageRaster}, matching {@link RasterFrame} and the GL/JavaFX
 * presentation path. Playback opens a {@link JavaCvVideoStream}.</p>
 *
 * <p><b>Still vs. video.</b> FFmpeg demuxes a still image as a one-frame video
 * stream, so {@link #classify} treats a video stream with no audio, a single
 * frame and no duration as {@link MediaKind#IMAGE}; anything with more frames or
 * a real duration is {@link MediaKind#VIDEO}; an audio-only file is
 * {@link MediaKind#AUDIO}; otherwise empty. This is a heuristic adequate for the
 * evaluation backend.</p>
 *
 * <p>Not thread-safe; callers serialize access (see {@code MediaService}). Each
 * call opens its own short-lived grabber, so concurrent thumbnail opens are
 * independent. Self-contained: imports only
 * {@code io.github.ghosthack.mediabrowser.media.*}, {@code org.bytedeco.*}, AWT and the
 * JDK — never {@code media.pure.*} or {@code io.github.ghosthack.*}.</p>
 */
public final class JavaCvMediaFacade implements MediaFacade {

    public JavaCvMediaFacade() {
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            return withGrabber(file, g -> Optional.ofNullable(kindOf(g)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public MediaProbe probe(Path file) {
        return withGrabber(file, g -> {
            MediaKind kind = kindOf(g);
            if (kind == null) {
                throw new MediaException("javacv: not a media file: " + file.getFileName());
            }
            return probeFrom(file, g, kind);
        });
    }

    @Override
    public VisualResult loadVisual(Path file) {
        return withGrabber(file, g -> {
            MediaKind kind = kindOf(g);
            if (kind == null) {
                throw new MediaException("javacv: not a media file: " + file.getFileName());
            }
            MediaProbe probe = probeFrom(file, g, kind);
            if (kind == MediaKind.AUDIO) {
                return new VisualResult(probe, Optional.empty());
            }
            return new VisualResult(probe, Optional.of(decodePoster(g, file)));
        });
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        return withGrabber(file, g -> {
            MediaKind kind = kindOf(g);
            if (kind == null) {
                throw new MediaException("javacv: not a media file: " + file.getFileName());
            }
            if (kind == MediaKind.AUDIO) {
                return Thumbnail.empty(MediaKind.AUDIO);
            }
            RasterFrame frame = Thumbnails.scale(decodePoster(g, file), maxEdge, mode);
            return new Thumbnail(Optional.of(frame), kind);
        });
    }

    @Override
    public Metadata readMetadata(Path file) {
        return withGrabber(file, g -> {
            Metadata.Builder out = new Metadata.Builder(file);
            addMap(out, "Container", g.getMetadata());
            return out.build();
        });
    }

    @Override
    public VideoStream openVideo(Path file) {
        return new JavaCvVideoStream(file);
    }

    @Override
    public String nativeVersions() {
        try {
            return "JavaCV (bundled FFmpeg " + avutil.av_version_info().getString() + ")";
        } catch (RuntimeException | LinkageError e) {
            return "JavaCV (bundled FFmpeg)";
        }
    }

    @Override
    public void close() {
        // Stateless: each call opens and releases its own grabber.
    }

    // --- internals ----------------------------------------------------------

    /** A task run against a started {@link FFmpegFrameGrabber}. */
    @FunctionalInterface
    private interface GrabberTask<T> {
        T run(FFmpegFrameGrabber grabber) throws IOException;
    }

    /**
     * Opens and starts a grabber over {@code file}, runs {@code task}, and always
     * releases the grabber. Wraps any failure as a {@link MediaException}.
     */
    private <T> T withGrabber(Path file, GrabberTask<T> task) {
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(file.toString());
        try {
            g.start();
            return task.run(g);
        } catch (MediaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MediaException("javacv: cannot read " + file.getFileName()
                    + ": " + e.getMessage(), e);
        } finally {
            try {
                g.stop();
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
            try {
                g.release();
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
        }
    }

    /**
     * Classifies a started grabber: a one-frame, no-audio, no-duration video
     * stream is an {@link MediaKind#IMAGE} (FFmpeg demuxes stills as a one-frame
     * video); any richer video stream is {@link MediaKind#VIDEO}; an audio-only
     * file is {@link MediaKind#AUDIO}; otherwise {@code null}.
     */
    private static MediaKind kindOf(FFmpegFrameGrabber g) {
        boolean audio = g.hasAudio();
        if (g.hasVideo()) {
            int frames = g.getLengthInVideoFrames();
            long len = g.getLengthInTime();
            boolean still = !audio && frames <= 1 && len <= 0;
            return still ? MediaKind.IMAGE : MediaKind.VIDEO;
        }
        return audio ? MediaKind.AUDIO : null;
    }

    /**
     * Decodes the first image frame of {@code file} into a tightly packed BGRA
     * {@link RasterFrame} (the poster); throws if there is no decodable frame.
     * The conversion happens while the grabber/converter are still open, so the
     * returned frame is independent of their native buffers.
     */
    private static RasterFrame decodePoster(FFmpegFrameGrabber g, Path file) throws IOException {
        try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
            Frame frame = g.grabImage();
            if (frame == null) {
                throw new MediaException("javacv: no decodable frame in " + file.getFileName());
            }
            BufferedImage img = converter.convert(frame);
            if (img == null) {
                throw new MediaException("javacv: cannot convert frame of " + file.getFileName());
            }
            // Bake the container/display rotation so the poster is upright, as
            // the Apple (AVFoundation) backend's frames are.
            return RasterFrames.rotateCw(BufferedImageRaster.toRaster(img), quarterTurns(g));
        }
    }

    /** Clockwise quarter-turns (0..3) for the file's display rotation; 0 if unknown. */
    private static int quarterTurns(FFmpegFrameGrabber g) {
        try {
            return VideoRotation.quarterTurnsCw(g.getDisplayRotation());
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private MediaProbe probeFrom(Path file, FFmpegFrameGrabber g, MediaKind kind) {
        String container = container(g, file);
        long size = fileSize(file);
        long len = g.getLengthInTime();
        long duration = len > 0 ? len : -1;
        // Report display (post-rotation) dimensions: a 90°/270° rotation swaps
        // them, matching the upright poster/playback frames.
        boolean swap = (quarterTurns(g) & 1) != 0;
        int w = swap ? g.getImageHeight() : g.getImageWidth();
        int h = swap ? g.getImageWidth() : g.getImageHeight();
        return switch (kind) {
            case IMAGE -> new MediaProbe(file, MediaKind.IMAGE, container, size,
                    -1, -1, w, h,
                    null, 0, null, -1, -1, null);
            case AUDIO -> new MediaProbe(file, MediaKind.AUDIO, container, size,
                    duration, -1, -1, -1,
                    null, 0, nullIfBlank(g.getAudioCodecName()),
                    positiveOr(g.getSampleRate()), positiveOr(g.getAudioChannels()), null);
            case VIDEO -> new MediaProbe(file, MediaKind.VIDEO, container, size,
                    duration, -1, w, h,
                    nullIfBlank(g.getVideoCodecName()), g.getFrameRate(),
                    nullIfBlank(g.getAudioCodecName()),
                    positiveOr(g.getSampleRate()), positiveOr(g.getAudioChannels()), null);
        };
    }

    /** Upper-cased FFmpeg demuxer name (e.g. {@code MOV,MP4,...}), or the extension. */
    private static String container(FFmpegFrameGrabber g, Path file) {
        String format = g.getFormat();
        if (format != null && !format.isBlank()) {
            return format.toUpperCase(Locale.ROOT);
        }
        return extension(file);
    }

    private static void addMap(Metadata.Builder out, String group, Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        map.forEach((k, v) -> out.add(group, k, v));
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static int positiveOr(int v) {
        return v > 0 ? v : -1;
    }

    private static String extension(Path file) {
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
