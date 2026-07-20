package io.github.ghosthack.mediabrowser.media.apple;

import io.github.ghosthack.mediabrowser.media.ImageSequences;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.Metadata;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.VisualResult;

import io.github.ghosthack.panama.media.avfoundation.AVFoundation;
import io.github.ghosthack.panama.media.avfoundation.AudioInfo;
import io.github.ghosthack.panama.media.avfoundation.VideoInfo;
import io.github.ghosthack.panama.media.cgimage.ImageIO;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.PixelFormat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link MediaFacade} backed entirely by Apple's media stack through the
 * vendored Panama FFM bindings: still images via {@code ImageIO}/CoreGraphics,
 * and video + audio via AVFoundation (probe, first-frame, and frame-by-frame
 * playback). No FFmpeg/libvips dependency.
 *
 * <p>Images are recognized with ImageIO content-sniffing; audio/video with a
 * container-extension whitelist plus an AVFoundation track probe. An optional
 * {@code fallback} facade can be supplied to cover formats Apple cannot decode
 * (e.g. WebM/VP9); when {@code null} the facade is 100% Apple.</p>
 *
 * <p>Decoded rasters are always tightly packed BGRA, matching
 * {@link RasterFrame} and the GL presentation path. Not thread-safe; callers
 * serialize access (see {@code MediaService}).</p>
 */
public final class AppleMediaFacade implements MediaFacade {

