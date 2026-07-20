package io.github.ghosthack.mediabrowser.gl;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_ANY_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;

/**
 * Cross-platform (Windows/Linux) offscreen OpenGL renderer for video frames,
 * the GLFW-based sibling of {@link io.github.ghosthack.mediabrowser.gl.GlVideoRenderer}:
 * each BGRA frame is uploaded as a texture, drawn as a screen quad into a
 * framebuffer object of the same size, and read back as BGRA bytes. The GL
 * rendering (FBO, texture upload, draw, {@code glReadPixels} readback) is a
 * faithful replica of {@code gl.GlVideoRenderer}; only the context-creation
 * mechanism differs — this class uses a <b>hidden GLFW window</b> instead of an
 * Apple CGL context, so it works off macOS where CGL cannot be constructed.
 *
 * <p>The whole object — from constructor to {@link #close} — is confined to one
 * thread (the playback thread), which must not have another OpenGL context
 * current. On Windows/Linux GLFW does not require the main thread; on macOS GLFW
 * <em>would</em> require the AppKit main thread, which is exactly why macOS keeps
 * the CGL {@code GlVideoRenderer} and must <b>never</b> use this class.</p>
 *
 * <p><b>Process-global GLFW lifecycle.</b> GLFW itself (its {@code glfwInit} /
 * {@code glfwTerminate} and window creation) is a process-global, main-thread-
 * affine library with no internal locking. Each renderer, though, is built and
 * closed on a fresh per-video playback thread, and two playback sessions can
 * briefly overlap (a video switch whose {@code player.close()} hits its join
 * timeout, repeat/autoplay churn, or fast held-arrow browsing). Initialising or
 * terminating GLFW per-instance from those threads then races — two threads
 * register the same Win32 helper window class, and one session's
 * {@code glfwTerminate()} frees the other's live window/context — corrupting the
 * process heap and crashing ({@code EXCEPTION_ACCESS_VIOLATION} /
 * {@code STATUS_HEAP_CORRUPTION}). So GLFW is initialised exactly <b>once</b> for
 * the whole process (never terminated while the app runs) and every window
 * create/destroy is serialized on {@link #GLFW_LOCK}; only the per-instance
 * hidden window and its GL objects are owned and torn down by each renderer.</p>
 *
 * <p>Defensive by construction: every native step ({@code glfwInit},
 * {@code glfwCreateWindow}, GL 3.0/FBO availability, framebuffer completeness,
 * the trailing {@code glGetError}) is checked and throws a normal Java exception
 * on failure, so the caller's try/catch can fall back to the pure-JavaFX
 * presenter rather than proceeding into a half-initialized context.</p>
 */
public final class GlfwVideoRenderer implements AutoCloseable {

    private final int width;
    private final int height;
    /** User rotation in 90° clockwise quarter-turns, normalized to 0..3. */
    private final int quarterTurns;
    /** Output (post-rotation) dimensions: width/height swapped for odd turns. */
    private final int outWidth;
    private final int outHeight;

    /**
     * Serializes the process-global GLFW operations (one-time init, and every
     * window create/destroy + context-current change) across all renderers, so
     * two overlapping playback threads never race on GLFW's unlocked global
     * state. The per-thread GL rendering ({@code render}) does not touch GLFW
     * globals and stays lock-free.
     */
    private static final Object GLFW_LOCK = new Object();
    /** Whether {@code glfwInit} has run for this process (guarded by {@link #GLFW_LOCK}). */
    private static boolean glfwInitialized;
    /**
     * One shared printing error callback for the process: set once and never
     * freed, so it outlives every renderer (GLFW keeps an error callback past
     * any teardown). Replaces the old per-instance callback whose freeing race
     * the previous {@code close()} had to defend against.
     */
    private static GLFWErrorCallback sharedErrorCallback;

    private long window;
    private int sourceTexture = -1;
    private int targetTexture = -1;
    private int framebuffer = -1;
    private boolean closed;

    public GlfwVideoRenderer(int width, int height) {
        this(width, height, 0);
    }

