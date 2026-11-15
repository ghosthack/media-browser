package io.github.ghosthack.mediabrowser.media;

import java.util.Optional;

/**
 * Result of loading a media item's visual: the probe metadata (extracted
 * from the same native open as the decode, so no separate probe call is
 * needed) and the decoded raster, when the media has one — audio without
 * cover art does not.
 *
 * <p>{@code corruption} is the decoder's message when it produced the frame
 * but reported the data damaged (a libjpeg-turbo corrupt-data warning), or
 * null for a clean decode — see docs/media-layer.md, "Corrupt data".</p>
 */
public record VisualResult(MediaProbe probe, Optional<RasterFrame> frame, String corruption) {

    public VisualResult(MediaProbe probe, Optional<RasterFrame> frame) {
        this(probe, frame, null);
    }

    /** This result with {@code probe} in its place; the frame and verdict carry over. */
    public VisualResult withProbe(MediaProbe probe) {
        return new VisualResult(probe, frame, corruption);
    }

    /** This result with {@code frame} in its place; the probe and verdict carry over. */
    public VisualResult withFrame(Optional<RasterFrame> frame) {
        return new VisualResult(probe, frame, corruption);
    }

    /** This result labelled as decoded from corrupt data ({@code null} for clean). */
    public VisualResult withCorruption(String corruption) {
        return new VisualResult(probe, frame, corruption);
    }
}
