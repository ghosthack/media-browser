package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.mediabrowser.media.VisualResult;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Ffm;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;
import io.github.ghosthack.mediabrowser.media.ffm.bind.Rational;
import io.github.ghosthack.mediabrowser.media.ffm.bind.TileGrid;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AV container probing and first-frame extraction through a {@link FfmpegBindings}
 * (libavformat / libavcodec / libswscale). All native access goes through the
 * injected bindings, so this logic is FFmpeg-version-agnostic.
 */
final class FfmpegAv {

    private final FfmpegBindings ff;

    FfmpegAv(FfmpegBindings ff) {
        this.ff = ff;
        ff.init();
    }

    String version() {
        return ff.version();
    }

    MediaProbe probe(Path file, long fileSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxPtr = ff.openInput(arena, file);
            try {
                return describe(ff.derefFormatContext(ctxPtr), file, fileSize);
            } finally {
                ff.closeInput(ctxPtr);
            }
        }
    }

    /** Reads the probe metadata off an already-open format context. */
    private MediaProbe describe(MemorySegment ctx, Path file, long fileSize) {
        String container = null;
        MemorySegment iformat = ff.iformat(ctx);
        if (!iformat.equals(MemorySegment.NULL)) {
            container = ff.iformatLongName(iformat);
            if (container == null) container = ff.iformatName(iformat);
        }

        long duration = ff.duration(ctx);
        long durationMicros = duration == ff.noptsValue() ? -1 : duration;
        long bitRate = ff.bitRate(ctx);
        if (bitRate <= 0) bitRate = -1;

        int vIdx = FfmpegStreamSelector.selectPrimaryVideoStream(
                ff, ctx, ff.findBestStream(ctx, ff.mediaTypeVideo(), MemorySegment.NULL));
        int aIdx = ff.findBestStream(ctx, ff.mediaTypeAudio(), MemorySegment.NULL);

        int width = -1, height = -1;
        String videoCodec = null, audioCodec = null;
        double frameRate = -1;
        int sampleRate = -1, channels = -1;
        boolean attachedPicOnly = false;

        if (vIdx >= 0) {
            MemorySegment vs = ff.stream(ctx, vIdx);
            MemorySegment par = ff.codecpar(vs);
            width = ff.parWidth(par);
            height = ff.parHeight(par);
            int orientation = ff.videoExifOrientation(vs);
            TileGrid grid = findTileGrid(ctx, vIdx);
            if (grid != null) {
                // A tiled HEIF/AVIF still: the stream is one tile of the
                // picture; report the composed canvas, not the tile.
                width = grid.width();
                height = grid.height();
                orientation = gridOrientation(ctx, grid);
            }
            // Report display (post-orientation) dimensions: the transposing
            // orientations (5..8) swap them, matching the upright frame the
            // decode bakes.
            if (orientation >= 5) {
                int swap = width;
                width = height;
                height = swap;
            }
            videoCodec = ff.codecName(ff.parCodecId(par));
            Rational fr = ff.avgFrameRate(vs);
            if (fr.isPositive()) frameRate = fr.num() / (double) fr.den();
            attachedPicOnly = (ff.disposition(vs) & ff.dispositionAttachedPic()) != 0;
        }
        if (aIdx >= 0) {
            MemorySegment par = ff.codecpar(ff.stream(ctx, aIdx));
            audioCodec = ff.codecName(ff.parCodecId(par));
            sampleRate = ff.parSampleRate(par);
            channels = ff.parChannels(par);
        }

        MediaKind kind;
        if (vIdx >= 0 && !attachedPicOnly) {
            kind = MediaKind.VIDEO;
        } else if (aIdx >= 0) {
            kind = MediaKind.AUDIO;
        } else if (vIdx >= 0) {
            kind = MediaKind.VIDEO;
        } else {
            throw new MediaException("ffmpeg: no audio/video streams in " + file.getFileName());
        }

        return new MediaProbe(file, kind, container, fileSize, durationMicros, bitRate,
                width, height, videoCodec, frameRate, audioCodec, sampleRate, channels, null);
    }

    /**
     * Probes the file and decodes the first frame of the best video stream
     * (which for audio files with cover art is the attached picture) into
     * BGRA — both off a single native open. The frame is empty when the file
     * has no decodable visual.
     */
    VisualResult firstFrameWithProbe(Path file, long fileSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxPtr = ff.openInput(arena, file);
            try {
                MemorySegment ctx = ff.derefFormatContext(ctxPtr);
                MediaProbe probe = describe(ctx, file, fileSize);
                return new VisualResult(probe,
                        Optional.ofNullable(decodeFirstFrame(arena, ctx, -1, ThumbnailMode.FIT)));
            } finally {
                ff.closeInput(ctxPtr);
            }
        }
    }

    /**
     * Probes the file and decodes a downscaled poster from the best video
     * stream (the attached picture for cover-art audio). In {@link ThumbnailMode#FIT}
     * it fits within a {@code maxEdge} box; in {@link ThumbnailMode#FILL} it is
     * centre-cropped to a {@code maxEdge × maxEdge} square. Empty when the file
     * has no decodable visual.
     */
    Thumbnail thumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxPtr = ff.openInput(arena, file);
            try {
                MemorySegment ctx = ff.derefFormatContext(ctxPtr);
                MediaProbe probe = describe(ctx, file, -1);
                RasterFrame poster = decodeFirstFrame(arena, ctx, maxEdge, mode);
                return new Thumbnail(Optional.ofNullable(poster), probe.kind());
            } finally {
                ff.closeInput(ctxPtr);
            }
        }
    }

    /**
     * First frame of the best video stream as BGRA, or null when none. When
     * {@code maxEdge > 0} the frame is downscaled to fit within that box at its
     * coded (stored) size; otherwise it is returned at coded size.
     */
    private RasterFrame decodeFirstFrame(Arena arena, MemorySegment ctx, int maxEdge,
                                         ThumbnailMode mode) {
        MemorySegment cctxPtr = MemorySegment.NULL;
        MemorySegment pktPtr = MemorySegment.NULL;
        MemorySegment framePtr = MemorySegment.NULL;
        try {
            MemorySegment decoderPtr = arena.allocate(ValueLayout.ADDRESS);
            int vIdx = FfmpegStreamSelector.selectPrimaryVideoStream(
                    ff, ctx, ff.findBestStream(ctx, ff.mediaTypeVideo(), decoderPtr));
            if (vIdx < 0) return null;
            MemorySegment codec = decoderPtr.get(ValueLayout.ADDRESS, 0);
            if (codec.equals(MemorySegment.NULL)) return null;

            TileGrid grid = findTileGrid(ctx, vIdx);
            if (grid != null) {
                // Tiled HEIF/AVIF still: vIdx carries one tile, not the
                // picture. Thumbnails pick the smallest baked representation
                // (docs/heic-followups-handoff.md); the viewer composes the
                // primary grid.
                return maxEdge > 0
                        ? decodeSmallestRepresentation(arena, ctx, grid, maxEdge, mode)
                        : decodeTileGrid(arena, ctx, grid, maxEdge, mode);
            }

            MemorySegment cctx = ff.allocContext3(codec);
            if (cctx.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: cannot allocate codec context");
            }
            cctxPtr = Ffm.pointerTo(arena, cctx);

            ff.check(ff.parametersToContext(cctx, ff.codecpar(ff.stream(ctx, vIdx))),
                    "avcodec_parameters_to_context");
            ff.check(ff.open2(cctx, codec), "avcodec_open2");

            MemorySegment pkt = ff.packetAlloc();
            pktPtr = Ffm.pointerTo(arena, pkt);
            MemorySegment frame = ff.frameAlloc();
            framePtr = Ffm.pointerTo(arena, frame);

            if (!decodeOneFrame(ctx, cctx, pkt, frame, vIdx)) return null;

            RasterFrame poster = scaleFrameToBgra(arena, frame, maxEdge, mode);
            if (poster == null) return null;
            // Bake the container/display orientation (rotation AND mirror —
            // imir on HEIF items) into the poster so it is upright, like the
            // Apple (AVFoundation) backend's frames.
            return RasterFrames.applyExifOrientation(poster,
                    ff.videoExifOrientation(ff.stream(ctx, vIdx)));
        } finally {
            if (!framePtr.equals(MemorySegment.NULL)) ff.frameFree(framePtr);
            if (!pktPtr.equals(MemorySegment.NULL)) ff.packetFree(pktPtr);
            if (!cctxPtr.equals(MemorySegment.NULL)) ff.freeContext(cctxPtr);
        }
    }

    private boolean decodeOneFrame(MemorySegment ctx, MemorySegment cctx,
                                   MemorySegment pkt, MemorySegment frame, int vIdx) {
        boolean draining = false;
        while (true) {
            int rr = ff.receiveFrame(cctx, frame);
            if (rr == 0) return true;
            if (rr == ff.averrorEof()) return false;
            if (rr != ff.averrorEagain()) {
                throw new MediaException("ffmpeg: decode failed: " + ff.errStr(rr));
            }
            if (draining) return false;

            // decoder wants more input
            int r = ff.readFrame(ctx, pkt);
            if (r < 0) {
                ff.sendPacket(cctx, MemorySegment.NULL);
                draining = true;
                continue;
            }
            if (ff.packetStreamIndex(pkt) == vIdx) {
                int sr = ff.sendPacket(cctx, pkt);
                if (sr < 0 && sr != ff.averrorEagain() && sr != ff.averrorEof()) {
                    ff.packetUnref(pkt);
                    throw new MediaException("ffmpeg: send packet failed: " + ff.errStr(sr));
                }
            }
            ff.packetUnref(pkt);
        }
    }

    /**
     * The primary tile grid when the selected video stream is a tile, or null
     * (the ordinary case). Multi-grid files are real — an iPhone HEIC carries
     * the picture grid plus a gray gain-map grid and a 10-bit HDR rendition
     * grid — and best-stream scoring between same-params tiles is essentially
     * index order, so the grid <em>containing</em> the pick is luck; the
     * primary picture is deterministically the largest-area grid whose tiles
     * are 8-bit SDR color (largest of any format if no grid qualifies).
     */
    private TileGrid findTileGrid(MemorySegment ctx, int vIdx) {
        TileGrid largest = null;
        TileGrid largestSdr = null;
        boolean tileSelected = false;
        int n = ff.nbStreamGroups(ctx);
        for (int i = 0; i < n; i++) {
            TileGrid grid = ff.tileGrid(ctx, i);
            if (grid == null) {
                continue;
            }
            tileSelected |= grid.containsStream(vIdx);
            long area = (long) grid.width() * grid.height();
            if (largest == null
                    || area > (long) largest.width() * largest.height()) {
                largest = grid;
            }
            if (sdrGrid(ctx, grid) && (largestSdr == null
                    || area > (long) largestSdr.width() * largestSdr.height())) {
                largestSdr = grid;
            }
        }
        return tileSelected ? (largestSdr != null ? largestSdr : largest) : null;
    }

    /** Whether the grid's tiles decode to 8-bit SDR color — the gain-map grid is gray, the HDR rendition grid 10-bit. */
    private boolean sdrGrid(MemorySegment ctx, TileGrid grid) {
        return SDR_8BIT_PIX_FMTS.contains(ff.pixFmtName(
                ff.parFormat(ff.codecpar(ff.stream(ctx, grid.tiles()[0].streamIndex())))));
    }

    /**
     * A grid item's {@code irot}/{@code imir} land on the grid's own coded
     * side data as an EXIF-style orientation; some writers put it on the tile
     * streams instead, so fall back there.
     */
    private int gridOrientation(MemorySegment ctx, TileGrid grid) {
        int orientation = grid.exifOrientation();
        return orientation != 1 ? orientation
                : ff.videoExifOrientation(ff.stream(ctx, grid.tiles()[0].streamIndex()));
    }

    /**
     * Composes a tiled HEIF/AVIF still (every iPhone photo): decodes each tile
     * stream's coded picture and blits it at its canvas offset, then crops to
     * the presentation window — the grid-assembly counterpart of
     * {@code ffmpeg}'s own stream-group handling, which plain
     * {@code av_find_best_stream} consumers never get.
     *
     * <p>One shared codec context decodes every tile (a grid mixing codecs is
     * a loud error, never a partial canvas): frames leave a decoder in send
     * order, so a queue of sent stream indexes pairs each output frame with
     * its canvas placement(s).</p>
     */
    private RasterFrame decodeTileGrid(Arena arena, MemorySegment ctx, TileGrid grid,
                                       int maxEdge, ThumbnailMode mode) {
        long canvasBytes = (long) grid.width() * grid.height() * 4;
        if (grid.width() <= 0 || grid.height() <= 0 || canvasBytes > Integer.MAX_VALUE - 16) {
            throw new MediaException("ffmpeg: unreasonable tile grid canvas "
                    + grid.width() + "x" + grid.height());
        }
        Map<Integer, List<TileGrid.Tile>> placements = new HashMap<>();
        for (TileGrid.Tile tile : grid.tiles()) {
            placements.computeIfAbsent(tile.streamIndex(), k -> new ArrayList<>()).add(tile);
        }
        int codecId = ff.parCodecId(ff.codecpar(ff.stream(ctx, grid.tiles()[0].streamIndex())));
        for (int streamIndex : placements.keySet()) {
            if (ff.parCodecId(ff.codecpar(ff.stream(ctx, streamIndex))) != codecId) {
                throw new MediaException("ffmpeg: tile grid mixes codecs in "
                        + placements.size() + " tile streams; unsupported");
            }
        }

        MemorySegment codec = ff.findDecoder(codecId);
        if (codec.equals(MemorySegment.NULL)) {
            throw new MediaException("ffmpeg: no decoder for tile codec "
                    + ff.codecName(codecId));
        }
        byte[] canvas = new byte[(int) canvasBytes];
        MemorySegment cctxPtr = MemorySegment.NULL;
        MemorySegment pktPtr = MemorySegment.NULL;
        MemorySegment framePtr = MemorySegment.NULL;
        try {
            MemorySegment cctx = ff.allocContext3(codec);
            if (cctx.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: cannot allocate codec context");
            }
            cctxPtr = Ffm.pointerTo(arena, cctx);
            ff.check(ff.parametersToContext(cctx,
                            ff.codecpar(ff.stream(ctx, grid.tiles()[0].streamIndex()))),
                    "avcodec_parameters_to_context");
            // Frame threading pipelines the independent intra tiles across
            // cores; libavcodec's default is a single thread.
            ff.setAutoThreads(cctx);
            ff.check(ff.open2(cctx, codec), "avcodec_open2");

            MemorySegment pkt = ff.packetAlloc();
            pktPtr = Ffm.pointerTo(arena, pkt);
            MemorySegment frame = ff.frameAlloc();
            framePtr = Ffm.pointerTo(arena, frame);

            ArrayDeque<Integer> sent = new ArrayDeque<>();
            Set<Integer> composed = new HashSet<>();
            // Overlapping tiles compose in offsets-array order (later wins,
            // per the spec) — those buffer and blit after the decode loop.
            // The common case (Apple grids never overlap) streams straight
            // onto the canvas.
            Map<Integer, RasterFrame> buffered = tilesOverlap(ctx, grid) ? new HashMap<>() : null;
            int pendingStream = -1;    // packet kept in pkt after a send EAGAIN
            boolean draining = false;
            while (true) {
                int rr = ff.receiveFrame(cctx, frame);
                if (rr == 0) {
                    Integer streamIndex = sent.poll();
                    if (streamIndex == null) {
                        throw new MediaException("ffmpeg: unexpected tile grid frame");
                    }
                    // Per-tile arena: the BGRA staging buffers would otherwise
                    // pile up in the decode arena for the whole compose.
                    try (Arena tileArena = Arena.ofConfined()) {
                        RasterFrame tile = scaleFrameToBgra(tileArena, frame, -1, ThumbnailMode.FIT);
                        if (tile == null) {
                            throw new MediaException("ffmpeg: tile decoded with no pixels");
                        }
                        if (buffered != null) {
                            buffered.put(streamIndex, tile);
                        } else {
                            for (TileGrid.Tile placement : placements.get(streamIndex)) {
                                blit(canvas, grid.width(), grid.height(), tile,
                                        placement.x() - grid.horizontalOffset(),
                                        placement.y() - grid.verticalOffset());
                            }
                        }
                    }
                    ff.frameUnref(frame);
                    composed.add(streamIndex);
                    if (composed.size() == placements.size()) break;
                    continue;
                }
                if (rr == ff.averrorEof()) break;
                if (rr != ff.averrorEagain()) {
                    throw new MediaException("ffmpeg: tile decode failed: " + ff.errStr(rr));
                }
                if (draining) break;
                if (pendingStream >= 0) {
                    int sr = ff.sendPacket(cctx, pkt);
                    if (sr >= 0) {
                        sent.add(pendingStream);
                        pendingStream = -1;
                        ff.packetUnref(pkt);
                    } else if (sr != ff.averrorEagain()) {
                        ff.packetUnref(pkt);
                        throw new MediaException("ffmpeg: send tile packet failed: " + ff.errStr(sr));
                    }
                    continue;
                }
                int r = ff.readFrame(ctx, pkt);
                if (r < 0) {
                    ff.sendPacket(cctx, MemorySegment.NULL);
                    draining = true;
                    continue;
                }
                int streamIndex = ff.packetStreamIndex(pkt);
                if (!placements.containsKey(streamIndex)) {
                    ff.packetUnref(pkt);
                    continue;
                }
                int sr = ff.sendPacket(cctx, pkt);
                if (sr >= 0) {
                    sent.add(streamIndex);
                    ff.packetUnref(pkt);
                } else if (sr == ff.averrorEagain()) {
                    pendingStream = streamIndex;   // retry after the next receive
                } else {
                    ff.packetUnref(pkt);
                    throw new MediaException("ffmpeg: send tile packet failed: " + ff.errStr(sr));
                }
            }
            if (composed.size() < placements.size()) {
                throw new MediaException("ffmpeg: tile grid incomplete: decoded "
                        + composed.size() + " of " + placements.size() + " tile streams");
            }
            if (buffered != null) {
                for (TileGrid.Tile placement : grid.tiles()) {
                    blit(canvas, grid.width(), grid.height(),
                            buffered.get(placement.streamIndex()),
                            placement.x() - grid.horizontalOffset(),
                            placement.y() - grid.verticalOffset());
                }
            }
        } finally {
            if (!framePtr.equals(MemorySegment.NULL)) ff.frameFree(framePtr);
            if (!pktPtr.equals(MemorySegment.NULL)) ff.packetFree(pktPtr);
            if (!cctxPtr.equals(MemorySegment.NULL)) ff.freeContext(cctxPtr);
        }

        RasterFrame composedFrame = new RasterFrame(grid.width(), grid.height(), canvas);
        if (maxEdge > 0) {
            composedFrame = scaleBgra(composedFrame, maxEdge, mode);
        }
        return RasterFrames.applyExifOrientation(composedFrame, gridOrientation(ctx, grid));
    }

    /** Whether any two tile placements intersect — the coded tile sizes come from each tile stream's parameters. */
    private boolean tilesOverlap(MemorySegment ctx, TileGrid grid) {
        TileGrid.Tile[] tiles = grid.tiles();
        int[] w = new int[tiles.length];
        int[] h = new int[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            MemorySegment par = ff.codecpar(ff.stream(ctx, tiles[i].streamIndex()));
            w[i] = ff.parWidth(par);
            h[i] = ff.parHeight(par);
        }
        for (int i = 0; i < tiles.length; i++) {
            for (int j = i + 1; j < tiles.length; j++) {
                if (tiles[i].x() < tiles[j].x() + w[j] && tiles[j].x() < tiles[i].x() + w[i]
                        && tiles[i].y() < tiles[j].y() + h[j] && tiles[j].y() < tiles[i].y() + h[i]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 8-bit SDR color formats a pyramid candidate may use — the 10-bit HDR preview and the gray gain/depth auxiliaries must never substitute for the picture. */
    private static final Set<String> SDR_8BIT_PIX_FMTS = Set.of(
            "yuv420p", "yuvj420p", "yuv422p", "yuvj422p", "yuv444p", "yuvj444p",
            "nv12", "nv21");

    /**
     * Thumbnail-path representation selection over the pyramid Apple bakes
     * into every HEIC (docs/heic-followups-handoff.md): smaller TILE_GRID
     * variants and standalone preview/thumbnail streams, all reachable as
     * plain streams. Picks the smallest representation that still covers the
     * request without upscaling; candidates must match the primary grid's
     * aspect (±1%) and, for standalone streams, decode to 8-bit SDR color.
     * Files without a pyramid (sips-minted, third-party) fall through to
     * composing the primary grid — deterministic capability routing, not a
     * failure fallback.
     */
    private RasterFrame decodeSmallestRepresentation(Arena arena, MemorySegment ctx,
                                                     TileGrid primary, int maxEdge,
                                                     ThumbnailMode mode) {
        long bestArea = (long) primary.width() * primary.height();
        TileGrid bestGrid = primary;
        int bestStream = -1;

        Set<Integer> tileStreams = new HashSet<>();
        int groups = ff.nbStreamGroups(ctx);
        for (int i = 0; i < groups; i++) {
            TileGrid g = ff.tileGrid(ctx, i);
            if (g == null) {
                continue;
            }
            for (TileGrid.Tile tile : g.tiles()) {
                tileStreams.add(tile.streamIndex());
            }
            long area = (long) g.width() * g.height();
            if (area < bestArea && aspectMatches(primary, g.width(), g.height())
                    && covers(g.width(), g.height(), maxEdge, mode)
                    && sdrGrid(ctx, g)) {
                bestArea = area;
                bestGrid = g;
            }
        }

        int nb = ff.nbStreams(ctx);
        for (int i = 0; i < nb; i++) {
            MemorySegment par = ff.codecpar(ff.stream(ctx, i));
            if (ff.parCodecType(par) != ff.mediaTypeVideo() || tileStreams.contains(i)) {
                continue;
            }
            int w = ff.parWidth(par);
            int h = ff.parHeight(par);
            long area = (long) w * h;
            if (w <= 0 || h <= 0 || area >= bestArea
                    || !aspectMatches(primary, w, h) || !covers(w, h, maxEdge, mode)
                    || !SDR_8BIT_PIX_FMTS.contains(ff.pixFmtName(ff.parFormat(par)))) {
                continue;
            }
            bestArea = area;
            bestStream = i;
        }

        if (bestStream >= 0) {
            RasterFrame frame = decodeStreamFrame(arena, ctx, bestStream, maxEdge, mode);
            if (frame == null) {
                // The candidate was selected deterministically; a pyramid
                // stream with no decodable frame is a broken file, and the
                // demuxer position is already past the tile packets, so a
                // compose fallback here would be a hidden partial-read path.
                throw new MediaException("ffmpeg: pyramid stream " + bestStream
                        + " carries no decodable frame");
            }
            int orientation = ff.videoExifOrientation(ff.stream(ctx, bestStream));
            return RasterFrames.applyExifOrientation(frame,
                    orientation != 1 ? orientation : gridOrientation(ctx, primary));
        }
        return decodeTileGrid(arena, ctx, bestGrid, maxEdge, mode);
    }

    /** Whether {@code w x h} matches the primary grid's aspect within 1%. */
    private static boolean aspectMatches(TileGrid primary, int w, int h) {
        long cross = (long) w * primary.height();
        long base = (long) h * primary.width();
        return Math.abs(cross - base) * 100 <= base;
    }

    /** Whether a {@code w x h} source covers a {@code maxEdge} request without upscaling. */
    private static boolean covers(int w, int h, int maxEdge, ThumbnailMode mode) {
        return (mode == ThumbnailMode.FILL ? Math.min(w, h) : Math.max(w, h)) >= maxEdge;
    }

    /** First frame of one specific stream, scaled like {@link #scaleFrameToBgra}; null when the stream yields no frame. */
    private RasterFrame decodeStreamFrame(Arena arena, MemorySegment ctx, int streamIndex,
                                          int maxEdge, ThumbnailMode mode) {
        MemorySegment par = ff.codecpar(ff.stream(ctx, streamIndex));
        MemorySegment codec = ff.findDecoder(ff.parCodecId(par));
        if (codec.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment cctxPtr = MemorySegment.NULL;
        MemorySegment pktPtr = MemorySegment.NULL;
        MemorySegment framePtr = MemorySegment.NULL;
        try {
            MemorySegment cctx = ff.allocContext3(codec);
            if (cctx.equals(MemorySegment.NULL)) {
                throw new MediaException("ffmpeg: cannot allocate codec context");
            }
            cctxPtr = Ffm.pointerTo(arena, cctx);
            ff.check(ff.parametersToContext(cctx, par), "avcodec_parameters_to_context");
            ff.check(ff.open2(cctx, codec), "avcodec_open2");
            MemorySegment pkt = ff.packetAlloc();
            pktPtr = Ffm.pointerTo(arena, pkt);
            MemorySegment frame = ff.frameAlloc();
            framePtr = Ffm.pointerTo(arena, frame);
            if (!decodeOneFrame(ctx, cctx, pkt, frame, streamIndex)) {
                return null;
            }
            return scaleFrameToBgra(arena, frame, maxEdge, mode);
        } finally {
            if (!framePtr.equals(MemorySegment.NULL)) ff.frameFree(framePtr);
            if (!pktPtr.equals(MemorySegment.NULL)) ff.packetFree(pktPtr);
            if (!cctxPtr.equals(MemorySegment.NULL)) ff.freeContext(cctxPtr);
        }
    }

    /** Copies a tile's rows into the canvas at {@code (dstX, dstY)}, clipped (offsets may be negative from the presentation crop). */
    private static void blit(byte[] canvas, int canvasW, int canvasH,
                             RasterFrame tile, int dstX, int dstY) {
        int srcX = Math.max(0, -dstX);
        int srcY = Math.max(0, -dstY);
        int x = Math.max(0, dstX);
        int y = Math.max(0, dstY);
        int w = Math.min(tile.width() - srcX, canvasW - x);
        int h = Math.min(tile.height() - srcY, canvasH - y);
        byte[] src = tile.bgra();
        for (int row = 0; row < h; row++) {
            System.arraycopy(src, ((srcY + row) * tile.width() + srcX) * 4,
                    canvas, ((y + row) * canvasW + x) * 4, w * 4);
        }
    }

    /**
     * Downscales a BGRA raster through swscale — the composed-canvas analogue
     * of {@link #scaleFrameToBgra}'s thumbnail path, with the same box math
     * and the same deliberate no-SAR convention. Never upscales.
     */
    private RasterFrame scaleBgra(RasterFrame src, int maxEdge, ThumbnailMode mode) {
        int w = src.width();
        int h = src.height();
        int dstW, dstH;
        int cropSide = 0;
        if (mode == ThumbnailMode.FILL) {
            int shortEdge = Math.min(w, h);
            cropSide = Math.min(maxEdge, shortEdge);
            double scale = cropSide / (double) shortEdge;
            dstW = Math.max(cropSide, (int) Math.round(w * scale));
            dstH = Math.max(cropSide, (int) Math.round(h * scale));
        } else {
            int[] fit = Thumbnails.fittedSize(w, h, maxEdge);
            dstW = fit[0];
            dstH = fit[1];
        }
        if (dstW >= w && dstH >= h) {
            return cropSide > 0 ? Thumbnails.cropCenterSquare(src, cropSide) : src;
        }
        MemorySegment sws = ff.swsGetContextToBgra(w, h, ff.pixFmtBgra(), dstW, dstH, ff.swsArea());
        if (sws.equals(MemorySegment.NULL)) {
            throw new MediaException("ffmpeg: cannot create swscale context for BGRA "
                    + w + "x" + h);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment srcBuf = arena.allocate((long) w * h * 4);
            MemorySegment.copy(src.bgra(), 0, srcBuf, ValueLayout.JAVA_BYTE, 0, src.bgra().length);
            MemorySegment srcData = arena.allocate(ValueLayout.ADDRESS, 4);
            srcData.setAtIndex(ValueLayout.ADDRESS, 0, srcBuf);
            MemorySegment srcStride = arena.allocate(ValueLayout.JAVA_INT, 4);
            srcStride.setAtIndex(ValueLayout.JAVA_INT, 0, w * 4);

            long exactBytes = (long) dstW * dstH * 4;
            MemorySegment dstBuf = Ffm.allocateSwscaleBgraDst(arena, dstW, dstH);
            MemorySegment dstData = arena.allocate(ValueLayout.ADDRESS, 4);
            dstData.setAtIndex(ValueLayout.ADDRESS, 0, dstBuf);
            MemorySegment dstStride = arena.allocate(ValueLayout.JAVA_INT, 4);
            dstStride.setAtIndex(ValueLayout.JAVA_INT, 0, dstW * 4);

            ff.swsScale(sws, srcData, srcStride, 0, h, dstData, dstStride);

            RasterFrame scaled = new RasterFrame(dstW, dstH,
                    dstBuf.asSlice(0, exactBytes).toArray(ValueLayout.JAVA_BYTE));
            return cropSide > 0 ? Thumbnails.cropCenterSquare(scaled, cropSide) : scaled;
        } finally {
            ff.swsFreeContext(sws);
        }
    }

    /**
     * Scales a decoded frame to BGRA. With {@code maxEdge <= 0} this is the
     * coded size with bilinear scaling (the viewer's full-frame path,
     * unchanged); with {@code maxEdge > 0} it fits within the box at the
     * frame's coded (stored) aspect using area averaging, which downscales
     * cleaner for thumbnails.
     *
     * <p>The thumbnail deliberately does <em>not</em> apply the sample aspect
     * ratio: the viewer ({@link #decodeFirstFrame} at coded size), the probe
     * dimensions, video playback ({@code FfmpegVideoStream}) and the pure-Java
     * backend all present frames at their coded pixels, so SAR-correcting only
     * the poster made anamorphic media (e.g. a QCIF {@code .3gp} with SAR
     * 12:11) look stretched in the mosaic relative to its own viewer.</p>
     */
    private RasterFrame scaleFrameToBgra(Arena arena, MemorySegment frame, int maxEdge,
                                         ThumbnailMode mode) {
        int w = ff.frameWidth(frame);
        int h = ff.frameHeight(frame);
        int fmt = ff.frameFormat(frame);
        if (w <= 0 || h <= 0 || fmt < 0) return null;

        int dstW, dstH, flags;
        int cropSide = 0;   // > 0 means centre-crop the cover-scaled output (FILL)
        if (maxEdge <= 0) {
            dstW = w;
            dstH = h;
            flags = ff.swsBilinear();
        } else {
            // Fit/fill at the coded (stored) pixel aspect, matching the viewer,
            // the probe, video playback and the pure-Java backend; see the
            // method javadoc for why SAR is not applied here.
            flags = ff.swsArea();
            if (mode == ThumbnailMode.FILL) {
                // Cover-scale so the shorter edge == side, then crop the centred
                // side x side square out of the scaled output.
                int shortEdge = Math.min(w, h);
                cropSide = Math.min(maxEdge, shortEdge);
                double scale = cropSide / (double) shortEdge;
                dstW = Math.max(cropSide, (int) Math.round(w * scale));
                dstH = Math.max(cropSide, (int) Math.round(h * scale));
            } else {
                int[] fit = Thumbnails.fittedSize(w, h, maxEdge);
                dstW = fit[0];
                dstH = fit[1];
            }
        }

        MemorySegment sws = ff.swsGetContextToBgra(w, h, fmt, dstW, dstH, flags);
        if (sws.equals(MemorySegment.NULL)) {
            throw new MediaException("ffmpeg: cannot create swscale context for "
                    + ff.pixFmtName(fmt) + " " + w + "x" + h);
        }
        try {
            // Padded so swscale's SIMD tail over-write stays inside the
            // allocation (see Ffm.allocateSwscaleBgraDst); only the exact
            // dstW*dstH*4 prefix is read back into the frame.
            long exactBytes = (long) dstW * dstH * 4;
            MemorySegment dstBuf = Ffm.allocateSwscaleBgraDst(arena, dstW, dstH);
            MemorySegment dstData = arena.allocate(ValueLayout.ADDRESS, 4);
            dstData.setAtIndex(ValueLayout.ADDRESS, 0, dstBuf);
            MemorySegment dstStride = arena.allocate(ValueLayout.JAVA_INT, 4);
            dstStride.setAtIndex(ValueLayout.JAVA_INT, 0, dstW * 4);

            ff.swsScale(sws, ff.frameData(frame), ff.frameLinesize(frame),
                    0, h, dstData, dstStride);

            RasterFrame scaled = new RasterFrame(dstW, dstH,
                    dstBuf.asSlice(0, exactBytes).toArray(ValueLayout.JAVA_BYTE));
            return cropSide > 0 ? Thumbnails.cropCenterSquare(scaled, cropSide) : scaled;
        } finally {
            ff.swsFreeContext(sws);
        }
    }
}