    public GlfwVideoRenderer(int width, int height, int quarterTurns) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.quarterTurns = ((quarterTurns % 4) + 4) % 4;
        boolean swap = (this.quarterTurns & 1) == 1;
        this.outWidth = swap ? this.height : this.width;
        this.outHeight = swap ? this.width : this.height;
        boolean ok = false;
        try {
            createContext();
            createPipeline();
            ok = true;
        } finally {
            if (!ok) close();
        }
    }

    /** Output width after rotation (height for odd quarter-turns). */
    public int outWidth() {
        return outWidth;
    }

    /** Output height after rotation (width for odd quarter-turns). */
    public int outHeight() {
        return outHeight;
    }

    /**
     * Renders one tightly packed BGRA frame ({@code width × height}) and reads
     * the (post-rotation) result back into {@code dst} (capacity at least
     * outWidth*outHeight*4 — the same total bytes — position 0, top-down BGRA
     * rows). Mirrors {@code gl.GlVideoRenderer#render(MemorySegment, ByteBuffer)}.
     */
    public void render(MemorySegment bgraFrame, ByteBuffer dst) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, bgraFrame.asByteBuffer());

        // The quad maps texture row 0 (top of the video) to the bottom of the
        // framebuffer, so the bottom-up glReadPixels rows come out top-down; the
        // texture coordinates are cycled by quarterTurns to bake in the rotation.
        glBegin(GL_QUADS);
        for (int i = 0; i < 4; i++) {
            float[] tc = TEXCOORDS[(i - quarterTurns + 4) % 4];
            glTexCoord2f(tc[0], tc[1]);
            glVertex2f(VERTICES[i][0], VERTICES[i][1]);
        }
        glEnd();

        glReadPixels(0, 0, outWidth, outHeight, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, dst);
    }

    /**
     * Quad vertices (NDC) and their identity texture coordinates, in lockstep:
     * vertex {@code i} samples {@code TEXCOORDS[(i - quarterTurns) mod 4]}, so the
     * texture is rotated clockwise around the same quad. The vertical flip stays
     * fixed (it cancels the texture's top origin against the bottom-up readback).
     */
    private static final float[][] VERTICES = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
    private static final float[][] TEXCOORDS = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    private void createContext() {
        // Window creation, the one-time init and the context-current change all
        // mutate GLFW global state, so they run under GLFW_LOCK (see the class
        // note): two overlapping playback threads must not register the helper
        // window class or walk GLFW's window list at the same time.
        synchronized (GLFW_LOCK) {
            ensureGlfwInitialized();
            glfwDefaultWindowHints();
            // Hidden window — we only need its OpenGL context, never a visible frame.
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            // Stay on the driver-default (compatibility) profile so the replicated
            // fixed-function pipeline (glBegin/glEnd, glTexEnvi, GL_TEXTURE_2D) works
            // while still exposing core FBOs (GL 3.0+).
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_ANY_PROFILE);

            window = glfwCreateWindow(width, height, "mb-offscreen", 0L, 0L);
            if (window == 0L) {
                throw new IllegalStateException("GLFW window/context creation failed");
            }
            glfwMakeContextCurrent(window);
        }
        // Capabilities live in thread-local GL state (not GLFW globals), so this
        // is read after the lock, on this renderer's confined thread.
        GLCapabilities caps = GL.createCapabilities();
        if (!caps.OpenGL30) {
            throw new IllegalStateException("OpenGL 3.0+ required for framebuffer objects (got "
                    + glGetString(GL_VERSION) + ")");
        }
    }

    /**
     * Initialises GLFW once for the whole process; subsequent calls are no-ops.
     * The caller must hold {@link #GLFW_LOCK}. GLFW is deliberately never
     * {@code glfwTerminate()}'d while the app runs — terminating it from a
     * per-video playback thread is exactly what corrupted the heap when two
     * sessions overlapped.
     */
    private static void ensureGlfwInitialized() {
        if (glfwInitialized) {
            return;
        }
        // A printing error callback so any GLFW failure surfaces on stderr; set
        // once for the process and never freed.
        sharedErrorCallback = GLFWErrorCallback.createPrint(System.err);
        sharedErrorCallback.set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }
        glfwInitialized = true;
    }

    private void createPipeline() {
        sourceTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, sourceTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (ByteBuffer) null);

        targetTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, targetTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, outWidth, outHeight, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (ByteBuffer) null);

        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, targetTexture, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }

        // A windowless/hidden context has no usable default drawable: the
        // viewport starts out 0×0, so set it explicitly.
        glViewport(0, 0, outWidth, outHeight);
        glBindTexture(GL_TEXTURE_2D, sourceTexture);
        glEnable(GL_TEXTURE_2D);
        glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException("OpenGL setup failed: 0x"
                    + Integer.toHexString(error));
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (window != 0L) {
            try {
                // GL object deletes act on this thread's current context, not
                // GLFW globals, so they run before the lock.
                if (framebuffer != -1) glDeleteFramebuffers(framebuffer);
                if (targetTexture != -1) glDeleteTextures(targetTexture);
                if (sourceTexture != -1) glDeleteTextures(sourceTexture);
                GL.setCapabilities(null);
                // Detaching the context and destroying the window walk GLFW's
                // global window list, so serialize them with every other
                // renderer's create/destroy. GLFW is NEVER terminated here: the
                // process-global library and its shared error callback outlive
                // every renderer; only this instance's hidden window is freed.
                synchronized (GLFW_LOCK) {
                    glfwMakeContextCurrent(0L);
                    glfwDestroyWindow(window);
                }
            } catch (Throwable ignore) {
                // Best-effort teardown; never let close() throw.
            }
            window = 0L;
        }
    }
}
