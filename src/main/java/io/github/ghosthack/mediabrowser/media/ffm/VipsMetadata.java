package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.Metadata;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Ffm;
import io.github.ghosthack.mediabrowser.media.ffm.bind.VipsBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

/**
 * Full still-image metadata read through a {@link VipsBindings}. Opens the file
 * header-only ({@code vips_image_new_from_file} is lazy) and enumerates every
 * field {@code vips_image_get_fields} exposes — EXIF/XMP/IPTC/ICC and PNG/JPEG
 * text chunks — without ever decoding pixels.
 *
 * <p><b>Native lifetime</b> (strict, mirrors {@link VipsStills}): every
 * {@code vips_image_get_as_string} out pointer is {@code g_free}'d, the field
 * names array is {@code g_strfreev}'d, and the image is {@code g_object_unref}'d
 * — all in {@code finally}.</p>
 */
final class VipsMetadata {

    private final VipsBindings vips;

    /**
     * GType of {@code VipsBlob}, fetched once (after {@code vips_init}). Used to
     * tell binary fields apart from text fields.
     *
     * <p><b>Why GType detection?</b> {@code vips_image_get_as_string}
     * base64-encodes blob fields (EXIF/XMP/ICC/thumbnail data), so a string-shape
     * match never fires and you would dump a multi-KB base64 blob into a cell.
     * GType detection is exact, and {@code vips_image_get_blob} gives the true
     * byte count without materializing/encoding the blob at all.</p>
     */
    private final long blobType;

    VipsMetadata(VipsBindings vips) {
        this.vips = vips;
        this.blobType = vips.blobType();
    }

    Metadata read(Path file) {
        Metadata.Builder out = new Metadata.Builder(file);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment img = vips.imageNewFromFile(arena, file);
            try {
                MemorySegment names = vips.imageGetFields(img);
                try {
                    readFields(arena, img, names, out);
                } finally {
                    if (!names.equals(MemorySegment.NULL)) vips.gStrfreev(names);
                }
            } finally {
                vips.gObjectUnref(img);
            }
        }
        return out.build();
    }

    /** Walks the NULL-terminated {@code gchar**} field-name array. */
    private void readFields(Arena arena, MemorySegment img, MemorySegment names,
                            Metadata.Builder out) {
        if (names.equals(MemorySegment.NULL)) return;
        MemorySegment array = names.reinterpret(Long.MAX_VALUE);
        for (long i = 0; ; i++) {
            MemorySegment namePtr = array.getAtIndex(ValueLayout.ADDRESS, i);
            if (namePtr.equals(MemorySegment.NULL)) break;
            String name = Ffm.cstr(namePtr);
            if (name == null) continue;
            out.add(groupFor(name), entryFor(arena, img, name, namePtr));
        }
    }

    /**
     * Reads one field: binary blobs become a {@code <binary, N bytes>}
     * placeholder (true length, never materialized); everything else is read as
     * a string and capped by the model.
     */
    private Metadata.Entry entryFor(Arena arena, MemorySegment img, String name,
                                    MemorySegment namePtr) {
        if (vips.imageGetTypeof(img, namePtr) == blobType) {
            return Metadata.Entry.binary(name, blobLength(arena, img, namePtr));
        }
        MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
        int rc = vips.imageGetAsString(img, namePtr, outPtr);
        if (rc != 0) {
            // Present per get_fields but unreadable: keep the key, empty value.
            return Metadata.Entry.of(name, "");
        }
        MemorySegment strPtr = outPtr.get(ValueLayout.ADDRESS, 0);
        if (strPtr.equals(MemorySegment.NULL)) {
            return Metadata.Entry.of(name, "");
        }
        try {
            return Metadata.Entry.of(name, strPtr.reinterpret(Long.MAX_VALUE).getString(0));
        } finally {
            vips.gFree(strPtr);
        }
    }

    /** True byte length of a blob field via {@code vips_image_get_blob} (no copy). */
    private long blobLength(Arena arena, MemorySegment img, MemorySegment namePtr) {
        MemorySegment dataPtr = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment lenPtr = arena.allocate(ValueLayout.JAVA_LONG);
        int rc = vips.imageGetBlob(img, namePtr, dataPtr, lenPtr);
        return rc == 0 ? lenPtr.get(ValueLayout.JAVA_LONG, 0) : 0;
    }

    /**
     * Dumb, purely prefix-based grouping of a raw libvips field name into a
     * source/IFD bucket. No humanizing of the key itself.
     */
    private static String groupFor(String key) {
        if (key.startsWith("exif-ifd0-")) return "EXIF IFD0";
        if (key.startsWith("exif-ifd1-")) return "EXIF IFD1";
        if (key.startsWith("exif-ifd2-")) return "EXIF Exif";
        if (key.startsWith("exif-ifd3-")) return "EXIF GPS";
        if (key.startsWith("exif-ifd4-")) return "EXIF Interoperability";
        if (key.startsWith("exif-")) return "EXIF";
        if (key.startsWith("xmp")) return "XMP";
        if (key.startsWith("iptc")) return "IPTC";
        if (key.startsWith("icc")) return "ICC";
        if (key.startsWith("png-comment-")) return "PNG text";
        return "Image";
    }
}
