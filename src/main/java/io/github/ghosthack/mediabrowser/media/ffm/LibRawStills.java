package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.librawffm.libraw.LibRaw;
import io.github.ghosthack.librawffm.libraw.libraw_data_t;
import io.github.ghosthack.librawffm.libraw.libraw_image_sizes_t;
import io.github.ghosthack.librawffm.libraw.libraw_imgother_t;
import io.github.ghosthack.librawffm.libraw.libraw_iparams_t;
import io.github.ghosthack.librawffm.libraw.libraw_output_params_t;
import io.github.ghosthack.librawffm.libraw.libraw_processed_image_t;
import io.github.ghosthack.librawffm.libraw.libraw_thumbnail_t;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Camera-RAW capability route over LibRaw (the
 * {@code io.github.ghosthack:libraw-ffm} artifact) for the bundled-FFmpeg
 * backends: a format family FFmpeg does not cover, routed by extension the
 * same way baseline JPEG routes to {@link TurboJpegStills}.
 *
 * <p>Two decode paths, chosen per file:
 * <ul>
 *   <li><b>Embedded preview</b> — {@code libraw_unpack_thumb} extracts the
 *       camera's JPEG preview (full-resolution on most cameras) without
 *       touching raw pixels; the bytes go through
 *       {@link TurboJpegStills#decodeScaled} (shrink-on-load), and LibRaw's
 *       flip — not the preview's own EXIF, which LibRaw already folded in —
 *       is applied on top. Used only when the preview covers the requested
 *       box and the TurboJPEG native is present.</li>
 *   <li><b>Raw decode</b> — {@code half_size} demosaic for thumbnails
 *       (half of a 12+ MP raw covers any grid tile), full-size for the
 *       viewer. Camera white balance, 8-bit output; LibRaw applies the
 *       orientation flip itself in {@code dcraw_make_mem_image}. Some phone
 *       DNGs (e.g. Samsung) carry no preview at all, so this path is not an
 *       edge case.</li>
 * </ul>
 *
 * <p>Failures follow the backend's convention: a missing native means RAW
 * extensions are simply not claimed (logged loudly once); a file LibRaw
 * cannot decode surfaces as an error at decode time, never as a silent
 * substitute rendering.</p>
 */
final class LibRawStills {

    /** Extensions routed to LibRaw. Kept to formats LibRaw actually decodes. */
    private static final Set<String> RAW_EXTENSIONS = Set.of(
            "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2",
            "raf", "orf", "rw2", "pef", "srw", "erf", "kdc", "dcr", "mos",
            "mrw", "rwl", "iiq", "3fr", "fff", "mef", "x3f");

    private static volatile Boolean available;

    private LibRawStills() {}

    static boolean isRaw(String extension) {
        return RAW_EXTENSIONS.contains(extension);
    }

    /** Whether the native loaded on this platform (probed once, on first use). */
    static boolean available() {
        Boolean a = available;
        if (a == null) {
            synchronized (LibRawStills.class) {
                a = available;
                if (a == null) {
                    available = a = probe();
                }
            }
        }
        return a;
    }

    private static boolean probe() {
        try {
            return LibRaw.libraw_versionNumber() > 0;
        } catch (Throwable t) {
            System.err.println("[LibRawStills] camera-RAW route unavailable: " + t);
            return false;
        }
    }

    /** LibRaw library version, e.g. {@code "0.22.2"}, for nativeVersions(). */
    static String version() {
        return LibRaw.libraw_version().reinterpret(64).getString(0);
    }

    /** Probe-level facts readable without decoding raw pixels. */
    record RawInfo(int width, int height, String make, String model,
                   float isoSpeed, float shutter, float aperture, float focalLen,
                   long timestampSeconds) {}

    static Optional<RawInfo> info(Path file) {
        if (!available()) {
            return Optional.empty();
        }
        MemorySegment lr = LibRaw.libraw_init(0);
        if (lr.equals(MemorySegment.NULL)) {
            return Optional.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            if (LibRaw.libraw_open_file(lr, arena.allocateFrom(file.toString()))
                    != LibRaw.LIBRAW_SUCCESS()) {
                return Optional.empty();
            }
            MemorySegment data = lr.reinterpret(libraw_data_t.sizeof());
            int w = LibRaw.libraw_get_iwidth(lr);
            int h = LibRaw.libraw_get_iheight(lr);
            int flip = libraw_image_sizes_t.flip(libraw_data_t.sizes(data));
            if (flip == 5 || flip == 6) {
                int t = w;
                w = h;
                h = t;
            }
            MemorySegment idata = libraw_data_t.idata(data);
            MemorySegment other = libraw_data_t.other(data);
            return Optional.of(new RawInfo(w, h,
                    libraw_iparams_t.make(idata).getString(0),
                    libraw_iparams_t.model(idata).getString(0),
                    libraw_imgother_t.iso_speed(other),
                    libraw_imgother_t.shutter(other),
                    libraw_imgother_t.aperture(other),
                    libraw_imgother_t.focal_len(other),
                    libraw_imgother_t.timestamp(other)));
        } catch (Throwable t) {
            return Optional.empty();
        } finally {
            LibRaw.libraw_close(lr);
        }
    }

    /**
     * Thumbnail via the embedded preview when it covers the box (and TurboJPEG
     * is on), else via half-size raw decode. Empty = this file is not
     * decodable here; the caller surfaces that, it does not substitute.
     */
    static Optional<RasterFrame> thumbnail(Path file, int maxEdge, ThumbnailMode mode,
                                           boolean turboJpeg) {
        if (!available() || maxEdge <= 0) {
            return Optional.empty();
        }
        MemorySegment lr = LibRaw.libraw_init(0);
        if (lr.equals(MemorySegment.NULL)) {
            return Optional.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            if (LibRaw.libraw_open_file(lr, arena.allocateFrom(file.toString()))
                    != LibRaw.LIBRAW_SUCCESS()) {
                return Optional.empty();
            }
            if (turboJpeg) {
                Optional<RasterFrame> preview = previewThumbnail(lr, maxEdge, mode);
                if (preview.isPresent()) {
                    return preview;
                }
            }
            return rawDecode(lr, arena, true)
                    .map(f -> Thumbnails.scale(f, maxEdge, mode));
        } catch (Throwable t) {
            return Optional.empty();
        } finally {
            LibRaw.libraw_close(lr);
        }
    }

    /** Full-quality decode for the viewer. */
    static Optional<RasterFrame> fullDecode(Path file) {
        if (!available()) {
            return Optional.empty();
        }
        MemorySegment lr = LibRaw.libraw_init(0);
        if (lr.equals(MemorySegment.NULL)) {
            return Optional.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            if (LibRaw.libraw_open_file(lr, arena.allocateFrom(file.toString()))
                    != LibRaw.LIBRAW_SUCCESS()) {
                return Optional.empty();
            }
            return rawDecode(lr, arena, false);
        } catch (Throwable t) {
            return Optional.empty();
        } finally {
            LibRaw.libraw_close(lr);
        }
    }

    /**
     * Embedded JPEG preview through the TurboJPEG scaled decode. LibRaw's flip
     * is the single orientation authority (the preview's own EXIF, when
     * present, describes the same rotation — applying both would double it).
     */
    private static Optional<RasterFrame> previewThumbnail(MemorySegment lr,
                                                          int maxEdge, ThumbnailMode mode) {
        if (LibRaw.libraw_unpack_thumb(lr) != LibRaw.LIBRAW_SUCCESS()) {
            return Optional.empty();
        }
        MemorySegment data = lr.reinterpret(libraw_data_t.sizeof());
        MemorySegment thumb = libraw_data_t.thumbnail(data);
        int tlength = libraw_thumbnail_t.tlength(thumb);
        if (libraw_thumbnail_t.tformat(thumb) != LibRaw.LIBRAW_THUMBNAIL_JPEG() || tlength <= 0) {
            return Optional.empty();
        }
        int tw = Short.toUnsignedInt(libraw_thumbnail_t.twidth(thumb));
        int th = Short.toUnsignedInt(libraw_thumbnail_t.theight(thumb));
        int covering = mode == ThumbnailMode.FILL ? Math.min(tw, th) : Math.max(tw, th);
        if (tw > 0 && th > 0 && covering < maxEdge) {
            return Optional.empty(); // preview smaller than the box: decode raw instead
        }
        byte[] jpeg = new byte[tlength];
        MemorySegment.copy(libraw_thumbnail_t.thumb(thumb).reinterpret(tlength),
                ValueLayout.JAVA_BYTE, 0, jpeg, 0, tlength);
        int orientation = flipToExifOrientation(
                libraw_image_sizes_t.flip(libraw_data_t.sizes(data)));
        return TurboJpegStills.decodeScaled(jpeg, maxEdge, mode)
                .map(f -> RasterFrames.applyExifOrientation(f, orientation));
    }

    /**
     * Demosaic to 8-bit RGB (camera white balance, optional half-size) and
     * repack as BGRA. LibRaw applies the orientation flip in
     * {@code dcraw_make_mem_image}, so the frame arrives upright.
     */
    private static Optional<RasterFrame> rawDecode(MemorySegment lr, Arena arena,
                                                   boolean halfSize) {
        if (LibRaw.libraw_unpack(lr) != LibRaw.LIBRAW_SUCCESS()) {
            return Optional.empty();
        }
        MemorySegment data = lr.reinterpret(libraw_data_t.sizeof());
        MemorySegment params = libraw_data_t.params(data);
        libraw_output_params_t.use_camera_wb(params, 1);
        if (halfSize) {
            libraw_output_params_t.half_size(params, 1);
        }
        LibRaw.libraw_set_output_bps(lr, 8);
        if (LibRaw.libraw_dcraw_process(lr) != LibRaw.LIBRAW_SUCCESS()) {
            return Optional.empty();
        }
        MemorySegment errc = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment img = LibRaw.libraw_dcraw_make_mem_image(lr, errc);
        if (img.equals(MemorySegment.NULL)) {
            return Optional.empty();
        }
        try {
            MemorySegment hdr = img.reinterpret(libraw_processed_image_t.sizeof());
            int w = Short.toUnsignedInt(libraw_processed_image_t.width(hdr));
            int h = Short.toUnsignedInt(libraw_processed_image_t.height(hdr));
            int colors = Short.toUnsignedInt(libraw_processed_image_t.colors(hdr));
            int bits = Short.toUnsignedInt(libraw_processed_image_t.bits(hdr));
            int dataSize = libraw_processed_image_t.data_size(hdr);
            if (libraw_processed_image_t.type(hdr) != LibRaw.LIBRAW_IMAGE_BITMAP()
                    || colors != 3 || bits != 8 || (long) w * h * 3 != dataSize) {
                return Optional.empty();
            }
            byte[] rgb = new byte[dataSize];
            MemorySegment.copy(img.reinterpret(libraw_processed_image_t.data$offset() + dataSize),
                    ValueLayout.JAVA_BYTE, libraw_processed_image_t.data$offset(),
                    rgb, 0, dataSize);
            byte[] bgra = new byte[w * h * 4];
            for (int i = 0, o = 0; i < dataSize; i += 3, o += 4) {
                bgra[o] = rgb[i + 2];
                bgra[o + 1] = rgb[i + 1];
                bgra[o + 2] = rgb[i];
                bgra[o + 3] = (byte) 0xFF;
            }
            return Optional.of(new RasterFrame(w, h, bgra));
        } finally {
            LibRaw.libraw_dcraw_clear_mem(img);
        }
    }

    /** dcraw flip values (0/3/5/6) to EXIF orientation (1/3/8/6). */
    private static int flipToExifOrientation(int flip) {
        return switch (flip) {
            case 3 -> 3;
            case 5 -> 8;
            case 6 -> 6;
            default -> 1;
        };
    }
}
