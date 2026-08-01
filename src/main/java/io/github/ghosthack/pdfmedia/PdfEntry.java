package io.github.ghosthack.pdfmedia;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * An immutable snapshot of one extractable PDF media object.
 *
 * <p>{@link #name()} is untrusted PDF metadata or a synthetic member name. It must not be
 * resolved directly against a host directory. Duplicate names remain distinct by
 * {@link #index()}.</p>
 *
 * @param index stable zero-based position in the archive index
 * @param name original attachment name or synthetic image/media name
 * @param kind whether the bytes are an embedded file or a raster extracted from a PDF image
 * @param origins PDF structures through which this object was found
 * @param mediaType MIME media type when known
 * @param declaredSize logical attachment size or encoded XObject stream size when declared
 * @param raster raw-bitstream decoder metadata for raster entries
 */
public record PdfEntry(
        int index,
        String name,
        Kind kind,
        Set<Origin> origins,
        Optional<String> mediaType,
        OptionalLong declaredSize,
        Optional<PdfRasterDescriptor> raster) {

    /** The two filesystem-level content shapes exposed by this library. */
    public enum Kind {
        /** An attached or rich-media file whose original bytes are retained. */
        EMBEDDED_FILE,
        /** A physical PDF image object's raw encoded stream. */
        RASTER,
        /** Decoder side data referenced by another entry, such as JBIG2 global segments. */
        DECODER_AUXILIARY
    }

    /** Where an entry is referenced in the PDF object graph. */
    public enum Origin {
        EMBEDDED_FILE,
        ASSOCIATED_FILE,
        FILE_ATTACHMENT,
        RICH_MEDIA,
        SCREEN_RENDITION,
        MOVIE,
        SOUND,
        THREE_D,
        IMAGE_XOBJECT,
        INLINE_IMAGE,
        JBIG2_GLOBALS
    }

    public PdfEntry {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        origins = Set.copyOf(origins);
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("origins must not be empty");
        }
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(declaredSize, "declaredSize");
        if (declaredSize.isPresent() && declaredSize.getAsLong() < 0) {
            throw new IllegalArgumentException("declaredSize must be non-negative");
        }
        Objects.requireNonNull(raster, "raster");
        if (kind == Kind.RASTER && raster.isEmpty()) {
            throw new IllegalArgumentException("raster entries require decoder metadata");
        }
        if (kind != Kind.RASTER && raster.isPresent()) {
            throw new IllegalArgumentException("only raster entries carry decoder metadata");
        }
    }

    /** Returns whether opening this entry yields a raw PDF raster bitstream. */
    public boolean isRaster() {
        return kind == Kind.RASTER;
    }

    /** Returns the raster width, or zero for non-raster entries. */
    public int width() {
        return raster.map(PdfRasterDescriptor::width).orElse(0);
    }

    /** Returns the raster height, or zero for non-raster entries. */
    public int height() {
        return raster.map(PdfRasterDescriptor::height).orElse(0);
    }
}
