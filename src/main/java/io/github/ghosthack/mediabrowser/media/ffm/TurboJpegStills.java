package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.turbojpegffm.turbojpeg.TurboJpeg;
import io.github.ghosthack.turbojpegffm.turbojpeg.tjscalingfactor;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
 * <p><b>Declines instead of guessing.</b> {@link #thumbnail} returns empty for
 * anything outside the well-trodden path — CMYK/YCCK colorspaces, lossless
 * JPEG, parse trouble — and the caller decodes via FFmpeg instead (capability
 * routing, documented per backend). A missing native surfaces loudly at
 * backend creation via {@link #available()}, never as silent degradation.</p>
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
     * Scaled decode of a baseline/progressive YCbCr-or-grayscale JPEG, upright
     * per EXIF, sized per {@code maxEdge}/{@code mode}. Empty = caller decodes
     * via FFmpeg instead.
     */
    static Optional<RasterFrame> thumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        if (!available() || maxEdge <= 0) {
            return Optional.empty();
        }
        byte[] jpeg;
        try {
            jpeg = Files.readAllBytes(file);
        } catch (IOException e) {
            return Optional.empty();
        }
        int orientation = exifOrientation(jpeg);
        return decodeScaled(jpeg, maxEdge, mode)
                .map(f -> RasterFrames.applyExifOrientation(f, orientation));
    }

    /**
     * The scaled-decode core on raw JPEG bytes, orientation NOT applied —
     * for callers whose orientation authority is outside the JPEG (e.g. the
     * {@link LibRawStills} embedded-preview path, where LibRaw's flip wins).
     */
    static Optional<RasterFrame> decodeScaled(byte[] jpeg, int maxEdge, ThumbnailMode mode) {
        if (!available() || maxEdge <= 0) {
            return Optional.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = TurboJpeg.tj3InitVersion(
                    TurboJpeg.TJINIT_DECOMPRESS(), TurboJpeg.TURBOJPEG_VERSION_NUMBER());
            if (handle.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            try {
                MemorySegment buf = arena.allocate(jpeg.length);
                MemorySegment.copy(jpeg, 0, buf, ValueLayout.JAVA_BYTE, 0, jpeg.length);
                if (TurboJpeg.tj3DecompressHeader(handle, buf, jpeg.length) != 0) {
                    return Optional.empty();
                }
                int w = TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_JPEGWIDTH());
                int h = TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_JPEGHEIGHT());
                int cs = TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_COLORSPACE());
                if (w <= 0 || h <= 0
                        || cs == TurboJpeg.TJCS_CMYK() || cs == TurboJpeg.TJCS_YCCK()
                        || TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_LOSSLESS()) != 0
                        // Progressive: every scan spans the full image, so DCT
                        // scaling saves little and FFmpeg measures ~35% faster
                        // — decline and let the fallback take it.
                        || TurboJpeg.tj3Get(handle, TurboJpeg.TJPARAM_PROGRESSIVE()) != 0) {
                    return Optional.empty();
                }

                int[] scaled = pickScalingFactor(arena, handle, w, h, maxEdge, mode);
                if (scaled == null) {
                    return Optional.empty();
                }
                int sw = scaled[0], sh = scaled[1];
                MemorySegment dst = arena.allocate((long) sw * sh * 4);
                if (TurboJpeg.tj3Decompress8(handle, buf, jpeg.length, dst, sw * 4,
                        TurboJpeg.TJPF_BGRA()) != 0) {
                    return Optional.empty();
                }
                byte[] bgra = new byte[sw * sh * 4];
                MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0, bgra, 0, bgra.length);

                return Optional.of(Thumbnails.scale(new RasterFrame(sw, sh, bgra), maxEdge, mode));
            } finally {
                TurboJpeg.tj3Destroy(handle);
            }
        } catch (Throwable t) {
            return Optional.empty(); // any native hiccup: fall back to FFmpeg
        }
    }

    /**
     * Smallest supported DCT scaling factor that still covers {@code maxEdge}
     * (never upscales), applied to the handle. FIT needs the scaled long edge
     * to cover the box; FILL center-crops a square, so the scaled short edge
     * must cover it. Returns the scaled {@code {width, height}}, or null to
     * decline.
     */
    private static int[] pickScalingFactor(Arena arena, MemorySegment handle,
                                           int w, int h, int maxEdge, ThumbnailMode mode) {
        MemorySegment countPtr = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment factors = TurboJpeg.tj3GetScalingFactors(countPtr);
        int count = countPtr.get(ValueLayout.JAVA_INT, 0);
        if (factors.equals(MemorySegment.NULL) || count <= 0) {
            return null;
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
            return null;
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
