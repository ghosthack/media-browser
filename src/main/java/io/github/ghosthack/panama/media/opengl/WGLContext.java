package io.github.ghosthack.panama.media.opengl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * A Windows WGL context. WGL only creates a context for a window's device
 * context, so this owns a hidden window — never shown, never drawn into, its
 * messages left to {@code DefWindowProcW} — the same trick as a GLFW hidden
 * window. The pixel format matches GLFW's defaults minus depth/stencil
 * (offscreen work draws into an FBO): RGBA 8-8-8-8,
 * double-buffered. {@code wglCreateContext} yields the driver's highest
 * compatibility-profile context, as GLFW's default hints did.
 *
 * <p>Entry points beyond GL 1.1 come from {@code wglGetProcAddress}, which
 * signals failure with 0, 1, 2, 3 or −1; the GL 1.1 ones are only exports of
 * {@code opengl32.dll}.</p>
 *
 * <p>The window class is registered once per process (under a lock: two
 * threads may create contexts at once) and never unregistered. Each
 * context's window belongs to its creating thread, which is also where
 * {@link #close} must run.</p>
 */
public final class WGLContext implements GLContext {

    private static final String CLASS_NAME = "panama-media-opengl-offscreen";
    private static final int WS_CLIPCHILDREN = 0x02000000;
    private static final int WS_CLIPSIBLINGS = 0x04000000;
    private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    private static final int PFD_DOUBLEBUFFER = 0x01;
    private static final int PFD_DRAW_TO_WINDOW = 0x04;
    private static final int PFD_SUPPORT_OPENGL = 0x20;
    private static final int PFD_TYPE_RGBA = 0;
    private static final int PFD_MAIN_PLANE = 0;

    /** {@code WNDCLASSEXW} on 64-bit Windows. */
    private static final StructLayout WNDCLASSEXW = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            JAVA_INT.withName("style"),
            ADDRESS.withName("lpfnWndProc"),
            JAVA_INT.withName("cbClsExtra"),
            JAVA_INT.withName("cbWndExtra"),
            ADDRESS.withName("hInstance"),
            ADDRESS.withName("hIcon"),
            ADDRESS.withName("hCursor"),
            ADDRESS.withName("hbrBackground"),
            ADDRESS.withName("lpszMenuName"),
            ADDRESS.withName("lpszClassName"),
            ADDRESS.withName("hIconSm"));

    /** {@code PIXELFORMATDESCRIPTOR}: 40 bytes, all BYTE fields between the DWORDs. */
    private static final long PFD_SIZE = 40;

    private static final Object LOCK = new Object();
    private static MemorySegment className;
    private static MemorySegment instance;
    private static SymbolLookup opengl32;
    private static MethodHandle getModuleHandle;
    private static MethodHandle registerClassEx;
    private static MethodHandle createWindowEx;
    private static MethodHandle destroyWindow;
    private static MethodHandle getDC;
    private static MethodHandle releaseDC;
    private static MethodHandle choosePixelFormat;
    private static MethodHandle setPixelFormat;
    private static MethodHandle wglCreateContext;
    private static MethodHandle wglMakeCurrent;
    private static MethodHandle wglDeleteContext;
    private static MethodHandle wglGetProcAddress;
    private static MethodHandle getLastError;

    private MemorySegment window;
    private MemorySegment dc;
    private MemorySegment context;
    private GL gl;

    private WGLContext() {}

    /** Creates the hidden window and context and makes it current on this thread. */
    public static WGLContext create() {
        ensureClassRegistered();
        WGLContext c = new WGLContext();
        boolean ok = false;
        try (Arena arena = Arena.ofConfined()) {
            c.window = (MemorySegment) createWindowEx.invokeExact(0, className,
                    arena.allocateFrom(CLASS_NAME, StandardCharsets.UTF_16LE),
                    WS_OVERLAPPEDWINDOW | WS_CLIPSIBLINGS | WS_CLIPCHILDREN,
                    0, 0, 16, 16,
                    MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL);
            if (c.window.equals(MemorySegment.NULL)) {
                throw failure("CreateWindowExW");
            }
            c.dc = (MemorySegment) getDC.invokeExact(c.window);
            if (c.dc.equals(MemorySegment.NULL)) {
                throw failure("GetDC");
            }

            MemorySegment pfd = arena.allocate(PFD_SIZE, 4);
            pfd.set(JAVA_SHORT, 0, (short) PFD_SIZE);          // nSize
            pfd.set(JAVA_SHORT, 2, (short) 1);                 // nVersion
            pfd.set(JAVA_INT, 4, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER);
            pfd.set(JAVA_BYTE, 8, (byte) PFD_TYPE_RGBA);       // iPixelType
            pfd.set(JAVA_BYTE, 9, (byte) 24);                  // cColorBits
            pfd.set(JAVA_BYTE, 16, (byte) 8);                  // cAlphaBits
            pfd.set(JAVA_BYTE, 26, (byte) PFD_MAIN_PLANE);     // iLayerType
            int format = (int) choosePixelFormat.invokeExact(c.dc, pfd);
            if (format == 0) {
                throw failure("ChoosePixelFormat");
            }
            if ((int) setPixelFormat.invokeExact(c.dc, format, pfd) == 0) {
                throw failure("SetPixelFormat");
            }
            c.context = (MemorySegment) wglCreateContext.invokeExact(c.dc);
            if (c.context.equals(MemorySegment.NULL)) {
                throw failure("wglCreateContext");
            }
            if ((int) wglMakeCurrent.invokeExact(c.dc, c.context) == 0) {
                throw failure("wglMakeCurrent");
            }
            c.gl = GL.load(WGLContext::proc, false);
            ok = true;
            return c;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("WGL context creation failed", t);
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
        return "WGL";
    }

    @Override
    public void close() {
        try {
            if (context != null) {
                int ignored = (int) wglMakeCurrent.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
                ignored = (int) wglDeleteContext.invokeExact(context);
                context = null;
            }
            if (dc != null) {
                int ignored = (int) releaseDC.invokeExact(window, dc);
                dc = null;
            }
            if (window != null) {
                int ignored = (int) destroyWindow.invokeExact(window);
                window = null;
            }
        } catch (Throwable t) {
            throw new IllegalStateException("WGL context teardown failed", t);
        }
    }

    private static MemorySegment proc(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = (MemorySegment) wglGetProcAddress.invokeExact(
                    arena.allocateFrom(name, StandardCharsets.US_ASCII));
            long a = p.address();
            if (a != 0 && a != 1 && a != 2 && a != 3 && a != -1) {
                return p;
            }
        } catch (Throwable t) {
            throw new IllegalStateException("wglGetProcAddress failed", t);
        }
        return opengl32.find(name).orElse(null);
    }

    /** Binds the Win32/WGL functions and registers the window class, once. */
    private static void ensureClassRegistered() {
        synchronized (LOCK) {
            if (className == null) bind();
        }
    }

    private static void bind() {
        Linker linker = Linker.nativeLinker();
        Arena global = Arena.global();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", global);
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", global);
        SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", global);
        // opengl32 is loaded before any gdi32 pixel-format call, which routes into it.
        opengl32 = SymbolLookup.libraryLookup("opengl32", global);

        getModuleHandle = linker.downcallHandle(kernel32.findOrThrow("GetModuleHandleW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        getLastError = linker.downcallHandle(kernel32.findOrThrow("GetLastError"),
                FunctionDescriptor.of(JAVA_INT));
        registerClassEx = linker.downcallHandle(user32.findOrThrow("RegisterClassExW"),
                FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
        createWindowEx = linker.downcallHandle(user32.findOrThrow("CreateWindowExW"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        destroyWindow = linker.downcallHandle(user32.findOrThrow("DestroyWindow"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getDC = linker.downcallHandle(user32.findOrThrow("GetDC"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        releaseDC = linker.downcallHandle(user32.findOrThrow("ReleaseDC"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        choosePixelFormat = linker.downcallHandle(gdi32.findOrThrow("ChoosePixelFormat"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        setPixelFormat = linker.downcallHandle(gdi32.findOrThrow("SetPixelFormat"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        wglCreateContext = linker.downcallHandle(opengl32.findOrThrow("wglCreateContext"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        wglMakeCurrent = linker.downcallHandle(opengl32.findOrThrow("wglMakeCurrent"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        wglDeleteContext = linker.downcallHandle(opengl32.findOrThrow("wglDeleteContext"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        wglGetProcAddress = linker.downcallHandle(opengl32.findOrThrow("wglGetProcAddress"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));

        try {
            instance = (MemorySegment) getModuleHandle.invokeExact(MemorySegment.NULL);
            MemorySegment name = global.allocateFrom(CLASS_NAME, StandardCharsets.UTF_16LE);
            MemorySegment wc = global.allocate(WNDCLASSEXW);
            wc.set(JAVA_INT, offset("cbSize"), (int) WNDCLASSEXW.byteSize());
            // DefWindowProcW is itself a WNDPROC: no Java upcall is needed.
            wc.set(ADDRESS, offset("lpfnWndProc"), user32.findOrThrow("DefWindowProcW"));
            wc.set(ADDRESS, offset("hInstance"), instance);
            wc.set(ADDRESS, offset("lpszClassName"), name);
            if ((short) registerClassEx.invokeExact(wc) == 0) {
                throw failure("RegisterClassExW");
            }
            className = name;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("WGL window class registration failed", t);
        }
    }

    private static long offset(String field) {
        return WNDCLASSEXW.byteOffset(groupElement(field));
    }

    /**
     * An exception naming the failed call and {@code GetLastError}. FFM does
     * not promise the JVM leaves the thread's last-error untouched between
     * downcalls, so the code is a best-effort hint, not a guarantee.
     */
    private static IllegalStateException failure(String call) {
        int code;
        try {
            code = (int) getLastError.invokeExact();
        } catch (Throwable t) {
            code = -1;
        }
        return new IllegalStateException(call + " failed (GetLastError " + code + ")");
    }
}
