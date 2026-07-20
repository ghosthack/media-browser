package io.github.ghosthack.mediabrowser.gl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glCheckFramebufferStatusEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glDeleteFramebuffersEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glFramebufferTexture2DEXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glGenFramebuffersEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;

/**
 * Offscreen OpenGL renderer (LWJGL) for video frames: each BGRA frame is
 * uploaded as a texture, drawn as a screen quad into a framebuffer object of
 * the same size, and read back as BGRA bytes.
 *
 * <p>Uses a windowless CGL context (macOS), so it needs neither GLFW nor the
 * AppKit main thread and can coexist with JavaFX. The whole object — from
 * constructor to {@link #close} — is confined to one thread, which must not
 * have another OpenGL context current.</p>
 *
 * <p>An optional user rotation ({@code quarterTurns}, 90° clockwise steps) is
 * baked in on the GPU at no extra per-frame cost: the output framebuffer swaps
 * width/height for odd turns and the textured quad's texture coordinates are
 * rotated, so the readback already carries the upright (rotated) frame — the
 * caller sizes its {@code dst}/PixelBuffer to {@link #outWidth()} ×
 * {@link #outHeight()}.</p>
 */
public final class GlVideoRenderer implements AutoCloseable {

    private final int width;
    private final int height;
    /** User rotation in 90° clockwise quarter-turns, normalized to 0..3. */
    private final int quarterTurns;
    /** Output (post-rotation) dimensions: width/height swapped for odd turns. */
    private final int outWidth;
    private final int outHeight;
    private long context;
    private int sourceTexture = -1;
    private int targetTexture = -1;
    private int framebuffer = -1;
    private boolean closed;

    public GlVideoRenderer(int width, int height) {
        this(width, height, 0);
    }

    public GlVideoRenderer(int width, int height, int quarterTurns) {
        this.width = width;
        this.height = height;
        this.quarterTurns = ((quarterTurns % 4) + 4) % 4;
        boolean swap = (this.quarterTurns & 1) == 1;
        this.outWidth = swap ? height : width;
        this.outHeight = swap ? width : height;
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
     * rows).
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attribs = stack.ints(
                    CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_Legacy,
                    CGL.kCGLPFAColorSize, 24,
                    CGL.kCGLPFAAlphaSize, 8,
                    0);
            PointerBuffer pf = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            checkCgl(CGL.CGLChoosePixelFormat(attribs, pf, count), "CGLChoosePixelFormat");
            long pixelFormat = pf.get(0);
            try {
                PointerBuffer ctx = stack.mallocPointer(1);
                checkCgl(CGL.CGLCreateContext(pixelFormat, 0, ctx), "CGLCreateContext");
                context = ctx.get(0);
            } finally {
                CGL.CGLDestroyPixelFormat(pixelFormat);
            }
            checkCgl(CGL.CGLSetCurrentContext(context), "CGLSetCurrentContext");
        }
        if (!GL.createCapabilities().GL_EXT_framebuffer_object) {
            throw new IllegalStateException("OpenGL context lacks EXT_framebuffer_object");
        }
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

        framebuffer = glGenFramebuffersEXT();
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, framebuffer);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT,
                GL_TEXTURE_2D, targetTexture, 0);
        int status = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
            throw new IllegalStateException("framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }

        // A windowless context has no drawable: the viewport starts out 0×0.
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
        if (context != 0) {
            if (framebuffer != -1) glDeleteFramebuffersEXT(framebuffer);
            if (targetTexture != -1) glDeleteTextures(targetTexture);
            if (sourceTexture != -1) glDeleteTextures(sourceTexture);
            GL.setCapabilities(null);
            CGL.CGLSetCurrentContext(0);
            CGL.CGLDestroyContext(context);
            context = 0;
        }
    }

    private static void checkCgl(int error, String what) {
        if (error != 0) {
            throw new IllegalStateException(what + " failed: " + CGL.CGLErrorString(error)
                    + " (" + error + ")");
        }
    }
}
