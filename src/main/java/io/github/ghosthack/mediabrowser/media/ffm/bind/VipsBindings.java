package io.github.ghosthack.mediabrowser.media.ffm.bind;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Semantic seam over the libvips jextract stubs. As with {@link FfmpegBindings},
 * the still-image logic ({@code VipsStills}, {@code VipsMetadata}) talks only to
 * this interface; the dylib load path, GLib pointer plumbing, and the variadic
 * {@code vips_*} invokers live entirely in an implementation, so the same logic
 * can run against more than one libvips build.
 */
public interface VipsBindings {

    // ---- lifecycle -------------------------------------------------------

    /** Initializes libvips (idempotent); throws on failure. */
    void init(String appName);

    /** Human-readable libvips version, e.g. {@code "8.16.0"}. */
    String version();

    /** Current vips error buffer contents, then clears it. Never null. */
    String takeError();

    // ---- open / probe ----------------------------------------------------

    /** Loader-sniffing operation name (e.g. {@code VipsForeignLoadPngFile}), or null. */
    String findLoad(Arena arena, Path file);

    /**
     * Lazily opens {@code file} into a {@code VipsImage*}. Throws
     * {@link MediaException} (with the vips error) on failure. The caller owns
     * the returned image and must {@link #gObjectUnref} it.
     */
    MemorySegment imageNewFromFile(Arena arena, Path file);

    // ---- image header reads ---------------------------------------------

    int imageGetBands(MemorySegment img);

    int imageGetFormat(MemorySegment img);

    int imageGetWidth(MemorySegment img);

    int imageGetHeight(MemorySegment img);

    int imageGetInterpretation(MemorySegment img);

    int imageHasAlpha(MemorySegment img);

    /** NULL-terminated {@code gchar**} of metadata field names (caller {@code g_strfreev}s). */
    MemorySegment imageGetFields(MemorySegment img);

    long imageGetTypeof(MemorySegment img, MemorySegment namePtr);

    int imageGetAsString(MemorySegment img, MemorySegment namePtr, MemorySegment outPtr);

    int imageGetBlob(MemorySegment img, MemorySegment namePtr,
                     MemorySegment dataPtr, MemorySegment lenPtr);

    /** GType of {@code VipsBlob}, to tell binary fields from text. */
    long blobType();

    // ---- decode / normalize ---------------------------------------------

    /**
     * Shrink-on-load thumbnail straight to a {@code VipsImage*} in
     * {@code *outPtr}: in {@link ThumbnailMode#FIT} it fits within a
     * {@code maxEdge} box, in {@link ThumbnailMode#FILL} it centre-crops to a
     * {@code maxEdge × maxEdge} square. Returns the vips return code (0 == ok).
     */
    int thumbnail(Arena arena, Path file, MemorySegment outPtr, int maxEdge, ThumbnailMode mode);

    /** {@code vips_colourspace(in, &out, space)}; returns the vips return code. */
    int colourspace(MemorySegment in, MemorySegment outPtr, int space);

    /** {@code vips_cast(in, &out, format)}; returns the vips return code. */
    int cast(MemorySegment in, MemorySegment outPtr, int format);

    /** {@code vips_addalpha(in, &out)}; returns the vips return code. */
    int addAlpha(MemorySegment in, MemorySegment outPtr);

    /** Copies the image out as tightly packed bytes; {@code *sizePtr} gets the length. */
    MemorySegment imageWriteToMemory(MemorySegment img, MemorySegment sizePtr);

    // ---- GLib lifetime ---------------------------------------------------

    void gObjectUnref(MemorySegment obj);

    void gFree(MemorySegment mem);

    void gStrfreev(MemorySegment strv);

    // ---- constants -------------------------------------------------------

    int formatUchar();

    int interpretationSRGB();

    int interpretationBW();
}
