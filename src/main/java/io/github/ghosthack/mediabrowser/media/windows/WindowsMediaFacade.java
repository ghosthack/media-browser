package io.github.ghosthack.mediabrowser.media.windows;

import io.github.ghosthack.mediabrowser.media.BgraAlpha;
import io.github.ghosthack.mediabrowser.media.CorruptMediaException;
import io.github.ghosthack.mediabrowser.media.ImageSequences;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaEngineTrace;
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

import io.github.ghosthack.panama.media.core.DecodeException;
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
import java.util.function.Supplier;

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
                    () -> animationTrack(file).isPresent()));
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
                    MediaEngineTrace.Recorder.disabled(), ANIMATED_VISUAL,
                    () -> animationTrack(file),
                    vi -> videoProbe(file, vi, audioInfo(file)),
                    () -> imageProbe(file));
        }
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            VideoInfo vi = videoInfo(file);
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                // Phase 6 (merged AV probe): a single probe() reads BOTH the
                // video track and, best-effort, the first audio track so a
                // video-with-sound reports audioCodec/sampleRate/channels on
                // the SAME MediaProbe. A silent video leaves those fields at
                // the unknown sentinels; a failed audio read throws (the
                // source reader just opened this file, so it is a real error).
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
        return loadVisual(file, MediaEngineTrace.Recorder.disabled());
    }

    @Override
    public VisualResult loadVisual(Path file, MediaEngineTrace.Recorder trace) {
        if (sniffImage(file)) {
            return ImageSequences.decodeStillOrAnimation(file, trace, ANIMATED_VISUAL,
                    () -> animationTrack(file),
                    vi -> mfVideoVisual(file, vi, trace),
                    () -> traced(trace, "WIC still decode", () -> wicVisual(file)));
        }
        String ext = extension(file);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            long probeStarted = trace.beginAttempt();
            VideoInfo vi;
            try {
                vi = videoInfo(file);
            } catch (RuntimeException e) {
                trace.failed("Media Foundation video probe", probeStarted, e);
                throw e;
            }
            if (vi != null && vi.width() > 0 && vi.height() > 0) {
                trace.succeeded("Media Foundation video probe", probeStarted,
                        vi.width() + "×" + vi.height());
                // Poster ~10% in skips a black/fade-in opening frame. Keep the
                // confined Arena open until videoRaster copies the pixels out, and
                // wrap any DecodeException so it never leaks past the facade.
                long durationMs = vi.durationMillis();
                long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
                try (Arena arena = Arena.ofConfined()) {
                    DecodedImage<PixelFormat> frame =
                            MediaFoundation.extractFrame(
                                    arena, file.toString(), posterMs, 0,
                                    attempt -> recordMfAttempt(trace, attempt));
                    return new VisualResult(videoProbe(file, vi), Optional.of(videoRaster(frame)));
                } catch (RuntimeException e) {
                    throw failure("windows-native: cannot decode video frame "
                            + file.getFileName() + ": " + e.getMessage(), e);
                }
            } else {
                trace.failed("Media Foundation video probe", probeStarted,
                        "No playable video stream");
            }
        }
        // Audio: no cover art in v1 (matches AppleMediaFacade) -> empty visual.
        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)) {
            long audioStarted = trace.beginAttempt();
            AudioInfo ai;
            try {
                ai = audioInfo(file);
            } catch (RuntimeException e) {
                trace.failed("Media Foundation audio probe", audioStarted, e);
                throw e;
            }
            if (ai != null && ai.hasAudio()) {
                trace.succeeded("Media Foundation audio probe", audioStarted,
                        "Audio-only visual (no cover art)");
                return new VisualResult(audioProbe(file, ai), Optional.empty());
            }
            trace.failed("Media Foundation audio probe", audioStarted,
                    "No playable audio stream");
        }
        throw new MediaException("windows-native: cannot load visual of " + file.getFileName());
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        // Stills: WIC source-scales during decode (embedded-thumbnail fast path
        // + EXIF orientation baked in), fitting the box; FILL then centre-crops
        // the square in Java. Mirrors the Apple stills path exactly.
        if (sniffImage(file)) {
            return stillOrAnimationThumbnail(file, maxEdge, mode,
                    MediaEngineTrace.Recorder.disabled());
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
                    RasterFrame raster = Thumbnails.scale(videoRaster(frame), maxEdge, mode);
                    return new Thumbnail(Optional.of(raster), MediaKind.VIDEO);
                } catch (RuntimeException e) {
                    throw failure("windows-native: cannot thumbnail video "
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

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode,
                                   MediaEngineTrace.Recorder trace) {
        if (sniffImage(file)) {
            return stillOrAnimationThumbnail(file, maxEdge, mode, trace);
        }
        return tracedThumbnail(trace, "Media Foundation native-downscaled poster",
                maxEdge, mode, () -> loadThumbnail(file, maxEdge, mode));
    }

    /**
     * A sniffed still: the animated poster when Media Foundation exposes the
     * animation track, else the WIC still — each named in the trace, with the
     * decline recorded when the animation has no playable track.
     */
    private Thumbnail stillOrAnimationThumbnail(Path file, int maxEdge, ThumbnailMode mode,
                                                MediaEngineTrace.Recorder trace) {
        return ImageSequences.decodeStillOrAnimation(file, trace, ANIMATED_THUMBNAIL,
                () -> animationTrack(file),
                vi -> tracedThumbnail(trace, ANIMATED_THUMBNAIL, maxEdge, mode,
                        () -> mfVideoThumbnail(file, vi, maxEdge, mode)),
                () -> tracedThumbnail(trace, "WIC source-scaled thumbnail", maxEdge, mode,
                        () -> wicThumbnail(file, maxEdge, mode)));
    }

    private static Thumbnail tracedThumbnail(MediaEngineTrace.Recorder trace, String strategy,
                                             int maxEdge, ThumbnailMode mode,
                                             Supplier<Thumbnail> action) {
        long started = trace.beginAttempt();
        try {
            Thumbnail result = action.get();
            trace.succeeded(strategy, started,
                    mode + ", requested ≤" + maxEdge + " px, produced "
                            + result.frame().map(f -> f.width() + "×" + f.height() + " BGRA")
                                    .orElse("no visual frame"));
            return result;
        } catch (RuntimeException | Error e) {
            trace.failed(strategy, started, e);
            throw e;
        }
    }

    // -- still (WIC) vs animated AVIF/HEIC (Media Foundation) ----------------
    //
    // For an animated AVIF/HEIC the WIC still sniff matches but only the moov
    // track plays. ImageSequences.decodeStillOrAnimation runs the mf* path when
    // MF exposes the animation track, and the wic* still when it does not —
    // e.g. its MP4 source rejects the avis/hevs brand or the AV1/HEVC Store
    // codec MFT is missing; the same gate classify() uses, recorded as DECLINED
    // in the trace. A failure on a track MF did open is the file's decode
    // error, never swapped for the still. Unlike FFmpeg, MF never exposes the
    // still primary item as a track, so there is no still-vs-track stream to
    // disambiguate here.

    private static final String ANIMATED_VISUAL = "Media Foundation animation track";
    private static final String ANIMATED_THUMBNAIL = "Media Foundation animated poster";

    private VisualResult wicVisual(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> img = WIC.decodeFromPath(arena, file.toString());
            return new VisualResult(imageProbe(file), Optional.of(stillRaster(img)));
        } catch (RuntimeException e) {
            throw failure("windows-native: cannot decode image "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private Thumbnail wicThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> thumb =
                    WIC.decodeThumbnailFromPath(arena, file.toString(), maxEdge);
            RasterFrame frame = stillRaster(thumb);
            if (mode == ThumbnailMode.FILL) frame = Thumbnails.fill(frame, maxEdge);
            return new Thumbnail(Optional.of(frame), MediaKind.IMAGE);
        } catch (RuntimeException e) {
            throw failure("windows-native: cannot thumbnail image "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private VisualResult mfVideoVisual(Path file, VideoInfo vi,
                                       MediaEngineTrace.Recorder trace) {
        trace.succeeded("Media Foundation animation probe", trace.beginAttempt(),
                vi.width() + "×" + vi.height());
        long decodeStarted = trace.beginAttempt();
        long durationMs = vi.durationMillis();
        long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame =
                    MediaFoundation.extractFrame(
                            arena, file.toString(), posterMs, 0,
                            attempt -> recordMfAttempt(trace, attempt));
            return new VisualResult(videoProbe(file, vi, audioInfo(file)),
                    Optional.of(videoRaster(frame)));
        } catch (RuntimeException e) {
            MediaException failure = failure(
                    "windows-native: cannot decode animation frame "
                    + file.getFileName() + ": " + e.getMessage(), e);
            trace.failed(ANIMATED_VISUAL, decodeStarted, failure);
            throw failure;
        }
    }

    private static void recordMfAttempt(
            MediaEngineTrace.Recorder trace,
            MediaFoundation.FrameDecodeAttempt attempt) {
        trace.record(
                attempt.strategy(),
                attempt.succeeded()
                        ? MediaEngineTrace.Outcome.SUCCEEDED
                        : MediaEngineTrace.Outcome.FAILED,
                attempt.elapsedNanos(),
                attempt.detail());
    }

    private static <T> T traced(
            MediaEngineTrace.Recorder trace,
            String strategy,
            Supplier<T> action) {
        long started = trace.beginAttempt();
        try {
            T result = action.get();
            trace.succeeded(strategy, started, "Foreground visual");
            return result;
        } catch (RuntimeException | Error failure) {
            trace.failed(strategy, started, failure);
            throw failure;
        }
    }

    private Thumbnail mfVideoThumbnail(Path file, VideoInfo vi, int maxEdge,
                                       ThumbnailMode mode) {
        long durationMs = vi.durationMillis();
        long posterMs = durationMs > 0 ? (long) (durationMs * 0.1) : 0;
        try (Arena arena = Arena.ofConfined()) {
            DecodedImage<PixelFormat> frame =
                    MediaFoundation.extractFrame(arena, file.toString(), posterMs, maxEdge);
            RasterFrame raster = Thumbnails.scale(videoRaster(frame), maxEdge, mode);
            return new Thumbnail(Optional.of(raster), MediaKind.VIDEO);
        } catch (RuntimeException e) {
            throw failure("windows-native: cannot thumbnail animation "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The MF animation track of an animated AVIF/HEIC; empty when Media
     * Foundation exposes no playable track, which is what routes the file to
     * the still.
     */
    private static Optional<VideoInfo> animationTrack(Path file) {
        VideoInfo info;
        try {
            info = videoInfo(file);
        } catch (MediaException e) {
            // WIC already matched this file as an AVIF/HEIC still. Media
            // Foundation refusing to create a source for the avis/hevs
            // container is the engine's capability gap (seen as a bare E_FAIL,
            // not MF_E_UNSUPPORTED_BYTESTREAM_TYPE), so the caller declines to
            // the still and records it, even when the open failure reads as
            // corrupt data: MF's MP4 source cannot tell an avis container it
            // does not understand from a damaged one, and the WIC still then
            // reports its own verdict. A file I/O error, or any failure after
            // MF opened the container, is still this file's error.
            if (rejectsAnimationContainer(e.getCause())) return Optional.empty();
            throw e;
        }
        return Optional.ofNullable(info)
                .filter(vi -> vi.width() > 0 && vi.height() > 0);
    }

    /**
     * Whether Media Foundation declined to open an animated AVIF/HEIC
     * container: {@code MFCreateSourceReaderFromURL} failed with an HRESULT
     * outside the Win32 facility ({@code 0x8007xxxx}: path, access, sharing
     * errors stay failures). Pure, so it is testable on any OS.
     */
    static boolean rejectsAnimationContainer(Throwable failure) {
        if (!(failure instanceof DecodeException)) return false;
        String message = failure.getMessage();
        if (message == null || !message.startsWith("MFCreateSourceReaderFromURL failed")) return false;
        Matcher m = HRESULT.matcher(message);
        if (!m.find()) return false;
        int hr = Integer.parseUnsignedInt(m.group(1), 16);
        return (hr >>> 16) != 0x8007;
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
            throw failure("windows-native: cannot read metadata of "
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
            throw failure("windows-native: cannot open video "
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
     *
     * <p>A probe that fails with a corrupt-data HRESULT ({@link #corruptData})
     * also counts as an image: WIC picked a decoder by the file's signature
     * and that decoder rejected the header, so the still path runs, fails the
     * same way, and labels the file corrupt instead of "cannot probe".</p>
     */
    private static boolean sniffImage(Path file) {
        if (!WIC.isAvailable()) {
            return false;
        }
        try {
            WIC.getSize(file.toString());
            return true;
        } catch (RuntimeException e) {
            return corruptData(e) != null;
        }
    }

    // -- corrupt data ----------------------------------------------------------

    static final int WINCODEC_ERR_BADIMAGE = 0x88982F60;
    static final int WINCODEC_ERR_BADHEADER = 0x88982F61;
    static final int WINCODEC_ERR_BADMETADATAHEADER = 0x88982F63;
    static final int WINCODEC_ERR_BADSTREAMDATA = 0x88982F70;
    static final int MF_E_INVALID_FILE_FORMAT = 0xC00D36BE;
    static final int MF_E_INVALID_STREAM_DATA = 0xC00D36CB;
    static final int MF_E_INVALID_POSITION = 0xC00D36E5;

    /**
     * The name of the HRESULT when it says the file's data is damaged, else
     * {@code null}. {@code operation} is the binding's message, which names
     * the failing call. Only codes whose meaning is invalid data count:
     * WIC's bad image / header / metadata header / stream data, and Media
     * Foundation's invalid file format / stream data. {@code
     * MF_E_INVALID_POSITION} counts when the MP4 source raises it opening the
     * file or reading a sample (its index points past the end of the data: a
     * truncated or garbled container), not from a seek. Unsupported formats,
     * missing codecs, I/O and access errors ({@code WINCODEC_ERR_STREAMREAD},
     * {@code 0x8007xxxx}, …) are not corrupt. Pure, so it is testable on any OS.
     */
    static String corruptDataName(int hr, String operation) {
        return switch (hr) {
            case WINCODEC_ERR_BADIMAGE -> "WINCODEC_ERR_BADIMAGE";
            case WINCODEC_ERR_BADHEADER -> "WINCODEC_ERR_BADHEADER";
            case WINCODEC_ERR_BADMETADATAHEADER -> "WINCODEC_ERR_BADMETADATAHEADER";
            case WINCODEC_ERR_BADSTREAMDATA -> "WINCODEC_ERR_BADSTREAMDATA";
            case MF_E_INVALID_FILE_FORMAT -> "MF_E_INVALID_FILE_FORMAT";
            case MF_E_INVALID_STREAM_DATA -> "MF_E_INVALID_STREAM_DATA";
            case MF_E_INVALID_POSITION ->
                    operation != null && (operation.startsWith("MFCreateSourceReaderFromURL")
                            || operation.startsWith("ReadSample"))
                            ? "MF_E_INVALID_POSITION" : null;
            default -> null;
        };
    }

    /**
     * The corrupt-data HRESULT name a failure carries, searching its causes
     * and suppressed exceptions (the binding's frame extraction keeps the
     * Source Reader attempt as suppressed under the manual-pipeline one), or
     * {@code null}. Only {@link DecodeException} messages of the binding's
     * {@code "<call> … (HRESULT 0x…)"} form are read. Pure.
     */
    static String corruptData(Throwable failure) {
        return corruptData(failure, 0);
    }

    private static String corruptData(Throwable t, int depth) {
        if (t == null || depth > 8) return null;
        if (t instanceof DecodeException && t.getMessage() != null) {
            Matcher m = HRESULT.matcher(t.getMessage());
            if (m.find()) {
                String name = corruptDataName(
                        Integer.parseUnsignedInt(m.group(1), 16), t.getMessage());
                if (name != null) return name;
            }
        }
        for (Throwable s : t.getSuppressed()) {
            String name = corruptData(s, depth + 1);
            if (name != null) return name;
        }
        return t.getCause() == t ? null : corruptData(t.getCause(), depth + 1);
    }

    /**
     * The facade's failure for a binding exception: a
     * {@link CorruptMediaException} naming the HRESULT when the decoder
     * reported invalid data ({@link #corruptData}), else a plain
     * {@link MediaException}. The cause is kept either way.
     */
    private static MediaException failure(String message, RuntimeException cause) {
        String corrupt = corruptData(cause);
        return corrupt == null
                ? new MediaException(message, cause)
                : new CorruptMediaException(message + " (" + corrupt + ")", cause);
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
     * {@link Arena}. {@code null} means "not applicable": Media Foundation
     * has no source for this container or the file has no video stream
     * ({@link #isNotApplicable}). Any other failure is this file's error and
     * throws, rather than reading as "no video" and routing the file on to
     * a path that then fails without the cause.
     */
    private static VideoInfo videoInfo(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            return MediaFoundation.getVideoInfo(arena, file.toString());
        } catch (RuntimeException e) {
            if (isNotApplicable(e)) return null;
            throw failure("windows-native: cannot read the video stream of "
                    + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** {@code MF_E_INVALIDSTREAMNUMBER}: the reader has no stream of that type. */
    static final int MF_E_INVALIDSTREAMNUMBER = 0xC00D36B3;
    /** {@code MF_E_UNSUPPORTED_BYTESTREAM_TYPE}: no MF source handles the container. */
    static final int MF_E_UNSUPPORTED_BYTESTREAM_TYPE = 0xC00D36C4;
    private static final Pattern HRESULT = Pattern.compile("HRESULT 0x([0-9a-fA-F]{1,8})");

    /**
     * Whether a Media Foundation probe failure is the answer "not this
     * format / no such stream" rather than a failure: only the two HRESULTs
     * that say exactly that. Everything else — a corrupt container
     * ({@code MF_E_INVALID_FILE_FORMAT}), an I/O error, MF unavailable — is
     * a real failure. Pure (parses the binding's {@code "(HRESULT 0x…)"}
     * message) so it is testable on any OS.
     */
    static boolean isNotApplicable(Throwable failure) {
        if (!(failure instanceof DecodeException)) return false;
        String message = failure.getMessage();
        if (message == null) return false;
        Matcher m = HRESULT.matcher(message);
        if (!m.find()) return false;
        int hr = Integer.parseUnsignedInt(m.group(1), 16);
        return hr == MF_E_INVALIDSTREAMNUMBER || hr == MF_E_UNSUPPORTED_BYTESTREAM_TYPE;
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
     * {@link Arena}. A file with no audio stream reports
     * {@code hasAudio() == false}; {@code null} means Media Foundation has
     * no source for the container ({@link #isNotApplicable}). Any other
     * failure throws, as in {@link #videoInfo}.
     */
    private static AudioInfo audioInfo(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            return MediaFoundation.getAudioInfo(arena, file.toString());
        } catch (RuntimeException e) {
            if (isNotApplicable(e)) return null;
            throw failure("windows-native: cannot read the audio stream of "
                    + file.getFileName() + ": " + e.getMessage(), e);
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
            throw failure("windows-native: cannot probe image "
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
     * A WIC still or thumbnail as a straight-alpha {@link RasterFrame}. The
     * vendored WIC binding always format-converts to {@code 32bppPBGRA}
     * (premultiplied), while {@link RasterFrame} is straight alpha, so the
     * packed copy is un-premultiplied. Opaque pixels (alpha 255) are unchanged.
     *
     * <p>Stills only: never route Media Foundation frames here — see
     * {@link #videoRaster}.</p>
     */
    static RasterFrame stillRaster(DecodedImage<PixelFormat> img) {
        byte[] bgra = packBgra(img);
        BgraAlpha.unpremultiplyInPlace(bgra);
        return new RasterFrame(img.width(), img.height(), bgra);
    }

    /**
     * A Media Foundation frame as a {@link RasterFrame}, bytes copied verbatim.
     * MF frames are opaque video and are deliberately NOT un-premultiplied:
     * the vendored NV12 path writes alpha 255 (already straight), and the
     * {@code MFVideoFormat_RGB32} path passes MF's fourth byte through, which
     * is undefined padding rather than alpha — dividing colour by it would
     * corrupt the frame (a padding byte of 0 would blank it).
     */
    static RasterFrame videoRaster(DecodedImage<PixelFormat> img) {
        return new RasterFrame(img.width(), img.height(), packBgra(img));
    }

    /**
     * Copies a vendored BGRA {@link DecodedImage} into tightly packed bytes
     * (golden rule #3): the fast path copies verbatim when
     * {@code stride == width*4}, otherwise each row's first {@code width*4} bytes
     * are copied and the per-row stride padding dropped, yielding a
     * {@code width*height*4}-byte output.
     *
     * <p>Pure (MemorySegment/Panama only — no WIC/MF); it and its two callers
     * above are package-private/static so the BGRA stride-packing and alpha
     * contracts are covered deterministically under {@code mvn test} on any OS
     * (no native APIs, no constructor).</p>
     */
    static byte[] packBgra(DecodedImage<PixelFormat> img) {
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
        return dst;
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
