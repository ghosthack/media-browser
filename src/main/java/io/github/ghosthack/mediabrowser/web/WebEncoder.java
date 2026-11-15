package io.github.ghosthack.mediabrowser.web;

import io.github.ghosthack.mediabrowser.media.RasterFrame;

/**
 * Turns a decoded frame into the bytes the phone page receives. JPEG only
 * today ({@link JpegEncoder}); another format (AVIF, WebP) slots in behind
 * this without touching the server, which takes its media type from here.
 */
public interface WebEncoder {

    /** The {@code Content-Type} of what {@link #encode} returns. */
    String mediaType();

    /** A short token that changes whenever this encoder's output would (cache keys, ETags). */
    String id();

    /**
     * {@code frame} encoded at {@code quality} (1..100). Straight alpha is
     * composited over black, the mosaic's background.
     */
    byte[] encode(RasterFrame frame, int quality);
}
