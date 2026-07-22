package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.ImageSequences;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.Metadata;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.VisualResult;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;
import io.github.ghosthack.mediabrowser.media.ffm.bind.VipsBindings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@link MediaFacade} backed by libvips (stills) and FFmpeg (AV media)
 * through Panama FFM jextract stubs.
 *
 * <p>Stills are recognized with {@code vips_foreign_find_load} (content
 * sniffing). Everything else is handed to the FFmpeg demuxer probe, gated by
 * a container-extension whitelist so that arbitrary binary/text files are not
 * misdetected by permissive demuxers.</p>
 *
 * <p><b>The magickload catch-all.</b> When the libvips build includes
 * ImageMagick, its {@code magickload} loader sniffs content extremely
 * permissively: ImageMagick falls back to its headerless TGA coder as a
 * last-resort guess for unidentified data (the crash was
 * {@code error/tga.c/ReadTGAImage}), so {@code vips_foreign_find_load} can
 * claim an arbitrary binary — a QuickTime {@code .MOV}, a {@code .bin}
 * firmware image, a half-downloaded file — as a still and shadow the FFmpeg
 * video path. Rather than deny-list every container that gets mis-claimed,
 * {@link #stillLoader} <em>allow-lists</em> magick: it trusts a magick match
 * only for a {@link #MAGICK_STILL_EXTENSIONS known still extension} and
 * ignores it otherwise (so the file routes to FFmpeg, or is reported
 * unsupported, instead of failing a bogus still decode). vips's own loaders
 * (PNG/JPEG/GIF/TIFF/WebP/HEIF) detect by real magic numbers and are never
 * gated.</p>
 *
 * <p><b>Animated AVIF/HEIC and GIF.</b> libvips/libheif claims an animated
 * AVIF/HEIC as a still loader, and libvips (giflib) likewise claims a GIF, but
 * an animated AVIF/HEIC carries a {@code moov} track and a multi-frame GIF holds
 * many image blocks the still decode can only freeze on. When
 * {@link ImageSequences#isAnimatedImageSequence} flags one, it is routed to the
 * FFmpeg video path (which demuxes the {@code moov} track / GIF frames and
 * decodes them) instead — falling back to the libvips still primary item if the
 * FFmpeg build cannot demux the animation. {@code gif} is also in the
 * {@link #AV_EXTENSIONS} whitelist so the FFmpeg demuxer claims it even on a
 * libvips build with no GIF loader.</p>
 */
public final class FfmpegVipsMediaFacade implements MediaFacade {

    // Package-visible: FfmpegFfmMediaFacade gates on the same list.
    static final Set<String> AV_EXTENSIONS = Set.of(
            "mp4", "m4v", "m4s", "mkv", "webm", "avi", "mov", "qt", "wmv", "asf",
            "flv", "f4v", "divx", "mpg", "mpeg", "m2v", "mpv",
            "ts", "m2ts", "mts", "ogv", "ogm", "3gp", "3g2", "vob", "mxf", "nut",
            "dv", "rm",
            "mp3", "mp2", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
            "aiff", "aif", "ape", "mka", "ac3", "dts", "amr", "caf", "au", "ra",
            "mpc", "wv", "tta", "spx", "dsf",
            // GIF: an animated GIF plays through the FFmpeg gif demuxer; listed
            // here so it is claimed even when this libvips build lacks giflib.
            "gif");

    /**
     * Still-image extensions an ImageMagick ({@code magickload}) match is
     * trusted for. magick's coders are the only permissive libvips loaders
     * (its TGA fallback guesses headerless data, claiming arbitrary binaries),
     * so {@link #stillLoader} honours a magick match only for these known
     * formats. Excludes anything vips loads through its own magic-sniffing
     * loaders (PNG/JPEG/GIF/TIFF/WebP/HEIF/AVIF), which never reach magick.
     */
    private static final Set<String> MAGICK_STILL_EXTENSIONS = Set.of(
            "tga", "icb", "vda", "vst",            // Targa family (the catch-all coder)
            "psd", "psb",                          // Photoshop
            "ico", "cur",                          // Windows icon / cursor
            "dds", "dib",                          // DirectDraw surface / DIB
            "pcx", "sgi", "rgb", "rgba", "bw",     // legacy raster
            "xpm", "xbm", "pict", "pct", "pic",    // X11 / QuickDraw
            "ppm", "pgm", "pbm", "pnm", "pfm",     // netpbm
            "exr", "hdr",                          // HDR
            "jp2", "j2k", "jpc", "jpx",            // JPEG 2000
            "dpx", "cin", "fits", "fit", "fts",   // cinema / scientific
            "jng", "mng", "miff", "mtv", "otb", "palm", "wpg");

    private final FfmpegBindings ffmpeg;
    private final FfmpegAv av;
    private final FfmpegMetadata ffmpegMetadata;
    private final VipsStills stills;
    private final VipsMetadata vipsMetadata;

    /**
     * @param ffmpeg the FFmpeg binding (selects the FFmpeg version/install)
     * @param vips   the libvips binding (selects the libvips version/install)
     */
    public FfmpegVipsMediaFacade(FfmpegBindings ffmpeg, VipsBindings vips) {
        this.ffmpeg = ffmpeg;
        // vips_init must run before anything touches libvips (incl. VipsMetadata's
        // blob-type probe), so initialize the libraries up front.
        vips.init("media-browser");
        this.av = new FfmpegAv(ffmpeg);              // also quiets libav logging
        this.ffmpegMetadata = new FfmpegMetadata(ffmpeg);
        this.stills = new VipsStills(vips);
        this.vipsMetadata = new VipsMetadata(vips);
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        if (stillLoader(file) != null) {
            return Optional.of(ImageSequences.stillOrAnimationKind(file));
        }
        if (AV_EXTENSIONS.contains(extension(file))) {
            try {
                return Optional.of(av.probe(file, -1).kind());
            } catch (MediaException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public MediaProbe probe(Path file) {
        long size = fileSize(file);
        String loader = stillLoader(file);
        if (loader != null) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> av.probe(file, size),
                    () -> stills.probe(file, size, loader));
        }
        return av.probe(file, size);
    }

    @Override
    public VisualResult loadVisual(Path file) {
        long size = fileSize(file);
        String loader = stillLoader(file);
        if (loader != null) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> av.firstFrameWithProbe(file, size),
                    () -> stills.decodeWithProbe(file, size, loader));
        }
        return av.firstFrameWithProbe(file, size);
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        String loader = stillLoader(file);
        if (loader != null) {
            return ImageSequences.decodeStillOrAnimation(file,
                    () -> av.thumbnail(file, maxEdge, mode),
                    () -> new Thumbnail(Optional.of(stills.thumbnail(file, maxEdge, mode)),
                            MediaKind.IMAGE));
        }
        return av.thumbnail(file, maxEdge, mode);
    }

    @Override
    public Metadata readMetadata(Path file) {
        // Same still-vs-AV branch as probe(): libvips loader-sniffing decides,
        // dispatching stills to VipsMetadata and everything else to FfmpegMetadata.
        String loader = stillLoader(file);
        if (loader != null) {
            return vipsMetadata.read(file);
        }
        return ffmpegMetadata.read(file);
    }

    @Override
    public VideoStream openVideo(Path file) {
        return new FfmpegVideoStream(ffmpeg, file);
    }

    @Override
    public String nativeVersions() {
        return "FFmpeg " + av.version() + ", libvips " + stills.version();
    }

    @Override
    public void close() {
        // native libraries stay loaded for the lifetime of the JVM
    }

    /**
     * The libvips still loader for {@code file}, or {@code null} when none
     * applies (so the file routes to the FFmpeg AV path). Like
     * {@link VipsStills#findLoader} but distrusts the permissive ImageMagick
     * catch-all: a magick match is only honoured for a
     * {@link #MAGICK_STILL_EXTENSIONS known still extension}, since magick's
     * TGA fallback will otherwise claim arbitrary binaries (AV containers,
     * non-media junk) and shadow FFmpeg with a still decode that can only fail.
     */
    private String stillLoader(Path file) {
        String loader = stills.findLoader(file);
        if (loader != null && isMagickLoader(loader)
                && !MAGICK_STILL_EXTENSIONS.contains(extension(file))) {
            return null;
        }
        return loader;
    }

    private static boolean isMagickLoader(String loader) {
        return loader.toLowerCase(Locale.ROOT).contains("magick");
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
