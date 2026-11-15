package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Content detection for <em>animated</em> image sequences that wear a
 * still-image extension: animated AVIF / HEIC, and multi-frame GIF.
 *
 * <p>An animated AVIF (ftyp major brand {@code avis}) — and its HEVC sibling
 * (brand {@code hevs}) — carries a still primary image item <em>and</em> a
 * top-level {@code moov} animation track (an ISO-BMFF movie box, exactly like
 * an {@code .mp4}). Still AVIF/HEIC files have only a {@code meta} item and no
 * {@code moov}. A GIF is animated when it holds more than one image block. In
 * every case the still loaders (libvips/libheif, WIC, ImageIO) decode only a
 * single frame, so without a content probe these animations would silently
 * freeze on their first frame — and the viewer, which arms playback only for
 * {@link MediaKind#VIDEO}, would never offer Play/autoplay.
 *
 * <p>{@link #isAnimatedImageSequence(Path)} cleanly separates the animated case
 * (which the FFM facade routes to the FFmpeg video path — the pure AV1 decoder
 * cannot play a moov track whose sequence header lives in {@code av1C}, and the
 * still decoders only render frame 0) from ordinary stills (which keep the
 * libvips/WIC/ImageIO still path). The probe is gated by the declared extension
 * so the common image-browsing path never touches disk for non-AVIF/HEIC/GIF
 * files, and the cheap {@code moov}-box / GIF-block scan is memoised per
 * {@code path|length|lastModified} (bounded LRU) so re-evaluating the same item
 * (listing, autoplay arming, viewer re-entry) stays off the hot path. Only a
 * completed scan is memoised: an I/O error is not a "not animated" verdict.
 *
 * <p>Ported from the Swing predecessor's {@code ImageDecodeUtil}
 * ({@code isAnimatedImageSequence} / {@code hasTopLevelMoov}); the GIF branch is
 * an additional cheap structural scan.
 */
public final class ImageSequences {

    /**
     * AVIF/HEIC-family extensions — the only still-image containers that can
     * also carry a {@code moov} animation track.
     */
    private static final Set<String> IMAGE_SEQUENCE_EXTENSIONS = Set.of(
            "avif", "avifs", "avis",
            "heic", "heif", "heics", "heifs", "hif");

    /** The one still-image extension whose container is a multi-frame animation. */
    private static final String GIF_EXTENSION = "gif";

    /** Bound on the top-level box walk so a malformed file cannot spin. */
    private static final int MAX_TOPLEVEL_BOXES = 4096;

    /** Bound on the GIF block walk so a malformed file cannot spin. */
    private static final int MAX_GIF_BLOCKS = 1 << 20;

    /** Most verdicts kept; the least recently used is dropped past this. */
    static final int MAX_MEMOISED_VERDICTS = 4096;

    /**
     * Memoised verdicts of completed scans, keyed by
     * {@code locator|length|lastModified} so a file edited in place is
     * re-probed, and bounded so a long session over many folders cannot grow
     * it without limit. Guarded by its own monitor.
     */
    private static final Map<String, Boolean> ANIMATED_SEQUENCE_CACHE =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_MEMOISED_VERDICTS;
                }
            };

    /** Memoised verdict count; for tests. */
    static int memoisedVerdicts() {
        synchronized (ANIMATED_SEQUENCE_CACHE) {
            return ANIMATED_SEQUENCE_CACHE.size();
        }
    }

    private ImageSequences() {}

    /**
     * Returns {@code true} when the file is an <em>animated</em> image sequence
     * wearing a still extension: an AVIF/HEIC carrying a top-level {@code moov}
     * animation track, or a GIF holding more than one image frame. Names that
     * are neither AVIF/HEIC nor GIF short-circuit without reading a byte.
     *
     * <p>This is the <em>classification</em> form, called for every listed
     * file: one it cannot read is reported as not an animation rather than
     * failing the listing, but that answer is never memoised — the next call
     * scans again. Decoding goes through {@link #decodeStillOrAnimation},
     * which throws on the same read error instead.
     */
    public static boolean isAnimatedImageSequence(Path file) {
        try {
            return scanAnimatedImageSequence(file);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@link #isAnimatedImageSequence} for a decode: the verdict, or the read
     * error that prevented one.
     *
     * @throws MediaException when the file cannot be scanned
     */
    private static boolean requireAnimatedVerdict(Path file) {
        try {
            return scanAnimatedImageSequence(file);
        } catch (IOException e) {
            throw new MediaException("cannot read " + file.getFileName()
                    + " to tell a still from an animation: " + e, e);
        }
    }

    /** The memoised scan; an {@link IOException} leaves nothing memoised. */
    private static boolean scanAnimatedImageSequence(Path file) throws IOException {
        if (file == null) {
            return false;
        }
        String ext = extension(file);
        boolean gif = GIF_EXTENSION.equals(ext);
        if (!gif && !isImageSequenceExtension(ext)) {
            return false;
        }
        // Read through java.nio.file, not java.io.File: this runs during every
        // directory scan, and an entry inside an archive has no java.io.File at
        // all. The probes below only walk headers, so they work the same on
        // either filesystem — and crucially without extracting anything, which
        // a per-file scan must never trigger.
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (RuntimeException e) {
            throw new IOException("cannot stat " + file.getFileName() + ": " + e, e);
        }
        if (!attributes.isRegularFile()) {
            return false;
        }
        // Keyed on the locator so two archives holding the same inner path do
        // not share a memoised verdict.
        String key = ArchivePaths.format(file.toAbsolutePath()) + '|' + attributes.size()
                + '|' + attributes.lastModifiedTime().toMillis();
        Boolean cached;
        synchronized (ANIMATED_SEQUENCE_CACHE) {
            cached = ANIMATED_SEQUENCE_CACHE.get(key);
        }
        if (cached != null) {
            return cached;
        }
        boolean animated = gif ? hasMultipleGifFrames(file) : hasTopLevelMoov(file);
        synchronized (ANIMATED_SEQUENCE_CACHE) {
            ANIMATED_SEQUENCE_CACHE.put(key, animated);
        }
        return animated;
    }

    private static boolean isImageSequenceExtension(String ext) {
        return ext != null && IMAGE_SEQUENCE_EXTENSIONS.contains(ext);
    }

    /**
     * Routing combinator shared by the strict native facades. A native still
     * loader has already claimed {@code file} (WIC / ImageIO sniff), but if
     * the file is actually an animated AVIF/HEIC the still decode would only
     * freeze on its first frame. The route is decided up front, by the same
     * two facts {@link #stillOrAnimationKind(Path, BooleanSupplier)} classifies
     * on — never by catching a failure:
     * <ul>
     *   <li>an ordinary still runs {@code stillPath}, never touching the
     *       heavier video path;</li>
     *   <li>an animation whose track the OS video engine does not expose
     *       ({@code animationTrack} is empty: Media Foundation rejects the
     *       {@code avis} brand, the Store codec is missing, ...) is the file
     *       the facade classified {@link MediaKind#IMAGE}: it runs
     *       {@code stillPath}, with the decline recorded in {@code trace};</li>
     *   <li>an animation with a track runs {@code animationPath}, and a
     *       failure there propagates. It is not swapped for the still: the
     *       file was classified {@link MediaKind#VIDEO}, and a frozen first
     *       frame standing in for a failed decode is exactly the silent
     *       degrade this must not produce.</li>
     *   <li>an {@code animationTrack} that <em>throws</em> (the engine could
     *       not answer at all: I/O or native failure) is recorded as failed
     *       and propagates; it is not read as "no track".</li>
     * </ul>
     *
     * @param file               the file a native still loader already matched
     * @param trace              records the decline; the two paths record their
     *                           own success or failure
     * @param animationStrategy  trace label of the animation route
     * @param animationTrack     the engine's handle on the animation track, or
     *                           empty when it exposes none
     * @param animationPath      decode via the video backend
     * @param stillPath          decode the still primary item
     * @throws MediaException when the file cannot be read to tell which it is
     */
    public static <V, T> T decodeStillOrAnimation(Path file,
                                                  MediaEngineTrace.Recorder trace,
                                                  String animationStrategy,
                                                  Supplier<Optional<V>> animationTrack,
                                                  Function<V, T> animationPath,
                                                  Supplier<T> stillPath) {
        if (!requireAnimatedVerdict(file)) {
            return stillPath.get();
        }
        long started = trace.beginAttempt();
        Optional<V> track;
        try {
            track = animationTrack.get();
        } catch (RuntimeException | Error e) {
            // The engine failed to answer (I/O, native error) — not the same
            // as "exposes no track": never decode the still in its place.
            trace.failed(animationStrategy, started, e);
            throw e;
        }
        if (track.isEmpty()) {
            trace.declined(animationStrategy, started,
                    "Animated, but this engine exposes no playable animation track;"
                            + " decoding the still primary item");
            return stillPath.get();
        }
        return animationPath.apply(track.get());
    }

    /**
     * The {@link MediaKind} a <em>permissive</em> backend (FFmpeg) should report
     * once its still sniff has matched {@code file}: {@link MediaKind#VIDEO} for
     * an animated AVIF/HEIC (so the viewer offers playback), else
     * {@link MediaKind#IMAGE}. FFmpeg's demuxer + bundled AV1/HEVC decoders
     * handle the moov track whenever the {@code moov} is present, so content
     * detection alone is a reliable VIDEO signal here.
     */
    public static MediaKind stillOrAnimationKind(Path file) {
        return isAnimatedImageSequence(file) ? MediaKind.VIDEO : MediaKind.IMAGE;
    }

    /**
     * The {@link MediaKind} a <em>strict</em> backend (Media Foundation, Apple)
     * should report for a still-sniffed {@code file}: {@link MediaKind#VIDEO}
     * only when the file is an animated AVIF/HEIC <em>and</em>
     * {@code videoBackendCanOpen} confirms the OS video engine can actually
     * demux it, else {@link MediaKind#IMAGE}.
     *
     * <p>Unlike FFmpeg, Media Foundation and AVFoundation rely on the OS source
     * resolver + Store codec MFTs, which reject many animated AVIF/HEIC
     * containers outright (e.g. MF refuses the {@code avis} major brand even
     * renamed to {@code .mp4}). Gating on real openability keeps the viewer from
     * enabling a Play button that can only error: such files show as the WIC /
     * ImageIO still instead. {@code videoBackendCanOpen} is only evaluated for
     * the rare animated AVIF/HEIC, never for ordinary stills.
     */
    public static MediaKind stillOrAnimationKind(Path file, BooleanSupplier videoBackendCanOpen) {
        return isAnimatedImageSequence(file) && videoBackendCanOpen.getAsBoolean()
                ? MediaKind.VIDEO
                : MediaKind.IMAGE;
    }

    /** Fills {@code buffer} to its limit; false at end of file. */
    private static boolean readFully(SeekableByteChannel channel, ByteBuffer buffer)
            throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scans the top-level ISO-BMFF box list for a {@code moov} box (handles
     * 32-bit, 64-bit {@code largesize}, and the {@code size == 0} "to EOF"
     * forms). Bounded so a malformed file cannot spin. A truncated or
     * malformed box list is a verdict (no moov); a read error is thrown.
     */
    private static boolean hasTopLevelMoov(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long pos = 0;
            ByteBuffer box = ByteBuffer.allocate(16);
            for (int i = 0; i < MAX_TOPLEVEL_BOXES && pos + 8 <= size; i++) {
                channel.position(pos);
                box.clear().limit(8);
                if (!readFully(channel, box)) {
                    break;
                }
                box.flip();
                long size32 = Integer.toUnsignedLong(box.getInt());
                byte[] type = new byte[4];
                box.get(type);
                if ("moov".equals(new String(type, StandardCharsets.US_ASCII))) {
                    return true;
                }
                long header = 8;
                long boxSize = size32;
                if (size32 == 1) {
                    if (pos + 16 > size) {
                        break;
                    }
                    box.clear().limit(8);
                    if (!readFully(channel, box)) {
                        break;
                    }
                    boxSize = box.flip().getLong();
                    header = 16;
                } else if (size32 == 0) {
                    break; // last box, extends to EOF, and it isn't moov
                }
                if (boxSize < header) {
                    break;
                }
                long next = pos + boxSize;
                if (next <= pos || next > size) {
                    break;
                }
                pos = next;
            }
        }
        // Walked every box (or hit a truncated/malformed one): no moov.
        return false;
    }

    /**
     * Returns {@code true} when the GIF holds more than one image frame (i.e.
     * it is animated). Walks the GIF block structure — header + logical screen
     * descriptor, then the block stream — counting {@code 0x2C} image
     * descriptors and stopping the instant a second one is found, so an animated
     * GIF is confirmed cheaply without decoding a single pixel of LZW. A
     * single-frame GIF, an unrecognised header, or a truncated block stream
     * reads as <em>not</em> animated (a still), so the file keeps the image
     * path. A read error is not a verdict: it is thrown.
     *
     * <p>Sub-block payloads (image data, extension data, colour tables) are
     * skipped via their length prefixes only — never buffered — and the walk is
     * bounded by {@link #MAX_GIF_BLOCKS} so a malformed file cannot spin.
     */
    private static boolean hasMultipleGifFrames(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            byte[] header = new byte[6];
            in.readFully(header);
            String magic = new String(header, StandardCharsets.US_ASCII);
            if (!"GIF87a".equals(magic) && !"GIF89a".equals(magic)) {
                return false;
            }
            // Logical Screen Descriptor: width(2) height(2) packed(1) bg(1) aspect(1).
            byte[] lsd = new byte[7];
            in.readFully(lsd);
            int packed = lsd[4] & 0xFF;
            if ((packed & 0x80) != 0) { // global colour table present
                skipFully(in, 3 * (1 << ((packed & 0x07) + 1)));
            }
            int frames = 0;
            for (int i = 0; i < MAX_GIF_BLOCKS; i++) {
                int id = in.read();
                if (id < 0 || id == 0x3B) { // EOF or trailer
                    break;
                }
                if (id == 0x2C) { // Image Descriptor: one frame
                    if (++frames >= 2) {
                        return true; // animated — stop at the second frame
                    }
                    byte[] desc = new byte[9]; // left,top,width,height(8) + packed(1)
                    in.readFully(desc);
                    int imgPacked = desc[8] & 0xFF;
                    if ((imgPacked & 0x80) != 0) { // local colour table present
                        skipFully(in, 3 * (1 << ((imgPacked & 0x07) + 1)));
                    }
                    if (in.read() < 0) { // LZW minimum code size
                        break;
                    }
                    skipSubBlocks(in); // image data
                } else if (id == 0x21) { // Extension: label + sub-blocks
                    if (in.read() < 0) {
                        break;
                    }
                    skipSubBlocks(in);
                } else {
                    break; // unknown/corrupt block
                }
            }
            return frames >= 2;
        } catch (EOFException truncated) {
            // The file ends mid-block: it cannot be confirmed animated.
            return false;
        }
    }

    /** Skips a GIF sub-block chain: {@code [len][len bytes]…} ended by a 0 length. */
    private static void skipSubBlocks(DataInputStream in) throws IOException {
        int len;
        while ((len = in.read()) > 0) {
            skipFully(in, len);
        }
        // len == 0 (block terminator) or -1 (EOF) ends the chain.
    }

    /** Skips exactly {@code n} bytes, falling back to reads at buffer boundaries. */
    private static void skipFully(DataInputStream in, int n) throws IOException {
        int remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException();
                }
                remaining--;
            } else {
                remaining -= (int) skipped;
            }
        }
    }

    private static String extension(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
