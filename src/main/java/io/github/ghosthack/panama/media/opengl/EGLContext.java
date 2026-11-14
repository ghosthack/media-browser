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
 * A Linux EGL desktop-OpenGL context on a 1×1 pbuffer: no window at all.
 * {@code eglBindAPI(EGL_OPENGL_API)} with no context attributes yields the
 * driver's compatibility profile, as GLFW's default hints did.
 *
 * <p>The display is the default one (the X11/Wayland session the process runs
 * in), or Mesa's surfaceless platform when there is no session — e.g. a test
 * run over SSH. It is initialised once per process and never terminated:
 * {@code eglTerminate} would pull the display out from under every other
 * thread's live context (the same hazard as calling {@code glfwTerminate}
 * while another thread still renders).</p>
 */
public final class EGLContext implements GLContext {

    private static final int EGL_SUCCESS = 0x3000;
    private static final int EGL_ALPHA_SIZE = 0x3021;
    private static final int EGL_BLUE_SIZE = 0x3022;
    private static final int EGL_GREEN_SIZE = 0x3023;
    private static final int EGL_RED_SIZE = 0x3024;
    private static final int EGL_SURFACE_TYPE = 0x3033;
    private static final int EGL_NONE = 0x3038;
    private static final int EGL_RENDERABLE_TYPE = 0x3040;
    private static final int EGL_HEIGHT = 0x3056;
    private static final int EGL_WIDTH = 0x3057;
    private static final int EGL_OPENGL_API = 0x30A2;
    private static final int EGL_PBUFFER_BIT = 0x0001;
    private static final int EGL_OPENGL_BIT = 0x0008;
    private static final int EGL_PLATFORM_SURFACELESS_MESA = 0x31DD;

    private static final Object LOCK = new Object();
    private static MemorySegment display;
    private static String displayKind;
    private static SymbolLookup libGL;
    private static MethodHandle getDisplay;
    private static MethodHandle initialize;
    private static MethodHandle bindApi;
    private static MethodHandle chooseConfig;
    private static MethodHandle createContext;
    private static MethodHandle createPbufferSurface;
    private static MethodHandle makeCurrent;
    private static MethodHandle destroySurface;
    private static MethodHandle destroyContext;
    private static MethodHandle getError;
    private static MethodHandle getProcAddress;

    private MemorySegment surface;
    private MemorySegment context;
    private GL gl;

    private EGLContext() {}

    /** Creates the context and its pbuffer and makes it current on this thread. */
    public static EGLContext create() {
        MemorySegment dpy = ensureDisplay();
        EGLContext c = new EGLContext();
        boolean ok = false;
        try (Arena arena = Arena.ofConfined()) {
            // eglBindAPI is per thread: every playback thread binds desktop GL.
            check((int) bindApi.invokeExact(EGL_OPENGL_API), "eglBindAPI");
            MemorySegment attribs = arena.allocateFrom(JAVA_INT,
                    EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                    EGL_NONE);
            MemorySegment config = arena.allocate(ADDRESS);
            MemorySegment count = arena.allocate(JAVA_INT);
            check((int) chooseConfig.invokeExact(dpy, attribs, config, 1, count), "eglChooseConfig");
            if (count.get(JAVA_INT, 0) < 1) {
                throw new IllegalStateException("eglChooseConfig: no RGBA8 desktop-GL pbuffer config");
            }
            MemorySegment cfg = config.get(ADDRESS, 0);

            c.context = (MemorySegment) createContext.invokeExact(dpy, cfg,
                    MemorySegment.NULL, MemorySegment.NULL);
            if (c.context.equals(MemorySegment.NULL)) {
                throw failure("eglCreateContext");
            }
            c.surface = (MemorySegment) createPbufferSurface.invokeExact(dpy, cfg,
                    arena.allocateFrom(JAVA_INT, EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE));
            if (c.surface.equals(MemorySegment.NULL)) {
                throw failure("eglCreatePbufferSurface");
            }
            check((int) makeCurrent.invokeExact(dpy, c.surface, c.surface, c.context),
                    "eglMakeCurrent");
            c.gl = GL.load(EGLContext::proc, false);
            ok = true;
            return c;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("EGL context creation failed", t);
        } finally {
            if (!ok) c.close();
        }
    }

    @Override
    public GL gl() {
        return gl;
    }

    @Override
    public String api() {
        return "EGL";
    }

    /** {@code "default"} (the session's display) or {@code "surfaceless"}; {@code null} before first use. */
    public static String displayKind() {
        synchronized (LOCK) {
            return displayKind;
        }
    }

