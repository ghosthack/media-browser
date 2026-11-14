package io.github.ghosthack.mediabrowser.gl;

import io.github.ghosthack.mediabrowser.VideoPresentation.GlReadback;
import io.github.ghosthack.mediabrowser.media.MediaException;
import io.github.ghosthack.panama.media.opengl.CGLContext;
import io.github.ghosthack.panama.media.opengl.GL;
import io.github.ghosthack.panama.media.opengl.GLContext;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static io.github.ghosthack.panama.media.opengl.GL.*;

/**
 * The mujgl twin of {@link GlVideoRenderer} (macOS) and
 * {@link GlfwVideoRenderer} (Windows/Linux): the same texture upload, rotated
 * quad, FBO and {@code glReadPixels} readback, and on macOS the same
 * VideoToolbox NV12 IOSurface shader — but over the FFM {@code mujgl}
 * binding instead of LWJGL, so it needs no LWJGL classes or natives. Only the
 * context differs off macOS: WGL's hidden window on Windows and an EGL
 * pbuffer on Linux instead of a GLFW hidden window.
 *
 * <p>Readback ({@link GlReadback}) is synchronous, as in the LWJGL renderers;
 * through a pixel buffer object mapped at once (same frame, no delay); or
 * through two PBOs, each frame read into one while the other — the previous
 * frame, read a frame ago — is mapped and copied out, so the CPU need not
 * wait for the GPU. Previous-frame readback presents every frame one frame
 * late; the first is read at once so playback never starts blank.</p>
 *
 * <p>Confined to one thread from constructor to {@link #close}, which must not
 * have another OpenGL context current — as for the LWJGL renderers.</p>
 */
public final class MujglVideoRenderer implements AutoCloseable {

    private final int width;
    private final int height;
    /** User rotation in 90° clockwise quarter-turns, normalized to 0..3. */
    private final int quarterTurns;
    /** Output (post-rotation) dimensions: width/height swapped for odd turns. */
    private final int outWidth;
    private final int outHeight;
    private final GlReadback readback;
    /** Pack buffers: the first only for same-frame, both alternated for previous-frame. */
    private final int[] packBuffers = {-1, -1};
    private long framesRead;
    private GLContext context;
    private GL gl;
    private int sourceTexture = -1;
    private int targetTexture = -1;
    private int framebuffer = -1;
    private boolean closed;

    // Zero-copy NV12/IOSurface path (macOS only): lazy, as in GlVideoRenderer.
    private int nv12Program = -1;
    private int nv12TexY = -1;
    private int nv12TexC = -1;
    private int nv12ConvUniform = -1;
    private int nv12OffsetUniform = -1;
    private boolean nv12Failed;

    public MujglVideoRenderer(int width, int height) {
        this(width, height, 0);
    }

    public MujglVideoRenderer(int width, int height, int quarterTurns) {
        this(width, height, quarterTurns, GlReadback.SYNC);
    }

    public MujglVideoRenderer(int width, int height, int quarterTurns, GlReadback readback) {
        this.readback = readback == null ? GlReadback.SYNC : readback;
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

    /** The context API this renderer runs on: {@code "CGL"}, {@code "WGL"} or {@code "EGL"}. */
    public String api() {
        return context.api();
    }

    /** The function table, for tests that poke the current context. */
    GL gl() {
        return gl;
    }

    /**
     * Renders one tightly packed BGRA frame and reads the (post-rotation)
     * result back into {@code dst}; see {@link GlVideoRenderer#render}.
     *
     * @throws io.github.ghosthack.mediabrowser.media.MediaException if OpenGL
     *         reports an error for the frame, so a stale readback is never
     *         presented as valid
     */
    public void render(MemorySegment bgraFrame, ByteBuffer dst) {
        gl.glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, bgraFrame);

        // Texture row 0 (top of the video) goes to the bottom of the
        // framebuffer, so the bottom-up readback comes out top-down; the
        // texture coordinates are cycled by quarterTurns to bake in the rotation.
        gl.glBegin(GL_QUADS);
        for (int i = 0; i < 4; i++) {
            float[] tc = TEXCOORDS[(i - quarterTurns + 4) % 4];
            gl.glTexCoord2f(tc[0], tc[1]);
            gl.glVertex2f(VERTICES[i][0], VERTICES[i][1]);
        }
        gl.glEnd();

        readBack(dst);
    }

