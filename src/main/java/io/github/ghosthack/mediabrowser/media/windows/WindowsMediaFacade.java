package io.github.ghosthack.mediabrowser.media.windows;

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

import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.Dimensions;
import io.github.ghosthack.panama.media.core.PixelFormat;
import io.github.ghosthack.panama.media.core.Platform;
import io.github.ghosthack.panama.media.mediafoundation.AudioInfo;
import io.github.ghosthack.panama.media.mediafoundation.MediaFoundation;
import io.github.ghosthack.panama.media.mediafoundation.VideoInfo;
import io.github.ghosthack.panama.media.wic.WIC;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MediaFacade} backed entirely by Windows 11 native media frameworks,
 * with <b>no FFmpeg/libvips/pure fallback</b>: every method either succeeds
 * through a Windows native API or throws {@link MediaException}.
 *
 * <p>Mirrors {@code AppleMediaFacade}'s split, with the Windows equivalents:</p>
 * <ul>
 *   <li><b>Stills</b> (decode, embedded thumbnails, EXIF/XMP/IPTC/GPS metadata,
 *       orientation, multi-frame counts) via the <b>Windows Imaging Component
 *       (WIC)</b> — {@code IWICImagingFactory} / {@code IWICBitmapDecoder} /
 *       {@code IWICMetadataQueryReader}.</li>
 *   <li><b>Video + audio</b> (probe, poster frame, frame-by-frame playback) via
 *       <b>Media Foundation</b> — {@code IMFSourceReader} with the built-in
 *       color converter producing BGRA.</li>
 * </ul>
 *
 * <p>All native interfaces here are COM (vtable dispatch); the binding layer
 * lives in {@code io.github.ghosthack.panama.media.{comruntime,wic,mediafoundation}}
 * and pins COM (MTA) + MFStartup process-wide, so the facade just calls them.
 * Decoded rasters are tightly packed BGRA to match {@link RasterFrame} and the
 * GL presentation path. Not thread-safe; callers serialize access (see
 * {@code MediaService}).</p>
 *
 * <p><b>Status:</b> Phases 1–4 are implemented — images classify, probe,
 * decode, and thumbnail through WIC; video files classify, probe (w/h/duration/
 * fps), produce a poster frame, and play back frame-by-frame via Media
 * Foundation ({@link #openVideo}); audio-only files classify and probe (codec/
 * sample rate/channels/duration) via Media Foundation. Cover art is not
 * surfaced (audio yields an empty visual, matching {@code AppleMediaFacade}).
 * Still metadata ({@link #readMetadata}) is enumerated through WIC
 * ({@code IWICMetadataQueryReader}); AV metadata is deferred (degrades to an
 * empty snapshot, matching {@code AppleMediaFacade}).</p>
 */
public final class WindowsMediaFacade implements MediaFacade {

    /** Containers that may carry a video track. */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "m4s", "mkv", "webm", "avi", "mov", "wmv", "flv", "mpg", "mpeg",
            "ts", "m2ts", "mts", "ogv", "3gp", "vob", "mxf", "nut", "rm");

    /** Audio-only containers (copied from {@code AppleMediaFacade}). */
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
            "aiff", "aif", "ape", "mka", "ac3", "dts", "amr");

    public WindowsMediaFacade() {
        if (!Platform.IS_WINDOWS) {
            throw new IllegalStateException(
                    "Windows native media frameworks are unavailable (not Windows?)");
        }
        if (!WIC.isAvailable()) {
            throw new IllegalStateException(
                    "Windows Imaging Component (windowscodecs) failed to load");
        }
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        // Image sniff FIRST so an MP4 carrying an image-like header still goes
        // the right way; audio classification lands in Phase 4. An animated
        // AVIF/HEIC (moov track) classifies as VIDEO only when Media Foundation
        // can actually demux it (it rejects many avis-brand containers); else it
        // stays an IMAGE the WIC still path renders, so the viewer never offers a
        // Play button that can only error.
        if (sniffImage(file)) {
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
        // Audio comes LAST so a video container with an audio track stays VIDEO
        // (handled above). A video-extension file with no video track also falls
        // through here, so an audio-only .mp4/.mov still classifies as AUDIO.
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            AudioInfo ai = audioInfo(file);
            if (ai != null && ai.hasAudio()) {
                return Optional.of(MediaKind.AUDIO);
            }
        }
        return Optional.empty();
    }

    @Override
    public MediaProbe probe(Path file) {
        if (sniffImage(file)) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> mfVideoProbe(file),
                    () -> imageProbe(file));
        }
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                // Phase 6 (merged AV probe): a single probe() reads BOTH the
                // video track and, best-effort, the first audio track so a
                // video-with-sound reports audioCodec/sampleRate/channels on
                // the SAME MediaProbe. audioInfo() swallows any failure to null,
                // so a missing/undecodable audio track leaves those fields at
                // the unknown sentinels and never changes the VIDEO result.
                return videoProbe(file, vi, audioInfo(file));
            }
        }
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            AudioInfo ai = audioInfo(file);
            if (ai != null && ai.hasAudio()) {
                return audioProbe(file, ai);
            }
        }
        throw new MediaException("windows-native: cannot probe " + file.getFileName());
    }

    @Override
    public VisualResult loadVisual(Path file) {
        if (sniffImage(file)) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> mfVideoVisual(file),
                    () -> wicVisual(file));
        }
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                // Poster ~10% in skips a black/fade-in opening frame. Keep the
                // confined Arena open until toRaster copies the pixels out, and
                // wrap any DecodeException so it never leaks past the facade.
                long durationMs = vi.durationMillis();
                long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
                try (Arena arena = Arena.ofConfined()) {
                    DecodedImage<PixelFormat> frame =
                            MediaFoundation.extractFrame(arena, file.toString(), posterMs);
                    return new VisualResult(videoProbe(file, vi), Optional.of(toRaster(frame)));
                } catch (RuntimeException e) {
                    throw new MediaException("windows-native: cannot decode video frame "
                            + file.getFileName() + ": " + e.getMessage(), e);
                }
            }
        }
        // Audio: no cover art in v1 (matches AppleMediaFacade) -> empty visual.
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            AudioInfo ai = audioInfo(file);
            if (ai != null && ai.hasAudio()) {
                return new VisualResult(audioProbe(file, ai), Optional.empty());
            }
        }
        throw new MediaException("windows-native: cannot load visual of " + file.getFileName());
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        // Stills: WIC source-scales during decode (embedded-thumbnail fast path
        // + EXIF orientation baked in), fitting the box; FILL then centre-crops
        // the square in Java. Mirrors the Apple stills path exactly.
        if (sniffImage(file)) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> mfVideoThumbnail(file, maxEdge, mode),
                    () -> wicThumbnail(file, maxEdge, mode));
        }
        // Video posters: extract the poster ~10% in, asking Media Foundation to
        // DOWNSCALE during decode so the longer edge fits maxEdge (Phase 6 native
        // poster downscale — mirrors AVAssetImageGenerator setMaximumSize:). The
        // native hint degrades gracefully (a decoder that rejects the size yields
        // a full-size frame), and the JVM-side Thumbnails.scale still finishes the
        // exact box / FILL centre-crop. Only loadThumbnail passes maxEdge;
        // loadVisual keeps the full-size poster.
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                long durationMs = vi.durationMillis();
                long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
                try (Arena arena = Arena.ofConfined()) {
                    DecodedImage<PixelFormat> frame =
                            MediaFoundation.extractFrame(arena, file.toString(), posterMs, maxEdge);
                    RasterFrame raster = Thumbnails.scale(toRaster(frame), maxEdge, mode);
                    return new Thumbnail(Optional.of(raster), MediaKind.VIDEO);
                } catch (RuntimeException e) {
                    throw new MediaException("windows-native: cannot thumbnail video "
                            + file.getFileName() + ": " + e.getMessage(), e);
                }
            }
        }
        // Audio: no cover art in v1 -> empty thumbnail tagged AUDIO so the
        // mosaic draws its AUD placeholder.
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            AudioInfo ai = audioInfo(file);
            if (ai != null && ai.hasAudio()) {
                return Thumbnail.empty(MediaKind.AUDIO);
            }
        }
        throw new MediaException("windows-native: cannot thumbnail " + file.getFileName());
    }

    // -- still (WIC) vs animated AVIF/HEIC (Media Foundation) ----------------
    //
    // For an animated AVIF/HEIC the WIC still sniff matches but only the moov
    // track plays. ImageSequences.decodeStillOrAnimation runs the mf* path and
    // falls back to the wic* still when MF throws — e.g. its MP4 source rejects
    // the avis/hevs brand or the AV1/HEVC Store codec MFT is missing. Unlike
    // FFmpeg, MF never exposes the still primary item as a track, so there is no
    // still-vs-track stream to disambiguate here.

    private VisualResult wicVisual(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> img = WIC.decodeFromPath(arena, file.toString());
            return new VisualResult(imageProbe(file), Optional.of(toRaster(img)));
        } catch (RuntimeException e) {
            throw new MediaException("windows-native: cannot decode image "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private Thumbnail wicThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> thumb =
                    WIC.decodeThumbnailFromPath(arena, file.toString(), maxEdge);
            RasterFrame frame = toRaster(thumb);
            if (mode == ThumbnailMode.FILL) frame = Thumbnails.fill(frame, maxEdge);
            return new Thumbnail(Optional.of(frame), MediaKind.IMAGE);
        } catch (RuntimeException e) {
            throw new MediaException("windows-native: cannot thumbnail image "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private MediaProbe mfVideoProbe(Path file) {
        VideoInfo vi = requireAnimationTrack(file);
        return videoProbe(file, vi, audioInfo(file));
    }

    private VisualResult mfVideoVisual(Path file) {
        VideoInfo vi = requireAnimationTrack(file);
        long durationMs = vi.durationMillis();
        long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame =
                    MediaFoundation.extractFrame(arena, file.toString(), posterMs);
            return new VisualResult(videoProbe(file, vi, audioInfo(file)),
                    Optional.of(toRaster(frame)));
        } catch (RuntimeException e) {
            throw new MediaException("windows-native: cannot decode animation frame "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private Thumbnail mfVideoThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        VideoInfo vi = requireAnimationTrack(file);
        long durationMs = vi.durationMillis();
        long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame =
                    MediaFoundation.extractFrame(arena, file.toString(), posterMs, maxEdge);
            RasterFrame raster = Thumbnails.scale(toRaster(frame), maxEdge, mode);
            return new Thumbnail(Optional.of(raster), MediaKind.VIDEO);
        } catch (RuntimeException e) {
            throw new MediaException("windows-native: cannot thumbnail animation "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The MF animation track of an animated AVIF/HEIC, or a {@link MediaException}
     *  (so the still falls back) when Media Foundation exposes no playable track. */
    private VideoInfo requireAnimationTrack(Path file) {
        VideoInfo vi = videoInfo(file);
        if (vi == null || vi.width() <= 0 || vi.height() <= 0) {
            throw new MediaException("windows-native: no playable animation track in "
                    + file.getFileName());
        }
        return vi;
    }

    @Override
    public Metadata readMetadata(Path file) {
        // On-demand ONLY (never called from probe/decode). Stills: enumerate the
        // full EXIF/GPS/XMP/IPTC tree through WIC and bucket each query path into
        // a display group. AV: deferred -> degrade to an empty snapshot (the
        // panel shows "no metadata"), matching AppleMediaFacade's "AV metadata is
        // a later task" rather than throwing.
        if (sniffImage(file)) {
            return stillMetadata(file);
        }
        return Metadata.empty(file);
    }

    /** The {@code "<binary, N bytes>"} placeholder WIC emits for blob/byte-vector values. */
    private static final Pattern BINARY_PLACEHOLDER = Pattern.compile("<binary, (\\d+) bytes>");

    /**
     * Thin native wrapper: reads the flat WIC metadata query map for a still and
     * hands it to the pure {@link #buildStillMetadata} transform. Keeps the
     * native {@code WIC.readMetadata} call (and its failure -> {@link MediaException}
     * translation) isolated from the map -> {@link Metadata} decision logic so the
     * latter is unit-testable on any OS.
     */
    private Metadata stillMetadata(Path file) {
        Map<String, String> raw;
        try {
            raw = WIC.readMetadata(file.toString());
        } catch (RuntimeException e) {
            throw new MediaException("windows-native: cannot read metadata of "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
        return buildStillMetadata(file, raw);
    }

    /**
     * Builds a {@link Metadata} snapshot for a still from the flat WIC query map
     * ({@code query-path -> stringified value}). Each path is bucketed into a
     * display group ({@code Image}/{@code EXIF}/{@code GPS}/{@code XMP}/{@code IPTC});
     * a {@code "<binary, N bytes>"} value becomes an {@code addBinary} entry (so
     * the panel italicises it and never shows a hex dump), everything else a
     * capped text entry. The WIC binding already caps strings and never copies
     * blob payloads onto the heap.
     *
     * <p>Pure (no native calls) and package-private/static so the map -> Metadata
     * decision logic — binary-placeholder routing and {@link #groupFor} grouping —
     * is covered deterministically under {@code mvn test} on any OS.</p>
     */
    static Metadata buildStillMetadata(Path file, Map<String, String> raw) {
        Metadata.Builder out = new Metadata.Builder(file);
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            String group = groupFor(key);
            Matcher m = BINARY_PLACEHOLDER.matcher(value);
            if (m.matches()) {
                out.addBinary(group, key, Long.parseLong(m.group(1)));
            } else {
                out.add(group, key, value);
            }
        }
        return out.build();
    }

    /**
     * Buckets a raw WIC metadata query path into a display group. Purely
     * prefix/substring based (no key humanizing). WIC's enumerator addresses IFDs
     * either by name ({@code /app1/ifd/gps/…}) or by tag number
     * ({@code /app1/{ushort=0}/{ushort=34853}/…}); both schemes are matched, and
     * GPS/XMP/IPTC win over the generic EXIF/Image buckets (the GPS query path
     * also contains {@code /app1}, so GPS is tested first).
     */
    static String groupFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.contains("/gps") || p.contains("{ushort=34853}")) return "GPS";
        if (p.contains("xmp")) return "XMP";
        if (p.contains("iptc")) return "IPTC";
        if (p.contains("/exif/") || p.contains("{ushort=34665}")
                || p.contains("/app1") || p.contains("/ifd")) return "EXIF";
        return "Image";
    }

    @Override
    public VideoStream openVideo(Path file) {
        // Media Foundation sequential BGRA pull (manual MFT pipeline kept open
        // across frames) -> WindowsVideoStream. Wrap any native failure (e.g. a
        // missing Store codec surfacing as DecodeException) in MediaException so
        // it never leaks past the facade.
        try {
            return new WindowsVideoStream(MediaFoundation.openVideo(file.toString()));
        } catch (RuntimeException e) {
            throw new MediaException("windows-native: cannot open video "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String nativeVersions() {
        String base = "Windows WIC (" + (WIC.isAvailable() ? "available" : "unavailable")
                + ") + Media Foundation ("
                + (MediaFoundation.isAvailable() ? "available" : "unavailable") + ")";
        String highlight = mfDiagnosticsHighlight();
        return highlight == null ? base : base + " \u2014 " + highlight;
    }

    /**
     * Condenses the heavyweight {@link MediaFoundation#diagnose()} multi-line
     * report into a single short headline for the About dialog / SmokeTest
     * header: {@code "MF: all checks passed"} when nothing is wrong, otherwise
     * the first problem line (codec/MFT/registry issue) trimmed to one line.
     *
     * <p>Best-effort: any failure (or a non-Windows {@code NOT_WINDOWS} report)
     * returns {@code null} so the caller keeps the bare availability string and
     * the About dialog can never break or throw.
     */
    private static String mfDiagnosticsHighlight() {
        try {
            return diagnosticsHighlight(MediaFoundation.diagnose());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Pure transform behind {@link #mfDiagnosticsHighlight()}: condenses an
     * already-fetched {@code diagnose()} report into the single headline.
     * {@code null} for a null/blank report, {@code "MF: all checks passed"} when
     * no line satisfies {@link #isMfProblemLine}, otherwise {@code "MF: "} plus
     * the first problem line trimmed to 80 chars. Contains no native calls so it
     * is unit-testable on any OS.
     */
    static String diagnosticsHighlight(String report) {
        if (report == null || report.isBlank()) {
            return null;
        }
        for (String raw : report.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty() && isMfProblemLine(line)) {
                return "MF: " + truncate(line, 80);
            }
        }
        return "MF: all checks passed";
    }

    /**
     * True when a {@code diagnose()} line signals a problem rather than an OK
     * status. The healthy lines are {@code "Platform: Windows"},
     * {@code "DLLs: OK"}, {@code "MFStartup: OK"}, {@code "H264 Decoder MFT: OK"}
     * and {@code "All Media Foundation checks passed."}; everything else carrying
     * an uppercase FAILED/MISSING/FAILURE/ERROR marker (or {@code NOT_WINDOWS})
     * is a problem.
     */
    static boolean isMfProblemLine(String line) {
        if (line.endsWith(": OK") || line.equals("Platform: Windows")
                || line.startsWith("All Media Foundation checks passed")) {
            return false;
        }
        return line.startsWith("NOT_WINDOWS") || line.contains("FAILED")
                || line.contains("MISSING") || line.contains("FAILURE")
                || line.contains("ERROR");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    @Override
    public void close() {
        // The WIC factory and COM/MF runtime are pinned process-wide by the
        // bindings; nothing per-facade to release.
    }

    // -- internals -----------------------------------------------------------

    /**
     * Content-sniff whether WIC can decode the file as a still image, probing
     * the <b>real file</b> ({@code WIC.getSize} → {@code CreateDecoderFromFilename}).
     *
     * <p>An in-memory sniff of just the first ~4 KB ({@code WIC.canDecode}) gives
     * false negatives for valid images whose decodable header lies past that
     * window: JPEGs with a large EXIF/ICC {@code APP} segment ahead of the
     * {@code SOF} marker fail with {@code WINCODEC_ERR_BADHEADER (0x88982f61)} and
     * make WIC's JPEG codec log "JPEG datastream contains no image" on the
     * truncated buffer; some PNGs even fail with
     * {@code WINCODEC_ERR_UNKNOWNIMAGEFORMAT (0x88982f07)}. Reading the first 4 KB
     * already opens the file, so the in-memory shortcut saves nothing here, and a
     * full-file probe matches the {@code loadVisual}/{@code loadThumbnail} decode
     * path exactly — a positive sniff means the real decode succeeds too.</p>
     */
    private static boolean sniffImage(Path file) {
        if (!WIC.isAvailable()) {
            return false;
        }
        try {
            WIC.getSize(file.toString());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Probes a video file with no audio merge. Convenience overload used by the
     * poster paths ({@code loadVisual}/{@code loadThumbnail}) that only need the
     * video fields; the audio fields stay unknown ({@code null}/{@code -1}).
     */
    private MediaProbe videoProbe(Path file, VideoInfo info) {
        return videoProbe(file, info, null);
    }

    /**
     * Probes a video file from a single {@link VideoInfo} read, optionally
     * merging in the first audio track's {@link AudioInfo} (Phase 6 merged AV
     * probe). Mirrors the Apple facade's {@code videoProbe}: guards a negative
     * duration to {@code -1} before converting millis to micros; the video codec
     * name comes from {@link VideoInfo#codec()} (best-effort, {@code null} for an
     * unmapped subtype); the bit rate is the overall container rate
     * ({@link #overallBitRate}) computed from file size and duration.
     *
     * <p>The {@code audio} argument is best-effort: {@code null} or a track that
     * reports {@code !hasAudio()} leaves {@code audioCodec/sampleRate/channels}
     * at the unknown sentinels ({@code null}/{@code -1}), so it never throws or
     * changes the video result. An absent sample rate/channel count likewise
     * degrades to {@code -1}; the codec name is best-effort and may be
     * {@code null} ({@code describe()} skips it).</p>
     */
    private MediaProbe videoProbe(Path file, VideoInfo info, AudioInfo audio) {
        long durationMicros = info.durationMillis() >= 0 ? info.durationMillis() * 1000L : -1;
        long size = fileSize(file);
        long bitRate = overallBitRate(size, durationMicros);
        String audioCodec = null;
        int sampleRate = -1;
        int channels = -1;
        if (audio != null && audio.hasAudio()) {
            audioCodec = audio.codec();
            sampleRate = audio.sampleRate() > 0 ? audio.sampleRate() : -1;
            channels = audio.channels() > 0 ? audio.channels() : -1;
        }
        return new MediaProbe(file, MediaKind.VIDEO,
                extension(file).toUpperCase(Locale.ROOT), size,
                durationMicros, bitRate, info.width(), info.height(),
                info.codec(), info.frameRate(), audioCodec, sampleRate, channels, null);
    }

    /**
     * Computes the overall container bit rate in <b>bits per second</b> from the
     * total file size and duration ({@code fileSize * 8 / durationSeconds}) — the
     * standard "overall bit rate" figure MediaInfo/FFmpeg report. Degrades to the
     * unknown sentinel {@code -1} when either input is non-positive (e.g. an
     * unknown duration), so {@link MediaProbe#describe()} skips the "Bit rate"
     * row. Uses {@code long} math ({@code 8L}, {@code 1_000_000L}) to avoid int
     * overflow on large files; {@code durationMicros} is the same micros value
     * placed in the probe.
     */
    static long overallBitRate(long fileSize, long durationMicros) {
        return (durationMicros > 0 && fileSize > 0)
                ? fileSize * 8L * 1_000_000L / durationMicros : -1;
    }

    /**
     * Opens the asset once for a {@link VideoInfo} read in a fresh confined
     * {@link Arena}, swallowing any failure (e.g. {@code DecodeException} for a
     * missing Store codec) to {@code null} so {@code classify} can fall through
     * to {@link Optional#empty()} without leaking a runtime decode error.
     */
    private static VideoInfo videoInfo(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            return MediaFoundation.getVideoInfo(arena, file.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Probes the first audio stream through a single {@link AudioInfo} read.
     * Mirrors the Apple facade's {@code audioProbe}: guards a negative duration
     * to {@code -1} micros, and maps an absent sample rate/channel count to the
     * unknown sentinel {@code -1}. The codec name is best-effort
     * ({@code null} when the subtype maps to nothing — {@code describe()} skips
     * it). The bit rate is the overall container rate ({@link #overallBitRate})
     * computed from file size and duration; width/height/video-codec stay
     * unknown for audio.
     */
    private MediaProbe audioProbe(Path file, AudioInfo info) {
        long durationMicros = info.durationMillis() >= 0 ? info.durationMillis() * 1000L : -1;
        long size = fileSize(file);
        long bitRate = overallBitRate(size, durationMicros);
        int sampleRate = info.sampleRate() > 0 ? info.sampleRate() : -1;
        int channels = info.channels() > 0 ? info.channels() : -1;
        return new MediaProbe(file, MediaKind.AUDIO,
                extension(file).toUpperCase(Locale.ROOT), size,
                durationMicros, bitRate, -1, -1,
                null, 0, info.codec(), sampleRate, channels, null);
    }

    /**
     * Opens the asset once for an {@link AudioInfo} read in a fresh confined
     * {@link Arena}, swallowing any failure (e.g. {@code DecodeException} for an
     * undecodable container) to {@code null} so {@code classify} can fall
     * through to {@link Optional#empty()} without leaking a runtime decode error.
     */
    private static AudioInfo audioInfo(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            return MediaFoundation.getAudioInfo(arena, file.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MediaProbe imageProbe(Path file) {
        long size = fileSize(file);
        try {
            Dimensions dim = WIC.getSize(file.toString());
            return new MediaProbe(file, MediaKind.IMAGE,
                    extension(file).toUpperCase(Locale.ROOT), size,
                    -1, -1, dim.width(), dim.height(),
                    null, 0, null, -1, -1, pixelDescription(file));
        } catch (RuntimeException e) {
            throw new MediaException("windows-native: cannot probe image "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads a still's native pixel-format description (bit depth / channel
     * layout, e.g. {@code "24-bit RGB"}, {@code "32-bit BGRA"}) via
     * {@link WIC#describePixelFormat} for {@link MediaProbe#pixelDescription}
     * (Phase 6 image pixel description). Best-effort: an unrecognised format or
     * any native hiccup degrades to {@code null} (the info panel skips it)
     * rather than failing the probe — the dimensions already succeeded.
     */
    private static String pixelDescription(Path file) {
        try {
            return WIC.describePixelFormat(file.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Copies a vendored BGRA {@link DecodedImage} into a tightly packed
     * {@link RasterFrame} (golden rule #3): the fast path copies verbatim when
     * {@code stride == width*4}, otherwise each row's first {@code width*4} bytes
     * are copied and the per-row stride padding dropped, yielding a
     * {@code width*height*4}-byte output.
     *
     * <p>Pure (MemorySegment/Panama only — no WIC/MF) and relaxed from
     * {@code private} to package-private/static so the BGRA stride-packing
     * contract is covered deterministically under {@code mvn test} on any OS
     * (no native APIs, no constructor).</p>
     */
    static RasterFrame toRaster(DecodedImage<PixelFormat> img) {
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

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
