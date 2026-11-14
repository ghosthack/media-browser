package io.github.ghosthack.panama.media.opengl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The OpenGL entry points an offscreen texture → framebuffer → readback
 * pipeline needs (fixed-function quads, a fragment shader, framebuffer and
 * pixel-buffer objects) — and nothing else — as FFM downcalls. Names and
 * argument order follow the C API (and LWJGL's static methods), so code reads
 * the same under either binding.
 *
 * <p>An instance is a function table for one context, resolved through that
 * context's {@link ProcLookup} once it is current (WGL may hand out
 * context-specific pointers, as LWJGL's {@code GL.createCapabilities} assumes
 * too). It is confined to the context's thread: the scratch memory used for
 * out-parameters is not shared. An entry point the driver does not export
 * resolves to {@code null} and throws {@link UnsupportedOperationException}
 * only when called, so a GL 2.1 context can still load the table.</p>
 */
public final class GL {

    public static final int GL_NO_ERROR = 0;
    public static final int GL_FALSE = 0;
    public static final int GL_QUADS = 0x0007;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_VENDOR = 0x1F00;
    public static final int GL_RENDERER = 0x1F01;
    public static final int GL_VERSION = 0x1F02;
    public static final int GL_EXTENSIONS = 0x1F03;
    public static final int GL_LUMINANCE = 0x1909;
    public static final int GL_LUMINANCE_ALPHA = 0x190A;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_REPLACE = 0x1E01;
    public static final int GL_TEXTURE_ENV_MODE = 0x2200;
    public static final int GL_TEXTURE_ENV = 0x2300;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    public static final int GL_TEXTURE_WRAP_T = 0x2803;
    public static final int GL_RGBA8 = 0x8058;
    public static final int GL_BGRA = 0x80E1;
    public static final int GL_CLAMP_TO_EDGE = 0x812F;
    public static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;
    public static final int GL_TEXTURE0 = 0x84C0;
    public static final int GL_TEXTURE1 = 0x84C1;
    public static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_INFO_LOG_LENGTH = 0x8B84;
    public static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    public static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    public static final int GL_FRAMEBUFFER = 0x8D40;
    public static final int GL_READ_ONLY = 0x88B8;
    public static final int GL_WRITE_ONLY = 0x88B9;
    public static final int GL_STREAM_DRAW = 0x88E0;
    public static final int GL_STREAM_READ = 0x88E1;
    public static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    public static final int GL_PIXEL_UNPACK_BUFFER = 0x88EC;

    private static final Linker LINKER = Linker.nativeLinker();

    /** Out-parameter scratch (ints, a float[9] matrix): one context thread only. */
    private final MemorySegment scratch = Arena.ofAuto().allocate(64, 8);

    private final MethodHandle getError;
    private final MethodHandle finish;
    private final MethodHandle genBuffers;
    private final MethodHandle deleteBuffers;
    private final MethodHandle bindBuffer;
    private final MethodHandle bufferData;
    private final MethodHandle mapBuffer;
    private final MethodHandle unmapBuffer;
    private final MethodHandle getString;
    private final MethodHandle enable;
    private final MethodHandle viewport;
    private final MethodHandle genTextures;
    private final MethodHandle deleteTextures;
    private final MethodHandle bindTexture;
    private final MethodHandle texParameteri;
    private final MethodHandle texImage2D;
    private final MethodHandle texSubImage2D;
    private final MethodHandle texEnvi;
    private final MethodHandle begin;
    private final MethodHandle end;
    private final MethodHandle texCoord2f;
    private final MethodHandle vertex2f;
    private final MethodHandle readPixels;
    private final MethodHandle activeTexture;
    private final MethodHandle createShader;
    private final MethodHandle shaderSource;
    private final MethodHandle compileShader;
    private final MethodHandle getShaderiv;
    private final MethodHandle getShaderInfoLog;
    private final MethodHandle deleteShader;
    private final MethodHandle createProgram;
    private final MethodHandle attachShader;
    private final MethodHandle linkProgram;
    private final MethodHandle getProgramiv;
    private final MethodHandle getProgramInfoLog;
    private final MethodHandle deleteProgram;
    private final MethodHandle useProgram;
    private final MethodHandle getUniformLocation;
    private final MethodHandle uniform1i;
    private final MethodHandle uniform3f;
    private final MethodHandle uniformMatrix3fv;
    /** Core (GL 3.0) or {@code EXT_framebuffer_object} entry points, per {@link #load}. */
    private final MethodHandle genFramebuffers;
    private final MethodHandle bindFramebuffer;
    private final MethodHandle framebufferTexture2D;
    private final MethodHandle checkFramebufferStatus;
    private final MethodHandle deleteFramebuffers;

