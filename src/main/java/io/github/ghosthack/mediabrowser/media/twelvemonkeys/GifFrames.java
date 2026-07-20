package io.github.ghosthack.mediabrowser.media.twelvemonkeys;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Animated-GIF compositor over the JDK GIF {@link ImageReader}. Each stored GIF
 * frame is only a sub-rectangle of the logical screen with its own offset and a
 * disposal method describing how it is cleared before the next frame; this class
 * replays that little state machine so {@link #frame(int)} returns each frame
 * already <em>composited onto the full logical canvas</em> — exactly what a
 * still poster or the {@link GifVideoStream} playback path needs.
 *
 * <p>Disposal handling (read from each frame's {@code GraphicControlExtension}):</p>
 * <ul>
 *   <li>{@code none}/{@code doNotDispose} — the frame stays on the canvas, the
 *       next frame draws over it;</li>
 *   <li>{@code restoreToBackgroundColor} — the frame's rectangle is cleared to
 *       transparent before the next frame;</li>
 *   <li>{@code restoreToPrevious} — the canvas is reverted to its contents from
 *       before this frame was drawn.</li>
 * </ul>
 *
 * <p>Frame&nbsp;0 is the poster. Per-frame delays are exposed in microseconds
 * ({@code delayTime} centiseconds &times; 10000), <em>clamped up to a 100&nbsp;ms
 * minimum</em> for the "as fast as possible" frames every real GIF viewer fixes
 * up (see {@link #normalizeDelayMicros}). The GIF's NETSCAPE2.0/ANIMEXTS1.0
 * loop count is parsed and exposed by {@link #loopCount()} (so the playback path
 * can loop like a real viewer). Self-contained: imports only
 * {@code javax.imageio.*}, {@code java.awt.*}/{@code java.awt.image.*},
 * {@code org.w3c.dom.*} and the JDK.</p>
 */
public final class GifFrames implements Closeable {

    /** The JDK GIF per-image native metadata format. */
    private static final String GIF_IMAGE_FORMAT = "javax_imageio_gif_image_1.0";
    /** The JDK GIF per-stream native metadata format. */
    private static final String GIF_STREAM_FORMAT = "javax_imageio_gif_stream_1.0";

    /** GIF spec: clear the frame's rectangle to background before the next frame. */
    private static final int DISPOSE_RESTORE_BACKGROUND = 2;
    /** GIF spec: revert the canvas to its pre-frame contents before the next frame. */
    private static final int DISPOSE_RESTORE_PREVIOUS = 3;

    /**
     * {@link #loopCount()} value when the GIF carries no NETSCAPE2.0/ANIMEXTS1.0
     * looping extension: by convention such a GIF is played exactly once.
     */
    public static final int LOOP_UNSPECIFIED = -1;
    /** {@link #loopCount()} value for a GIF that loops forever (NETSCAPE count 0). */
    public static final int LOOP_INFINITE = 0;

    /**
     * Default delay (100&nbsp;ms) substituted for a frame that asks to display
     * "as fast as possible" — see {@link #normalizeDelayMicros}.
     */
    private static final long DEFAULT_DELAY_MICROS = 100_000L;
    /**
     * A stored delay at or below this (10&nbsp;ms, i.e. {@code delayTime <= 1}
     * centisecond, including the very common 0) is treated as "as fast as
     * possible" and replaced with {@link #DEFAULT_DELAY_MICROS}.
     */
    private static final long MIN_HONORED_DELAY_MICROS = 10_000L;

    private final ImageInputStream input;
    private final ImageReader reader;
    private final int width;
    private final int height;
    private final int numFrames;
    private final long[] delayMicros;
    private final FrameInfo[] frames;
    private final int loopCount;

    /**
     * Count of underlying {@code reader.read(i)} source decodes performed (across
     * both {@link #frame(int)} and any {@link Cursor}). Package-visible so tests
     * can assert that a forward {@link Cursor} pass is O(n) — one decode per
     * frame — rather than the O(n²) of repeated random-access {@link #frame(int)}.
     */
    private long decodeCount;

    private GifFrames(ImageInputStream input, ImageReader reader) throws IOException {
        this.input = input;
        this.reader = reader;
        int count = reader.getNumImages(true);
        if (count <= 0) {
            count = 1;
        }
        this.numFrames = count;
        this.frames = new FrameInfo[count];
        this.delayMicros = new long[count];
        int logicalW = 0;
        int logicalH = 0;
        try {
            int[] screen = readLogicalScreen(reader.getStreamMetadata());
            logicalW = screen[0];
            logicalH = screen[1];
        } catch (IOException | RuntimeException ignore) {
            // Fall back to frame extents below.
        }
        for (int i = 0; i < count; i++) {
            FrameInfo info = readFrameInfo(i);
            frames[i] = info;
            delayMicros[i] = normalizeDelayMicros(info.delayCentis);
            logicalW = Math.max(logicalW, info.left + info.frameWidth);
            logicalH = Math.max(logicalH, info.top + info.frameHeight);
        }
        if (logicalW <= 0 || logicalH <= 0) {
            // Last resort: the dimensions ImageIO reports for frame 0.
            logicalW = Math.max(logicalW, reader.getWidth(0));
            logicalH = Math.max(logicalH, reader.getHeight(0));
        }
        this.width = logicalW;
        this.height = logicalH;
        this.loopCount = readLoopCount();
    }

    /** Opens {@code file} as a GIF and reads its frame structure. */
    public static GifFrames open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        ImageInputStream iis = ImageIO.createImageInputStream(file.toFile());
        if (iis == null) {
            throw new IOException("no ImageInputStream for " + file);
        }
        ImageReader reader = null;
        try {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            while (it.hasNext()) {
                ImageReader candidate = it.next();
                if ("gif".equalsIgnoreCase(candidate.getFormatName())) {
                    reader = candidate;
                    break;
                }
                if (reader == null) {
                    reader = candidate; // keep the first as a fallback
                } else {
                    candidate.dispose();
                }
            }
            if (reader == null) {
                throw new IOException("no ImageReader for " + file);
            }
            reader.setInput(iis, false, false);
            return new GifFrames(iis, reader);
        } catch (IOException | RuntimeException e) {
            if (reader != null) {
                reader.dispose();
            }
            try {
                iis.close();
            } catch (IOException ignore) {
                // best effort
            }
            throw e;
        }
    }

    /** Number of frames in the GIF (&ge; 1). */
    public int numFrames() {
        return numFrames;
    }

    /** Logical screen width in pixels. */
    public int width() {
        return width;
    }

    /** Logical screen height in pixels. */
    public int height() {
        return height;
    }

    /** Per-frame display delay in microseconds (index-aligned with the frames). */
    public long[] delayMicros() {
        return delayMicros.clone();
    }

    /** Display delay of frame {@code index} in microseconds. */
    public long delayMicros(int index) {
        return delayMicros[index];
    }

    /**
     * The GIF's loop count from its NETSCAPE2.0/ANIMEXTS1.0 Application Extension:
     * {@link #LOOP_INFINITE} (0) to loop forever, a positive iteration count, or
     * {@link #LOOP_UNSPECIFIED} (-1) when the GIF carries no looping extension
     * (by convention, play once). The playback path ({@link GifVideoStream}) uses
     * this to repeat the animation like a real viewer.
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * Converts a stored {@code delayTime} (centiseconds) to microseconds,
     * clamping "as fast as possible" frames up to {@link #DEFAULT_DELAY_MICROS}.
     *
     * <p>A great many GIFs (notably the classic zero-delay "flash" animations and
     * ads) specify a {@code delayTime} of 0 — or, historically, any value of
     * 10&nbsp;ms or less ({@code delayTime <= 1} centisecond) — meaning "display
     * as fast as the viewer can". Honoring that literally would make
     * {@link GifVideoStream} race through frames with no pacing (and report a
     * degenerate frame rate). Every real GIF viewer (Firefox, Chromium, Safari)
     * instead substitutes a default of 100&nbsp;ms for such frames, so the GIF
     * plays at a watchable ~10&nbsp;fps. This is the single source of truth for
     * that rule, shared by the playback pacing and the facade's
     * {@code frameRate}/{@code durationMicros} probe.</p>
     */
    private static long normalizeDelayMicros(int delayCentis) {
        long micros = delayCentis * 10_000L;
        return micros <= MIN_HONORED_DELAY_MICROS ? DEFAULT_DELAY_MICROS : micros;
    }

    /**
     * Frame {@code index} composited onto the full logical canvas (honouring
     * offsets and the disposal methods of all preceding frames). The returned
     * image is {@code TYPE_INT_ARGB} and {@code width()}&times;{@code height()}.
     *
     * <p>Random access: this re-composites frames {@code 0..index} from scratch,
     * so it costs {@code index + 1} source decodes. It is ideal for the poster
     * ({@code frame(0)}) and one-off seeks; for forward playback use a
     * {@link Cursor}, which decodes each source frame exactly once.</p>
     */
    public BufferedImage frame(int index) throws IOException {
        if (index < 0 || index >= numFrames) {
            throw new IndexOutOfBoundsException("frame " + index + " of " + numFrames);
        }
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        BufferedImage saved = null;  // reusable pre-frame snapshot for restoreToPrevious
        boolean savedValid = false;
        for (int i = 0; i <= index; i++) {
            FrameInfo info = frames[i];
            if (info.disposal == DISPOSE_RESTORE_PREVIOUS) {
                if (saved == null) {
                    saved = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                }
                copyPixels(canvas, saved); // snapshot the pre-frame canvas
                savedValid = true;
            }
            drawSource(i, info, canvas);
            if (i == index) {
                break;
            }
            // Apply this frame's disposal before drawing the next one.
            switch (info.disposal) {
                case DISPOSE_RESTORE_BACKGROUND -> clearRect(canvas, info);
                case DISPOSE_RESTORE_PREVIOUS -> {
                    if (savedValid) {
                        copyPixels(saved, canvas); // revert in place; keep canvas identity
                        savedValid = false;
                    }
                }
                default -> { /* none / doNotDispose: keep the canvas */ }
            }
        }
        return canvas;
    }

    /**
     * Opens a forward-only {@link Cursor} that composites frames incrementally,
     * decoding each source frame exactly once over a full pass. Its output for
     * frame {@code i} is pixel-identical to {@link #frame(int) frame(i)}, but a
     * complete forward pass is O(n) source decodes instead of O(n²).
     */
    public Cursor cursor() {
        return new Cursor();
    }

    /** Total underlying source decodes performed so far (tests rely on this). */
    long decodeCount() {
        return decodeCount;
    }

    /** Decodes source frame {@code i} and draws it onto {@code canvas} at its offset. */
    private void drawSource(int i, FrameInfo info, BufferedImage canvas) throws IOException {
        decodeCount++;
        BufferedImage src = reader.read(i);
        Graphics2D g = canvas.createGraphics();
        try {
            g.drawImage(src, info.left, info.top, null);
        } finally {
            g.dispose();
        }
    }

    /**
     * Forward-only incremental compositor. Maintains the running canvas and the
     * {@code restoreToPrevious} snapshot across calls so that each source frame
     * is decoded once; {@link #next()} returns the next composited frame (the
     * live canvas, valid until the following {@code next()}), or {@code null}
     * once the GIF is exhausted. Confined to its caller's thread, like the
     * {@link GifVideoStream} that drives it.
     */
    public final class Cursor {
        private int cursor = -1;
        private BufferedImage canvas;
        private BufferedImage savedPrev; // reusable pre-frame snapshot for restoreToPrevious
        private boolean savedValid;

        private Cursor() {
        }

        /**
         * Advances to and returns the next composited frame ({@code TYPE_INT_ARGB},
         * {@code width()}&times;{@code height()}), or {@code null} at the end. The
         * returned image is the cursor's live canvas and is overwritten by the
         * next call, so consumers must copy out what they need immediately.
         */
        public BufferedImage next() throws IOException {
            int i = cursor + 1;
            if (i >= numFrames) {
                return null;
            }
            if (i == 0) {
                canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            } else {
                // Apply the previous frame's disposal before drawing this one.
                FrameInfo prev = frames[i - 1];
                switch (prev.disposal) {
                    case DISPOSE_RESTORE_BACKGROUND -> clearRect(canvas, prev);
                    case DISPOSE_RESTORE_PREVIOUS -> {
                        if (savedValid) {
                            copyPixels(savedPrev, canvas); // revert in place; keep canvas identity
                            savedValid = false;
                        }
                    }
                    default -> { /* none / doNotDispose: keep the canvas */ }
                }
            }
            FrameInfo info = frames[i];
            if (info.disposal == DISPOSE_RESTORE_PREVIOUS) {
                if (savedPrev == null) {
                    savedPrev = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                }
                copyPixels(canvas, savedPrev); // snapshot the pre-frame canvas
                savedValid = true;
            }
            drawSource(i, info, canvas);
            cursor = i;
            return canvas;
        }
    }

    @Override
    public void close() {
        reader.dispose();
        try {
            input.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- frame metadata -----------------------------------------------------

    private FrameInfo readFrameInfo(int index) {
        int left = 0;
        int top = 0;
        int frameWidth = 0;
        int frameHeight = 0;
        int disposal = 0;
        int delayCentis = 0;
        try {
            IIOMetadata md = reader.getImageMetadata(index);
            Node tree = md == null ? null : md.getAsTree(GIF_IMAGE_FORMAT);
            Node descriptor = findChild(tree, "ImageDescriptor");
            if (descriptor != null) {
                left = attrInt(descriptor, "imageLeftPosition", 0);
                top = attrInt(descriptor, "imageTopPosition", 0);
                frameWidth = attrInt(descriptor, "imageWidth", 0);
                frameHeight = attrInt(descriptor, "imageHeight", 0);
            }
            Node gce = findChild(tree, "GraphicControlExtension");
            if (gce != null) {
                disposal = disposalCode(attr(gce, "disposalMethod"));
                delayCentis = attrInt(gce, "delayTime", 0);
            }
        } catch (IOException | RuntimeException ignore) {
            // Leave defaults; the full-canvas fallback below keeps it sane.
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            try {
                frameWidth = reader.getWidth(index);
                frameHeight = reader.getHeight(index);
            } catch (IOException | RuntimeException ignore) {
                // leave as-is
            }
        }
        return new FrameInfo(left, top, frameWidth, frameHeight, disposal, delayCentis);
    }

    /**
     * Reads the GIF's loop count from the NETSCAPE2.0 (or ANIMEXTS1.0) Application
     * Extension, which the JDK GIF reader attaches to image&nbsp;0's metadata as an
     * {@code ApplicationExtensions/ApplicationExtension} node whose user object is
     * the 3-byte sub-block {@code {1, loopLo, loopHi}} (little-endian count).
     * Returns {@link #LOOP_UNSPECIFIED} when the extension is absent or malformed.
     */
    private int readLoopCount() {
        try {
            IIOMetadata md = reader.getImageMetadata(0);
            Node tree = md == null ? null : md.getAsTree(GIF_IMAGE_FORMAT);
            Node appExts = findChild(tree, "ApplicationExtensions");
            if (appExts == null) {
                return LOOP_UNSPECIFIED;
            }
            for (Node child = appExts.getFirstChild(); child != null;
                    child = child.getNextSibling()) {
                if (!"ApplicationExtension".equals(child.getNodeName())) {
                    continue;
                }
                String id = attr(child, "applicationID");
                if (!"NETSCAPE".equalsIgnoreCase(id) && !"ANIMEXTS".equalsIgnoreCase(id)) {
                    continue;
                }
                if (child instanceof IIOMetadataNode node
                        && node.getUserObject() instanceof byte[] bytes
                        && bytes.length >= 3 && (bytes[0] & 0xFF) == 1) {
                    return (bytes[1] & 0xFF) | ((bytes[2] & 0xFF) << 8);
                }
            }
            return LOOP_UNSPECIFIED;
        } catch (IOException | RuntimeException ignore) {
            return LOOP_UNSPECIFIED;
        }
    }

    private static int[] readLogicalScreen(IIOMetadata streamMetadata) {
        if (streamMetadata == null) {
            return new int[] {0, 0};
        }
        Node tree = streamMetadata.getAsTree(GIF_STREAM_FORMAT);
        Node lsd = findChild(tree, "LogicalScreenDescriptor");
        if (lsd == null) {
            return new int[] {0, 0};
        }
        return new int[] {
                attrInt(lsd, "logicalScreenWidth", 0),
                attrInt(lsd, "logicalScreenHeight", 0),
        };
    }

    /** Maps the GIF native {@code disposalMethod} string to its numeric code. */
    private static int disposalCode(String name) {
        if (name == null) {
            return 0;
        }
        return switch (name) {
            case "restoreToBackgroundColor" -> DISPOSE_RESTORE_BACKGROUND;
            case "restoreToPrevious" -> DISPOSE_RESTORE_PREVIOUS;
            default -> 0; // "none" / "doNotDispose" / unknown → keep
        };
    }

    // --- canvas helpers -----------------------------------------------------

    private void clearRect(BufferedImage canvas, FrameInfo info) {
        int x = Math.max(0, info.left);
        int y = Math.max(0, info.top);
        int w = Math.min(info.frameWidth, width - x);
        int h = Math.min(info.frameHeight, height - y);
        if (w <= 0 || h <= 0) {
            return;
        }
        // Fast path: our canvas is always a fresh TYPE_INT_ARGB, so clear the
        // disposal rectangle with a per-row Arrays.fill over its int data bank
        // instead of O(w*h) BufferedImage.setRGB calls. setRGB goes through the
        // sample model one pixel at a time (and un-tracks the managed image);
        // a bulk row fill is dramatically cheaper for the large rectangles that
        // restoreToBackgroundColor disposal clears on every playback frame.
        WritableRaster raster = canvas.getRaster();
        if (raster.getDataBuffer() instanceof DataBufferInt intBuffer
                && raster.getSampleModel() instanceof SinglePixelPackedSampleModel spp
                && intBuffer.getNumBanks() == 1
                && intBuffer.getOffset() == 0
                && spp.getScanlineStride() == width
                && raster.getSampleModelTranslateX() == 0
                && raster.getSampleModelTranslateY() == 0) {
            int[] data = intBuffer.getData();
            for (int row = y; row < y + h; row++) {
                int base = row * width + x;
                Arrays.fill(data, base, base + w, 0); // transparent
            }
            return;
        }
        // Defensive fallback for any unexpected raster layout.
        for (int row = y; row < y + h; row++) {
            for (int col = x; col < x + w; col++) {
                canvas.setRGB(col, row, 0); // transparent
            }
        }
    }

    /**
     * Replaces {@code dst}'s contents with {@code src}'s (both the full logical
     * canvas, {@code TYPE_INT_ARGB}). Used to snapshot/revert the canvas for
     * {@code restoreToPrevious} disposal. For the standard packed-int canvas this
     * class always holds, this is a single {@link System#arraycopy} over the int
     * data bank — no allocation and no {@code drawImage} per restoreToPrevious
     * frame, the same bulk-copy strategy {@link #clearRect} uses. A defensive
     * {@link AlphaComposite#Src} {@code drawImage} (a replace, not a composite)
     * covers any unexpected raster layout.
     */
    private void copyPixels(BufferedImage src, BufferedImage dst) {
        WritableRaster sr = src.getRaster();
        WritableRaster dr = dst.getRaster();
        if (sr.getDataBuffer() instanceof DataBufferInt sb
                && dr.getDataBuffer() instanceof DataBufferInt db
                && sr.getSampleModel() instanceof SinglePixelPackedSampleModel ssp
                && dr.getSampleModel() instanceof SinglePixelPackedSampleModel dsp
                && sb.getNumBanks() == 1 && db.getNumBanks() == 1
                && sb.getOffset() == 0 && db.getOffset() == 0
                && ssp.getScanlineStride() == width && dsp.getScanlineStride() == width
                && sr.getSampleModelTranslateX() == 0 && sr.getSampleModelTranslateY() == 0
                && dr.getSampleModelTranslateX() == 0 && dr.getSampleModelTranslateY() == 0) {
            System.arraycopy(sb.getData(), 0, db.getData(), 0, width * height);
            return;
        }
        // Defensive fallback: replace (not composite) dst with src.
        Graphics2D g = dst.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
    }

    // --- DOM helpers --------------------------------------------------------

    private static Node findChild(Node parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
    }

    private static String attr(Node node, String name) {
        if (node == null) {
            return null;
        }
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) {
            return null;
        }
        Node a = attrs.getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }

    private static int attrInt(Node node, String name, int fallback) {
        String s = attr(node, name);
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Immutable per-frame placement + timing read from the GIF metadata. */
    private record FrameInfo(int left, int top, int frameWidth, int frameHeight,
                             int disposal, int delayCentis) {
    }
}
