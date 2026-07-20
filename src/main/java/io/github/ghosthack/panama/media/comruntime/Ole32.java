package io.github.ghosthack.panama.media.comruntime;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.core.Platform;

/**
 * Panama FFM bindings to <b>ole32.dll</b> — Windows COM infrastructure.
 * <p>
 * Provides the shared COM lifecycle, IUnknown vtable dispatch, GUID allocation,
 * HRESULT checking, and wide-string helpers that Windows-specific modules
 * (WIC, Media Foundation, etc.) need.
 * <p>
 * COM vtable dispatch strategy: {@link Linker#downcallHandle(FunctionDescriptor)}
 * (no fixed address) returns a {@link MethodHandle} that takes an extra leading
 * {@link MemorySegment} parameter for the function pointer. At call time we read
 * the function pointer from the COM object's vtable and pass it as the first arg.
 * <p>
 * Requires native access for {@code io.github.ghosthack.panama.media.comruntime}
 * at runtime (or {@code ALL-UNNAMED} for classpath launches).
 * Only functional on Windows; all handles are {@code null} on other OSes so the
 * class loads safely on any platform.
 */
public final class Ole32 {

    private Ole32() {}

    // ── OS guard ────────────────────────────────────────────────────────

    private static final boolean IS_WINDOWS = Platform.IS_WINDOWS;

    // ── Constants ───────────────────────────────────────────────────────

    /** COINIT_MULTITHREADED = 0x0 */
    public static final int COINIT_MULTITHREADED = 0x0;

    /** CLSCTX_INPROC_SERVER = 0x1 */
    public static final int CLSCTX_INPROC_SERVER = 0x1;

    // ── GUID layout (16 bytes: uint32, uint16, uint16, byte[8]) ────────

