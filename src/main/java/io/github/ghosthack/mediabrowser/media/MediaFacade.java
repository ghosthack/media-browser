package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Facade over the native media stack (FFmpeg + libvips, accessed through
 * Panama FFM jextract stubs).
 *
 * <p>Implementations are not required to be thread-safe; callers must
 * serialize access (see {@code MediaService}). All methods may perform
 * blocking native I/O and must not be called on the JavaFX thread.</p>
 */
public interface MediaFacade extends AutoCloseable {

    /**
     * Probe-based check whether the file can be opened as media, and as what
     * kind. Stills are detected with the libvips loader-sniffing API; AV
     * containers with the FFmpeg demuxer probing API.
     */
    Optional<MediaKind> classify(Path file);

    /** Full probe of an openable media file (format, streams, dimensions, ...). */
    MediaProbe probe(Path file);

    /**
     * Decode the visual of the file together with its probe metadata, both
     * off a single native open. The frame is the full raster for stills, the
     * first video frame (or attached cover art) for AV media; empty when the
     * file has no visual (e.g. audio without cover art).
     */
    VisualResult loadVisual(Path file);

    /**
     * Decode a small preview rendition of the file in the requested
     * {@link ThumbnailMode}: {@code FIT} fits the whole image within a
     * {@code maxEdge} box (aspect-preserved), {@code FILL} centre-crops it to a
     * square (side ≤ {@code maxEdge}) for a seamless mosaic. Used to fill the
     * mosaic view.
     *
     * <p>The default implementation decodes the full visual via
     * {@link #loadVisual} and downscales it on the JVM ({@link Thumbnails#scale}),
     * so every backend works without extra code. Backends override this to
     * downscale natively during decode (libvips shrink-on-load, FFmpeg
     * swscale, ImageIO/AVFoundation), which is far cheaper for a gallery.</p>
     *
     * <p>Unlike the playback decoder this still follows the serialize-access
     * contract for a given facade instance; the thumbnail service may, however,
     * fan calls across several short-lived facade-equivalent native opens (see
     * {@code MediaService}).</p>
     */
    default Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        VisualResult visual = loadVisual(file);
        return new Thumbnail(visual.frame().map(f -> Thumbnails.scale(f, maxEdge, mode)),
                visual.probe().kind());
    }

    /** {@link #loadThumbnail(Path, int, ThumbnailMode)} in {@link ThumbnailMode#FIT}. */
    default Thumbnail loadThumbnail(Path file, int maxEdge) {
        return loadThumbnail(file, maxEdge, ThumbnailMode.FIT);
    }

    /**
     * Reads the file's full, raw metadata — every EXIF/XMP/IPTC/ICC/PNG-text
     * field for stills, every container/per-stream {@code av_dict} tag for AV —
     * as a backend-agnostic {@link Metadata} snapshot.
     *
     * <p>This is an <b>on-demand</b> call, never part of the probe/decode path:
     * the value materialization (multi-KB/MB XMP, AI prompts) is the whole cost
     * the viewer defers until the user asks. Like {@link #probe}, it performs
     * blocking native I/O, is not thread-safe for a given facade instance, and
     * must not be called on the JavaFX thread; the service runs it on a
     * dedicated executor, off the browse/probe/decode thread.</p>
     *
     * <p>Coverage varies per backend (FFmpeg+libvips: full; Apple: partial;
     * Pure: none) — the default returns an empty snapshot so backends without
     * a metadata path compile and degrade gracefully.</p>
     */
    default Metadata readMetadata(Path file) {
        return Metadata.empty(file);
    }

    /**
     * Opens a sequential decoder over the file's video stream, for playback.
     *
     * <p>Unlike the other facade methods this may be called from any thread:
     * the decoder is independent of all other facade state. The returned
     * stream is confined to the calling thread.</p>
     */
    VideoStream openVideo(Path file);

    /** Human-readable native library versions, for the About dialog. */
    String nativeVersions();

    @Override
    void close();
}
