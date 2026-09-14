package io.github.ghosthack.panama.media.fileassoc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import io.github.ghosthack.panama.media.comruntime.Ole32;
import io.github.ghosthack.panama.media.core.Platform;

/**
 * Panama FFM bindings to <b>shell32.dll</b> — tells Explorer that a file
 * association changed (so a double-click reflects a new registration
 * immediately instead of waiting for a logoff/reboot), and opens a document
 * or URI through the shell ({@code ShellExecuteW}) — the only launcher that
 * resolves protocol URIs such as {@code ms-settings:} the way the Start menu
 * and {@code start} do; handing such a URI to {@code explorer.exe} as an
 * argument makes it open a folder instead.
 * <p>
 * Only functional on Windows; all handles are {@code null} on other OSes so
 * the class loads safely on any platform.
 */
public final class Shell32 {

    private Shell32() {}

    private static final boolean IS_WINDOWS = Platform.IS_WINDOWS;

    /** SHCNE_ASSOCCHANGED */
    private static final int SHCNE_ASSOCCHANGED = 0x08000000;
    /** SHCNF_IDLIST */
    private static final int SHCNF_IDLIST = 0x0000;
    /** SW_SHOWNORMAL */
    private static final int SW_SHOWNORMAL = 1;

    private static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup LOADER_LOOKUP;
    private static final MethodHandle SHChangeNotify;
    private static final MethodHandle ShellExecuteW;

    static {
        if (IS_WINDOWS) {
            System.loadLibrary("Shell32");
            LOADER_LOOKUP = SymbolLookup.loaderLookup();

            // void SHChangeNotify(LONG wEventId, UINT uFlags, LPCVOID dwItem1, LPCVOID dwItem2)
            SHChangeNotify = downcall("SHChangeNotify",
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            // HINSTANCE ShellExecuteW(HWND, LPCWSTR lpOperation, LPCWSTR lpFile,
            //     LPCWSTR lpParameters, LPCWSTR lpDirectory, INT nShowCmd)
            ShellExecuteW = downcall("ShellExecuteW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        } else {
            LOADER_LOOKUP = null;
            SHChangeNotify = null;
            ShellExecuteW = null;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOADER_LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    /** Returns {@code true} if shell32.dll was loaded (i.e. running on Windows). */
    public static boolean isAvailable() {
        return IS_WINDOWS;
    }

    /**
     * Tells Explorer that file-type associations changed, so Open-with /
     * double-click behavior reflects new registrations immediately. Best
     * effort: a failure here only delays Explorer noticing, so it never
     * throws.
     */
    /**
     * Opens {@code file} — a path, or a protocol URI such as
     * {@code ms-settings:defaultapps?registeredAppUser=Media Browser} (spaces
     * literal, not encoded) — through the shell's "open" verb, exactly as the
     * Start menu or a {@code start "" "<file>"} would. Synchronous only in
     * the sense that the shell has dispatched it; the target runs on its own.
     *
     * @return {@code true} when the shell accepted the request (the
     *         documented {@code HINSTANCE > 32} success convention);
     *         {@code false} off Windows or when the shell refused it
     */
    public static boolean shellOpen(String file) {
        if (!IS_WINDOWS) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) ShellExecuteW.invokeExact(
                    MemorySegment.NULL, Ole32.wstr(arena, "open"), Ole32.wstr(arena, file),
                    MemorySegment.NULL, MemorySegment.NULL, SW_SHOWNORMAL);
            return result.address() > 32;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void notifyAssociationsChanged() {
        if (!IS_WINDOWS) return;
        try {
            SHChangeNotify.invokeExact(SHCNE_ASSOCCHANGED, SHCNF_IDLIST,
                    MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable ignored) {
            // Best-effort refresh; a missed notify just delays Explorer catching up.
        }
    }
}
