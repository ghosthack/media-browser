package io.github.ghosthack.mediabrowser.media;

import java.util.Optional;

/**
 * Reads a failure the way the views present it. Failures reach the UI wrapped
 * — {@code CompletionException}, "cannot read x: …" re-throws — so the
 * corrupt-data marker ({@link CorruptData}) is looked for along the whole
 * cause chain rather than only on the outermost exception.
 */
public final class MediaFailures {

    /** Prefix the views put before a corrupt file's decoder message. */
    public static final String CORRUPT_PREFIX = "Corrupt: ";

    private MediaFailures() {}

    /** The first {@link CorruptData} failure in {@code error}'s cause chain. */
    public static Optional<CorruptData> corruption(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 64; t = t.getCause(), depth++) {
            if (t instanceof CorruptData corrupt) return Optional.of(corrupt);
        }
        return Optional.empty();
    }

    public static boolean isCorrupt(Throwable error) {
        return corruption(error).isPresent();
    }

    /**
     * The one-line message a view shows for {@code error}: "Corrupt: " plus the
     * decoder's own message for corrupt data, otherwise the innermost cause's
     * message (what the views always showed).
     */
    public static String message(Throwable error) {
        Optional<CorruptData> corrupt = corruption(error);
        if (corrupt.isPresent()) return CORRUPT_PREFIX + text(corrupt.get().getMessage(), corrupt.get());
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        return text(t.getMessage(), t);
    }

    private static String text(String message, Object failure) {
        return message == null ? failure.toString() : message;
    }
}
