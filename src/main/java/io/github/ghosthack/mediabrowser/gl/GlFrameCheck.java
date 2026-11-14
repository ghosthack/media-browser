package io.github.ghosthack.mediabrowser.gl;

import io.github.ghosthack.mediabrowser.media.MediaException;

import java.util.function.IntSupplier;

/**
 * Per-frame OpenGL error check shared by {@link GlVideoRenderer} and
 * {@link GlfwVideoRenderer}. Setup is checked once in each constructor, but a
 * context loss or driver reset (Windows TDR) mid-playback makes
 * {@code glReadPixels} fail silently: the slot keeps its old (or zero) pixels
 * and a stale/black frame would be presented as valid. So each renderer polls
 * {@code glGetError} exactly once per frame, after the readback (more often
 * would stall the pipeline), and a pending error ends playback loudly through
 * the player's {@code onError} instead of degrading the picture.
 */
final class GlFrameCheck {

    /** Cap on flags drained after the first (a lost context may never clear). */
    private static final int MAX_DRAINED = 8;

    private GlFrameCheck() {}

    /**
     * Throws a {@link MediaException} naming the GL error(s) if
     * {@code glGetError} reports any; otherwise returns after a single call.
     */
    static void afterReadback(IntSupplier glGetError, String renderer) {
        int error = glGetError.getAsInt();
        if (error == 0) {
            return;
        }
        StringBuilder names = new StringBuilder(name(error));
        for (int i = 0; i < MAX_DRAINED; i++) {
            int next = glGetError.getAsInt();
            if (next == 0) break;
            names.append(", ").append(name(next));
        }
        throw new MediaException(renderer + ": OpenGL error during frame render/readback ("
                + names + "); frame discarded — GPU context lost or driver reset?");
    }

    /** Symbolic name of a {@code glGetError} code, with its hex value. */
    static String name(int error) {
        String symbol = switch (error) {
            case 0x0500 -> "GL_INVALID_ENUM";
            case 0x0501 -> "GL_INVALID_VALUE";
            case 0x0502 -> "GL_INVALID_OPERATION";
            case 0x0503 -> "GL_STACK_OVERFLOW";
            case 0x0504 -> "GL_STACK_UNDERFLOW";
            case 0x0505 -> "GL_OUT_OF_MEMORY";
            case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            case 0x0507 -> "GL_CONTEXT_LOST";
            default -> "GL error";
        };
        return symbol + " 0x" + Integer.toHexString(error);
    }
}
