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
            "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "tif", "tiff", "webp",
            "heic", "heif", "avif", "jp2", "j2k", "jpf", "jxl", "ico", "tga",
            "ppm", "pgm", "pbm", "pnm", "pfm", "exr", "hdr", "dds", "psd",
            "svg", "raw", "cr2", "cr3", "nef", "arw", "dng", "orf", "rw2",
            "raf", "pcx", "xpm");

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "m4s", "mkv", "webm", "avi", "mov", "wmv", "flv",
            "mpg", "mpeg", "ts", "m2ts", "mts", "ogv", "3gp", "vob", "mxf",
            "nut", "rm");

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
            "aiff", "aif", "ape", "mka", "ac3", "dts", "amr");

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
