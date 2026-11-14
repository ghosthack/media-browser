package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.ColorProfile;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.mediabrowser.media.color.EmbeddedProfileReader;
import io.github.ghosthack.turbojpegffm.turbojpeg.TurboJpeg;
import io.github.ghosthack.turbojpegffm.turbojpeg.tjscalingfactor;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JPEG thumbnail fast path over libjpeg-turbo (the
 * {@code io.github.ghosthack:turbojpeg-ffm} artifact): decodes at the smallest
 * DCT scaling factor that still covers the requested box (libjpeg's
 * shrink-on-load), then hands the small raster to {@link Thumbnails#scale} for
 * the exact geometry and {@link RasterFrames#applyExifOrientation} for the
 * EXIF orientation (all eight values) — the same baking convention as the
 * TwelveMonkeys facade. Used only by the explicit {@code ffmpeg-ffm-turbojpeg}
 * backend variant ({@link FfmpegFfmMediaFacade#withTurboJpeg()}); there is no
 * system-property switch.
 *
 * <p><b>Declines instead of guessing.</b> {@link #attempt} declines anything
 * outside the well-trodden path — progressive, CMYK/YCCK, lossless, 12/16-bit, or bytes
 * that are not JPEG at all — and the caller decodes via FFmpeg instead
 * (capability routing, documented per backend). A native failure on a JPEG
 * the path did claim is not a decline: it throws, with libjpeg-turbo's own
 * message. A missing native surfaces loudly at backend creation via
 * {@link #available()}, never as silent degradation.</p>
 */
final class TurboJpegStills {

    private static volatile Boolean available;

    private TurboJpegStills() {}

    /** Whether the native loaded on this platform (probed once, on first use). */
    static boolean available() {
        Boolean a = available;
        if (a == null) {
            synchronized (TurboJpegStills.class) {
                a = available;
                if (a == null) {
                    available = a = probe();
                }
            }
        }
        return a;
    }

    private static boolean probe() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment n = arena.allocate(ValueLayout.JAVA_INT);
            return !TurboJpeg.tj3GetScalingFactors(n).equals(MemorySegment.NULL);
        } catch (Throwable t) {
            System.err.println("[TurboJpegStills] fast path unavailable: " + t);
            return false;
        }
    }

    /**
     * The fast path's verdict on one JPEG, from a single decode: pixels, or a
     * genuine capability decline for FFmpeg to take. A native failure is
     * neither — {@link #attempt} throws it.
     */
    sealed interface Attempt permits Decoded, Declined {}

    /**
     * Upright, sized pixels; {@code colorProfile} when requested and embedded;
     * {@code warning} is libjpeg-turbo's non-fatal complaint about the data
     * (e.g. extraneous bytes), or null.
     */
    record Decoded(RasterFrame frame, ColorProfile colorProfile, String warning)
            implements Attempt {}

    record Declined(String reason) implements Attempt {}

    /** Runs as each fast-path attempt starts; a no-op outside tests. */
    static volatile Runnable onAttempt = () -> {};

    /**
     * Scaled decode of a baseline YCbCr-or-grayscale JPEG, upright per EXIF,
     * sized per {@code maxEdge}/{@code mode}, with the ICC profile extracted
     * from the same bytes when {@code withProfile}.
     *
     * @throws MediaException when the file cannot be read or libjpeg-turbo
     *         fails on it — never reported as a decline
     */
    static Attempt attempt(Path file, int maxEdge, ThumbnailMode mode, boolean withProfile) {
        onAttempt.run();
        if (!available()) return new Declined("turbojpeg-ffm natives unavailable");
        if (maxEdge <= 0) return new Declined("no target size");
        byte[] jpeg;
        try {
            jpeg = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new MediaException("cannot read " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
        Attempt attempt = decode(jpeg, maxEdge, mode);
        if (!(attempt instanceof Decoded decoded)) return attempt;
        int orientation = exifOrientation(jpeg);
        ColorProfile profile = withProfile
                ? EmbeddedProfileReader.readJpeg(jpeg).orElse(null) : null;
        return new Decoded(RasterFrames.applyExifOrientation(decoded.frame(), orientation),
                profile, decoded.warning());
    }

    /**
     * The scaled-decode core on raw JPEG bytes, orientation NOT applied —
     * for callers whose orientation authority is outside the JPEG (e.g. the
     * {@link LibRawStills} embedded-preview path, where LibRaw's flip wins).
     * Same verdict split as {@link #attempt}: a decline is a capability gap
     * the caller routes on, a data warning rides on {@link Decoded#warning}
     * for the caller to judge.
     *
     * @throws MediaException when libjpeg-turbo fails on the bytes — never
     *         reported as a decline
     */
    static Attempt decodeScaled(byte[] jpeg, int maxEdge, ThumbnailMode mode) {
        if (!available()) return new Declined("turbojpeg-ffm natives unavailable");
        if (maxEdge <= 0) return new Declined("no target size");
        return decode(jpeg, maxEdge, mode);
    }

    private static Attempt decode(byte[] jpeg, int maxEdge, ThumbnailMode mode) {
        if (jpeg.length < 3 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8
                || (jpeg[2] & 0xFF) != 0xFF) {
            return new Declined("not JPEG data (no SOI marker)");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = TurboJpeg.tj3InitVersion(
                    TurboJpeg.TJINIT_DECOMPRESS(), TurboJpeg.TURBOJPEG_VERSION_NUMBER());
            if (handle.equals(MemorySegment.NULL)) {
                throw new MediaException("TurboJPEG cannot create a decompressor: "
                        + errorString(MemorySegment.NULL));
            }
            try {
                MemorySegment buf = arena.allocate(jpeg.length);
                MemorySegment.copy(jpeg, 0, buf, ValueLayout.JAVA_BYTE, 0, jpeg.length);
                String warning = check(handle,
                        TurboJpeg.tj3DecompressHeader(handle, buf, jpeg.length), "header");
                int w = TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_JPEGWIDTH());
                int h = TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_JPEGHEIGHT());
                int cs = TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_COLORSPACE());
                if (w <= 0 || h <= 0) {
                    throw new MediaException("TurboJPEG header reports " + w + "×" + h);
                }
                if (cs == TurboJpeg.TJCS_CMYK()) return new Declined("CMYK JPEG");
                if (cs == TurboJpeg.TJCS_YCCK()) return new Declined("YCCK JPEG");
                if (TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_LOSSLESS()) != 0) {
                    return new Declined("lossless JPEG");
                }
                // tj3Decompress8 takes 8-bit samples only; a 12- or 16-bit
                // JPEG is a capability gap, not a broken file — FFmpeg
                // decodes it.
                int precision = TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_PRECISION());
                if (precision != 8) {
                    return new Declined(precision + "-bit JPEG");
                }
                // Progressive: every scan spans the full image, so DCT scaling
                // saves little and FFmpeg measures ~35% faster — decline.
                if (TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_PROGRESSIVE()) != 0) {
                    return new Declined("progressive JPEG (FFmpeg decodes these faster)");
                }

                int[] scaled = pickScalingFactor(arena, handle, w, h, maxEdge, mode);
                int sw = scaled[0], sh = scaled[1];
                MemorySegment dst = arena.allocate((long) sw * sh * 4);
                String decodeWarning = check(handle, TurboJpeg.tj3Decompress8(
                        handle, buf, jpeg.length, dst, sw * 4, TurboJpeg.TJPF_BGRA()), "decode");
                if (decodeWarning != null) warning = decodeWarning;
                byte[] bgra = new byte[sw * sh * 4];
                MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0, bgra, 0, bgra.length);

                return new Decoded(Thumbnails.scale(new RasterFrame(sw, sh, bgra), maxEdge, mode),
                        null, warning);
            } finally {
                TurboJpeg.tj3Destroy(handle);
            }
        }
    }

    /**
     * Null on success; libjpeg-turbo's message for a non-fatal warning (the
     * output is still produced); throws on a fatal error.
     */
    private static String check(MemorySegment handle, int status, String stage) {
        if (status == 0) return null;
        // Read the code first: fetching the string clears the instance error.
        boolean warning = TurboJpeg.tj3GetErrorCode(handle) == TurboJpeg.TJERR_WARNING();
        String message = errorString(handle);
        if (warning) return message;
        throw new MediaException("TurboJPEG " + stage + " failed: " + message);
    }

    private static String errorString(MemorySegment handle) {
        MemorySegment text = TurboJpeg.tj3GetErrorStr(handle);
        return text.equals(MemorySegment.NULL) ? "unknown error"
                : text.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * Smallest supported DCT scaling factor that still covers {@code maxEdge}
     * (never upscales), applied to the handle. FIT needs the scaled long edge
     * to cover the box; FILL center-crops a square, so the scaled short edge
     * must cover it. Returns the scaled {@code {width, height}}.
     *
     * @throws MediaException when libjpeg-turbo offers or accepts no factor
     */
    private static int[] pickScalingFactor(Arena arena, MemorySegment handle,
                                           int w, int h, int maxEdge, ThumbnailMode mode) {
        MemorySegment countPtr = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment factors = TurboJpeg.tj3GetScalingFactors(countPtr);
        int count = countPtr.get(ValueLayout.JAVA_INT, 0);
        if (factors.equals(MemorySegment.NULL) || count <= 0) {
            throw new MediaException("TurboJPEG offers no scaling factors: "
                    + errorString(MemorySegment.NULL));
        }
        factors = factors.reinterpret(tjscalingfactor.sizeof() * count);
        int bestNum = 1, bestDenom = 1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            MemorySegment f = tjscalingfactor.asSlice(factors, i);
            int num = tjscalingfactor.num(f), denom = tjscalingfactor.denom(f);
            if (num > denom) {
                continue; // never upscale
            }
            int sw = tjScaled(w, num, denom), sh = tjScaled(h, num, denom);
            int covering = mode == ThumbnailMode.FILL ? Math.min(sw, sh) : Math.max(sw, sh);
            if (covering < maxEdge && (num != denom)) {
                continue; // would undershoot the box (full size is always acceptable)
            }
            long area = (long) sw * sh;
            if (area < bestArea) {
                bestArea = area;
                bestNum = num;
                bestDenom = denom;
            }
        }
        MemorySegment chosen = tjscalingfactor.allocate(arena);
        tjscalingfactor.num(chosen, bestNum);
        tjscalingfactor.denom(chosen, bestDenom);
        if (TurboJpeg.tj3SetScalingFactor(handle, chosen) != 0) {
            throw new MediaException("TurboJPEG rejected scaling factor " + bestNum + "/"
                    + bestDenom + ": " + errorString(handle));
        }
        return new int[] {tjScaled(w, bestNum, bestDenom), tjScaled(h, bestNum, bestDenom)};
    }

    /** {@code TJSCALED}: dimension under a num/denom scaling factor, rounded up. */
    private static int tjScaled(int dim, int num, int denom) {
        return (int) (((long) dim * num + denom - 1) / denom);
    }

    /**
     * The EXIF orientation of a JPEG file, read from its head (128 KB is
     * ample: the APP1 segment precedes the image data). Needs no native —
     * usable even where the turbojpeg fast path is unavailable.
     */
    static int exifOrientation(Path file) {
        try (var in = Files.newInputStream(file)) {
            return exifOrientation(in.readNBytes(128 * 1024));
        } catch (IOException e) {
            return 1;
        }
    }

    /**
     * The EXIF orientation (1..8) from the APP1 segment, or 1 when absent or
     * unparseable. Bounded scan: SOI, then marker segments until SOS.
     */
    static int exifOrientation(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return 1; // SOS/EOI: no EXIF before image data
            }
            int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpeg.length) {
                return 1;
            }
            if (marker == 0xE1 && len >= 10
                    && jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x' && jpeg[pos + 6] == 'i'
                    && jpeg[pos + 7] == 'f' && jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                return tiffOrientation(jpeg, pos + 10, pos + 2 + len);
            }
            pos += 2 + len;
        }
        return 1;
    }

    /** Orientation tag (0x0112) from IFD0 of the TIFF block at {@code [tiff, end)}. */
    private static int tiffOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean le;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            le = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            le = false;
        } else {
            return 1;
        }
        if (u16(b, tiff + 2, le) != 42) {
            return 1;
        }
        long ifd = u32(b, tiff + 4, le);
        long entriesAt = tiff + ifd + 2;
        if (tiff + ifd + 2 > end) {
            return 1;
        }
        int count = u16(b, (int) (tiff + ifd), le);
        for (int i = 0; i < count; i++) {
            long entry = entriesAt + i * 12L;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(b, (int) entry, le) == 0x0112) {
                int v = u16(b, (int) entry + 8, le); // SHORT value, first two bytes
                return (v >= 1 && v <= 8) ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int off, boolean le) {
        int a = b[off] & 0xFF, c = b[off + 1] & 0xFF;
        return le ? (c << 8 | a) : (a << 8 | c);
    }

    private static long u32(byte[] b, int off, boolean le) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            r |= (long) (b[off + i] & 0xFF) << (le ? 8 * i : 8 * (3 - i));
        }
        return r;
    }
}
