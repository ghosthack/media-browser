package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Ffm;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Demosaic-grade decode of JPEG-XL-compressed DNGs (DNG 1.7, iPhone 17) —
 * the files LibRaw 0.22 stubs out on. Mirrors the HEIC TILE_GRID composition
 * ({@code FfmpegAv.decodeTileGrid}), but the tiles come from TIFF
 * TileOffsets/TileByteCounts ({@link DngTiff}) instead of stream groups: each
 * tile is an independent JXL codestream decoded through the bundled FFmpeg's
 * statically linked libjxl, stitched into one 16-bit LinearRaw mosaic, then
 * rendered to display sRGB by the pure-Java DNG chain ({@link DngRender}).
 *
 * <p>Only LinearRaw (already-demosaiced) 3-sample rasters are claimed — the
 * only shape Apple writes. Anything else returns empty so callers keep their
 * existing fallback (embedded preview), never a silent substitute.</p>
 */
final class DngJxlStills {

    private DngJxlStills() {
    }

    /**
     * Full render of a JXL-compressed DNG, upright (IFD0 orientation baked).
     * Empty when {@code file} has no JXL raw SubIFD or an unsupported shape;
     * decode failures inside a claimed file throw loudly.
     */
    static Optional<RasterFrame> fullDecode(FfmpegBindings ff, Path file) {
        Optional<DngTiff> maybe = DngTiff.openJxlDng(file);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        DngTiff dng = maybe.get();
        if (dng.samplesPerPixel() != 3) {
            // Bayer-mosaic JXL DNGs would need a demosaic pass we don't have.
            return Optional.empty();
        }
        short[] mosaic = decodeTiles(ff, dng);
        RasterFrame rendered = DngRender.render(dng, mosaic, dng.width(), dng.height());
        return Optional.of(
                RasterFrames.applyExifOrientation(rendered, dng.orientation()));
    }

    /** Decodes every tile and stitches the interleaved 16-bit RGB mosaic. */
    static short[] decodeTiles(FfmpegBindings ff, DngTiff dng) {
        int width = dng.width();
        int height = dng.height();
        int tileW = dng.tileWidth();
        int tileH = dng.tileLength();
        long pixels = (long) width * height * 3;
        if (width <= 0 || height <= 0 || tileW <= 0 || tileH <= 0
                || pixels > Integer.MAX_VALUE - 16) {
            throw new MediaException("DNG: unreasonable raw geometry "
                    + width + "x" + height + " tiles " + tileW + "x" + tileH);
        }
        int across = (width + tileW - 1) / tileW;
        int down = (height + tileH - 1) / tileH;
        int tiles = dng.tileCount();
        if (across * down != tiles) {
            throw new MediaException("DNG: tile count " + tiles + " does not cover "
                    + across + "x" + down + " grid");
        }
        MemorySegment codec = ff.findDecoder(ff.codecIdJpegxl());
        if (codec.equals(MemorySegment.NULL)) {
            throw new MediaException(
                    "ffmpeg: no JPEG XL decoder (ffmpeg-ffm natives too old?)");
        }
        short[] canvas = new short[(int) pixels];
        for (int i = 0; i < tiles; i++) {
            int tx = (i % across) * tileW;
            int ty = (i / across) * tileH;
            decodeTileInto(ff, codec, dng.tileBytes(i), canvas, width, height, tx, ty);
        }
        return canvas;
    }

    /**
     * Decodes one JXL codestream and blits it at (tx, ty), cropping the
     * right/bottom edge overhang like {@code FfmpegAv.blit} does for HEIC.
     */
    private static void decodeTileInto(FfmpegBindings ff, MemorySegment codec,
                                       byte[] bytes, short[] canvas,
                                       int width, int height, int tx, int ty) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cctx = ff.allocContext3(codec);
            if (cctx.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: cannot allocate codec context");
            }
            MemorySegment cctxPtr = Ffm.pointerTo(arena, cctx);
            MemorySegment pktPtr = MemorySegment.NULL;
            MemorySegment framePtr = MemorySegment.NULL;
            try {
                ff.setAutoThreads(cctx);
                ff.check(ff.open2(cctx, codec), "avcodec_open2 (jpegxl)");
                MemorySegment pkt = ff.packetAlloc();
                pktPtr = Ffm.pointerTo(arena, pkt);
                MemorySegment frame = ff.frameAlloc();
                framePtr = Ffm.pointerTo(arena, frame);

                ff.packetSetData(arena, pkt, bytes);
                ff.check(ff.sendPacket(cctx, pkt), "send DNG tile");
                ff.sendPacket(cctx, MemorySegment.NULL);   // drain: one packet per stream
                int rr = ff.receiveFrame(cctx, frame);
                if (rr < 0) {
                    throw new MediaException("ffmpeg: DNG tile decode failed: " + ff.errStr(rr));
                }
                blit16(ff, frame, canvas, width, height, tx, ty);
            } finally {
                if (!framePtr.equals(MemorySegment.NULL)) ff.frameFree(framePtr);
                if (!pktPtr.equals(MemorySegment.NULL)) ff.packetFree(pktPtr);
                ff.freeContext(cctxPtr);
            }
        }
    }

    /** Copies a decoded 16-bit RGB(A) frame into the canvas at (tx, ty). */
    private static void blit16(FfmpegBindings ff, MemorySegment frame,
                               short[] canvas, int width, int height, int tx, int ty) {
        int fw = ff.frameWidth(frame);
        int fh = ff.frameHeight(frame);
        int fmt = ff.frameFormat(frame);
        int channels;
        if (fmt == ff.pixFmtRgb48le()) {
            channels = 3;
        } else if (fmt == ff.pixFmtRgba64le()) {
            channels = 4;
        } else {
            throw new MediaException("ffmpeg: DNG tile decoded to unsupported format "
                    + ff.pixFmtName(fmt) + " (expected 16-bit RGB)");
        }
        int linesize = ff.frameLinesize(frame).getAtIndex(ValueLayout.JAVA_INT, 0);
        MemorySegment plane = ff.frameData(frame)
                .getAtIndex(ValueLayout.ADDRESS, 0)
                .reinterpret((long) linesize * fh);
        int copyW = Math.min(fw, width - tx);
        int copyH = Math.min(fh, height - ty);
        short[] row = new short[fw * channels];
        for (int y = 0; y < copyH; y++) {
            MemorySegment.copy(plane, ValueLayout.JAVA_SHORT_UNALIGNED, (long) y * linesize,
                    row, 0, fw * channels);
            int dst = ((ty + y) * width + tx) * 3;
            if (channels == 3) {
                System.arraycopy(row, 0, canvas, dst, copyW * 3);
            } else {
                for (int x = 0; x < copyW; x++) {
                    canvas[dst + x * 3] = row[x * 4];
                    canvas[dst + x * 3 + 1] = row[x * 4 + 1];
                    canvas[dst + x * 3 + 2] = row[x * 4 + 2];
                }
            }
        }
    }
}
