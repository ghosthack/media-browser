package io.github.ghosthack.panama.media.opengl;

import io.github.ghosthack.panama.media.core.Platform;

/**
 * An offscreen OpenGL context made current on the creating thread, with its
 * function table. Nothing is ever drawn to a window: renderers draw into a
 * framebuffer object and read it back.
 *
 * <ul>
 *   <li>{@link CGLContext} — macOS, windowless.</li>
 *   <li>{@link WGLContext} — Windows; WGL needs a window's device context, so
 *       it owns a never-shown one.</li>
 *   <li>{@link EGLContext} — Linux; a 1×1 pbuffer, no window.</li>
 * </ul>
 *
 * <p>The context is confined to the creating thread from construction to
 * {@link #close}, which must run on that thread too.</p>
 */
public sealed interface GLContext extends AutoCloseable
        permits CGLContext, WGLContext, EGLContext {

    /** The function table, resolved while this context was current. */
    GL gl();

    /** The context API, for status labels: {@code "CGL"}, {@code "WGL"} or {@code "EGL"}. */
    String api();

    /** Releases the context (and any window/surface it owns); idempotent. */
    @Override
    void close();

    /**
     * Creates and makes current the host platform's context: CGL on macOS
     * (legacy 2.1 profile, {@code EXT_framebuffer_object} names), WGL on
     * Windows and EGL on Linux (driver-default compatibility profile, GL 3.0
     * core framebuffer names). Throws when no context can be created.
     */
    static GLContext create() {
        if (Platform.IS_MAC) return CGLContext.create();
        if (Platform.IS_WINDOWS) return WGLContext.create();
        if (Platform.IS_LINUX) return EGLContext.create();
        throw new UnsupportedOperationException("no OpenGL context API for "
                + System.getProperty("os.name"));
    }
}
