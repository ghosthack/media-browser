package io.github.ghosthack.mediabrowser.web;

/**
 * A request the phone page's server refuses, carrying the HTTP status, a
 * stable machine-readable {@code code} the page switches on, and the message
 * it shows. Rendered as {@code {"ok":false,"code":…,"error":…}}.
 */
final class WebException extends Exception {

    final int status;
    final String code;

    WebException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
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
