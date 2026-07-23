package io.github.ghosthack.mediabrowser.media.ffm.bind;

/**
 * An {@code AVStreamGroupTileGrid} lifted to a plain value (like
 * {@link Rational}), so callers never see a native layout: FFmpeg 7.1+ demuxes
 * a tiled HEIF/AVIF still (every iPhone photo) as one stream <em>per tile</em>
 * plus a stream group describing how the tiles compose onto a canvas.
 *
 * <p>The composed picture is a {@code codedWidth × codedHeight} canvas with
 * each tile blitted at its offset (later tiles over earlier on overlap), then
 * cropped to the {@code width × height} window at
 * {@code (horizontalOffset, verticalOffset)} — Apple grids code full tile rows
 * (e.g. 6×1024 = 6144) and crop to the real height (6048) here.</p>
 *
 * @param exifOrientation the grid's display-matrix orientation ({@code irot}
 *        and {@code imir} land on the grid's own coded side data) as an EXIF
 *        code (1..8) for {@code RasterFrames.applyExifOrientation};
 *        {@code 1} when the grid carries none
 * @param tiles one entry per canvas placement, in compose order; a stream may
 *        be referenced by more than one placement
 */
public record TileGrid(int codedWidth, int codedHeight,
                       int horizontalOffset, int verticalOffset,
                       int width, int height,
                       int exifOrientation,
                       Tile[] tiles) {

    /**
     * One tile placement: the format-context stream index carrying the tile's
     * coded picture (already resolved from the group-local index) and the
     * canvas offset of its top-left pixel.
     */
    public record Tile(int streamIndex, int x, int y) {}

    /** Whether {@code streamIndex} carries one of this grid's tiles. */
    public boolean containsStream(int streamIndex) {
        for (Tile tile : tiles) {
            if (tile.streamIndex() == streamIndex) {
                return true;
            }
        }
        return false;
    }
}
