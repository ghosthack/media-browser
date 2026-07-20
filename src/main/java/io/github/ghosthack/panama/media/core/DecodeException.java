package io.github.ghosthack.panama.media.core;

/**
 * Thrown when a native media library returns a decode error.
 * <p>
 * Distinguishes native decode failures (corrupt or unsupported input, codec
 * error, native API returning an error code) from precondition violations
 * ({@link IllegalArgumentException}) and library availability failures
 * ({@link IllegalStateException}).
 * <p>
 * Consumers that need to differentiate decode failures from other runtime
 * errors can catch this type specifically:
 * <pre>{@code
 * try {
 *     DecodedImage<PixelFormat> img = LibHeif.decode(arena, data);
 * } catch (IllegalStateException e) {
 *     // library not installed
 * } catch (DecodeException e) {
 *     // bad input data or codec failure
 * }
 * }</pre>
 */
public class DecodeException extends RuntimeException {

    public DecodeException(String message) {
        super(message);
    }

    public DecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
