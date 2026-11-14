package io.github.ghosthack.panama.media.opengl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A windowless Apple CGL context: legacy (2.1) profile, 24-bit color plus
 * 8-bit alpha, so the fixed-function pipeline is available next to
 * {@code EXT_framebuffer_object}. Every GL entry point is a plain export of OpenGL.framework, so
 * the function table needs no {@code GetProcAddress}.
 */
public final class CGLContext implements GLContext {

    private static final String FRAMEWORK = "/System/Library/Frameworks/OpenGL.framework/OpenGL";

    private static final int kCGLPFAColorSize = 8;
    private static final int kCGLPFAAlphaSize = 11;
    private static final int kCGLPFAOpenGLProfile = 99;
    private static final int kCGLOGLPVersion_Legacy = 0x1000;

    private static SymbolLookup lib;
    private static MethodHandle choosePixelFormat;
    private static MethodHandle destroyPixelFormat;
    private static MethodHandle createContext;
    private static MethodHandle setCurrentContext;
    private static MethodHandle destroyContext;
    private static MethodHandle errorString;

    private MemorySegment context;
    private final GL gl;

    private CGLContext(MemorySegment context) {
        this.context = context;
        this.gl = GL.load(CGLContext::proc, true);
    }

    /** Creates the context and makes it current on this thread. */
    public static CGLContext create() {
        bind();
        MemorySegment ctx;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attribs = arena.allocateFrom(JAVA_INT,
                    kCGLPFAOpenGLProfile, kCGLOGLPVersion_Legacy,
                    kCGLPFAColorSize, 24,
                    kCGLPFAAlphaSize, 8,
                    0);
            MemorySegment pf = arena.allocate(ADDRESS);
            MemorySegment count = arena.allocate(JAVA_INT);
            check((int) choosePixelFormat.invokeExact(attribs, pf, count), "CGLChoosePixelFormat");
            MemorySegment pixelFormat = pf.get(ADDRESS, 0);
            if (pixelFormat.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("CGLChoosePixelFormat found no pixel format");
            }
            try {
                MemorySegment out = arena.allocate(ADDRESS);
                check((int) createContext.invokeExact(pixelFormat, MemorySegment.NULL, out),
                        "CGLCreateContext");
                ctx = out.get(ADDRESS, 0);
            } finally {
                int ignored = (int) destroyPixelFormat.invokeExact(pixelFormat);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("CGL context creation failed", t);
        }
        try {
            check(setCurrent(ctx), "CGLSetCurrentContext");
            return new CGLContext(ctx);
        } catch (RuntimeException e) {
            setCurrent(MemorySegment.NULL);
            destroy(ctx);
            throw e;
        }
    }

    /** The {@code CGLContextObj}, for {@code CGLTexImageIOSurface2D}. */
    public long handle() {
        return context.address();
    }

    @Override
    public GL gl() {
        return gl;
    }

    @Override
    public String api() {
        return "CGL";
    }

    @Override
    public void close() {
        if (context == null) return;
        setCurrent(MemorySegment.NULL);
        destroy(context);
        context = null;
    }

    private static synchronized void bind() {
        if (lib != null) return;
        Linker linker = Linker.nativeLinker();
        SymbolLookup gl = SymbolLookup.libraryLookup(FRAMEWORK, Arena.global());
        choosePixelFormat = linker.downcallHandle(gl.findOrThrow("CGLChoosePixelFormat"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        destroyPixelFormat = linker.downcallHandle(gl.findOrThrow("CGLDestroyPixelFormat"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        createContext = linker.downcallHandle(gl.findOrThrow("CGLCreateContext"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        setCurrentContext = linker.downcallHandle(gl.findOrThrow("CGLSetCurrentContext"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        destroyContext = linker.downcallHandle(gl.findOrThrow("CGLDestroyContext"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        errorString = linker.downcallHandle(gl.findOrThrow("CGLErrorString"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        lib = gl;
    }

    private static MemorySegment proc(String name) {
        return lib.find(name).orElse(null);
    }

    private static int setCurrent(MemorySegment ctx) {
        try {
            return (int) setCurrentContext.invokeExact(ctx);
        } catch (Throwable t) {
            throw new IllegalStateException("CGLSetCurrentContext failed", t);
        }
    }

    private static void destroy(MemorySegment ctx) {
        try {
            int ignored = (int) destroyContext.invokeExact(ctx);
        } catch (Throwable t) {
            throw new IllegalStateException("CGLDestroyContext failed", t);
        }
    }

    private static void check(int error, String what) {
        if (error == 0) return;
        String message;
        try {
            MemorySegment s = (MemorySegment) errorString.invokeExact(error);
            message = s.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            message = "CGL error";
        }
        throw new IllegalStateException(what + " failed: " + message + " (" + error + ")");
    }
}
