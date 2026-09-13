package io.github.ghosthack.panama.media.fileassoc;

/**
 * Thrown when a Windows registry call ({@link Advapi32}) returns a failing
 * {@code LSTATUS}. Carries the raw status code so callers can recognize
 * specific failures (e.g. {@link Advapi32#ERROR_ACCESS_DENIED}) without
 * parsing the message.
 */
public class RegistryException extends RuntimeException {

    /** The failing {@code LSTATUS}, or {@code -1} when this wraps a non-status failure. */
    private final int status;

    public RegistryException(String message, int status) {
        super(message + " (status " + status + ")");
        this.status = status;
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    public int status() {
        return status;
    }
}
