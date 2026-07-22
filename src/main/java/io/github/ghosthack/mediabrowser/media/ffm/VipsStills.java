package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.VisualResult;
import io.github.ghosthack.mediabrowser.media.ffm.bind.VipsBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Still-image probing and decoding through a {@link VipsBindings}.
 *
 * <p>Decoding normalizes every image to 8-bit sRGB (or mono) with an alpha
 * band via libvips operations, then converts the interleaved bytes to BGRA
 * for JavaFX. All native access goes through the injected bindings.</p>
 */
final class VipsStills {

    private final VipsBindings vips;

    VipsStills(VipsBindings vips) {
        this.vips = vips;
    }

    String version() {
        return vips.version();
    }

    /** Loader-sniffing probe: operation name (e.g. VipsForeignLoadPngFile) or null. */
    String findLoader(Path file) {
        try (Arena arena = Arena.ofConfined()) {
            return vips.findLoad(arena, file);
        }
    }

    MediaProbe probe(Path file, long fileSize, String loader) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment img = vips.imageNewFromFile(arena, file);
            try {
                return describe(img, file, fileSize, loader);
            } finally {
                vips.gObjectUnref(img);
            }
        }
    }

    /**
     * Reads the probe metadata off an opened image. Must run before any
     * normalization: it describes the original bands/format, not the
     * 8-bit + alpha raster the decode produces.
     */
    private MediaProbe describe(MemorySegment img, Path file, long fileSize, String loader) {
        int bands = vips.imageGetBands(img);
        String pixels = bands + " band(s), " + formatName(vips.imageGetFormat(img));
        return new MediaProbe(file, MediaKind.IMAGE, prettyLoader(loader), fileSize,
                -1, -1,
                vips.imageGetWidth(img), vips.imageGetHeight(img),
                null, -1, null, -1, -1, pixels);
    }

    VisualResult decodeWithProbe(Path file, long fileSize, String loader) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment img = vips.imageNewFromFile(arena, file);
            MediaProbe probe = describe(img, file, fileSize, loader);
            // extractBgra takes ownership of img, applies EXIF orientation, and
            // unrefs it (even on error). Probe dimensions stay pre-orientation,
            // matching the thumbnail/pure convention.
            return new VisualResult(probe, Optional.of(extractBgra(arena, img)));
        }
    }

    /**
     * EXIF orientation (1..8) from the {@code orientation} metadata field vips
     * loaders populate from the EXIF Orientation tag; 1 (upright) when absent or
     * unreadable. Read at the top of {@link #extractBgra}, before normalization
     * swaps {@code img} through derived images.
     */
    private int readOrientation(Arena arena, MemorySegment img) {
        MemorySegment namePtr = arena.allocateFrom("orientation");
        if (vips.imageGetTypeof(img, namePtr) == 0) {
            return 1; // no orientation field
        }
        MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
        if (vips.imageGetAsString(img, namePtr, outPtr) != 0) {
            return 1;
        }
        MemorySegment strPtr = outPtr.get(ValueLayout.ADDRESS, 0);
        if (strPtr.equals(MemorySegment.NULL)) {
            return 1;
        }
        try {
            int v = Integer.parseInt(strPtr.reinterpret(Long.MAX_VALUE).getString(0).strip());
            return (v >= 1 && v <= 8) ? v : 1;
        } catch (NumberFormatException e) {
            return 1;
        } finally {
            vips.gFree(strPtr);
        }
    }

    /**
     * Shrink-on-load preview: decodes the file directly to a raster (libvips
     * uses libjpeg DCT scaling and the like, never materializing the full
     * image). In {@link ThumbnailMode#FIT} the result fits within a
     * {@code maxEdge} box; in {@link ThumbnailMode#FILL} it is centre-cropped to a
     * {@code maxEdge × maxEdge} square (vips crops as part of the shrink, so no
     * full-size raster is built). EXIF orientation is <em>not</em> applied here:
     * the binding passes {@code no_rotate=TRUE}, and {@link #extractBgra} bakes
     * orientation in afterwards — the same step the full-decode viewer path runs,
     * so the tile and the viewer never disagree.
     */
    RasterFrame thumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
            int rc = vips.thumbnail(arena, file, outPtr, maxEdge, mode);
            if (rc != 0) {
                throw new MediaException("vips: thumbnail failed for "
                        + file.getFileName() + ": " + vips.takeError());
            }
            // extractBgra takes ownership of the thumbnail image and unrefs it.
            return extractBgra(arena, outPtr.get(ValueLayout.ADDRESS, 0));
        }
    }

    /**
     * Normalizes an opened image to 8-bit sRGB (or mono) with an alpha band,
     * copies it out as tightly packed BGRA, and bakes in its EXIF orientation.
     * Takes ownership of {@code img} and releases it (the normalization steps
     * swap in derived images, each unref'ing its predecessor), so the caller must
     * not unref it afterwards.
     *
     * <p>This is the single shared decode tail for both still paths, so the
     * orientation step here is what keeps the mosaic thumbnail and the viewer in
     * agreement (see {@link #thumbnail} and {@link #decodeWithProbe}).</p>
     */
    private RasterFrame extractBgra(Arena arena, MemorySegment img) {
        try {
            // Both callers reach here with an un-rotated image still carrying its
            // orientation tag: the viewer path opens it raw (vips_image_new_from_file
            // never auto-rotates), and the thumbnail path runs vips_thumbnail with
            // no_rotate=TRUE. Reading + applying orientation here is the single
            // shared step that keeps the viewer and the mosaic tile in agreement.
            int orientation = readOrientation(arena, img);
            int srgb = vips.interpretationSRGB();
            int interp = vips.imageGetInterpretation(img);
            if (interp != srgb && interp != vips.interpretationBW()) {
                img = toColourspace(arena, img, srgb);
            }
            if (vips.imageGetFormat(img) != vips.formatUchar()) {
                img = castUchar(arena, img);
            }
            if (vips.imageHasAlpha(img) == 0) {
                img = addAlpha(arena, img);
            }
            int w = vips.imageGetWidth(img);
            int h = vips.imageGetHeight(img);
            int bands = vips.imageGetBands(img);

            MemorySegment sizePtr = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment mem = vips.imageWriteToMemory(img, sizePtr);
            if (mem.equals(MemorySegment.NULL)) {
                throw new MediaException("vips: write_to_memory failed: " + vips.takeError());
            }
            byte[] raw;
            try {
                raw = mem.reinterpret(sizePtr.get(ValueLayout.JAVA_LONG, 0))
                         .toArray(ValueLayout.JAVA_BYTE);
            } finally {
                vips.gFree(mem);
            }
            return RasterFrames.applyExifOrientation(toBgra(w, h, bands, raw), orientation);
        } finally {
            vips.gObjectUnref(img);
        }
    }

    private MemorySegment toColourspace(Arena arena, MemorySegment in, int space) {
        MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
        return swap(in, vips.colourspace(in, outPtr, space), outPtr);
    }

    private MemorySegment castUchar(Arena arena, MemorySegment in) {
        MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
        return swap(in, vips.cast(in, outPtr, vips.formatUchar()), outPtr);
    }

    private MemorySegment addAlpha(Arena arena, MemorySegment in) {
        MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
        return swap(in, vips.addAlpha(in, outPtr), outPtr);
    }

    /** Releases the input image; returns the operation output (or throws). */
    private MemorySegment swap(MemorySegment in, int rc, MemorySegment outPtr) {
        vips.gObjectUnref(in);
        if (rc != 0) {
            throw new MediaException("vips: operation failed: " + vips.takeError());
        }
        return outPtr.get(ValueLayout.ADDRESS, 0);
    }

    private static RasterFrame toBgra(int w, int h, int bands, byte[] raw) {
        int n = w * h;
        byte[] bgra = new byte[n * 4];
        switch (bands) {
            case 4 -> { // RGBA -> BGRA
                for (int i = 0; i < n; i++) {
                    int s = i * 4;
                    bgra[s] = raw[s + 2];
                    bgra[s + 1] = raw[s + 1];
                    bgra[s + 2] = raw[s];
                    bgra[s + 3] = raw[s + 3];
                }
            }
            case 2 -> { // grey + alpha
                for (int i = 0; i < n; i++) {
                    int s = i * 2, d = i * 4;
                    byte g = raw[s];
                    bgra[d] = g;
                    bgra[d + 1] = g;
                    bgra[d + 2] = g;
                    bgra[d + 3] = raw[s + 1];
                }
            }
            default -> throw new MediaException("vips: unexpected band count " + bands);
        }
        return new RasterFrame(w, h, bgra);
    }

    private static String prettyLoader(String loader) {
        if (loader == null) return null;
        String s = loader;
        if (s.startsWith("VipsForeignLoad")) s = s.substring("VipsForeignLoad".length());
        for (String suffix : new String[] {"File", "Buffer", "Source"}) {
            if (s.endsWith(suffix)) s = s.substring(0, s.length() - suffix.length());
        }
        return s.isEmpty() ? loader : s.toUpperCase() + " image";
    }

    private static String formatName(int format) {
        return switch (format) {
            case 0 -> "8-bit unsigned";
            case 1 -> "8-bit signed";
            case 2 -> "16-bit unsigned";
            case 3 -> "16-bit signed";
            case 4 -> "32-bit unsigned";
            case 5 -> "32-bit signed";
            case 6 -> "float";
            case 8 -> "double";
            default -> "format " + format;
        };
    }
}
