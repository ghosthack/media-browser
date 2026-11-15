package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.mediabrowser.media.CorruptData;

import java.io.IOException;

/**
 * A container that would not open because its reader found the structure
 * damaged — a truncated ZIP, a 7z header CRC mismatch, a RAR header cut short.
 * Raised only by {@link ArchiveCorruption}, wrapping the reader's own
 * exception as the cause; the message is the reader's description.
 */
public final class CorruptArchiveException extends IOException implements CorruptData {

    public CorruptArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
