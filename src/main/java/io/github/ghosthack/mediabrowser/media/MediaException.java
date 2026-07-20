package io.github.ghosthack.mediabrowser.media;

/** Failure raised by the native probing/decoding facade. */
public class MediaException extends RuntimeException {

    public MediaException(String message) {
        super(message);
    }

    public MediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
