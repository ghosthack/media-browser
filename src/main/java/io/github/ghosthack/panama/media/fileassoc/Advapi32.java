package io.github.ghosthack.panama.media.fileassoc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import io.github.ghosthack.panama.media.comruntime.Ole32;
import io.github.ghosthack.panama.media.core.Platform;

/**
 * Panama FFM bindings to <b>advapi32.dll</b> — Windows registry access.
 * <p>
 * Backs the file-associations feature's reads/writes under
 * {@code HKEY_CURRENT_USER\Software\Classes}. Unlike the COM-facing bindings
 * in {@code comruntime}/{@code wic}/{@code mediafoundation}, these functions
 * return a Win32 {@code LSTATUS} (0 = success), not an {@code HRESULT}, so
 * failure checking here is a separate {@link #failed(int)}/{@link #check}
 * pair rather than {@code Ole32}'s HRESULT-shaped one.
 * <p>
 * Only functional on Windows; all handles are {@code null} on other OSes so
 * the class loads safely on any platform.
 */
public final class Advapi32 {

    private Advapi32() {}

    // ── OS guard ────────────────────────────────────────────────────────

    private static final boolean IS_WINDOWS = Platform.IS_WINDOWS;

    // ── Predefined HKEYs (sentinel pointer values, not real handles) ──────

    /** HKEY_CURRENT_USER */
    public static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress(0x80000001L);

    // ── Constants ───────────────────────────────────────────────────────

    public static final int KEY_READ = 0x20019;
    public static final int KEY_WRITE = 0x20006;
    private static final int REG_OPTION_NON_VOLATILE = 0;
    private static final int REG_SZ = 1;
    public static final int ERROR_SUCCESS = 0;
    public static final int ERROR_FILE_NOT_FOUND = 2;
    public static final int ERROR_ACCESS_DENIED = 5;

    // ── Linker ─────────────────────────────────────────────────────────

    private static final Linker LINKER = Linker.nativeLinker();

    // ── DLL loading and advapi32 downcalls ────────────────────────────

    private static final SymbolLookup LOADER_LOOKUP;
    private static final MethodHandle RegCreateKeyExW;
    private static final MethodHandle RegOpenKeyExW;
    private static final MethodHandle RegSetValueExW;
    private static final MethodHandle RegQueryValueExW;
    private static final MethodHandle RegDeleteValueW;
    private static final MethodHandle RegDeleteTreeW;
    private static final MethodHandle RegQueryInfoKeyW;
    private static final MethodHandle RegCloseKey;

