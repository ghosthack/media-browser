package io.github.ghosthack.mediabrowser.web;

/**
 * A request the phone page's server refuses, carrying the HTTP status, a
 * stable machine-readable {@code code} the page switches on, and the message
 * it shows. Rendered as {@code {"ok":false,"code":…,"error":…}}.
 */
final class WebException extends Exception {

    final int status;
    final String code;
    /** A move's undo id that comes with this refusal (a move still running past its wait), or null. */
    String id;

    WebException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    WebException withId(String id) {
        this.id = id;
        return this;
    }

    static WebException badRequest(String message) {
        return new WebException(400, "bad-request", message);
    }

    static WebException outside(String message) {
        return new WebException(403, "outside-root", message);
    }

    static WebException notFound(String message) {
        return new WebException(404, "not-found", message);
    }
}
