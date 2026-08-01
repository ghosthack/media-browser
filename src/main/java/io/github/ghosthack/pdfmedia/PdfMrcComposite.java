package io.github.ghosthack.pdfmedia;

/**
 * Describes one Mixed Raster Content (MRC) page assembled from physical PDF image entries.
 *
 * <p>The background and foreground are co-registered color rasters. The mask is the foreground
 * entry's one-bit hard mask and may have a higher resolution. Entry indices refer to
 * {@link PdfArchive#entries()} and remain inspectable independently.</p>
 *
 * @param pageIndex zero-based PDF page index
 * @param width composition-grid width in pixels, taken from the high-resolution mask
 * @param height composition-grid height in pixels, taken from the high-resolution mask
 * @param backgroundEntryIndex background raster entry index
 * @param foregroundEntryIndex foreground raster entry index
 * @param maskEntryIndex one-bit hard-mask entry index
 * @param placement shared background/foreground placement in page user space
 */
public record PdfMrcComposite(
        int pageIndex,
        int width,
        int height,
        int backgroundEntryIndex,
        int foregroundEntryIndex,
        int maskEntryIndex,
        PdfTransform placement) {
    public PdfMrcComposite {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must not be negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("composite dimensions must be positive");
        }
        if (backgroundEntryIndex < 0 || foregroundEntryIndex < 0 || maskEntryIndex < 0) {
            throw new IllegalArgumentException("layer entry indices must not be negative");
        }
        if (backgroundEntryIndex == foregroundEntryIndex
                || backgroundEntryIndex == maskEntryIndex
                || foregroundEntryIndex == maskEntryIndex) {
            throw new IllegalArgumentException("MRC layer entry indices must be distinct");
        }
        placement = java.util.Objects.requireNonNull(placement, "placement");
    }
}
