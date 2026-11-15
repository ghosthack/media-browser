package io.github.ghosthack.mediabrowser.media;

import java.util.Optional;

/**
 * A small preview rendition of a media item for the mosaic view: the
 * downscaled raster (when the item has a visual) plus its kind, so the mosaic
 * can draw a placeholder tile and badges for items without one (e.g. audio
 * without cover art). The frame, when present, preserves the source aspect
 * ratio and fits within the requested {@code maxEdge} box.
 *
 * <p>{@code corruption} is the decoder's message when it produced these pixels
 * but reported the data damaged (a libjpeg-turbo corrupt-data warning), or
 * null for a clean decode — see docs/media-layer.md, "Corrupt data".</p>
 */
public record Thumbnail(Optional<RasterFrame> frame, MediaKind kind, ColorProfile colorProfile,
                        String corruption) {

    public Thumbnail(Optional<RasterFrame> frame, MediaKind kind) {
        this(frame, kind, null, null);
    }

    public Thumbnail(Optional<RasterFrame> frame, MediaKind kind, ColorProfile colorProfile) {
        this(frame, kind, colorProfile, null);
    }

    public Thumbnail {
        if (frame == null) throw new IllegalArgumentException("frame is null");
        if (kind == null) throw new IllegalArgumentException("kind is null");
    }

    /** A thumbnail with no visual (the mosaic draws a kind placeholder). */
    public static Thumbnail empty(MediaKind kind) {
        return new Thumbnail(Optional.empty(), kind);
    }

    /** This rendition with {@code frame} as its pixels; kind, profile and verdict carry over. */
    public Thumbnail withFrame(Optional<RasterFrame> frame) {
        return new Thumbnail(frame, kind, colorProfile, corruption);
    }

    /** This rendition labelled as decoded from corrupt data ({@code null} for clean). */
    public Thumbnail withCorruption(String corruption) {
        return new Thumbnail(frame, kind, colorProfile, corruption);
    }

    /** Approximate retained heap of the rendition's pixels, in bytes. */
    public long byteSize() {
        return frame.map(f -> (long) f.bgra().length).orElse(0L);
    }
}