    static {
        if (IS_WINDOWS) {
            System.loadLibrary("Advapi32");
            LOADER_LOOKUP = SymbolLookup.loaderLookup();

            // LSTATUS RegCreateKeyExW(HKEY, LPCWSTR, DWORD, LPWSTR, DWORD,
            //     REGSAM, const SECURITY_ATTRIBUTES*, PHKEY, LPDWORD)
            RegCreateKeyExW = downcall("RegCreateKeyExW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // LSTATUS RegOpenKeyExW(HKEY, LPCWSTR, DWORD, REGSAM, PHKEY)
            RegOpenKeyExW = downcall("RegOpenKeyExW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            // LSTATUS RegSetValueExW(HKEY, LPCWSTR, DWORD, DWORD, const BYTE*, DWORD)
            RegSetValueExW = downcall("RegSetValueExW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // LSTATUS RegQueryValueExW(HKEY, LPCWSTR, LPDWORD, LPDWORD, LPBYTE, LPDWORD)
            RegQueryValueExW = downcall("RegQueryValueExW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // LSTATUS RegDeleteValueW(HKEY, LPCWSTR)
            RegDeleteValueW = downcall("RegDeleteValueW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // LSTATUS RegDeleteTreeW(HKEY, LPCWSTR)  (Vista+, recursive)
            RegDeleteTreeW = downcall("RegDeleteTreeW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // LSTATUS RegQueryInfoKeyW(HKEY, LPWSTR, LPDWORD, LPDWORD, LPDWORD,
            //     LPDWORD, LPDWORD, LPDWORD, LPDWORD, LPDWORD, LPDWORD, PFILETIME)
            RegQueryInfoKeyW = downcall("RegQueryInfoKeyW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // LSTATUS RegCloseKey(HKEY)
            RegCloseKey = downcall("RegCloseKey",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        } else {
            LOADER_LOOKUP = null;
            RegCreateKeyExW = null;
            RegOpenKeyExW = null;
            RegSetValueExW = null;
            RegQueryValueExW = null;
            RegDeleteValueW = null;
            RegDeleteTreeW = null;
            RegQueryInfoKeyW = null;
            RegCloseKey = null;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOADER_LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    // ── Availability / status helpers ──────────────────────────────────

    /** Returns {@code true} if advapi32.dll was loaded (i.e. running on Windows). */
    public static boolean isAvailable() {
        return IS_WINDOWS;
    }

    /** Returns {@code true} if the LSTATUS indicates failure (anything but {@link #ERROR_SUCCESS}). */
    public static boolean failed(int status) {
        return status != ERROR_SUCCESS;
    }

    /** Throws {@link RegistryException} if the LSTATUS indicates failure. */
    public static void check(int status, String msg) {
        if (failed(status)) throw new RegistryException(msg, status);
    }

    // ── Typed helpers over the raw downcalls ───────────────────────────

    /**
     * Creates (or opens, if it already exists) {@code subKey} under
     * {@code hKey}, non-volatile, with the given access mask.
     *
     * @return the opened key handle; caller must {@link #regCloseKey} it
     */
    public static MemorySegment regCreateKeyEx(Arena arena, MemorySegment hKey,
                                               String subKey, int samDesired) {
        MemorySegment lpSubKey = Ole32.wstr(arena, subKey);
        MemorySegment phkResult = arena.allocate(ValueLayout.ADDRESS);
        try {
            int status = (int) RegCreateKeyExW.invokeExact(hKey, lpSubKey, 0,
                    MemorySegment.NULL, REG_OPTION_NON_VOLATILE, samDesired,
                    MemorySegment.NULL, phkResult, MemorySegment.NULL);
            check(status, "RegCreateKeyExW failed for " + subKey);
            return phkResult.get(ValueLayout.ADDRESS, 0);
        } catch (RegistryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RegistryException("RegCreateKeyExW failed for " + subKey, t);
        }
    }

    /**
     * Opens {@code subKey} under {@code hKey} with the given access mask, or
     * returns {@code null} if it does not exist. Any other failure throws.
     *
     * @return the opened key handle (caller must {@link #regCloseKey} it), or {@code null}
     */
    public static MemorySegment regOpenKeyExOrNull(Arena arena, MemorySegment hKey,
                                                    String subKey, int samDesired) {
        MemorySegment lpSubKey = Ole32.wstr(arena, subKey);
        MemorySegment phkResult = arena.allocate(ValueLayout.ADDRESS);
        try {
            int status = (int) RegOpenKeyExW.invokeExact(hKey, lpSubKey, 0, samDesired, phkResult);
            if (status == ERROR_FILE_NOT_FOUND) return null;
            check(status, "RegOpenKeyExW failed for " + subKey);
            return phkResult.get(ValueLayout.ADDRESS, 0);
        } catch (RegistryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RegistryException("RegOpenKeyExW failed for " + subKey, t);
        }
    }

    /** Writes a {@code REG_SZ} named value ({@code valueName == null} writes the key's default). */
    public static void regSetValueString(Arena arena, MemorySegment hKey,
                                         String valueName, String value) {
        MemorySegment lpValueName = valueName == null ? MemorySegment.NULL : Ole32.wstr(arena, valueName);
        byte[] utf16 = value.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment data = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, data, ValueLayout.JAVA_BYTE, 0, utf16.length);
        data.set(ValueLayout.JAVA_SHORT, utf16.length, (short) 0);
        try {
            int status = (int) RegSetValueExW.invokeExact(hKey, lpValueName, 0, REG_SZ,
                    data, (int) (utf16.length + 2L));
            check(status, "RegSetValueExW failed for " + (valueName == null ? "(Default)" : valueName));
        } catch (RegistryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RegistryException("RegSetValueExW failed for "
                    + (valueName == null ? "(Default)" : valueName), t);
        }
    }

    /**
     * Reads a {@code REG_SZ} named value ({@code valueName == null} reads the
     * key's default).
     *
     * @return the value, or {@code null} if the value does not exist
     */
    public static String regQueryValueString(Arena arena, MemorySegment hKey, String valueName) {
        MemorySegment lpValueName = valueName == null ? MemorySegment.NULL : Ole32.wstr(arena, valueName);
        MemorySegment lpType = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment lpcbData = arena.allocate(ValueLayout.JAVA_INT);
        try {
            // First call: discover the required buffer size.
            int status = (int) RegQueryValueExW.invokeExact(hKey, lpValueName,
                    MemorySegment.NULL, lpType, MemorySegment.NULL, lpcbData);
            if (status == ERROR_FILE_NOT_FOUND) return null;
            check(status, "RegQueryValueExW (size probe) failed for " + valueName);
            int size = lpcbData.get(ValueLayout.JAVA_INT, 0);
            if (size <= 0) return "";
            MemorySegment data = arena.allocate(size);
            status = (int) RegQueryValueExW.invokeExact(hKey, lpValueName,
                    MemorySegment.NULL, lpType, data, lpcbData);
            if (status == ERROR_FILE_NOT_FOUND) return null;
            check(status, "RegQueryValueExW failed for " + valueName);
            return readWstr(data, lpcbData.get(ValueLayout.JAVA_INT, 0));
        } catch (RegistryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RegistryException("RegQueryValueExW failed for " + valueName, t);
        }
    }

    /** Deletes a named value ({@code valueName == null} deletes the key's default); a no-op if absent. */
    public static void regDeleteValue(Arena arena, MemorySegment hKey, String valueName) {
        MemorySegment lpValueName = valueName == null ? MemorySegment.NULL : Ole32.wstr(arena, valueName);
        try {
            int status = (int) RegDeleteValueW.invokeExact(hKey, lpValueName);
            if (status != ERROR_FILE_NOT_FOUND) {
                check(status, "RegDeleteValueW failed for " + valueName);
            }
        } catch (RegistryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RegistryException("RegDeleteValueW failed for " + valueName, t);
        }
    }

    /** Recursively deletes {@code subKey} and everything under it; a no-op if it does not exist. */
    public static void regDeleteTree(Arena arena, MemorySegment hKey, String subKey) {
        MemorySegment lpSubKey = Ole32.wstr(arena, subKey);
        try {
            int status = (int) RegDeleteTreeW.invokeExact(hKey, lpSubKey);
            if (status != ERROR_FILE_NOT_FOUND) {
                check(status, "RegDeleteTreeW failed for " + subKey);
            }
        } catch (RegistryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RegistryException("RegDeleteTreeW failed for " + subKey, t);
        }
    }

    /**
     * Returns {@code true} if the open key {@code hKey} has zero subkeys and
     * zero values — the safe precondition for deleting a container key a
     * cleanup left behind, without risking another app's data that might
     * also live under the same key.
     */
    public static boolean regKeyIsEmpty(Arena arena, MemorySegment hKey) {
        MemorySegment subKeyCount = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment valueCount = arena.allocate(ValueLayout.JAVA_INT);
        try {
            int status = (int) RegQueryInfoKeyW.invokeExact(hKey,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    subKeyCount, MemorySegment.NULL, MemorySegment.NULL,
                    valueCount, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL);
            check(status, "RegQueryInfoKeyW failed");
            return subKeyCount.get(ValueLayout.JAVA_INT, 0) == 0
                    && valueCount.get(ValueLayout.JAVA_INT, 0) == 0;
        } catch (RegistryException e) {
            throw e;
        } catch (Throwable t) {
            throw new RegistryException("RegQueryInfoKeyW failed", t);
        }
    }

    /** Closes a key handle. Safe no-op for {@code null}/{@link MemorySegment#NULL}. */
    public static void regCloseKey(MemorySegment hKey) {
        if (hKey == null || MemorySegment.NULL.equals(hKey)) return;
        try {
            int ignored = (int) RegCloseKey.invokeExact(hKey);
        } catch (Throwable ignored) { /* native close cannot throw */ }
    }

    // ── Wide-string read helper (query results come back as raw bytes) ────

    /** Decodes a UTF-16LE registry value buffer, trimming any trailing NUL. */
    private static String readWstr(MemorySegment buf, long byteLen) {
        if (byteLen <= 0) return "";
        byte[] bytes = new byte[(int) byteLen];
        MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) byteLen);
        String s = new String(bytes, StandardCharsets.UTF_16LE);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }
}
