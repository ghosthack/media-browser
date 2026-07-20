package io.github.ghosthack.mediabrowser.media.jcodec;

import io.github.ghosthack.mediabrowser.media.BufferedImageRaster;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.VideoStream;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.scale.AWTUtil;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * A {@link VideoStream} over jcodec's pure-Java {@link FrameGrab}: each
 * {@link #next()} decodes the following picture, converts it to a
 * {@link BufferedImage} via jcodec-javase's {@link AWTUtil}, and writes it as
 * tightly packed straight-alpha BGRA ({@link BufferedImageRaster}) into one
 * reusable native buffer. Pure Java: H.264 / MPEG / ProRes in MP4/MOV/MKV.
 *
 * <p>Confined to the opening thread per the {@link VideoStream} contract: it
 * owns a single confined {@link Arena} and one reusable BGRA
 * {@link MemorySegment} of {@code width * height * 4} bytes, plus the underlying
 * seekable file channel. {@link #ptsMicros()} is the frame timestamp
 * (microseconds) from jcodec's frame metadata; the duration comes from the video
 * track meta. The very first frame is decoded eagerly in the constructor to
 * establish the (cropped, display) dimensions, then served by the first
 * {@link #next()} call.</p>
 *
 * <p>Self-contained: imports only {@code io.github.ghosthack.mediabrowser.media.*},
 * {@code org.jcodec.*}, {@code java.awt.image.BufferedImage},
 * {@code java.lang.foreign.*} and the JDK — never {@code media.pure.*} or
 * {@code io.github.ghosthack.*}.</p>
 */
public final class JcodecVideoStream implements VideoStream {

    private final FileChannelWrapper channel;
    private final FrameGrab grab;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment bgra;
    private final BufferedImageRaster.RowScratch scratch;
    private final int width;
    private final int height;
    private final long durationMicros;

    private BufferedImage pending;
    private long pendingPtsMicros;
    private long ptsMicros = -1;
    private boolean closed;

    /** Opens a frame grabber over {@code file}'s first video track. */
    public JcodecVideoStream(Path file) {
        FileChannelWrapper ch = null;
        boolean ok = false;
        try {
            ch = NIOUtils.readableChannel(file.toFile());
            FrameGrab g = FrameGrab.createFrameGrab(ch);
            this.channel = ch;
            this.grab = g;

            long dur = -1;
            SeekableDemuxerTrack track = g.getVideoTrack();
            if (track != null) {
                DemuxerTrackMeta meta = track.getMeta();
                if (meta != null && meta.getTotalDuration() > 0) {
                    dur = Math.round(meta.getTotalDuration() * 1_000_000.0);
                }
            }
            this.durationMicros = dur;

            PictureWithMetadata first = g.getNativeFrameWithMetadata();
            if (first == null || first.getPicture() == null) {
                throw new MediaException("jcodec: no decodable frame in "
                        + file.getFileName());
            }
            BufferedImage img = AWTUtil.toBufferedImage(first.getPicture());
            if (img == null) {
                throw new MediaException("jcodec: cannot convert frame of "
                        + file.getFileName());
            }
            this.width = img.getWidth();
            this.height = img.getHeight();
            if (width <= 0 || height <= 0) {
                throw new MediaException("jcodec: unknown video dimensions in "
                        + file.getFileName());
            }
            this.pending = img;
            this.pendingPtsMicros = Math.round(first.getTimestamp() * 1_000_000.0);
            this.bgra = arena.allocate((long) width * height * 4);
            this.scratch = new BufferedImageRaster.RowScratch(width);
            ok = true;
        } catch (MediaException e) {
            NIOUtils.closeQuietly(ch);
            throw e;
        } catch (IOException | JCodecException | RuntimeException e) {
            NIOUtils.closeQuietly(ch);
            throw new MediaException("jcodec: cannot open video " + file.getFileName()
                    + ": " + e.getMessage(), e);
        } finally {
            if (!ok) {
                arena.close();
            }
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public boolean next() {
        if (closed) {
            return false;
        }
        try {
            if (pending != null) {
                writeFrame(pending, pendingPtsMicros);
                pending = null;
                return true;
            }
            PictureWithMetadata p = grab.getNativeFrameWithMetadata();
            if (p == null || p.getPicture() == null) {
                return false;
            }
            BufferedImage img = AWTUtil.toBufferedImage(p.getPicture());
            if (img == null) {
                return false;
            }
            writeFrame(img, Math.round(p.getTimestamp() * 1_000_000.0));
            return true;
        } catch (MediaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MediaException("jcodec: decode failed: " + e.getMessage(), e);
        }
    }

    private void writeFrame(BufferedImage img, long pts) {
        if (img.getWidth() != width || img.getHeight() != height) {
            throw new MediaException("jcodec: frame is "
                    + img.getWidth() + "x" + img.getHeight()
                    + ", expected " + width + "x" + height);
        }
        BufferedImageRaster.writeBgra(img, bgra, scratch);
        ptsMicros = pts;
    }

    @Override
    public long ptsMicros() {
        return ptsMicros;
    }

    @Override
    public MemorySegment bgra() {
        return bgra;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending = null;
        try {
            NIOUtils.closeQuietly(channel);
        } finally {
            arena.close();
        }
    }
}