    @Override
    public void close() {
        if (context == null && surface == null) return;
        try {
            int ignored = (int) makeCurrent.invokeExact(display, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL);
            if (surface != null && !surface.equals(MemorySegment.NULL)) {
                ignored = (int) destroySurface.invokeExact(display, surface);
            }
            if (context != null && !context.equals(MemorySegment.NULL)) {
                ignored = (int) destroyContext.invokeExact(display, context);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("EGL context teardown failed", t);
        } finally {
            surface = null;
            context = null;
        }
    }

    /**
     * {@code eglGetProcAddress} first (it covers core entry points on Mesa and
     * NVIDIA, per {@code EGL_KHR_get_all_proc_addresses}), then the GL
     * library's own exports.
     */
    private static MemorySegment proc(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = (MemorySegment) getProcAddress.invokeExact(
                    arena.allocateFrom(name, StandardCharsets.US_ASCII));
            if (!p.equals(MemorySegment.NULL)) return p;
        } catch (Throwable t) {
            throw new IllegalStateException("eglGetProcAddress failed", t);
        }
        return libGL.find(name).orElse(null);
    }

    private static MemorySegment ensureDisplay() {
        synchronized (LOCK) {
            if (display == null) bind();
            return display;
        }
    }

    private static void bind() {
        Linker linker = Linker.nativeLinker();
        Arena global = Arena.global();
        SymbolLookup egl = SymbolLookup.libraryLookup("libEGL.so.1", global);
        libGL = openGlLibrary(global);
        getDisplay = linker.downcallHandle(egl.findOrThrow("eglGetDisplay"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        initialize = linker.downcallHandle(egl.findOrThrow("eglInitialize"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        bindApi = linker.downcallHandle(egl.findOrThrow("eglBindAPI"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        chooseConfig = linker.downcallHandle(egl.findOrThrow("eglChooseConfig"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        createContext = linker.downcallHandle(egl.findOrThrow("eglCreateContext"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        createPbufferSurface = linker.downcallHandle(egl.findOrThrow("eglCreatePbufferSurface"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        makeCurrent = linker.downcallHandle(egl.findOrThrow("eglMakeCurrent"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        destroySurface = linker.downcallHandle(egl.findOrThrow("eglDestroySurface"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        destroyContext = linker.downcallHandle(egl.findOrThrow("eglDestroyContext"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getError = linker.downcallHandle(egl.findOrThrow("eglGetError"),
                FunctionDescriptor.of(JAVA_INT));
        getProcAddress = linker.downcallHandle(egl.findOrThrow("eglGetProcAddress"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));

        boolean session = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        try {
            MemorySegment dpy = MemorySegment.NULL;
            String kind = null;
            if (session) {
                dpy = (MemorySegment) getDisplay.invokeExact(MemorySegment.NULL); // EGL_DEFAULT_DISPLAY
                kind = "default";
            } else {
                // eglGetPlatformDisplay is EGL 1.5; resolve it rather than link it,
                // so an EGL 1.4 library still binds (and fails here, loudly).
                MemorySegment platformDisplay = (MemorySegment) getProcAddress.invokeExact(
                        global.allocateFrom("eglGetPlatformDisplay", StandardCharsets.US_ASCII));
                if (!platformDisplay.equals(MemorySegment.NULL)) {
                    MethodHandle h = linker.downcallHandle(platformDisplay,
                            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
                    dpy = (MemorySegment) h.invokeExact(EGL_PLATFORM_SURFACELESS_MESA,
                            MemorySegment.NULL, MemorySegment.NULL);
                }
                kind = "surfaceless";
            }
            if (dpy.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("EGL: no " + kind + " display");
            }
            check((int) initialize.invokeExact(dpy, MemorySegment.NULL, MemorySegment.NULL),
                    "eglInitialize(" + kind + ")");
            displayKind = kind;
            display = dpy;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("EGL display initialisation failed", t);
        }
    }

    /** GLVND's GL library first, then the classic one; either exports GL 1.x. */
    private static SymbolLookup openGlLibrary(Arena arena) {
        try {
            return SymbolLookup.libraryLookup("libOpenGL.so.0", arena);
        } catch (IllegalArgumentException noGlvnd) {
            return SymbolLookup.libraryLookup("libGL.so.1", arena);
        }
    }

    private static void check(int result, String what) {
        if (result == 0) throw failure(what);
    }

    private static IllegalStateException failure(String what) {
        int code;
        try {
            code = (int) getError.invokeExact();
        } catch (Throwable t) {
            code = -1;
        }
        return new IllegalStateException(what + " failed (eglGetError 0x"
                + Integer.toHexString(code) + (code == EGL_SUCCESS ? ", EGL_SUCCESS" : "") + ")");
    }
}