    /**
     * Resolves the table through {@code procs}; the context must be current.
     * {@code extFramebuffers} picks the {@code EXT_framebuffer_object} names
     * ({@code glGenFramebuffersEXT}, …) instead of the GL 3.0 core ones —
     * Apple's legacy 2.1 context only has the former.
     */
    public static GL load(ProcLookup procs, boolean extFramebuffers) {
        return new GL(procs, extFramebuffers);
    }

    private GL(ProcLookup p, boolean ext) {
        String fb = ext ? "EXT" : "";
        getError = fn(p, "glGetError", FunctionDescriptor.of(JAVA_INT));
        finish = fn(p, "glFinish", FunctionDescriptor.ofVoid());
        genBuffers = fn(p, "glGenBuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        deleteBuffers = fn(p, "glDeleteBuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        bindBuffer = fn(p, "glBindBuffer", ints(2));
        bufferData = fn(p, "glBufferData",
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
        mapBuffer = fn(p, "glMapBuffer", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
        unmapBuffer = fn(p, "glUnmapBuffer", FunctionDescriptor.of(JAVA_BYTE, JAVA_INT));
        getString = fn(p, "glGetString", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        enable = fn(p, "glEnable", FunctionDescriptor.ofVoid(JAVA_INT));
        viewport = fn(p, "glViewport", ints(4));
        genTextures = fn(p, "glGenTextures", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        deleteTextures = fn(p, "glDeleteTextures", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        bindTexture = fn(p, "glBindTexture", ints(2));
        texParameteri = fn(p, "glTexParameteri", ints(3));
        texImage2D = fn(p, "glTexImage2D", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        texSubImage2D = fn(p, "glTexSubImage2D", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        texEnvi = fn(p, "glTexEnvi", ints(3));
        begin = fn(p, "glBegin", ints(1));
        end = fn(p, "glEnd", FunctionDescriptor.ofVoid());
        texCoord2f = fn(p, "glTexCoord2f", FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT));
        vertex2f = fn(p, "glVertex2f", FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT));
        readPixels = fn(p, "glReadPixels", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        activeTexture = fn(p, "glActiveTexture", ints(1));
        createShader = fn(p, "glCreateShader", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        shaderSource = fn(p, "glShaderSource",
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        compileShader = fn(p, "glCompileShader", ints(1));
        getShaderiv = fn(p, "glGetShaderiv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS));
        getShaderInfoLog = fn(p, "glGetShaderInfoLog",
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        deleteShader = fn(p, "glDeleteShader", ints(1));
        createProgram = fn(p, "glCreateProgram", FunctionDescriptor.of(JAVA_INT));
        attachShader = fn(p, "glAttachShader", ints(2));
        linkProgram = fn(p, "glLinkProgram", ints(1));
        getProgramiv = fn(p, "glGetProgramiv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS));
        getProgramInfoLog = fn(p, "glGetProgramInfoLog",
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        deleteProgram = fn(p, "glDeleteProgram", ints(1));
        useProgram = fn(p, "glUseProgram", ints(1));
        getUniformLocation = fn(p, "glGetUniformLocation",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        uniform1i = fn(p, "glUniform1i", ints(2));
        uniform3f = fn(p, "glUniform3f",
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        uniformMatrix3fv = fn(p, "glUniformMatrix3fv",
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_BYTE, ADDRESS));
        genFramebuffers = fn(p, "glGenFramebuffers" + fb,
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        bindFramebuffer = fn(p, "glBindFramebuffer" + fb, ints(2));
        framebufferTexture2D = fn(p, "glFramebufferTexture2D" + fb, ints(5));
        checkFramebufferStatus = fn(p, "glCheckFramebufferStatus" + fb,
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        deleteFramebuffers = fn(p, "glDeleteFramebuffers" + fb,
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
    }

    private static FunctionDescriptor ints(int n) {
        MemoryLayout[] args = new MemoryLayout[n];
        java.util.Arrays.fill(args, JAVA_INT);
        return FunctionDescriptor.ofVoid(args);
    }

    private static MethodHandle fn(ProcLookup procs, String name, FunctionDescriptor d) {
        MemorySegment address = procs.find(name);
        if (address == null || address.equals(MemorySegment.NULL)) {
            return null;
        }
        return LINKER.downcallHandle(address, d);
    }

    // --- state, textures, fixed function ----------------------------------

    public int glGetError() {
        try {
            return (int) need(getError, "glGetError").invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The string, or {@code null} when the driver returns none (no context current). */
    public String glGetString(int name) {
        try {
            MemorySegment s = (MemorySegment) need(getString, "glGetString").invokeExact(name);
            return s.equals(MemorySegment.NULL) ? null
                    : s.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glEnable(int cap) {
        try {
            need(enable, "glEnable").invokeExact(cap);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glViewport(int x, int y, int width, int height) {
        try {
            need(viewport, "glViewport").invokeExact(x, y, width, height);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int glGenTextures() {
        try {
            need(genTextures, "glGenTextures").invokeExact(1, scratch);
            return scratch.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glDeleteTextures(int texture) {
        try {
            scratch.set(JAVA_INT, 0, texture);
            need(deleteTextures, "glDeleteTextures").invokeExact(1, scratch);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glBindTexture(int target, int texture) {
        try {
            need(bindTexture, "glBindTexture").invokeExact(target, texture);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glTexParameteri(int target, int pname, int param) {
        try {
            need(texParameteri, "glTexParameteri").invokeExact(target, pname, param);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code pixels} may be {@link MemorySegment#NULL} to allocate storage only. */
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height,
                             int border, int format, int type, MemorySegment pixels) {
        try {
            need(texImage2D, "glTexImage2D").invokeExact(target, level, internalFormat,
                    width, height, border, format, type, pixels);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width,
                                int height, int format, int type, MemorySegment pixels) {
        try {
            need(texSubImage2D, "glTexSubImage2D").invokeExact(target, level, xoffset, yoffset,
                    width, height, format, type, pixels);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glTexEnvi(int target, int pname, int param) {
        try {
            need(texEnvi, "glTexEnvi").invokeExact(target, pname, param);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glBegin(int mode) {
        try {
            need(begin, "glBegin").invokeExact(mode);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glEnd() {
        try {
            need(end, "glEnd").invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glTexCoord2f(float s, float t) {
        try {
            need(texCoord2f, "glTexCoord2f").invokeExact(s, t);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    public void glVertex2f(float x, float y) {
        try {
            need(vertex2f, "glVertex2f").invokeExact(x, y);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Reads into a direct {@code dst} from its position (it must be direct). */
    public void glReadPixels(int x, int y, int width, int height, int format, int type,
                             ByteBuffer dst) {
        try {
            need(readPixels, "glReadPixels").invokeExact(x, y, width, height, format, type,
                    MemorySegment.ofBuffer(dst));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glActiveTexture(int texture) {
        try {
            need(activeTexture, "glActiveTexture").invokeExact(texture);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code glReadPixels} into a {@code MemorySegment}: client memory, or —
     * with a {@link #GL_PIXEL_PACK_BUFFER} bound — a byte offset into it
     * ({@code MemorySegment.ofAddress(offset)}).
     */
    public void glReadPixels(int x, int y, int width, int height, int format, int type,
                             MemorySegment dst) {
        try {
            need(readPixels, "glReadPixels").invokeExact(x, y, width, height, format, type, dst);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glFinish() {
        try {
            need(finish, "glFinish").invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- buffer objects (pixel pack/unpack) --------------------------------

    public int glGenBuffers() {
        try {
            need(genBuffers, "glGenBuffers").invokeExact(1, scratch);
            return scratch.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glDeleteBuffers(int buffer) {
        try {
            scratch.set(JAVA_INT, 0, buffer);
            need(deleteBuffers, "glDeleteBuffers").invokeExact(1, scratch);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glBindBuffer(int target, int buffer) {
        try {
            need(bindBuffer, "glBindBuffer").invokeExact(target, buffer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code data} may be {@link MemorySegment#NULL} to (re)allocate storage only. */
    public void glBufferData(int target, long size, MemorySegment data, int usage) {
        try {
            need(bufferData, "glBufferData").invokeExact(target, size, data, usage);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Maps the bound buffer; the result is sized {@code size} (the caller's
     * {@code glBufferData} size) and valid until {@link #glUnmapBuffer}.
     */
    public MemorySegment glMapBuffer(int target, int access, long size) {
        try {
            MemorySegment p = (MemorySegment) need(mapBuffer, "glMapBuffer").invokeExact(target, access);
            if (p.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("glMapBuffer returned NULL (error 0x"
                        + Integer.toHexString(glGetError()) + ")");
            }
            return p.reinterpret(size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code false} when the buffer's contents were lost while mapped. */
    public boolean glUnmapBuffer(int target) {
        try {
            return (byte) need(unmapBuffer, "glUnmapBuffer").invokeExact(target) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- shaders -----------------------------------------------------------

    public int glCreateShader(int type) {
        try {
            return (int) need(createShader, "glCreateShader").invokeExact(type);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glShaderSource(int shader, String source) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment strings = arena.allocate(ADDRESS);
            strings.set(ADDRESS, 0, arena.allocateFrom(source, StandardCharsets.UTF_8));
            // A null length array: the source is NUL-terminated.
            need(shaderSource, "glShaderSource").invokeExact(shader, 1, strings, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glCompileShader(int shader) {
        try {
            need(compileShader, "glCompileShader").invokeExact(shader);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int glGetShaderi(int shader, int pname) {
        try {
            need(getShaderiv, "glGetShaderiv").invokeExact(shader, pname, scratch);
            return scratch.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public String glGetShaderInfoLog(int shader) {
        int length = Math.max(1, glGetShaderi(shader, GL_INFO_LOG_LENGTH));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment log = arena.allocate(length);
            need(getShaderInfoLog, "glGetShaderInfoLog").invokeExact(shader, length,
                    MemorySegment.NULL, log);
            return log.getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glDeleteShader(int shader) {
        try {
            need(deleteShader, "glDeleteShader").invokeExact(shader);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int glCreateProgram() {
        try {
            return (int) need(createProgram, "glCreateProgram").invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glAttachShader(int program, int shader) {
        try {
            need(attachShader, "glAttachShader").invokeExact(program, shader);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glLinkProgram(int program) {
        try {
            need(linkProgram, "glLinkProgram").invokeExact(program);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int glGetProgrami(int program, int pname) {
        try {
            need(getProgramiv, "glGetProgramiv").invokeExact(program, pname, scratch);
            return scratch.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public String glGetProgramInfoLog(int program) {
        int length = Math.max(1, glGetProgrami(program, GL_INFO_LOG_LENGTH));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment log = arena.allocate(length);
            need(getProgramInfoLog, "glGetProgramInfoLog").invokeExact(program, length,
                    MemorySegment.NULL, log);
            return log.getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glDeleteProgram(int program) {
        try {
            need(deleteProgram, "glDeleteProgram").invokeExact(program);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glUseProgram(int program) {
        try {
            need(useProgram, "glUseProgram").invokeExact(program);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int glGetUniformLocation(int program, String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) need(getUniformLocation, "glGetUniformLocation").invokeExact(program,
                    arena.allocateFrom(name, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glUniform1i(int location, int value) {
        try {
            need(uniform1i, "glUniform1i").invokeExact(location, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glUniform3f(int location, float x, float y, float z) {
        try {
            need(uniform3f, "glUniform3f").invokeExact(location, x, y, z);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** One column-major 3×3 matrix ({@code value.length == 9}). */
    public void glUniformMatrix3fv(int location, boolean transpose, float[] value) {
        try {
            MemorySegment.copy(value, 0, scratch, JAVA_FLOAT, 0, 9);
            need(uniformMatrix3fv, "glUniformMatrix3fv").invokeExact(location, 1,
                    (byte) (transpose ? 1 : 0), scratch);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- framebuffer objects (core or EXT, per load) ----------------------

    public int glGenFramebuffers() {
        try {
            need(genFramebuffers, "glGenFramebuffers").invokeExact(1, scratch);
            return scratch.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glBindFramebuffer(int target, int framebuffer) {
        try {
            need(bindFramebuffer, "glBindFramebuffer").invokeExact(target, framebuffer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glFramebufferTexture2D(int target, int attachment, int textarget,
                                       int texture, int level) {
        try {
            need(framebufferTexture2D, "glFramebufferTexture2D").invokeExact(target, attachment,
                    textarget, texture, level);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int glCheckFramebufferStatus(int target) {
        try {
            return (int) need(checkFramebufferStatus, "glCheckFramebufferStatus").invokeExact(target);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void glDeleteFramebuffers(int framebuffer) {
        try {
            scratch.set(JAVA_INT, 0, framebuffer);
            need(deleteFramebuffers, "glDeleteFramebuffers").invokeExact(1, scratch);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Whether {@link #glGenFramebuffers} and friends resolved for this context. */
    public boolean hasFramebuffers() {
        return genFramebuffers != null && bindFramebuffer != null
                && framebufferTexture2D != null && checkFramebufferStatus != null
                && deleteFramebuffers != null;
    }

    private static MethodHandle need(MethodHandle handle, String name) {
        if (handle == null) {
            throw new UnsupportedOperationException(name + " is not exported by this OpenGL context");
        }
        return handle;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
