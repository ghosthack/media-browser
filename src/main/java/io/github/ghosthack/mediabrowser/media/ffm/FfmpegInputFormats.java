package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.ContentSniffer;
import io.github.ghosthack.mediabrowser.media.ExtensionClassifier;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/** Keeps content-sniff promotion and FFmpeg's later demuxer choice aligned. */
final class FfmpegInputFormats {

    private FfmpegInputFormats() {}

    /**
     * Opens a media input. Correctly named media keeps FFmpeg's normal
     * extension-assisted probing. For an extensionless or unknown-suffix file,
     * the same strong signature used by content detection supplies an explicit
     * demuxer so FFmpeg cannot independently mis-probe those bytes.
     */
    static MemorySegment open(FfmpegBindings ff, Arena arena, Path file) {
        return ff.openInput(arena, file, demuxerHint(file));
    }

    static String demuxerHint(Path file) {
        if (ExtensionClassifier.classify(file).isPresent()) return null;
        return ContentSniffer.canonicalExtension(file)
                .map(FfmpegInputFormats::demuxerForExtension)
                .orElse(null);
    }

    private static String demuxerForExtension(String extension) {
        return switch (extension) {
            case "jpg" -> "mjpeg";
            case "png" -> "png_pipe";
            case "gif" -> "gif";
            case "tif" -> "tiff_pipe";
            case "jxl" -> "jpegxl_pipe";
            case "bmp" -> "bmp_pipe";
            case "webp" -> "webp_pipe";
            case "mkv", "webm" -> "matroska";
            case "ogg", "opus", "ogv" -> "ogg";
            case "flac" -> "flac";
            case "mp3" -> "mp3";
            case "avi" -> "avi";
            case "wav" -> "wav";
            case "avif", "heic", "mp4", "m4v", "m4a", "mov", "3gp" -> "mov";
            default -> null;
        };
    }
}
