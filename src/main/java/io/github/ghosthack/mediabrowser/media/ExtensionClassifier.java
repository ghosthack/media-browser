package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies a file as media purely from its filename extension, with no
 * native probe and without reading a single byte. Backs
 * {@link DetectionMode#FILE_EXTENSION}.
 *
 * <p>The extension lists mirror the container whitelists used by the native
 * facades plus a broad set of still-image suffixes. A name is only treated as
 * having an extension when the dot is not the first character, so dotfiles
 * (e.g. {@code .bashrc}) are not classified — matching
 * {@link DirEntry#extension()}.</p>
 *
 * <p>This is deliberately backend-agnostic and content-blind: an
 * <em>animated</em> AVIF/HEIC wears a still-image extension, so it classifies
 * as {@link MediaKind#IMAGE} here. Whether such a file is actually playable is
 * a backend-specific question (FFmpeg always demuxes the {@code moov} track;
 * Windows Media Foundation / Apple only when the OS can; the pure stack never),
 * so that upgrade lives in {@code MediaService} where the facade is available,
 * not in this name-only classifier.</p>
 */
public final class ExtensionClassifier {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "bmp", "tif", "tiff", "webp",
            "heic", "heif", "avif", "jp2", "j2k", "jpf", "jls", "jxl", "ico", "tga",
            "ppm", "pgm", "pbm", "pnm", "pam", "pfm", "exr", "hdr", "dds", "psd",
            "svg", "raw",
            // Camera RAW: the full set LibRawStills.RAW_EXTENSIONS routes to
            // libraw-ffm. Keep the two in step — a name missing here is a file
            // FILE_EXTENSION mode hides even though the backend opens it.
            "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2",
            "raf", "orf", "rw2", "pef", "srw", "erf", "kdc", "dcr", "mos",
            "mrw", "rwl", "iiq", "3fr", "fff", "mef", "x3f", "dng",
            "pcx", "xpm", "xbm", "xwd", "sgi", "ras", "qoi", "dpx",
            // Kodak Photo CD: a pyramid of 192x128..3072x2048 in one file.
            "pcd");

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "m4s", "mkv", "webm", "avi", "mov", "qt", "wmv", "asf",
            "flv", "f4v", "divx", "mpg", "mpeg", "m2v", "mpv",
            "ts", "m2ts", "mts", "ogv", "ogm", "3gp", "3g2", "vob", "mxf",
            "nut", "dv", "rm");

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "mp2", "m4a", "m4b", "m4r", "3ga", "aac", "flac", "ogg", "oga",
            "opus", "wav", "wma", "aiff", "aif", "ape", "mka", "ac3", "dts", "amr",
            "caf", "au", "ra", "mpc", "wv", "tta", "spx", "dsf");

    private ExtensionClassifier() {}

    /**
     * The media kind implied by the file's extension, or empty when the
     * extension is unknown (or the name has none).
     */
    public static Optional<MediaKind> classify(Path file) {
        String ext = extension(file);
        if (ext.isEmpty()) return Optional.empty();
        if (IMAGE_EXTENSIONS.contains(ext)) return Optional.of(MediaKind.IMAGE);
        if (VIDEO_EXTENSIONS.contains(ext)) return Optional.of(MediaKind.VIDEO);
        if (AUDIO_EXTENSIONS.contains(ext)) return Optional.of(MediaKind.AUDIO);
        return Optional.empty();
    }

    private static String extension(Path file) {
        Path name = file.getFileName();
        if (name == null) return "";
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