    /** Containers that may carry a video track. */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "m4s", "mkv", "webm", "avi", "mov", "wmv", "flv", "mpg", "mpeg",
            "ts", "m2ts", "mts", "ogv", "3gp", "vob", "mxf", "nut", "rm");

    /** Audio-only containers. */
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
            "aiff", "aif", "ape", "mka", "ac3", "dts", "amr");

    /** Optional cover for formats Apple cannot decode; {@code null} = pure Apple. */
    private final MediaFacade fallback;

    public AppleMediaFacade(MediaFacade fallback) {
        if (!ImageIO.isAvailable() || !AVFoundation.isAvailable()) {
            throw new IllegalStateException(
                    "Apple media frameworks are unavailable (not macOS?)");
        }
        this.fallback = fallback;
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        if (sniffImage(file)) {
            // An animated AVIF/HEIC (moov track) reports VIDEO only when
            // AVFoundation can actually open it; an ordinary still (or one
            // AVFoundation declines) stays an IMAGE the ImageIO still renders, so
            // the viewer never offers a Play button that can only error.
            return Optional.of(ImageSequences.stillOrAnimationKind(file,
                    () -> videoInfo(file) != null));
        }
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                return Optional.of(MediaKind.VIDEO);
            }
        }
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            AudioInfo ai = audioInfo(file);
            if (ai != null && ai.hasAudio()) {
                return Optional.of(MediaKind.AUDIO);
            }
        }
        return fallback != null ? fallback.classify(file) : Optional.empty();
    }

    @Override
    public MediaProbe probe(Path file) {
        if (sniffImage(file)) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> avVideoProbe(file),
                    () -> imageProbe(file));
        }
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                return videoProbe(file, vi);
            }
        }
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            AudioInfo ai = audioInfo(file);
            if (ai != null && ai.hasAudio()) {
                return audioProbe(file, ai);
            }
        }
        if (fallback != null) {
            return fallback.probe(file);
        }
        throw new MediaException("apple: cannot probe " + file.getFileName());
    }

    @Override
    public VisualResult loadVisual(Path file) {
        if (sniffImage(file)) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> avVideoVisual(file),
                    () -> imageIoVisual(file));
        }
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                try (Arena arena = Arena.ofConfined()) {
                    DecodedImage<PixelFormat> frame =
                            AVFoundation.extractFrame(arena, file.toString(), 0);
                    return new VisualResult(videoProbe(file, vi), Optional.of(toRaster(frame)));
                }
            }
        }
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            AudioInfo ai = audioInfo(file);
            if (ai != null && ai.hasAudio()) {
                return new VisualResult(audioProbe(file, ai), Optional.empty()); // no cover art
            }
        }
        if (fallback != null) {
            return fallback.loadVisual(file);
        }
        throw new MediaException("apple: cannot load visual of " + file.getFileName());
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        // Stills: CGImageSourceCreateThumbnailAtIndex — reuses any embedded
        // EXIF/JPEG thumbnail, applies orientation, and reads straight from
        // the path without loading the whole file into the heap. ImageIO fits
        // within the box; FILL then centre-crops the square in Java.
        if (sniffImage(file)) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> avVideoThumbnail(file, maxEdge, mode),
                    () -> imageIoThumbnail(file, maxEdge, mode));
        }
        // Video posters: AVAssetImageGenerator with setMaximumSize: downscales
        // during decode (native, no full-size raster), seeking ~10% in to skip a
        // black/fade-in opening frame. FILL then centre-crops the square.
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                try {
                    return videoThumbnail(file, vi, maxEdge, mode);
                } catch (RuntimeException e) {
                    // best-effort: fall through to the interface default below
                }
            }
        }
        // Audio cover art (and any video the native path declined) fall back to
        // the interface default (full visual via AVFoundation + JVM scale/crop).
        return MediaFacade.super.loadThumbnail(file, maxEdge, mode);
    }

    // -- still (ImageIO) vs animated AVIF/HEIC (AVFoundation) ----------------
    //
    // For an animated AVIF/HEIC the ImageIO still sniff matches but only the
    // moov track plays. ImageSequences.decodeStillOrAnimation runs the av* path
    // and falls back to the imageIo* still when AVFoundation throws — e.g. it
    // declines the avis/hevs brand or lacks the codec. Like Media Foundation,
    // AVFoundation only enumerates real tracks, so there is no still-vs-track
    // stream to disambiguate.

    private VisualResult imageIoVisual(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> img = ImageIO.decodeFromPath(arena, file.toString());
            return new VisualResult(imageProbe(file), Optional.of(toRaster(img)));
        }
    }

    private Thumbnail imageIoThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> thumb =
                    ImageIO.decodeThumbnailFromPath(arena, file.toString(), maxEdge);
            RasterFrame frame = toRaster(thumb);
            if (mode == ThumbnailMode.FILL) frame = Thumbnails.fill(frame, maxEdge);
            return new Thumbnail(Optional.of(frame), MediaKind.IMAGE);
        }
    }

    private MediaProbe avVideoProbe(Path file) {
        return videoProbe(file, requireAnimationTrack(file));
    }

    private VisualResult avVideoVisual(Path file) {
        VideoInfo vi = requireAnimationTrack(file);
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame =
                    AVFoundation.extractFrame(arena, file.toString(), 0);
            return new VisualResult(videoProbe(file, vi), Optional.of(toRaster(frame)));
        } catch (RuntimeException e) {
            throw new MediaException("apple: cannot decode animation frame "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private Thumbnail avVideoThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        VideoInfo vi = requireAnimationTrack(file);
        try {
            return videoThumbnail(file, vi, maxEdge, mode);
        } catch (RuntimeException e) {
            throw new MediaException("apple: cannot thumbnail animation "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The AVFoundation animation track of an animated AVIF/HEIC, or a
     *  {@link MediaException} (so the still falls back) when there is none. */
    private VideoInfo requireAnimationTrack(Path file) {
        VideoInfo vi = videoInfo(file);
        if (vi == null || vi.width() <= 0 || vi.height() <= 0) {
            throw new MediaException("apple: no playable animation track in "
                    + file.getFileName());
        }
        return vi;
    }

    /**
     * Native video poster: extracts a frame ~10% into the asset, capped to an
     * aspect-preserving {@code maxEdge} box during decode. For {@link
     * ThumbnailMode#FILL} the native cap is enlarged so the short edge reaches
     * {@code maxEdge} before {@link Thumbnails#fill} centre-crops the square,
     * keeping the native downscale while still filling the tile.
     */
    private Thumbnail videoThumbnail(Path file, VideoInfo vi, int maxEdge, ThumbnailMode mode) {
        long durationMs = vi.durationMillis();
        long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
        int cap = maxEdge;
        if (mode == ThumbnailMode.FILL && maxEdge > 0) {
            int longEdge = Math.max(vi.width(), vi.height());
            int shortEdge = Math.min(vi.width(), vi.height());
            cap = (int) Math.ceil((double) maxEdge * longEdge / shortEdge);
        }
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame =
                    AVFoundation.extractFrame(arena, file.toString(), posterMs, cap);
            RasterFrame raster = Thumbnails.scale(toRaster(frame), maxEdge, mode);
            return new Thumbnail(Optional.of(raster), MediaKind.VIDEO);
        }
    }

    /**
     * Partial metadata (the §2.5 matrix's "Apple = partial"): stills expose
     * EXIF/TIFF/GPS via {@code ImageIO.readProperties} (no IPTC/XMP/PNG-text
     * — the vendored binding flattens only those three sub-dicts). AV metadata
     * needs an AVFoundation metadata-item binding not yet present, so it is
     * empty for v1; the panel degrades gracefully. Raw values, stringified and
     * capped by the model.
     */
    @Override
    public Metadata readMetadata(Path file) {
        if (sniffImage(file)) {
            return imageMetadata(file);
        }
        // AV metadata via AVMetadataItem is a later task; v1 returns empty
        // (or delegates to a fallback facade when one is configured).
        return fallback != null ? fallback.readMetadata(file) : Metadata.empty(file);
    }

    private Metadata imageMetadata(Path file) {
        Metadata.Builder out = new Metadata.Builder(file);
        try (ImageIO.ImageProperties p = ImageIO.readProperties(file.toString())) {
            out.add("Image", "width", Integer.toString(p.width()));
            out.add("Image", "height", Integer.toString(p.height()));
            out.add("Image", "orientation", Integer.toString(p.orientation()));
            if (p.bitDepth() != null) out.add("Image", "bitDepth", p.bitDepth().toString());
            if (p.colorModel() != null) out.add("Image", "colorModel", p.colorModel());
            if (p.hasAlpha() != null) out.add("Image", "hasAlpha", p.hasAlpha().toString());
            if (p.colorProfile() != null) out.add("Image", "colorProfile", p.colorProfile());
            addMap(out, "EXIF", p.exif());
            addMap(out, "TIFF", p.tiff());
            addMap(out, "GPS", p.gps());
        } catch (RuntimeException e) {
            throw new MediaException("apple: cannot read metadata of " + file.getFileName(), e);
        }
        return out.build();
    }

    /**
     * Emits each entry of a flattened ImageIO sub-dict into {@code group},
     * stringifying the typed value raw ({@link String#valueOf}); the model caps
     * any value (incl. huge {@code List}/{@code Map} stringifications). An empty
     * or null map adds no group.
     */
    private static void addMap(Metadata.Builder out, String group, Map<String, Object> map) {
        if (map == null) return;
        map.forEach((k, v) -> out.add(group, k, String.valueOf(v)));
    }

    @Override
    public VideoStream openVideo(Path file) {
        try {
            return new AppleVideoStream(AVFoundation.openVideo(file.toString()));
        } catch (RuntimeException e) {
            throw new MediaException("apple: cannot open video " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String nativeVersions() {
        String base = "Apple ImageIO + AVFoundation";
        return fallback != null ? base + "; fallback " + fallback.nativeVersions() : base;
    }

    @Override
    public void close() {
        if (fallback != null) fallback.close();
    }

    // -- track probes (each opens the asset once; value records, arena-free to return) --

    private static VideoInfo videoInfo(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            return AVFoundation.getVideoInfo(arena, file.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static AudioInfo audioInfo(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            return AVFoundation.getAudioInfo(arena, file.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // -- internals -----------------------------------------------------------

    /** Content-sniff whether ImageIO recognizes the file as a still image. */
    private static boolean sniffImage(Path file) {
        try {
            byte[] header = readHeader(file, 512);
            return header.length > 0 && ImageIO.canDecode(header, header.length);
        } catch (IOException e) {
            return false;
        }
    }

    private MediaProbe imageProbe(Path file) {
        long size = fileSize(file);
        try (ImageIO.ImageProperties p = ImageIO.readProperties(file.toString())) {
            String pixels = null;
            if (p.colorModel() != null) {
                pixels = p.colorModel()
                        + (p.bitDepth() != null ? " " + p.bitDepth() + "-bit" : "")
                        + (Boolean.TRUE.equals(p.hasAlpha()) ? ", alpha" : "");
            }
            return new MediaProbe(file, MediaKind.IMAGE,
                    extension(file).toUpperCase(Locale.ROOT), size,
                    -1, -1, p.width(), p.height(),
                    null, 0, null, -1, -1, pixels);
        } catch (RuntimeException e) {
            throw new MediaException("apple: cannot probe image " + file.getFileName(), e);
        }
    }

    private MediaProbe videoProbe(Path file, VideoInfo info) {
        long durationMicros = info.durationMillis() >= 0 ? info.durationMillis() * 1000L : -1;
        return new MediaProbe(file, MediaKind.VIDEO,
                extension(file).toUpperCase(Locale.ROOT), fileSize(file),
                durationMicros, -1, info.width(), info.height(),
                null, info.frameRate(), null, -1, -1, null);
    }

    private MediaProbe audioProbe(Path file, AudioInfo info) {
        long durationMicros = info.durationMillis() >= 0 ? info.durationMillis() * 1000L : -1;
        int sampleRate = info.sampleRate() > 0 ? (int) Math.round(info.sampleRate()) : -1;
        int channels = info.channels() > 0 ? info.channels() : -1;
        return new MediaProbe(file, MediaKind.AUDIO,
                extension(file).toUpperCase(Locale.ROOT), fileSize(file),
                durationMicros, -1, -1, -1,
                null, 0, info.codec(), sampleRate, channels, null);
    }

    /** Copies a vendored BGRA {@link DecodedImage} into a tightly packed {@link RasterFrame}. */
    private static RasterFrame toRaster(DecodedImage<PixelFormat> img) {
        int w = img.width();
        int h = img.height();
        int stride = img.stride();
        int rowBytes = w * 4;
        byte[] dst = new byte[w * h * 4];
        MemorySegment src = img.pixels();
        if (stride == rowBytes) {
            MemorySegment.copy(src, ValueLayout.JAVA_BYTE, 0, dst, 0, dst.length);
        } else {
            for (int y = 0; y < h; y++) {
                MemorySegment.copy(src, ValueLayout.JAVA_BYTE, (long) y * stride,
                        dst, y * rowBytes, rowBytes);
            }
        }
        return new RasterFrame(w, h, dst);
    }

    private static byte[] readHeader(Path file, int n) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(n);
        }
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
