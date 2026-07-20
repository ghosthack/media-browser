package io.github.ghosthack.mediabrowser.media;

import java.util.Optional;

/**
 * Result of loading a media item's visual: the probe metadata (extracted
 * from the same native open as the decode, so no separate probe call is
 * needed) and the decoded raster, when the media has one — audio without
 * cover art does not.
 */
public record VisualResult(MediaProbe probe, Optional<RasterFrame> frame) {
}