    /**
     * Reads the composed frame into {@code dst} — synchronously, through a
     * pack buffer, or the previous frame from the other pack buffer — then
     * polls {@code glGetError} once, as the LWJGL renderers do.
     */
    private void readBack(ByteBuffer dst) {
        if (readback == GlReadback.SYNC) {
            gl.glReadPixels(0, 0, outWidth, outHeight, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, dst);
            GlFrameCheck.afterReadback(gl::glGetError, "MujglVideoRenderer");
            return;
        }
        long bytes = (long) outWidth * outHeight * 4;
        boolean previous = readback == GlReadback.PBO_PREVIOUS_FRAME;
        int current = packBuffers[previous ? (int) (framesRead & 1) : 0];
        gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, current);
        gl.glReadPixels(0, 0, outWidth, outHeight, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,
                MemorySegment.NULL); // offset 0 into the pack buffer: returns without waiting
        int read = !previous || framesRead == 0 ? current : packBuffers[(int) ((framesRead + 1) & 1)];
        gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, read);
        MemorySegment mapped = gl.glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY, bytes);
        MemorySegment.copy(mapped, 0, MemorySegment.ofBuffer(dst), 0, bytes);
        boolean intact = gl.glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        framesRead++;
        GlFrameCheck.afterReadback(gl::glGetError, "MujglVideoRenderer");
        if (!intact) {
            throw new MediaException("MujglVideoRenderer: PBO contents lost while mapped; "
                    + "frame discarded");
        }
    }

    /** How frames are read back from the GPU. */
    public GlReadback readback() {
        return readback;
    }

    /**
     * Zero-copy render of a VideoToolbox NV12 frame on macOS; see
     * {@link GlVideoRenderer#renderSurface} — same contract, same shader.
     * Always {@code false} off CGL.
     */
    public boolean renderSurface(long cvPixelBuffer, int srcWidth, int srcHeight,
                                 int extraQuarterTurns, boolean bt709, boolean fullRange,
                                 ByteBuffer dst) {
        if (!(context instanceof CGLContext cgl) || !MacVideoSurfaces.AVAILABLE
                || nv12Failed || cvPixelBuffer == 0) {
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

        gl.glActiveTexture(GL_TEXTURE0);
        gl.glBindTexture(GL_TEXTURE_RECTANGLE, nv12TexY);
        int err = MacVideoSurfaces.texImageIoSurface2D(cgl.handle(), GL_TEXTURE_RECTANGLE,
                GL_LUMINANCE, srcWidth, srcHeight, GL_LUMINANCE, GL_UNSIGNED_BYTE, surface, 0);
        if (err == 0) {
            gl.glActiveTexture(GL_TEXTURE1);
            gl.glBindTexture(GL_TEXTURE_RECTANGLE, nv12TexC);
            err = MacVideoSurfaces.texImageIoSurface2D(cgl.handle(), GL_TEXTURE_RECTANGLE,
                    GL_LUMINANCE_ALPHA, (srcWidth + 1) / 2, (srcHeight + 1) / 2,
                    GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, surface, 1);
        }
        if (err != 0) {
            System.err.println("[MujglVideoRenderer] CGLTexImageIOSurface2D failed ("
                    + err + "); zero-copy disabled for this renderer");
            nv12Failed = true;
            restoreFixedFunctionState();
            // As in GlVideoRenderer: the bind reports through its return code
            // only, so no glGetError drain here.
            return false;
        }

        gl.glUseProgram(nv12Program);
        boolean full = fullRange || pixelFormat == MacVideoSurfaces.PIXEL_FORMAT_NV12_FULL;
        gl.glUniformMatrix3fv(nv12ConvUniform, false, GlVideoRenderer.yuvToRgbColumnMajor(bt709, full));
        gl.glUniform3f(nv12OffsetUniform, full ? 0f : 16f / 255f, 0.5f, 0.5f);

        int turns = ((quarterTurns + extraQuarterTurns) % 4 + 4) % 4;
        gl.glBegin(GL_QUADS);
        for (int i = 0; i < 4; i++) {
            float[] tc = TEXCOORDS[(i - turns + 4) % 4];
            gl.glTexCoord2f(tc[0] * srcWidth, tc[1] * srcHeight); // rectangle: pixel coords
            gl.glVertex2f(VERTICES[i][0], VERTICES[i][1]);
        }
        gl.glEnd();

        restoreFixedFunctionState();
        readBack(dst);
        return true;
    }

    private void restoreFixedFunctionState() {
        gl.glUseProgram(0);
        gl.glActiveTexture(GL_TEXTURE0);
        gl.glBindTexture(GL_TEXTURE_2D, sourceTexture);
    }

    private boolean ensureNv12Pipeline() {
        if (nv12Program >= 0) {
            return true;
        }
        int shader = gl.glCreateShader(GL_FRAGMENT_SHADER);
        gl.glShaderSource(shader, GlVideoRenderer.NV12_FRAGMENT_SHADER);
        gl.glCompileShader(shader);
        if (gl.glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            System.err.println("[MujglVideoRenderer] NV12 shader compile failed: "
                    + gl.glGetShaderInfoLog(shader));
            gl.glDeleteShader(shader);
            nv12Failed = true;
            return false;
        }
        int program = gl.glCreateProgram();
        gl.glAttachShader(program, shader);
        gl.glLinkProgram(program);
        gl.glDeleteShader(shader);
        if (gl.glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            System.err.println("[MujglVideoRenderer] NV12 program link failed: "
                    + gl.glGetProgramInfoLog(program));
            gl.glDeleteProgram(program);
            nv12Failed = true;
            return false;
        }
        nv12Program = program;
        nv12ConvUniform = gl.glGetUniformLocation(program, "conv");
        nv12OffsetUniform = gl.glGetUniformLocation(program, "yuvOffset");
        gl.glUseProgram(program);
        gl.glUniform1i(gl.glGetUniformLocation(program, "texY"), 0);
        gl.glUniform1i(gl.glGetUniformLocation(program, "texC"), 1);
        gl.glUseProgram(0);

        nv12TexY = gl.glGenTextures();
        nv12TexC = gl.glGenTextures();
        for (int tex : new int[] {nv12TexY, nv12TexC}) {
            gl.glBindTexture(GL_TEXTURE_RECTANGLE, tex);
            gl.glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        return true;
    }

    /** As in {@link GlVideoRenderer}: vertex {@code i} samples {@code TEXCOORDS[(i - turns) mod 4]}. */
    private static final float[][] VERTICES = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
    private static final float[][] TEXCOORDS = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    private void createContext() {
        context = GLContext.create();
        gl = context.gl();
        if (!gl.hasFramebuffers()) {
            throw new IllegalStateException(context.api() + " context lacks framebuffer objects (GL "
                    + gl.glGetString(GL_VERSION) + ")");
        }
        if (!(context instanceof CGLContext)) {
            // The GLFW renderer's GL 3.0 requirement: core FBOs plus the
            // compatibility fixed-function pipeline.
            String version = gl.glGetString(GL_VERSION);
            if (majorVersion(version) < 3) {
                throw new IllegalStateException("OpenGL 3.0+ required for framebuffer objects (got "
                        + version + ")");
            }
        } else if (!String.valueOf(gl.glGetString(GL_EXTENSIONS)).contains("GL_EXT_framebuffer_object")) {
            throw new IllegalStateException("OpenGL context lacks EXT_framebuffer_object");
        }
    }

    /** The leading major version of a {@code GL_VERSION} string, 0 when unparseable. */
    static int majorVersion(String version) {
        if (version == null) return 0;
        int i = 0;
        while (i < version.length() && Character.isDigit(version.charAt(i))) i++;
        return i == 0 ? 0 : Integer.parseInt(version.substring(0, i));
    }

    private void createPipeline() {
        sourceTexture = gl.glGenTextures();
        gl.glBindTexture(GL_TEXTURE_2D, sourceTexture);
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, MemorySegment.NULL);

        targetTexture = gl.glGenTextures();
        gl.glBindTexture(GL_TEXTURE_2D, targetTexture);
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, outWidth, outHeight, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, MemorySegment.NULL);

        framebuffer = gl.glGenFramebuffers();
        gl.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        gl.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, targetTexture, 0);
        int status = gl.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
        }

        if (readback != GlReadback.SYNC) {
            int count = readback == GlReadback.PBO_PREVIOUS_FRAME ? 2 : 1;
            for (int i = 0; i < count; i++) {
                packBuffers[i] = gl.glGenBuffers();
                gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffers[i]);
                gl.glBufferData(GL_PIXEL_PACK_BUFFER, (long) outWidth * outHeight * 4,
                        MemorySegment.NULL, GL_STREAM_READ);
            }
            gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        }

        // An offscreen context has no usable default drawable: set the viewport.
        gl.glViewport(0, 0, outWidth, outHeight);
        gl.glBindTexture(GL_TEXTURE_2D, sourceTexture);
        gl.glEnable(GL_TEXTURE_2D);
        gl.glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

        int error = gl.glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException("OpenGL setup failed: 0x"
                    + Integer.toHexString(error));
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (context != null) {
            if (gl != null) {
                if (framebuffer != -1) gl.glDeleteFramebuffers(framebuffer);
                if (targetTexture != -1) gl.glDeleteTextures(targetTexture);
                if (sourceTexture != -1) gl.glDeleteTextures(sourceTexture);
                if (nv12TexY != -1) gl.glDeleteTextures(nv12TexY);
                if (nv12TexC != -1) gl.glDeleteTextures(nv12TexC);
                if (nv12Program >= 0) gl.glDeleteProgram(nv12Program);
                for (int buffer : packBuffers) {
                    if (buffer != -1) gl.glDeleteBuffers(buffer);
                }
            }
            context.close();
            context = null;
        }
    }
}
