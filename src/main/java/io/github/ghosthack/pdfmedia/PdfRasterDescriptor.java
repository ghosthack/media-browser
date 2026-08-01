package io.github.ghosthack.pdfmedia;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Decoder metadata for a raw PDF raster bitstream.
 *
 * <p>The stream contains no synthetic image header and has not been normalized. Apply
 * {@link #filters()} in list order. PDF-native Flate/LZW sample planes also require the width,
 * height, component depth, color-space, decode array, and mask metadata carried here.</p>
 *
 * @param width image width in samples
 * @param height image height in samples
 * @param bitsPerComponent bits per component, or zero when supplied by the terminal codec
 * @param stencil whether the image is an image mask
 * @param interpolate whether a consumer should interpolate while scaling the image
 * @param filters ordered raw-bitstream decoder pipeline
 * @param colorSpace PDF color-space name or compact structural description when present
 * @param decode PDF Decode array
 * @param colorKeyMask PDF color-key Mask array
 * @param hardMaskEntryIndex entry index of an explicit hard mask
 * @param softMaskEntryIndex entry index of a soft mask
 * @param jbig2GlobalsEntryIndex entry containing decoded JBIG2 global segments
 */
public record PdfRasterDescriptor(
        int width,
        int height,
        int bitsPerComponent,
        boolean stencil,
        boolean interpolate,
        List<PdfFilter> filters,
        Optional<String> colorSpace,
        List<Double> decode,
        List<Long> colorKeyMask,
        OptionalInt hardMaskEntryIndex,
        OptionalInt softMaskEntryIndex,
        OptionalInt jbig2GlobalsEntryIndex) {

    public PdfRasterDescriptor {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("raster dimensions must be positive");
        }
        if (bitsPerComponent < 0) {
            throw new IllegalArgumentException("bitsPerComponent must be non-negative");
        }
        filters = List.copyOf(filters);
        Objects.requireNonNull(colorSpace, "colorSpace");
        decode = List.copyOf(decode);
        colorKeyMask = List.copyOf(colorKeyMask);
        Objects.requireNonNull(hardMaskEntryIndex, "hardMaskEntryIndex");
        Objects.requireNonNull(softMaskEntryIndex, "softMaskEntryIndex");
        Objects.requireNonNull(jbig2GlobalsEntryIndex, "jbig2GlobalsEntryIndex");
    }

    /** Returns the decoder-selection pipeline in the same order as {@link #filters()}. */
    public List<PdfFilter.Decoder> decoderStack() {
        return filters.stream().map(PdfFilter::decoder).toList();
    }
}
