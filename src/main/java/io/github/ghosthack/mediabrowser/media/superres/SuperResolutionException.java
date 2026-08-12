package io.github.ghosthack.mediabrowser.media.superres;

/** A user-actionable failure while preparing or running super resolution. */
public final class SuperResolutionException extends RuntimeException {

    public SuperResolutionException(String message) {
        super(message);
    }

    public SuperResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
