package io.github.ghosthack.mediabrowser.media.javacv;

import io.github.ghosthack.mediabrowser.media.BufferedImageRaster;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.VideoRotation;
import io.github.ghosthack.mediabrowser.media.VideoStream;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * A {@link VideoStream} over JavaCV's {@link FFmpegFrameGrabber} (bundled
 * FFmpeg): each {@link #next()} decodes the following video frame with
 * {@code grabImage()}, converts it to a {@link BufferedImage} via
 * {@link Java2DFrameConverter}, and writes it as tightly packed straight-alpha
 * BGRA ({@link BufferedImageRaster}) into one reusable native buffer.
 *
 * <p>Confined to the opening thread per the {@link VideoStream} contract: it
 * owns a single confined {@link Arena} and one reusable BGRA
 * {@link MemorySegment} of {@code width * height * 4} bytes. {@link #ptsMicros()}
 * is the grabber's frame timestamp (microseconds); dimensions and duration come
 * from the grabber. Closing it releases the grabber and converter and disposes
 * the arena.</p>
 *
 * <p>Self-contained: imports only {@code io.github.ghosthack.mediabrowser.media.*},
 * {@code org.bytedeco.javacv.*}, {@code java.awt.image.BufferedImage},
 * {@code java.lang.foreign.*} and the JDK — never {@code media.pure.*} or
 * {@code io.github.ghosthack.*}.</p>
 */
public final class JavaCvVideoStream implements VideoStream {

    private final FFmpegFrameGrabber grabber;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment bgra;
    private final BufferedImageRaster.RowScratch scratch;
    private final int width;              // coded frame width
    private final int height;             // coded frame height
    private final VideoRotation rotation; // null unless the file is rotated
    private final int displayWidth;       // post-rotation size reported to consumers
    private final int displayHeight;
    private final long durationMicros;

    private MemorySegment presented;      // bgra, or the rotated buffer
    private long ptsMicros = -1;
    private boolean closed;

    /** Opens and starts a grabber over {@code file}'s video stream. */
    public JavaCvVideoStream(Path file) {
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(file.toString());
        boolean ok = false;
        try {
            g.start();
            this.grabber = g;
            this.width = g.getImageWidth();
            this.height = g.getImageHeight();
            if (width <= 0 || height <= 0) {
                throw new MediaException("javacv: unknown video dimensions in "
                        + file.getFileName());
            }
            long len = g.getLengthInTime();
            this.durationMicros = len > 0 ? len : -1;
            this.bgra = arena.allocate((long) width * height * 4);
            this.scratch = new BufferedImageRaster.RowScratch(width);
            // Bake the container/display rotation so presented frames are
            // upright, matching the Apple (AVFoundation) backend.
            int q = VideoRotation.quarterTurnsCw(displayRotation(g));
            this.rotation = q != 0 ? new VideoRotation(width, height, q) : null;
            this.displayWidth = rotation != null ? rotation.displayWidth() : width;
            this.displayHeight = rotation != null ? rotation.displayHeight() : height;
            this.presented = bgra;
            ok = true;
        } catch (MediaException e) {
            closeQuietly(g);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(g);
            throw new MediaException("javacv: cannot open video " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
        finally {
            if (!ok) {
                arena.close();
            }
        }
    }

    @Override
    public int width() {
        return displayWidth;
    }

    @Override
    public int height() {
        return displayHeight;
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
            Frame frame = grabber.grabImage();
            if (frame == null) {
                return false;
            }
            BufferedImage img = converter.convert(frame);
            if (img == null) {
                return false;
            }
            if (img.getWidth() != width || img.getHeight() != height) {
                throw new MediaException("javacv: frame is "
                        + img.getWidth() + "x" + img.getHeight()
                        + ", expected " + width + "x" + height);
            }
            BufferedImageRaster.writeBgra(img, bgra, scratch);
            presented = rotation != null ? rotation.rotate(bgra) : bgra;
            ptsMicros = frame.timestamp;
            return true;
        } catch (MediaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MediaException("javacv: decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long ptsMicros() {
        return ptsMicros;
    }

    @Override
    public MemorySegment bgra() {
        return presented;
    }

    /** Display rotation in degrees, or 0 when the grabber cannot report it. */
    private static double displayRotation(FFmpegFrameGrabber g) {
        try {
            return g.getDisplayRotation();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            converter.close();
        } finally {
            try {
                closeQuietly(grabber);
            } finally {
                try {
                    if (rotation != null) rotation.close();
                } finally {
                    arena.close();
                }
            }
        }
    }

    private static void closeQuietly(FFmpegFrameGrabber grabber) {
        if (grabber == null) {
            return;
        }
        try {
            grabber.stop();
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
        try {
            grabber.release();
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }
}
