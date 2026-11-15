package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.librawffm.libraw.LibRaw;
import io.github.ghosthack.librawffm.libraw.libraw_data_t;
import io.github.ghosthack.librawffm.libraw.libraw_image_sizes_t;
import io.github.ghosthack.librawffm.libraw.libraw_imgother_t;
import io.github.ghosthack.librawffm.libraw.libraw_iparams_t;
import io.github.ghosthack.librawffm.libraw.libraw_output_params_t;
import io.github.ghosthack.librawffm.libraw.libraw_processed_image_t;
import io.github.ghosthack.librawffm.libraw.libraw_thumbnail_item_t;
import io.github.ghosthack.librawffm.libraw.libraw_thumbnail_list_t;
import io.github.ghosthack.librawffm.libraw.libraw_thumbnail_t;
import io.github.ghosthack.mediabrowser.media.CorruptMediaException;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li><b>Full-resolution preview</b> (viewer only) — DNG 1.7 files with
 *       JPEG-XL-compressed raw tiles (iPhone 17-era; Compression 52546) are a
 *       stub in LibRaw unless it is built against the Adobe DNG SDK + libjxl:
 *       {@code libraw_unpack} returns {@code LIBRAW_FILE_UNSUPPORTED}. When
 *       exactly that happens and the file carries a preview at full raw
 *       resolution (Apple always writes one), the camera's own render is the
 *       decode. Capability routing on the reported unpack verdict, not a
 *       failure fallback: any other unpack error still surfaces loudly.
 *       Thumbnails take the preview on the same verdict.</li>
 * </ul>
 *
 * <p>Failures follow the backend's convention: a missing native means RAW
 * extensions are simply not claimed (logged loudly once); a file LibRaw
 * cannot decode surfaces as a {@link MediaException} carrying LibRaw's own
 * error at decode time, never as an empty result or a silent substitute
 * rendering. {@link Attempt} keeps the two apart: {@link Declined} is only
 * ever "this route does not apply"; a failure is thrown.</p>
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

    /**
     * One decode's verdict: pixels, or a genuine "this route does not apply"
     * for the caller to report. A failure is neither — it is thrown as a
     * {@link MediaException} carrying LibRaw's own error, the same split as
     * {@link TurboJpegStills.Attempt}.
     */
    sealed interface Attempt permits Decoded, Declined {}

    /**
     * Upright pixels and the route that produced them, for the engine trace;
     * {@code corruption} is the decoder's message when the pixels came from
     * damaged data (an embedded preview libjpeg-turbo decoded with a
     * corrupt-data warning), else null.
     */
    record Decoded(RasterFrame frame, String route, String corruption) implements Attempt {

        Decoded(RasterFrame frame, String route) {
            this(frame, route, null);
        }

        /** The same pixels and verdict under a longer route description. */
        Decoded withRoute(String route) {
            return new Decoded(frame, route, corruption);
        }
    }

    record Declined(String reason) implements Attempt {}

    /** The work done on one opened LibRaw handle. */
    private interface Session<T> {
        T run(MemorySegment lr, Arena arena);
    }

    /**
     * Opens {@code file} on a fresh handle, runs {@code body}, closes the
     * handle. Every failure leaves as a {@link MediaException} naming the file
     * and the cause; {@link Error}s (OutOfMemoryError, a broken native link)
     * are never caught.
     */
    private static <T> T withOpenFile(Path file, Session<T> body) {
        if (!available()) {
            throw new MediaException("libraw-ffm natives unavailable; cannot read "
                    + file.getFileName());
        }
        MemorySegment lr = LibRaw.libraw_init(0);
        if (lr.equals(MemorySegment.NULL)) {
            throw new MediaException("LibRaw cannot allocate a decoder for "
                    + file.getFileName() + " (libraw_init returned NULL)");
        }
        try (Arena arena = Arena.ofConfined()) {
            int rc = LibRaw.libraw_open_file(lr, arena.allocateFrom(file.toString()));
            if (rc != LibRaw.LIBRAW_SUCCESS()) {
                throw failure(file, "libraw_open_file", rc);
            }
            return body.run(lr, arena);
        } catch (MediaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MediaException("LibRaw failed on " + file.getFileName() + ": " + e, e);
        } finally {
            LibRaw.libraw_close(lr);
        }
    }

    /**
     * LibRaw reads this many header bytes before identifying a file and fails
     * any shorter file with {@code LIBRAW_IO_ERROR} whatever its bytes (0.22.2,
     * pinned by a test): on such a file the code says nothing about the data.
     */
    static final int IDENTIFY_HEADER_LENGTH = 64;

    /**
     * A LibRaw call's failure. {@code LIBRAW_DATA_ERROR} ("Corrupted data or
     * unexpected EOF", raised by LibRaw's raw/JPEG decoders) is corrupt.
     * {@code LIBRAW_IO_ERROR} is shared between a file cut short (LibRaw's
     * EOF and out-of-bounds exceptions) and a genuine read failure, so
     * {@link #ioFailure} tells them apart by reading the file itself.
     */
    private static MediaException failure(Path file, String call, int rc) {
        String what = call + " failed: " + describe(rc);
        if (rc == LibRaw.LIBRAW_DATA_ERROR()) {
            return new CorruptMediaException("LibRaw cannot decode " + file.getFileName()
                    + ": " + what);
        }
        if (rc == LibRaw.LIBRAW_IO_ERROR()) {
            return ioFailure(file, what);
        }
        return new MediaException("LibRaw cannot decode " + file.getFileName() + ": " + what);
    }

    /**
     * Splits a {@code LIBRAW_IO_ERROR} deterministically, on the failure path
     * only (no cost for a good file). The file is read start to end with
     * plain Java I/O: if that read fails, it is a read error, reported with
     * the {@link IOException} as cause. If it succeeds, the bytes were all
     * there and LibRaw still ran out of them — the data ends before the
     * structure it describes, so the file is truncated or damaged. A file
     * shorter than {@link #IDENTIFY_HEADER_LENGTH} is the one exception: LibRaw
     * rejected it unread, so the code is no evidence about its data (like
     * unrecognised bytes). An archive entry is checked the same way, on the
     * temp file it was materialised to.
     */
    static MediaException ioFailure(Path file, String what) {
        long length;
        try {
            length = readThrough(file);
        } catch (IOException e) {
            return new MediaException("LibRaw cannot read " + file.getFileName()
                    + ": " + what + "; " + e, e);
        }
        if (length < IDENTIFY_HEADER_LENGTH) {
            return new MediaException("LibRaw cannot decode " + file.getFileName()
                    + ": " + what + " (" + length + " bytes, shorter than LibRaw's "
                    + IDENTIFY_HEADER_LENGTH + "-byte identification header)");
        }
        return new CorruptMediaException("LibRaw cannot decode " + file.getFileName()
                + ": file is truncated or damaged (LibRaw: " + what + ")");
    }

    /** Reads {@code file} to its end, returning the byte count. */
    private static long readThrough(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(buffer)) >= 0; ) {
                total += n;
            }
            return total;
        }
    }

    /** LibRaw's own text for a return code; positive codes are errno values. */
    static String describe(int rc) {
        if (rc > 0) {
            return "system error, errno " + rc;
        }
        MemorySegment text = LibRaw.libraw_strerror(rc);
        return (text.equals(MemorySegment.NULL) ? "unknown error"
                : text.reinterpret(Long.MAX_VALUE).getString(0)) + " (" + rc + ")";
    }

    /**
     * Header facts, no pixel decode.
     *
     * @throws MediaException when LibRaw cannot open the file
     */
    static RawInfo info(Path file) {
        return withOpenFile(file, (lr, arena) -> {
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
            return new RawInfo(w, h,
                    libraw_iparams_t.make(idata).getString(0),
                    libraw_iparams_t.model(idata).getString(0),
                    libraw_imgother_t.iso_speed(other),
                    libraw_imgother_t.shutter(other),
                    libraw_imgother_t.aperture(other),
                    libraw_imgother_t.focal_len(other),
                    libraw_imgother_t.timestamp(other));
        });
    }

    /**
     * Thumbnail via the embedded preview when it covers the box (and TurboJPEG
     * is on), else via half-size raw decode. The preview routes decline by
     * capability (no preview, not JPEG, too small) and the route taken is
     * named in {@link Decoded#route}; {@link Declined} means LibRaw reports
     * the raw compression unsupported and no preview applies either.
     *
     * @throws MediaException on any open, unpack, process or preview-decode
     *         failure — never an empty result
     */
    static Attempt thumbnail(Path file, int maxEdge, ThumbnailMode mode, boolean turboJpeg) {
        if (!available()) {
            return new Declined("libraw-ffm natives unavailable");
        }
        if (maxEdge <= 0) {
            return new Declined("no target size");
        }
        return withOpenFile(file, (lr, arena) -> {
            String previewDeclined = null;
            if (turboJpeg) {
                Attempt preview = previewThumbnail(file, lr, maxEdge, mode);
                if (preview instanceof Declined declined) {
                    previewDeclined = declined.reason();
                } else {
                    return preview;
                }
            }
            int rc = LibRaw.libraw_unpack(lr);
            if (rc == LibRaw.LIBRAW_SUCCESS()) {
                RasterFrame frame = Thumbnails.scale(
                        processUnpacked(file, lr, arena, true), maxEdge, mode);
                return new Decoded(frame, previewDeclined == null ? "half-size demosaic"
                        : "half-size demosaic (embedded preview declined: "
                                + previewDeclined + ")");
            }
            if (rc != LibRaw.LIBRAW_FILE_UNSUPPORTED()) {
                throw failure(file, "libraw_unpack", rc);
            }
            String unsupported = "libraw_unpack: " + describe(rc);
            if (turboJpeg) {
                return new Declined(unsupported + "; embedded preview declined: "
                        + previewDeclined);
            }
            // JXL-stubbed DNGs under the plain ffmpeg-ffm backend: LibRaw
            // reports the raw decode unsupported, so the embedded preview is
            // the thumbnail — the same policy the -turbojpeg variant applies
            // first. Fresh handle: the failed unpack poisons this one
            // (unpack_thumb would fail as an out-of-order call).
            return switch (withOpenFile(file,
                    (fresh, a) -> previewThumbnail(file, fresh, maxEdge, mode))) {
                case Decoded d -> d.withRoute(
                        d.route() + " (raw decode unsupported: " + unsupported + ")");
                case Declined d -> new Declined(unsupported
                        + "; embedded preview declined: " + d.reason());
            };
        });
    }

    /**
     * Full-quality decode for the viewer: the full-size demosaic, or — only on
     * LibRaw's {@code LIBRAW_FILE_UNSUPPORTED} unpack verdict — the embedded
     * full-resolution preview. {@link Declined} means neither applies.
     *
     * @throws MediaException on any other open, unpack, process or
     *         preview-decode failure
     */
    static Attempt fullDecode(Path file) {
        if (!available()) {
            return new Declined("libraw-ffm natives unavailable");
        }
        return withOpenFile(file, (lr, arena) -> {
            int rc = LibRaw.libraw_unpack(lr);
            if (rc == LibRaw.LIBRAW_SUCCESS()) {
                return new Decoded(processUnpacked(file, lr, arena, false),
                        "full-size demosaic");
            }
            if (rc != LibRaw.LIBRAW_FILE_UNSUPPORTED()) {
                throw failure(file, "libraw_unpack", rc);
            }
            // JXL-compressed DNG (see class doc): the camera's full-res
            // render is the decode, not a downgraded stand-in. Fresh
            // handle: after a failed unpack, LibRaw rejects unpack_thumb
            // on this one as an out-of-order call.
            String unsupported = "libraw_unpack: " + describe(rc);
            return switch (withOpenFile(file, (fresh, a) -> fullResPreview(file, fresh))) {
                case Decoded d -> d.withRoute(
                        d.route() + " (raw decode unsupported: " + unsupported + ")");
                case Declined d -> new Declined(unsupported
                        + "; embedded preview declined: " + d.reason());
            };
        });
    }

    /**
     * The embedded preview as the viewer image, only when it matches the raw's
     * visible resolution — a smaller preview is not a full-quality decode.
     * Requires the TurboJPEG native (ships with both FFM backends).
     */
    private static Attempt fullResPreview(Path file, MemorySegment lr) {
        if (unpackThumb(file, lr) instanceof Declined declined) {
            return declined;
        }
        MemorySegment thumb = libraw_data_t.thumbnail(lr.reinterpret(libraw_data_t.sizeof()));
        int tw = Short.toUnsignedInt(libraw_thumbnail_t.twidth(thumb));
        int th = Short.toUnsignedInt(libraw_thumbnail_t.theight(thumb));
        int iw = LibRaw.libraw_get_iwidth(lr);
        int ih = LibRaw.libraw_get_iheight(lr);
        if (tw < iw || th < ih) {
            return new Declined("it is " + tw + "×" + th + ", smaller than the "
                    + iw + "×" + ih + " raw");
        }
        return decodePreview(file, lr, Math.max(tw, th), ThumbnailMode.FIT,
                "embedded full-resolution preview");
    }

    /**
     * Embedded JPEG preview through the TurboJPEG scaled decode, when it
     * covers the requested box.
     */
    private static Attempt previewThumbnail(Path file, MemorySegment lr,
                                            int maxEdge, ThumbnailMode mode) {
        if (unpackThumb(file, lr) instanceof Declined declined) {
            return declined;
        }
        MemorySegment thumb = libraw_data_t.thumbnail(lr.reinterpret(libraw_data_t.sizeof()));
        int tw = Short.toUnsignedInt(libraw_thumbnail_t.twidth(thumb));
        int th = Short.toUnsignedInt(libraw_thumbnail_t.theight(thumb));
        int covering = mode == ThumbnailMode.FILL ? Math.min(tw, th) : Math.max(tw, th);
        if (tw > 0 && th > 0 && covering < maxEdge) {
            // preview smaller than the box: decode raw instead
            return new Declined("it is " + tw + "×" + th + ", smaller than the "
                    + maxEdge + " px box");
        }
        return decodePreview(file, lr, maxEdge, mode, "embedded preview");
    }

    /**
     * {@code libraw_unpack_thumb}, then the JPEG check. Null when a JPEG
     * preview is loaded on the handle; a decline when the file has none LibRaw
     * can hand over as JPEG.
     *
     * @throws MediaException on any other unpack_thumb verdict (I/O, corrupt
     *         data, out of memory); a {@link CorruptMediaException} when the
     *         preview runs past the end of the file
     */
    private static Declined unpackThumb(Path file, MemorySegment lr) {
        int rc = LibRaw.libraw_unpack_thumb(lr);
        if (rc == LibRaw.LIBRAW_NO_THUMBNAIL()
                || rc == LibRaw.LIBRAW_UNSUPPORTED_THUMBNAIL()
                || rc == LibRaw.LIBRAW_REQUEST_FOR_NONEXISTENT_THUMBNAIL()) {
            return new Declined("libraw_unpack_thumb: " + describe(rc));
        }
        if (rc != LibRaw.LIBRAW_SUCCESS()) {
            throw failure(file, "libraw_unpack_thumb", rc);
        }
        MemorySegment data = lr.reinterpret(libraw_data_t.sizeof());
        MemorySegment thumb = libraw_data_t.thumbnail(data);
        if (libraw_thumbnail_t.tformat(thumb) != LibRaw.LIBRAW_THUMBNAIL_JPEG()
                || libraw_thumbnail_t.tlength(thumb) <= 0) {
            return new Declined("it is not JPEG");
        }
        requirePreviewInsideFile(file, data, thumb);
        return null;
    }

    /**
     * LibRaw's {@code unpack_thumb} succeeds on a preview the file was cut
     * inside: it keeps the declared length and leaves the missing tail as
     * whatever the allocation held, so decoding it would give a different
     * verdict run to run. The preview's extent comes from LibRaw's own
     * thumbnail list ({@code thumbs_list}: offset + length of the entries with
     * this preview's dimensions and length); when every such entry ends
     * past the end of the file, the file is truncated — reported before any
     * of those bytes are decoded. No matching entry: nothing to compare.
     */
    private static void requirePreviewInsideFile(Path file, MemorySegment data,
                                                 MemorySegment thumb) {
        MemorySegment list = libraw_data_t.thumbs_list(data);
        int count = Math.min(libraw_thumbnail_list_t.thumbcount(list),
                (int) libraw_thumbnail_list_t.thumblist$dimensions()[0]);
        MemorySegment items = libraw_thumbnail_list_t.thumblist(list);
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new MediaException("LibRaw cannot read " + file.getFileName()
                    + ": embedded preview; " + e, e);
        }
        long end = -1;
        for (int i = 0; i < count; i++) {
            MemorySegment item = items.asSlice(i * libraw_thumbnail_item_t.sizeof(),
                    libraw_thumbnail_item_t.sizeof());
            if (libraw_thumbnail_item_t.tlength(item) != libraw_thumbnail_t.tlength(thumb)
                    || libraw_thumbnail_item_t.twidth(item) != libraw_thumbnail_t.twidth(thumb)
                    || libraw_thumbnail_item_t.theight(item) != libraw_thumbnail_t.theight(thumb)) {
                continue;
            }
            long itemEnd = libraw_thumbnail_item_t.toffset(item)
                    + Integer.toUnsignedLong(libraw_thumbnail_item_t.tlength(item));
            if (itemEnd <= size) {
                return;
            }
            end = itemEnd;
        }
        if (end > size) {
            throw new CorruptMediaException("LibRaw cannot decode " + file.getFileName()
                    + ": file is truncated or damaged (embedded preview ends at byte " + end
                    + ", past the end of the " + size + "-byte file)");
        }
    }

    /**
     * The unpacked JPEG preview through {@link TurboJpegStills#decodeScaled}.
     * LibRaw's flip is the single orientation authority (the preview's own
     * EXIF, when present, describes the same rotation — applying both would
     * double it). A preview TurboJPEG declines (a capability gap) is declined
     * with that reason. A preview whose JPEG data is damaged is never declined
     * into the raw demosaic, which would hide the damage behind a substitute
     * rendering: one libjpeg-turbo fails on is this file's corrupt-data error,
     * one it decoded with a corrupt-data warning is shown and labelled
     * ({@link Decoded#corruption}).
     */
    private static Attempt decodePreview(Path file, MemorySegment lr, int maxEdge,
                                         ThumbnailMode mode, String route) {
        MemorySegment data = lr.reinterpret(libraw_data_t.sizeof());
        MemorySegment thumb = libraw_data_t.thumbnail(data);
        int tlength = libraw_thumbnail_t.tlength(thumb);
        byte[] jpeg = new byte[tlength];
        MemorySegment.copy(libraw_thumbnail_t.thumb(thumb).reinterpret(tlength),
                ValueLayout.JAVA_BYTE, 0, jpeg, 0, tlength);
        int orientation = flipToExifOrientation(
                libraw_image_sizes_t.flip(libraw_data_t.sizes(data)));
        TurboJpegStills.Attempt attempt;
        try {
            attempt = TurboJpegStills.decodeScaled(jpeg, maxEdge, mode);
        } catch (CorruptMediaException e) {
            throw new CorruptMediaException(file.getFileName()
                    + ": embedded JPEG preview: " + e.getMessage(), e);
        }
        return switch (attempt) {
            case TurboJpegStills.Declined declined ->
                    new Declined("TurboJPEG declined it: " + declined.reason());
            case TurboJpegStills.Decoded decoded -> previewDecoded(decoded, orientation, route);
        };
    }

    /**
     * A preview libjpeg-turbo decoded, as this file's pixels: upright per
     * LibRaw's flip, labelled corrupt when the decode warned about damaged
     * data ({@link TurboJpegStills#corruptionOf}) — the image is still shown,
     * never swapped for the demosaic. A harmless warning is a trace note in
     * the route only.
     */
    static Decoded previewDecoded(TurboJpegStills.Decoded decoded, int orientation, String route) {
        RasterFrame upright = RasterFrames.applyExifOrientation(decoded.frame(), orientation);
        String corruption = decoded.corruption();
        if (corruption != null) {
            return new Decoded(upright, route + "; damaged: libjpeg-turbo warning: " + corruption,
                    "embedded JPEG preview: " + corruption);
        }
        return new Decoded(upright, decoded.warning() == null ? route
                : route + "; libjpeg-turbo warning: " + decoded.warning());
    }

    /**
     * Demosaic the unpacked raw to 8-bit RGB (camera white balance, optional
     * half-size) and repack as BGRA. LibRaw applies the orientation flip in
     * {@code dcraw_make_mem_image}, so the frame arrives upright.
     *
     * @throws MediaException when LibRaw fails or hands back an image this
     *         repack does not understand
     */
    private static RasterFrame processUnpacked(Path file, MemorySegment lr, Arena arena,
                                               boolean halfSize) {
        MemorySegment data = lr.reinterpret(libraw_data_t.sizeof());
        MemorySegment params = libraw_data_t.params(data);
        libraw_output_params_t.use_camera_wb(params, 1);
        if (halfSize) {
            libraw_output_params_t.half_size(params, 1);
        }
        LibRaw.libraw_set_output_bps(lr, 8);
        int rc = LibRaw.libraw_dcraw_process(lr);
        if (rc != LibRaw.LIBRAW_SUCCESS()) {
            throw failure(file, "libraw_dcraw_process", rc);
        }
        MemorySegment errc = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment img = LibRaw.libraw_dcraw_make_mem_image(lr, errc);
        if (img.equals(MemorySegment.NULL)) {
            throw failure(file, "libraw_dcraw_make_mem_image",
                    errc.get(ValueLayout.JAVA_INT, 0));
        }
        try {
            MemorySegment hdr = img.reinterpret(libraw_processed_image_t.sizeof());
            int w = Short.toUnsignedInt(libraw_processed_image_t.width(hdr));
            int h = Short.toUnsignedInt(libraw_processed_image_t.height(hdr));
            int colors = Short.toUnsignedInt(libraw_processed_image_t.colors(hdr));
            int bits = Short.toUnsignedInt(libraw_processed_image_t.bits(hdr));
            int dataSize = libraw_processed_image_t.data_size(hdr);
            int type = libraw_processed_image_t.type(hdr);
            if (type != LibRaw.LIBRAW_IMAGE_BITMAP()
                    || colors != 3 || bits != 8 || (long) w * h * 3 != dataSize) {
                throw new MediaException("LibRaw cannot decode " + file.getFileName()
                        + ": unexpected processed image (type " + type + ", " + w + "×" + h
                        + ", " + colors + " colors, " + bits + " bits, " + dataSize
                        + " bytes); expected an 8-bit RGB bitmap");
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
            return new RasterFrame(w, h, bgra);
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
