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
import static org.lwjgl.opengl.ARBTextureRectangle.GL_TEXTURE_RECTANGLE_ARB;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.*;

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

    // Zero-copy NV12/IOSurface path (macOS, VideoToolbox output): lazy —
    // nothing is created until the first renderSurface call.
    private int nv12Program = -1;
    private int nv12TexY = -1;
    private int nv12TexC = -1;
    private int nv12ConvUniform = -1;
    private int nv12OffsetUniform = -1;
    private boolean nv12Failed;

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
     * Zero-copy render of a VideoToolbox NV12 frame: binds the CVPixelBuffer's
     * IOSurface planes directly as rectangle textures in this CGL context (no
     * readback, no swscale, no upload), converts YUV→RGB in a fragment shader,
     * and reads the composed FBO back into {@code dst} exactly like
     * {@link #render}. {@code extraQuarterTurns} is the container rotation of
     * the coded frame (the surface is in coded orientation), composed with the
     * constructor's user rotation on the quad.
     *
     * <p>Returns {@code false} when this frame cannot go zero-copy — not
     * macOS, not an NV12 surface (e.g. 10-bit HDR P010), or the GL plumbing
     * failed — in which case the caller falls back to the CPU path. A pipeline
     * failure disables the path for the renderer's lifetime.</p>
     */
    public boolean renderSurface(long cvPixelBuffer, int srcWidth, int srcHeight,
                                 int extraQuarterTurns, boolean bt709, boolean fullRange,
                                 ByteBuffer dst) {
        if (!MacVideoSurfaces.AVAILABLE || nv12Failed || cvPixelBuffer == 0) {
            return false;
        }
        int pixelFormat = MacVideoSurfaces.pixelFormatType(cvPixelBuffer);
        if (pixelFormat != MacVideoSurfaces.PIXEL_FORMAT_NV12_VIDEO
                && pixelFormat != MacVideoSurfaces.PIXEL_FORMAT_NV12_FULL) {
            return false;
        }
        long surface = MacVideoSurfaces.ioSurface(cvPixelBuffer);
        if (surface == 0 || !ensureNv12Pipeline()) {
            return false;
        }

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_RECTANGLE_ARB, nv12TexY);
        int err = MacVideoSurfaces.texImageIoSurface2D(context, GL_TEXTURE_RECTANGLE_ARB,
                GL_LUMINANCE, srcWidth, srcHeight, GL_LUMINANCE, GL_UNSIGNED_BYTE, surface, 0);
        if (err == 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE_ARB, nv12TexC);
            err = MacVideoSurfaces.texImageIoSurface2D(context, GL_TEXTURE_RECTANGLE_ARB,
                    GL_LUMINANCE_ALPHA, (srcWidth + 1) / 2, (srcHeight + 1) / 2,
                    GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, surface, 1);
        }
        if (err != 0) {
            System.err.println("[GlVideoRenderer] CGLTexImageIOSurface2D failed ("
                    + err + "); zero-copy disabled for this renderer");
            nv12Failed = true;
            restoreFixedFunctionState();
            return false;
        }

        glUseProgram(nv12Program);
        boolean full = fullRange || pixelFormat == MacVideoSurfaces.PIXEL_FORMAT_NV12_FULL;
        glUniformMatrix3fv(nv12ConvUniform, false, yuvToRgbColumnMajor(bt709, full));
        glUniform3f(nv12OffsetUniform, full ? 0f : 16f / 255f, 0.5f, 0.5f);

        int turns = ((quarterTurns + extraQuarterTurns) % 4 + 4) % 4;
        glBegin(GL_QUADS);
        for (int i = 0; i < 4; i++) {
            float[] tc = TEXCOORDS[(i - turns + 4) % 4];
            glTexCoord2f(tc[0] * srcWidth, tc[1] * srcHeight); // rectangle: pixel coords
            glVertex2f(VERTICES[i][0], VERTICES[i][1]);
        }
        glEnd();

        restoreFixedFunctionState();
        glReadPixels(0, 0, outWidth, outHeight, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, dst);
        return true;
    }

    /** Back to the fixed-function BGRA pipeline {@link #render} expects. */
    private void restoreFixedFunctionState() {
        glUseProgram(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sourceTexture);
    }

    /** YUV→RGB conversion matrix, column-major for {@code glUniformMatrix3fv}. */
    private static float[] yuvToRgbColumnMajor(boolean bt709, boolean fullRange) {
        if (fullRange) {
            return bt709
                    ? new float[] {1f, 1f, 1f, 0f, -0.1873f, 1.8556f, 1.5748f, -0.4681f, 0f}
                    : new float[] {1f, 1f, 1f, 0f, -0.344f, 1.772f, 1.402f, -0.714f, 0f};
        }
        return bt709
                ? new float[] {1.164f, 1.164f, 1.164f, 0f, -0.213f, 2.112f, 1.793f, -0.533f, 0f}
                : new float[] {1.164f, 1.164f, 1.164f, 0f, -0.392f, 2.017f, 1.596f, -0.813f, 0f};
    }

    /** GLSL 1.20 fragment-only program (fixed-function vertex stage). */
    private static final String NV12_FRAGMENT_SHADER = """
            #version 120
            #extension GL_ARB_texture_rectangle : enable
            uniform sampler2DRect texY;
            uniform sampler2DRect texC;
            uniform mat3 conv;
            uniform vec3 yuvOffset;
            void main() {
                float y = texture2DRect(texY, gl_TexCoord[0].st).r;
                vec2 c = texture2DRect(texC, gl_TexCoord[0].st * 0.5).ra;
                vec3 rgb = conv * (vec3(y, c.x, c.y) - yuvOffset);
                gl_FragColor = vec4(rgb, 1.0);
            }
            """;

    private boolean ensureNv12Pipeline() {
        if (nv12Program >= 0) {
            return true;
        }
        int shader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(shader, NV12_FRAGMENT_SHADER);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            System.err.println("[GlVideoRenderer] NV12 shader compile failed: "
                    + glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            nv12Failed = true;
            return false;
        }
        int program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            System.err.println("[GlVideoRenderer] NV12 program link failed: "
                    + glGetProgramInfoLog(program));
            glDeleteProgram(program);
            nv12Failed = true;
            return false;
        }
        nv12Program = program;
        nv12ConvUniform = glGetUniformLocation(program, "conv");
        nv12OffsetUniform = glGetUniformLocation(program, "yuvOffset");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "texY"), 0);
        glUniform1i(glGetUniformLocation(program, "texC"), 1);
        glUseProgram(0);

        nv12TexY = glGenTextures();
        nv12TexC = glGenTextures();
        for (int tex : new int[] {nv12TexY, nv12TexC}) {
            glBindTexture(GL_TEXTURE_RECTANGLE_ARB, tex);
            glTexParameteri(GL_TEXTURE_RECTANGLE_ARB, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE_ARB, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE_ARB, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_RECTANGLE_ARB, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        return true;
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
            if (nv12TexY != -1) glDeleteTextures(nv12TexY);
            if (nv12TexC != -1) glDeleteTextures(nv12TexC);
            if (nv12Program >= 0) glDeleteProgram(nv12Program);
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
