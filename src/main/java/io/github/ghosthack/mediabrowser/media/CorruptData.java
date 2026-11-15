package io.github.ghosthack.mediabrowser.media;

/**
 * Marks a failure as "the decoder itself reported this file's data invalid":
 * FFmpeg's {@code AVERROR_INVALIDDATA}, LibRaw's {@code LIBRAW_DATA_ERROR},
 * libjpeg-turbo's corrupt-data messages, an archive reader's header/CRC
 * complaint. Never inferred from a generic failure — an I/O error, an
 * unsupported codec or an out-of-memory is not corrupt.
 *
 * <p>Implemented by {@link CorruptMediaException} (decode paths) and by the
 * archive layer's {@code CorruptArchiveException} (an {@link java.io.IOException},
 * since mounting is filesystem work). {@link MediaFailures} finds either in a
 * cause chain. The marker only labels a failure; nothing substitutes another
 * decode for a corrupt file.</p>
 */
public interface CorruptData {

    /** The decoder's own description of what is wrong with the data. */
    String getMessage();
}
