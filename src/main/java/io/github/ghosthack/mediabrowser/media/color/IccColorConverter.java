package io.github.ghosthack.mediabrowser.media.color;

import io.github.ghosthack.mediabrowser.media.ColorProfile;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.RasterFrame;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.ColorConvertOp;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/** Fast LittleCMS raster conversion for the application's packed BGRA contract. */
final class IccColorConverter {

    enum Outcome { APPLIED, SRGB, UNTAGGED }

    record Decision(RasterFrame frame, Outcome outcome, String detail) {}

    /** One transform instance per worker thread; ColorConvertOp instances serialize internally. */
    private static final ThreadLocal<Transform> TRANSFORM = new ThreadLocal<>();

    private IccColorConverter() {}

    static Decision convert(RasterFrame frame, ColorProfile profile) {
        if (profile == null) {
            return new Decision(frame, Outcome.UNTAGGED, "skipped (untagged)");
        }
        if (profile.isSrgb()) {
            return new Decision(frame, Outcome.SRGB,
                    "skipped (sRGB: " + profile.name() + ")");
        }

        try {
            return new Decision(filter(frame, profile), Outcome.APPLIED,
                    "applied (" + profile.name() + ")");
        } catch (RuntimeException ex) {
            throw new MediaException("ICC conversion failed for " + profile.name(), ex);
        }
    }

    /** Minimum pixels per band, so small rasters (thumbnails) stay single-threaded. */
    private static final int MIN_BAND_PIXELS = 512 * 1024;

    /**
     * ColorConvertOp's raster overload is the hot path; the transform runs
     * band-parallel because one instance serializes, and each worker converts
     * its rows back in place (LCMS only accepts pixelStride == band count, so
     * B,G,R are extracted per band and rewritten over the same BGRA storage;
     * alpha bytes are never touched).
     */
    private static RasterFrame filter(RasterFrame frame, ColorProfile profile) {
        int width = frame.width();
        int height = frame.height();
        byte[] bgra = frame.bgra();
        long pixels = (long) width * height;
        int bands = (int) Math.max(1, Math.min(
                Runtime.getRuntime().availableProcessors(), pixels / MIN_BAND_PIXELS));
        int rowsPerBand = (height + bands - 1) / bands;
        if (bands == 1) {
            convertRows(bgra, width, 0, height, profile);
        } else {
            java.util.stream.IntStream.range(0, bands).parallel().forEach(band -> {
                int firstRow = band * rowsPerBand;
                int rows = Math.min(rowsPerBand, height - firstRow);
                if (rows > 0) {
                    convertRows(bgra, width, firstRow, rows, profile);
                }
            });
        }
        return frame;
    }

    private static void convertRows(byte[] bgra, int width, int firstRow, int rows,
                                    ColorProfile profile) {
        int pixels = Math.multiplyExact(width, rows);
        int base = Math.multiplyExact(Math.multiplyExact(firstRow, width), 4);
        byte[] sourceBgr = new byte[Math.multiplyExact(pixels, 3)];
        for (int pixel = 0, src = base, dst = 0; pixel < pixels; pixel++, src += 4, dst += 3) {
            sourceBgr[dst] = bgra[src];
            sourceBgr[dst + 1] = bgra[src + 1];
            sourceBgr[dst + 2] = bgra[src + 2];
        }
        byte[] targetBgr = new byte[sourceBgr.length];
        operationFor(profile).filter(interleavedBgr(sourceBgr, width, rows),
                interleavedBgr(targetBgr, width, rows));
        for (int pixel = 0, src = 0, dst = base; pixel < pixels; pixel++, src += 3, dst += 4) {
            bgra[dst] = targetBgr[src];
            bgra[dst + 1] = targetBgr[src + 1];
            bgra[dst + 2] = targetBgr[src + 2];
        }
    }

    private static ColorConvertOp operationFor(ColorProfile profile) {
        Transform transform = TRANSFORM.get();
        if (transform == null || !transform.profile().equals(profile)) {
            ICC_ColorSpace source = new ICC_ColorSpace(
                    ICC_Profile.getInstance(profile.iccData()));
            ColorSpace target = ColorSpace.getInstance(ColorSpace.CS_sRGB);
            transform = new Transform(profile, new ColorConvertOp(source, target, null));
            TRANSFORM.set(transform);
        }
        return transform.operation();
    }

    private static WritableRaster interleavedBgr(byte[] data, int width, int height) {
        return Raster.createInterleavedRaster(new DataBufferByte(data, data.length),
                width, height, width * 3, 3, new int[] {2, 1, 0}, null);
    }

    private record Transform(ColorProfile profile, ColorConvertOp operation) {}
}
