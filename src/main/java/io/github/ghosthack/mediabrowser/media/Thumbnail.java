package io.github.ghosthack.mediabrowser.media;

import java.util.Optional;

/**
 * A small preview rendition of a media item for the mosaic view: the
 * downscaled raster (when the item has a visual) plus its kind, so the mosaic
 * can draw a placeholder tile and badges for items without one (e.g. audio
 * without cover art). The frame, when present, preserves the source aspect
 * ratio and fits within the requested {@code maxEdge} box.
 */
public record Thumbnail(Optional<RasterFrame> frame, MediaKind kind) {

    public Thumbnail {
        if (frame == null) throw new IllegalArgumentException("frame is null");
        if (kind == null) throw new IllegalArgumentException("kind is null");
    }

    /** A thumbnail with no visual (the mosaic draws a kind placeholder). */
    public static Thumbnail empty(MediaKind kind) {
        return new Thumbnail(Optional.empty(), kind);
    }

    /** Approximate retained heap of the rendition's pixels, in bytes. */
    public long byteSize() {
        return frame.map(f -> (long) f.bgra().length).orElse(0L);
    }
}
