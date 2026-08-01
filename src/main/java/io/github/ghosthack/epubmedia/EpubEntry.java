package io.github.ghosthack.epubmedia;

import java.util.Set;

/**
 * One physical ZIP member selected by the EPUB package manifest as image, audio, or video.
 *
 * @param index stable physical identity in this archive snapshot
 * @param packagePath normalized path from the EPUB ZIP root
 * @param manifestId package-document manifest ID
 * @param mediaType declared manifest media type
 * @param kind broad media family
 * @param origins manifest and cover conventions that reference this resource
 * @param uncompressedSize declared logical byte size, or {@code -1}
 * @param compressedSize declared stored byte size, or {@code -1}
 */
public record EpubEntry(
        int index,
        String packagePath,
        String manifestId,
        String mediaType,
        Kind kind,
        Set<Origin> origins,
        long uncompressedSize,
        long compressedSize) {

    /** Media families selected from the package manifest. */
    public enum Kind {
        IMAGE,
        AUDIO,
        VIDEO
    }

    /** EPUB structures that identify an entry. */
    public enum Origin {
        MANIFEST,
        EPUB3_COVER_IMAGE,
        EPUB2_COVER_META,
        GUIDE_COVER
    }

    public EpubEntry {
        if (index < 0) throw new IllegalArgumentException("index must not be negative");
        if (packagePath == null || packagePath.isBlank()) {
            throw new IllegalArgumentException("packagePath is blank");
        }
        if (manifestId == null || manifestId.isBlank()) {
            throw new IllegalArgumentException("manifestId is blank");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType is blank");
        }
        if (kind == null) throw new IllegalArgumentException("kind is null");
        origins = Set.copyOf(origins);
        if (uncompressedSize < -1 || compressedSize < -1) {
            throw new IllegalArgumentException("entry sizes must be -1 or non-negative");
        }
    }

    /** Whether an EPUB cover convention identifies this physical resource. */
    public boolean cover() {
        return origins.contains(Origin.EPUB3_COVER_IMAGE)
                || origins.contains(Origin.EPUB2_COVER_META)
                || origins.contains(Origin.GUIDE_COVER);
    }
}
