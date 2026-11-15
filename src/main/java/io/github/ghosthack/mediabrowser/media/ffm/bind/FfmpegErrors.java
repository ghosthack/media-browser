package io.github.ghosthack.mediabrowser.media.ffm.bind;

import io.github.ghosthack.mediabrowser.media.ContentSniffer;
import io.github.ghosthack.mediabrowser.media.CorruptMediaException;
import io.github.ghosthack.mediabrowser.media.MediaException;

import java.nio.file.Path;

/**
 * Turns an FFmpeg error code into the matching {@link MediaException}. The one
 * code FFmpeg uses for "these bytes are not valid", {@code AVERROR_INVALIDDATA},
 * becomes a {@link CorruptMediaException}; every other code (I/O, EOF,
 * ENOMEM, decoder/demuxer not found, …) stays a plain failure.
 */
public final class FfmpegErrors {

    /**
     * {@code AVERROR_INVALIDDATA} = {@code FFERRTAG('I','N','D','A')}: a tag,
     * not an errno, so it is the same on every platform. ffmpeg-ffm does not
     * export it as a constant.
     */
    public static final int AVERROR_INVALIDDATA =
            -('I' | ('N' << 8) | ('D' << 16) | ('A' << 24));

    private FfmpegErrors() {}

    /** {@code message} as a failure, corrupt when {@code err} is INVALIDDATA. */
    public static MediaException failure(String message, int err) {
        return err == AVERROR_INVALIDDATA
                ? new CorruptMediaException(message)
                : new MediaException(message);
    }

    /**
     * {@code avformat_open_input}'s failure. There INVALIDDATA is ambiguous:
     * "a demuxer recognised the bytes and rejected them" (a truncated MP4 with
     * no {@code moov}) and "no demuxer recognised them" (a misnamed or
     * unsupported file) share the code. It counts as corrupt only when the
     * file carries a media signature the app itself recognises
     * ({@link ContentSniffer}) — the bytes say what they are, and FFmpeg says
     * they are invalid.
     */
    public static MediaException openFailure(String message, int err, Path file) {
        if (err == AVERROR_INVALIDDATA && hasMediaSignature(file)) {
            return new CorruptMediaException(message);
        }
        return new MediaException(message);
    }

    private static boolean hasMediaSignature(Path file) {
        try {
            return ContentSniffer.canonicalExtension(file).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