    /** Standard Windows GUID / UUID memory layout. */
    public static final StructLayout GUID_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Data1"),
            ValueLayout.JAVA_SHORT.withName("Data2"),
            ValueLayout.JAVA_SHORT.withName("Data3"),
            MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("Data4")
    );

    // ── Linker ─────────────────────────────────────────────────────────

    private static final Linker LINKER = Linker.nativeLinker();

    // ── DLL loading and ole32 downcalls ────────────────────────────────

    private static final SymbolLookup LOADER_LOOKUP;
    private static final MethodHandle CoInitializeEx;
    private static final MethodHandle CoCreateInstance;
    private static final MethodHandle CoUninitialize;
    private static final MethodHandle CoTaskMemFree;
    private static final MethodHandle PropVariantClear;

    static {
        if (IS_WINDOWS) {
            System.loadLibrary("ole32");
            LOADER_LOOKUP = SymbolLookup.loaderLookup();

            // HRESULT CoInitializeEx(LPVOID pvReserved, DWORD dwCoInit)
            CoInitializeEx = downcall("CoInitializeEx",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // HRESULT CoCreateInstance(REFCLSID rclsid, LPUNKNOWN pUnkOuter,
            //     DWORD dwClsContext, REFIID riid, LPVOID *ppv)
            CoCreateInstance = downcall("CoCreateInstance",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // void CoUninitialize(void)
            CoUninitialize = downcall("CoUninitialize",
                    FunctionDescriptor.ofVoid());

            // void CoTaskMemFree(LPVOID pv)
            CoTaskMemFree = downcall("CoTaskMemFree",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // HRESULT PropVariantClear(PROPVARIANT *pvar)
            PropVariantClear = downcall("PropVariantClear",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        } else {
            LOADER_LOOKUP = null;
            CoInitializeEx = null;
            CoCreateInstance = null;
            CoUninitialize = null;
            CoTaskMemFree = null;
            PropVariantClear = null;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOADER_LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    // ── COM vtable dispatch handles ─────────────────────────────────────
    // Linker.downcallHandle(FunctionDescriptor) — no address — yields a
    // MethodHandle with an extra leading MemorySegment for the function pointer.

    // IUnknown::Release (vtable[2])
    //   ULONG Release([in] IUnknown *this)
    private static final MethodHandle IUnknown_Release = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IUnknown::QueryInterface (vtable[0])
    //   HRESULT QueryInterface([in] this, REFIID riid, [out] void **ppvObject)
    private static final MethodHandle IUnknown_QueryInterface = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // ── Availability ───────────────────────────────────────────────────

    /**
     * Returns {@code true} if ole32.dll was loaded successfully and
     * all downcall handles are available (i.e. running on Windows).
     */
    public static boolean isAvailable() {
        return IS_WINDOWS;
    }

    // ── HRESULT helpers ─────────────────────────────────────────────────

    /**
     * Returns {@code true} if the HRESULT indicates failure (negative value).
     */
    public static boolean failed(int hr) { return hr < 0; }

    /**
     * Throws {@link IllegalArgumentException} if the HRESULT indicates failure.
     *
     * @param hr  HRESULT value
     * @param msg descriptive message for the exception
     * @throws IllegalArgumentException if {@code hr < 0}
     */
    public static void check(int hr, String msg) {
        if (failed(hr))
            throw new DecodeException(
                    msg + " (HRESULT 0x" + Integer.toHexString(hr) + ")");
    }

    // ── GUID helpers ───────────────────────────────────────────────────

    /**
     * Allocates and populates a GUID in the given arena.
     * <p>
     * The {@code data4} array must have exactly 8 bytes.
     *
     * @param arena arena for the allocation
     * @param d1    Data1 (uint32)
     * @param d2    Data2 (uint16)
     * @param d3    Data3 (uint16)
     * @param d4    Data4 (8 bytes)
     * @return a {@link MemorySegment} containing the GUID
     */
    public static MemorySegment guid(Arena arena, int d1, short d2, short d3, byte[] d4) {
        MemorySegment seg = arena.allocate(GUID_LAYOUT);
        seg.set(ValueLayout.JAVA_INT, 0, d1);
        seg.set(ValueLayout.JAVA_SHORT, 4, d2);
        seg.set(ValueLayout.JAVA_SHORT, 6, d3);
        MemorySegment.copy(d4, 0, seg, ValueLayout.JAVA_BYTE, 8, 8);
        return seg;
    }

    // ── COM lifecycle ──────────────────────────────────────────────────

    /**
     * Initialises COM on the calling thread (multithreaded apartment).
     * Calls {@code CoInitializeEx(NULL, COINIT_MULTITHREADED)}.
     *
     * @return HRESULT from CoInitializeEx
     */
    public static int coInitializeEx() {
        try {
            return (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
        } catch (Throwable t) {
            throw new DecodeException("CoInitializeEx failed", t);
        }
    }

    /**
     * Creates a COM object via {@code CoCreateInstance} with {@code pUnkOuter=NULL}.
     *
     * @param clsid  CLSID of the object to create
     * @param clsCtx class context (e.g. {@link #CLSCTX_INPROC_SERVER})
     * @param iid    IID of the requested interface
     * @param ppv    pointer to receive the interface pointer
     * @return HRESULT from CoCreateInstance
     */
    public static int coCreateInstance(MemorySegment clsid, int clsCtx,
                                       MemorySegment iid, MemorySegment ppv) {
        try {
            return (int) CoCreateInstance.invokeExact(
                    clsid, MemorySegment.NULL, clsCtx, iid, ppv);
        } catch (Throwable t) {
            throw new DecodeException("CoCreateInstance failed", t);
        }
    }

    /**
     * Uninitialises COM on the calling thread.
     * Safe to call — wraps {@code CoUninitialize()} and swallows exceptions.
     */
    public static void coUninitialize() {
        try {
            CoUninitialize.invokeExact();
        } catch (Throwable ignored) { /* native uninit cannot throw */ }
    }

    /**
     * Frees a block of COM task memory (e.g. the {@code LPOLESTR} strings handed
     * back by {@code IEnumString::Next}). Wraps {@code CoTaskMemFree} and is a
     * safe no-op for {@code null}/{@link MemorySegment#NULL}.
     *
     * @param p task-allocated pointer to free, or {@code null}
     */
    public static void coTaskMemFree(MemorySegment p) {
        if (p == null || MemorySegment.NULL.equals(p)) return;
        try {
            CoTaskMemFree.invokeExact(p);
        } catch (Throwable ignored) { /* native free cannot throw */ }
    }

    /**
     * Clears a {@code PROPVARIANT} in place via {@code PropVariantClear}, freeing
     * any heap-allocated payload (strings, blobs) and releasing any contained
     * {@code IUnknown}. Call this on every PROPVARIANT filled by a metadata read
     * before its arena closes. Safe no-op for {@code null}/{@link MemorySegment#NULL}.
     *
     * @param pvar pointer to the {@code PROPVARIANT} to clear, or {@code null}
     */
    public static void propVariantClear(MemorySegment pvar) {
        if (pvar == null || MemorySegment.NULL.equals(pvar)) return;
        try {
            int hr = (int) PropVariantClear.invokeExact(pvar);
        } catch (Throwable ignored) { /* native clear cannot throw */ }
    }

    // ── COM vtable dispatch ────────────────────────────────────────────

    /**
     * Reads the function pointer at vtable index {@code idx} from a COM object.
     * <p>
     * COM object layout: the first pointer-sized value at the object's address
     * points to the vtable (an array of function pointers).
     *
     * @param comObj COM object pointer
     * @param idx    zero-based vtable index
     * @return the function pointer at the given vtable slot
     */
    public static MemorySegment vtable(MemorySegment comObj, int idx) {
        MemorySegment vtablePtr = comObj.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0);
        long offset = (long) idx * ValueLayout.ADDRESS.byteSize();
        return vtablePtr.reinterpret((long) (idx + 1) * ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, offset);
    }

    /**
     * Calls {@code IUnknown::Release} (vtable[2]) on a COM object.
     * Safe no-op if {@code comObj} is {@code null} or {@link MemorySegment#NULL}.
     *
     * @param comObj COM object pointer, or {@code null}
     */
    public static void release(MemorySegment comObj) {
        if (comObj != null && !MemorySegment.NULL.equals(comObj)) {
            try {
                int refCount = (int) IUnknown_Release.invokeExact(vtable(comObj, 2), comObj);
            } catch (Throwable ignored) { /* native release cannot throw */ }
        }
    }

    /**
     * Calls {@code IUnknown::QueryInterface} (vtable[0]) on a COM object.
     *
     * @param comObj COM object pointer
     * @param iid    IID of the requested interface
     * @param ppv    pointer to receive the interface pointer
     * @return HRESULT from QueryInterface
     */
    public static int queryInterface(MemorySegment comObj, MemorySegment iid,
                                     MemorySegment ppv) {
        try {
            return (int) IUnknown_QueryInterface.invokeExact(
                    vtable(comObj, 0), comObj, iid, ppv);
        } catch (Throwable t) {
            throw new DecodeException("IUnknown::QueryInterface failed", t);
        }
    }

    // ── Wide string helper ─────────────────────────────────────────────

    /**
     * Allocates a null-terminated UTF-16LE wide string (LPCWSTR) in the arena.
     *
     * @param arena arena for the allocation
     * @param s     the Java string to convert
     * @return a {@link MemorySegment} containing the wide string
     */
    public static MemorySegment wstr(Arena arena, String s) {
        byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L); // +2 for null terminator
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        seg.set(ValueLayout.JAVA_SHORT, utf16.length, (short) 0); // null terminator
        return seg;
    }
}
