package io.github.ghosthack.mediabrowser.media;

/**
 * A decode failure the decoder attributes to the file's data (see
 * {@link CorruptData}). Still a {@link MediaException}: every caller that
 * handles a decode error handles this one the same way — it only lets the
 * views say "corrupt" instead of a generic failure.
 */
public class CorruptMediaException extends MediaException implements CorruptData {

    public CorruptMediaException(String message) {
        super(message);
    }

    public CorruptMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
