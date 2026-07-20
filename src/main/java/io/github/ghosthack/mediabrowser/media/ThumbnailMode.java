package io.github.ghosthack.mediabrowser.media;

/**
 * How a preview rendition maps the source image into its tile.
 *
 * <ul>
 *   <li>{@link #FIT} — aspect-preserved, letterboxed: the whole image fits
 *       within the {@code maxEdge} box (the mosaic draws it on black).</li>
 *   <li>{@link #FILL} — crop-to-fill: the image is centre-cropped to a square
 *       (side ≤ {@code maxEdge}), so a borderless/marginless mosaic tiles
 *       seamlessly with no letterbox.</li>
 * </ul>
 *
 * <p>The mode is part of the rendition's identity ({@link ThumbnailKey}), so
 * FIT and FILL renditions of the same file cache separately.</p>
 */
public enum ThumbnailMode {
    FIT,
    FILL
}
