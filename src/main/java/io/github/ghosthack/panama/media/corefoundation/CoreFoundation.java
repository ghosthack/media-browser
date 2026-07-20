package io.github.ghosthack.panama.media.corefoundation;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.core.Platform;

/**
 * Panama FFM bindings to macOS CoreFoundation and the Objective-C runtime.
 * <p>
 * Shared base module for Apple platform utilities used by higher-level modules
 * (CGImage, AVFoundation, ImageIO).
 * <p>
 * All handles are {@code null} on non-macOS platforms so the class loads safely
 * on any OS. Call {@link #isAvailable()} before using any binding.
 * <p>
 * Thread-safe when each thread uses its own {@link Arena}.
 */
public final class CoreFoundation {

    private CoreFoundation() {}

    // ── OS guard ────────────────────────────────────────────────────────

    private static final boolean IS_MACOS = Platform.IS_MAC;

    // ── Constants ───────────────────────────────────────────────────────

    /** kCFStringEncodingUTF8 = 0x08000100 */
    private static final int kCFStringEncodingUTF8 = 0x08000100;

    /** kCFURLPOSIXPathStyle = 0 */
    private static final int kCFURLPOSIXPathStyle = 0;

    /** kCFNumberIntType = 9 (C {@code int}, 32-bit signed). */
    private static final int kCFNumberIntType = 9;

    /** kCFNumberLongLongType = 11 (C {@code long long}, 64-bit signed). */
    private static final int kCFNumberLongLongType = 11;

    /** kCFNumberDoubleType = 13 (C {@code double}, 64-bit). */
    private static final int kCFNumberDoubleType = 13;

    // ── Linker ──────────────────────────────────────────────────────────

    private static final Linker LINKER = Linker.nativeLinker();

    // ── Symbol lookup (shared with leaf modules via lookup()) ───────────

    private static final SymbolLookup LOOKUP;

    // ── CoreFoundation method handles ──────────────────────────────────

    private static final MethodHandle H_CF_DATA_CREATE_NO_COPY;
    private static final MethodHandle H_CF_RELEASE;
    private static final MethodHandle H_CF_RETAIN;
    private static final MethodHandle H_CF_STRING_CREATE_WITH_CSTRING;
    private static final MethodHandle H_CF_URL_CREATE_WITH_FS_PATH;
    private static final MethodHandle H_CF_NUMBER_CREATE;
    private static final MethodHandle H_CF_NUMBER_GET_VALUE;
    private static final MethodHandle H_CF_DICTIONARY_CREATE;
    private static final MethodHandle H_CF_DICTIONARY_GET_VALUE;
    private static final MethodHandle H_CF_DATA_CREATE_MUTABLE;
    private static final MethodHandle H_CF_DATA_GET_LENGTH;
    private static final MethodHandle H_CF_DATA_GET_BYTE_PTR;

    // Introspection / enumeration — added to support readProperties-style callers.
    private static final MethodHandle H_CF_GET_TYPE_ID;
    private static final MethodHandle H_CF_DICTIONARY_GET_COUNT;
    private static final MethodHandle H_CF_DICTIONARY_GET_KEYS_AND_VALUES;
    private static final MethodHandle H_CF_BOOLEAN_GET_VALUE;
    private static final MethodHandle H_CF_NUMBER_IS_FLOAT_TYPE;
    private static final MethodHandle H_CF_STRING_GET_LENGTH;
    private static final MethodHandle H_CF_STRING_GET_MAX_SIZE;
    private static final MethodHandle H_CF_STRING_GET_CSTRING;
    private static final MethodHandle H_CF_ARRAY_GET_COUNT;
    private static final MethodHandle H_CF_ARRAY_GET_VALUE_AT_INDEX;

    // Cached CFTypeID values, resolved once at init.
    private static final long CF_NUMBER_TYPE_ID;
    private static final long CF_BOOLEAN_TYPE_ID;
    private static final long CF_STRING_TYPE_ID;
    private static final long CF_ARRAY_TYPE_ID;
    private static final long CF_DICTIONARY_TYPE_ID;

    // ── CoreFoundation constant symbols ────────────────────────────────

    private static final MemorySegment K_CF_ALLOCATOR_NULL;
    private static final MemorySegment K_CF_ALLOCATOR_DEFAULT;
    private static final MemorySegment K_CF_BOOLEAN_TRUE;
    private static final MemorySegment K_CF_BOOLEAN_FALSE;
    private static final MemorySegment K_CF_TYPE_DICTIONARY_KEY_CALLBACKS;
    private static final MemorySegment K_CF_TYPE_DICTIONARY_VALUE_CALLBACKS;

    // ── Objective-C runtime method handles ─────────────────────────────

    private static final MethodHandle H_OBJC_GET_CLASS;
    private static final MethodHandle H_SEL_REGISTER_NAME;
    private static final MemorySegment OBJC_MSG_SEND_ADDR;
    private static final MethodHandle H_OBJC_AUTORELEASE_POOL_PUSH;
    private static final MethodHandle H_OBJC_AUTORELEASE_POOL_POP;

    // ── Static initializer ─────────────────────────────────────────────

    static {
        if (IS_MACOS) {
            System.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
            System.load("/usr/lib/libobjc.A.dylib");
            LOOKUP = SymbolLookup.loaderLookup();

            // ── CoreFoundation functions ───────────────────────────────

            // CFDataRef CFDataCreateWithBytesNoCopy(CFAllocatorRef, const UInt8*, CFIndex, CFAllocatorRef)
            H_CF_DATA_CREATE_NO_COPY = downcall("CFDataCreateWithBytesNoCopy",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // void CFRelease(CFTypeRef)
            H_CF_RELEASE = downcall("CFRelease",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // CFTypeRef CFRetain(CFTypeRef)
            H_CF_RETAIN = downcall("CFRetain",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // CFStringRef CFStringCreateWithCString(CFAllocatorRef, const char*, CFStringEncoding)
            H_CF_STRING_CREATE_WITH_CSTRING = downcall("CFStringCreateWithCString",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // CFURLRef CFURLCreateWithFileSystemPath(CFAllocatorRef, CFStringRef, CFURLPathStyle, Boolean)
            H_CF_URL_CREATE_WITH_FS_PATH = downcall("CFURLCreateWithFileSystemPath",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_BOOLEAN));

            // CFNumberRef CFNumberCreate(CFAllocatorRef, CFNumberType, const void*)
            H_CF_NUMBER_CREATE = downcall("CFNumberCreate",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // Boolean CFNumberGetValue(CFNumberRef, CFNumberType, void*)
            H_CF_NUMBER_GET_VALUE = downcall("CFNumberGetValue",
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // CFDictionaryRef CFDictionaryCreate(CFAllocatorRef, const void**, const void**,
            //     CFIndex, const CFDictionaryKeyCallBacks*, const CFDictionaryValueCallBacks*)
            H_CF_DICTIONARY_CREATE = downcall("CFDictionaryCreate",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // const void* CFDictionaryGetValue(CFDictionaryRef, const void*)
            H_CF_DICTIONARY_GET_VALUE = downcall("CFDictionaryGetValue",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // CFMutableDataRef CFDataCreateMutable(CFAllocatorRef, CFIndex capacity)
            H_CF_DATA_CREATE_MUTABLE = downcall("CFDataCreateMutable",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            // CFIndex CFDataGetLength(CFDataRef)
            H_CF_DATA_GET_LENGTH = downcall("CFDataGetLength",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // const UInt8* CFDataGetBytePtr(CFDataRef)
            H_CF_DATA_GET_BYTE_PTR = downcall("CFDataGetBytePtr",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // ── CoreFoundation introspection + enumeration ─────────────

            // CFTypeID CFGetTypeID(CFTypeRef)
            H_CF_GET_TYPE_ID = downcall("CFGetTypeID",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // CFIndex CFDictionaryGetCount(CFDictionaryRef)
            H_CF_DICTIONARY_GET_COUNT = downcall("CFDictionaryGetCount",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // void CFDictionaryGetKeysAndValues(CFDictionaryRef, const void** keys, const void** values)
            H_CF_DICTIONARY_GET_KEYS_AND_VALUES = downcall("CFDictionaryGetKeysAndValues",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // Boolean CFBooleanGetValue(CFBooleanRef)
            H_CF_BOOLEAN_GET_VALUE = downcall("CFBooleanGetValue",
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));

            // Boolean CFNumberIsFloatType(CFNumberRef)
            H_CF_NUMBER_IS_FLOAT_TYPE = downcall("CFNumberIsFloatType",
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));

            // CFIndex CFStringGetLength(CFStringRef)
            H_CF_STRING_GET_LENGTH = downcall("CFStringGetLength",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // CFIndex CFStringGetMaximumSizeForEncoding(CFIndex length, CFStringEncoding encoding)
            H_CF_STRING_GET_MAX_SIZE = downcall("CFStringGetMaximumSizeForEncoding",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

            // Boolean CFStringGetCString(CFStringRef, char* buf, CFIndex bufferSize, CFStringEncoding)
            H_CF_STRING_GET_CSTRING = downcall("CFStringGetCString",
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

            // CFIndex CFArrayGetCount(CFArrayRef)
            H_CF_ARRAY_GET_COUNT = downcall("CFArrayGetCount",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // const void* CFArrayGetValueAtIndex(CFArrayRef, CFIndex)
            H_CF_ARRAY_GET_VALUE_AT_INDEX = downcall("CFArrayGetValueAtIndex",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

            // Resolve each CFTypeID once — they're stable for the process lifetime.
            CF_NUMBER_TYPE_ID     = callTypeIdFn("CFNumberGetTypeID");
            CF_BOOLEAN_TYPE_ID    = callTypeIdFn("CFBooleanGetTypeID");
            CF_STRING_TYPE_ID     = callTypeIdFn("CFStringGetTypeID");
            CF_ARRAY_TYPE_ID      = callTypeIdFn("CFArrayGetTypeID");
            CF_DICTIONARY_TYPE_ID = callTypeIdFn("CFDictionaryGetTypeID");

            // ── CoreFoundation constants ───────────────────────────────

            K_CF_ALLOCATOR_NULL    = loadConstPtr("kCFAllocatorNull");
            K_CF_ALLOCATOR_DEFAULT = loadConstPtr("kCFAllocatorDefault");
            K_CF_BOOLEAN_TRUE      = loadConstPtr("kCFBooleanTrue");
            K_CF_BOOLEAN_FALSE     = loadConstPtr("kCFBooleanFalse");
            // struct-valued globals — raw address, no dereference
            K_CF_TYPE_DICTIONARY_KEY_CALLBACKS   = loadSymbolAddr("kCFTypeDictionaryKeyCallBacks");
            K_CF_TYPE_DICTIONARY_VALUE_CALLBACKS = loadSymbolAddr("kCFTypeDictionaryValueCallBacks");

            // ── Objective-C runtime ────────────────────────────────────

            // Class objc_getClass(const char* name)
            H_OBJC_GET_CLASS = downcall("objc_getClass",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // SEL sel_registerName(const char* str)
            H_SEL_REGISTER_NAME = downcall("sel_registerName",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // objc_msgSend address — callers create their own downcall handles
            OBJC_MSG_SEND_ADDR = LOOKUP.find("objc_msgSend")
                    .orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend not found"));

            // void* objc_autoreleasePoolPush(void)
            H_OBJC_AUTORELEASE_POOL_PUSH = downcall("objc_autoreleasePoolPush",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));

            // void objc_autoreleasePoolPop(void* pool)
            H_OBJC_AUTORELEASE_POOL_POP = downcall("objc_autoreleasePoolPop",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        } else {
            LOOKUP = null;

            H_CF_DATA_CREATE_NO_COPY = null;
            H_CF_RELEASE = null;
            H_CF_RETAIN = null;
            H_CF_STRING_CREATE_WITH_CSTRING = null;
            H_CF_URL_CREATE_WITH_FS_PATH = null;
            H_CF_NUMBER_CREATE = null;
            H_CF_NUMBER_GET_VALUE = null;
            H_CF_DICTIONARY_CREATE = null;
            H_CF_DICTIONARY_GET_VALUE = null;
            H_CF_DATA_CREATE_MUTABLE = null;
            H_CF_DATA_GET_LENGTH = null;
            H_CF_DATA_GET_BYTE_PTR = null;

            H_CF_GET_TYPE_ID = null;
            H_CF_DICTIONARY_GET_COUNT = null;
            H_CF_DICTIONARY_GET_KEYS_AND_VALUES = null;
            H_CF_BOOLEAN_GET_VALUE = null;
            H_CF_NUMBER_IS_FLOAT_TYPE = null;
            H_CF_STRING_GET_LENGTH = null;
            H_CF_STRING_GET_MAX_SIZE = null;
            H_CF_STRING_GET_CSTRING = null;
            H_CF_ARRAY_GET_COUNT = null;
            H_CF_ARRAY_GET_VALUE_AT_INDEX = null;

            CF_NUMBER_TYPE_ID = 0L;
            CF_BOOLEAN_TYPE_ID = 0L;
            CF_STRING_TYPE_ID = 0L;
            CF_ARRAY_TYPE_ID = 0L;
            CF_DICTIONARY_TYPE_ID = 0L;

            K_CF_ALLOCATOR_NULL = null;
            K_CF_ALLOCATOR_DEFAULT = null;
            K_CF_BOOLEAN_TRUE = null;
            K_CF_BOOLEAN_FALSE = null;
            K_CF_TYPE_DICTIONARY_KEY_CALLBACKS = null;
            K_CF_TYPE_DICTIONARY_VALUE_CALLBACKS = null;

            H_OBJC_GET_CLASS = null;
            H_SEL_REGISTER_NAME = null;
            OBJC_MSG_SEND_ADDR = null;
            H_OBJC_AUTORELEASE_POOL_PUSH = null;
            H_OBJC_AUTORELEASE_POOL_POP = null;
        }
    }

    // ── Public API: availability ────────────────────────────────────────

    /**
     * Returns {@code true} if CoreFoundation and the Objective-C runtime
     * were loaded successfully (i.e., the current OS is macOS).
     */
    public static boolean isAvailable() {
        return IS_MACOS;
    }

    // ── Public API: CoreFoundation functions ────────────────────────────

    /**
     * Wraps {@code CFDataCreateWithBytesNoCopy}.
     *
     * @param allocator   CF allocator (typically {@link #kCFAllocatorDefault()} or {@code MemorySegment.NULL})
     * @param bytes       pointer to the byte buffer
     * @param length      number of bytes
     * @param deallocator byte deallocator (use {@link #kCFAllocatorNull()} to suppress deallocation)
     * @return a new CFDataRef, or {@code MemorySegment.NULL} on failure
     */
    public static MemorySegment cfDataCreateNoCopy(MemorySegment allocator, MemorySegment bytes,
                                                   long length, MemorySegment deallocator) {
        try {
            return (MemorySegment) H_CF_DATA_CREATE_NO_COPY.invokeExact(
                    allocator, bytes, length, deallocator);
        } catch (Throwable t) {
            throw new DecodeException("CFDataCreateWithBytesNoCopy failed", t);
        }
    }

    /**
     * Releases a CoreFoundation object. Safe to call with {@code null} or
     * {@code MemorySegment.NULL} — the call is a no-op in those cases.
     *
     * @param ref the CF reference to release
     */
    public static void cfRelease(MemorySegment ref) {
        if (ref != null && !MemorySegment.NULL.equals(ref)) {
            try {
                H_CF_RELEASE.invokeExact(ref);
            } catch (Throwable ignored) { /* native release cannot throw */ }
        }
    }

    /**
     * Wraps {@code CFRetain}.
     *
     * @param ref the CF reference to retain
     * @return the retained reference
     */
    public static MemorySegment cfRetain(MemorySegment ref) {
        try {
            return (MemorySegment) H_CF_RETAIN.invokeExact(ref);
        } catch (Throwable t) {
            throw new DecodeException("CFRetain failed", t);
        }
    }

    /**
     * Creates a CFString from a Java string using UTF-8 encoding.
     * Wraps {@code CFStringCreateWithCString} with {@code kCFStringEncodingUTF8}.
     * <p>
     * Caller must release the returned CFString via {@link #cfRelease(MemorySegment)}.
     *
     * @param arena arena for the temporary C string allocation
     * @param str   the Java string to convert
     * @return a new CFStringRef
     */
    public static MemorySegment cfStringCreate(Arena arena, String str) {
        try {
            byte[] utf8 = str.getBytes(StandardCharsets.UTF_8);
            MemorySegment cstr = arena.allocate(utf8.length + 1L);
            MemorySegment.copy(utf8, 0, cstr, ValueLayout.JAVA_BYTE, 0, utf8.length);
            cstr.set(ValueLayout.JAVA_BYTE, utf8.length, (byte) 0);
            return (MemorySegment) H_CF_STRING_CREATE_WITH_CSTRING.invokeExact(
                    MemorySegment.NULL, cstr, kCFStringEncodingUTF8);
        } catch (Throwable t) {
            throw new DecodeException("CFStringCreateWithCString failed", t);
        }
    }

    /**
     * Creates a CFURL from a POSIX file path string.
     * <p>
     * Caller must release the returned CFURL via {@link #cfRelease(MemorySegment)}.
     *
     * @param arena arena for temporary allocations
     * @param path  POSIX file path
     * @return a new CFURLRef
     */
    public static MemorySegment cfUrlCreate(Arena arena, String path) {
        try {
            MemorySegment cfStr = cfStringCreate(arena, path);
            try {
                return (MemorySegment) H_CF_URL_CREATE_WITH_FS_PATH.invokeExact(
                        MemorySegment.NULL, cfStr, (long) kCFURLPOSIXPathStyle, false);
            } finally {
                cfRelease(cfStr);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("CFURLCreateWithFileSystemPath failed", t);
        }
    }

    /**
     * Creates a CFNumber holding a 32-bit int value ({@code kCFNumberIntType = 9}).
     * <p>
     * Caller must release the returned CFNumber via {@link #cfRelease(MemorySegment)}.
     *
     * @param arena arena for the temporary int buffer
     * @param value the int value
     * @return a new CFNumberRef
     */
    public static MemorySegment cfNumberCreateInt(Arena arena, int value) {
        try {
            MemorySegment buf = arena.allocate(ValueLayout.JAVA_INT);
            buf.set(ValueLayout.JAVA_INT, 0, value);
            return (MemorySegment) H_CF_NUMBER_CREATE.invokeExact(
                    MemorySegment.NULL, (long) kCFNumberIntType, buf);
        } catch (Throwable t) {
            throw new DecodeException("CFNumberCreate failed", t);
        }
    }

    /**
     * Creates a CFNumber holding a 64-bit double value ({@code kCFNumberDoubleType = 13}).
     * <p>
     * Caller must release the returned CFNumber via {@link #cfRelease(MemorySegment)}.
     *
     * @param arena arena for the temporary double buffer
     * @param value the double value
     * @return a new CFNumberRef
     */
    public static MemorySegment cfNumberCreateDouble(Arena arena, double value) {
        try {
            MemorySegment buf = arena.allocate(ValueLayout.JAVA_DOUBLE);
            buf.set(ValueLayout.JAVA_DOUBLE, 0, value);
            return (MemorySegment) H_CF_NUMBER_CREATE.invokeExact(
                    MemorySegment.NULL, (long) kCFNumberDoubleType, buf);
        } catch (Throwable t) {
            throw new DecodeException("CFNumberCreate(double) failed", t);
        }
    }

    /**
     * Creates an empty CFMutableData with the given capacity hint
     * (0 means unbounded). Caller must release via {@link #cfRelease(MemorySegment)}.
     *
     * @param capacity hint for initial allocation, or 0 for unbounded
     * @return a new CFMutableDataRef
     */
    public static MemorySegment cfDataCreateMutable(long capacity) {
        try {
            return (MemorySegment) H_CF_DATA_CREATE_MUTABLE.invokeExact(
                    MemorySegment.NULL, capacity);
        } catch (Throwable t) {
            throw new DecodeException("CFDataCreateMutable failed", t);
        }
    }

    /** Returns the number of bytes stored in a CFDataRef. */
    public static long cfDataGetLength(MemorySegment data) {
        try {
            return (long) H_CF_DATA_GET_LENGTH.invokeExact(data);
        } catch (Throwable t) {
            throw new DecodeException("CFDataGetLength failed", t);
        }
    }

    /**
     * Returns a read-only pointer to the backing byte buffer of a CFDataRef.
     * The pointer is valid until the CFData is released or mutated.
     */
    public static MemorySegment cfDataGetBytePtr(MemorySegment data) {
        try {
            return (MemorySegment) H_CF_DATA_GET_BYTE_PTR.invokeExact(data);
        } catch (Throwable t) {
            throw new DecodeException("CFDataGetBytePtr failed", t);
        }
    }

    /**
     * Extracts a 32-bit int from a CFNumber ({@code kCFNumberIntType = 9}).
     *
     * @param number the CFNumberRef
     * @param buf    a segment with at least 4 bytes to receive the value
     * @return {@code true} if the conversion succeeded
     */
    public static boolean cfNumberGetInt(MemorySegment number, MemorySegment buf) {
        try {
            return (boolean) H_CF_NUMBER_GET_VALUE.invokeExact(number, (long) kCFNumberIntType, buf);
        } catch (Throwable t) {
            throw new DecodeException("CFNumberGetValue failed", t);
        }
    }

    /**
     * Extracts a {@code double} from a CFNumber ({@code kCFNumberDoubleType = 13}).
     *
     * @param number the CFNumberRef
     * @param buf    a segment with at least 8 bytes to receive the value
     * @return {@code true} if the conversion succeeded
     */
    public static boolean cfNumberGetDouble(MemorySegment number, MemorySegment buf) {
        try {
            return (boolean) H_CF_NUMBER_GET_VALUE.invokeExact(number, (long) kCFNumberDoubleType, buf);
        } catch (Throwable t) {
            throw new DecodeException("CFNumberGetValue failed", t);
        }
    }

    /**
     * Creates an immutable CFDictionary from parallel key and value arrays.
     * Uses {@code kCFTypeDictionaryKeyCallBacks} and {@code kCFTypeDictionaryValueCallBacks}.
     * <p>
     * Caller must release the returned dictionary via {@link #cfRelease(MemorySegment)}.
     *
     * @param arena  arena for temporary pointer arrays
     * @param keys   array of CFTypeRef keys
     * @param values array of CFTypeRef values (must be same length as keys)
     * @return a new CFDictionaryRef
     */
    public static MemorySegment cfDictionaryCreate(Arena arena, MemorySegment[] keys,
                                                   MemorySegment[] values) {
        try {
            int count = keys.length;
            MemorySegment keysBuf = arena.allocate(ValueLayout.ADDRESS, count);
            MemorySegment valsBuf = arena.allocate(ValueLayout.ADDRESS, count);
            for (int i = 0; i < count; i++) {
                keysBuf.setAtIndex(ValueLayout.ADDRESS, i, keys[i]);
                valsBuf.setAtIndex(ValueLayout.ADDRESS, i, values[i]);
            }
            return (MemorySegment) H_CF_DICTIONARY_CREATE.invokeExact(
                    MemorySegment.NULL, keysBuf, valsBuf, (long) count,
                    K_CF_TYPE_DICTIONARY_KEY_CALLBACKS, K_CF_TYPE_DICTIONARY_VALUE_CALLBACKS);
        } catch (Throwable t) {
            throw new DecodeException("CFDictionaryCreate failed", t);
        }
    }

    /**
     * Wraps {@code CFDictionaryGetValue}. Returns the value for a key, or
     * {@code MemorySegment.NULL} if the key is not present.
     *
     * @param dict the CFDictionaryRef
     * @param key  the key to look up
     * @return the value, or {@code MemorySegment.NULL}
     */
    public static MemorySegment cfDictionaryGetValue(MemorySegment dict, MemorySegment key) {
        try {
            return (MemorySegment) H_CF_DICTIONARY_GET_VALUE.invokeExact(dict, key);
        } catch (Throwable t) {
            throw new DecodeException("CFDictionaryGetValue failed", t);
        }
    }

    // ── Public API: CoreFoundation constants ────────────────────────────

    /** Returns the {@code kCFAllocatorNull} singleton. */
    public static MemorySegment kCFAllocatorNull() {
        return K_CF_ALLOCATOR_NULL;
    }

    /** Returns the {@code kCFAllocatorDefault} singleton (pointer-to-pointer dereference). */
    public static MemorySegment kCFAllocatorDefault() {
        return K_CF_ALLOCATOR_DEFAULT;
    }

    /** Returns the {@code kCFBooleanTrue} singleton. */
    public static MemorySegment kCFBooleanTrue() {
        return K_CF_BOOLEAN_TRUE;
    }

    /** Returns the {@code kCFBooleanFalse} singleton. */
    public static MemorySegment kCFBooleanFalse() {
        return K_CF_BOOLEAN_FALSE;
    }

    /** Returns the address of the {@code kCFTypeDictionaryKeyCallBacks} struct. */
    public static MemorySegment kCFTypeDictionaryKeyCallBacks() {
        return K_CF_TYPE_DICTIONARY_KEY_CALLBACKS;
    }

    /** Returns the address of the {@code kCFTypeDictionaryValueCallBacks} struct. */
    public static MemorySegment kCFTypeDictionaryValueCallBacks() {
        return K_CF_TYPE_DICTIONARY_VALUE_CALLBACKS;
    }

    // ── Public API: Objective-C runtime ─────────────────────────────────

    /**
     * Wraps {@code objc_getClass}.
     *
     * @param arena arena for the temporary C string
     * @param name  Objective-C class name (e.g. {@code "NSObject"})
     * @return the Class pointer
     */
    public static MemorySegment objcGetClass(Arena arena, String name) {
        try {
            return (MemorySegment) H_OBJC_GET_CLASS.invokeExact(
                    arena.allocateFrom(name));
        } catch (Throwable t) {
            throw new DecodeException("objc_getClass failed for " + name, t);
        }
    }

    /**
     * Wraps {@code sel_registerName}.
     *
     * @param arena arena for the temporary C string
     * @param name  selector name (e.g. {@code "alloc"})
     * @return the SEL pointer
     */
    public static MemorySegment selRegisterName(Arena arena, String name) {
        try {
            return (MemorySegment) H_SEL_REGISTER_NAME.invokeExact(
                    arena.allocateFrom(name));
        } catch (Throwable t) {
            throw new DecodeException("sel_registerName failed for " + name, t);
        }
    }

    /**
     * Creates a downcall handle for {@code objc_msgSend} with the given
     * {@link FunctionDescriptor}. The descriptor must include the implicit
     * {@code (id self, SEL _cmd, ...)} parameters.
     *
     * @param fd the full function descriptor including self and _cmd
     * @return a MethodHandle bound to objc_msgSend with the given signature
     */
    public static MethodHandle msgSend(FunctionDescriptor fd) {
        return LINKER.downcallHandle(OBJC_MSG_SEND_ADDR, fd);
    }

    /**
     * Pushes an Objective-C autorelease pool.
     *
     * @return an opaque pool token to pass to {@link #autoreleasePoolPop(MemorySegment)}
     */
    public static MemorySegment autoreleasePoolPush() {
        try {
            return (MemorySegment) H_OBJC_AUTORELEASE_POOL_PUSH.invokeExact();
        } catch (Throwable t) {
            throw new DecodeException("objc_autoreleasePoolPush failed", t);
        }
    }

    /**
     * Pops an Objective-C autorelease pool, releasing all objects added since
     * the corresponding push.
     *
     * @param pool the opaque pool token returned by {@link #autoreleasePoolPush()}
     */
    public static void autoreleasePoolPop(MemorySegment pool) {
        try {
            H_OBJC_AUTORELEASE_POOL_POP.invokeExact(pool);
        } catch (Throwable t) {
            throw new DecodeException("objc_autoreleasePoolPop failed", t);
        }
    }

    // ── Public API: symbol loading helpers ──────────────────────────────

    /**
     * Dereferences a global constant pointer (e.g. {@code extern const CFStringRef kFoo}).
     * The symbol is a pointer-to-pointer in the dylib; this method reads through
     * the indirection to return the actual CFTypeRef value.
     *
     * @param name the symbol name
     * @return the dereferenced pointer value
     */
    public static MemorySegment loadConstPtr(String name) {
        MemorySegment sym = LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return sym.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0);
    }

    /**
     * Returns the raw symbol address for a struct-valued global
     * (e.g. {@code kCFTypeDictionaryKeyCallBacks}). No dereference is performed.
     *
     * @param name the symbol name
     * @return the raw address of the symbol
     */
    public static MemorySegment loadSymbolAddr(String name) {
        return LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
    }

    /**
     * Returns the shared {@link SymbolLookup} for additional lookups by leaf modules.
     * This lookup covers CoreFoundation.framework and libobjc.A.dylib.
     * Leaf modules that load additional frameworks (e.g. ImageIO, AVFoundation)
     * can use their own {@code SymbolLookup.loaderLookup()} after loading those
     * frameworks with {@code System.load}.
     *
     * @return the SymbolLookup, or {@code null} if not on macOS
     */
    public static SymbolLookup lookup() {
        return LOOKUP;
    }

    // ── Public API: introspection + enumeration ─────────────────────────

    /** Wraps {@code CFGetTypeID}. Matches against the {@code isCF*} helpers. */
    public static long cfGetTypeID(MemorySegment ref) {
        try {
            return (long) H_CF_GET_TYPE_ID.invokeExact(ref);
        } catch (Throwable t) {
            throw new DecodeException("CFGetTypeID failed", t);
        }
    }

    /** {@code true} if {@code ref} is a CFNumber. */
    public static boolean isCFNumber(MemorySegment ref) {
        return cfGetTypeID(ref) == CF_NUMBER_TYPE_ID;
    }

    /** {@code true} if {@code ref} is a CFBoolean. */
    public static boolean isCFBoolean(MemorySegment ref) {
        return cfGetTypeID(ref) == CF_BOOLEAN_TYPE_ID;
    }

    /** {@code true} if {@code ref} is a CFString. */
    public static boolean isCFString(MemorySegment ref) {
        return cfGetTypeID(ref) == CF_STRING_TYPE_ID;
    }

    /** {@code true} if {@code ref} is a CFArray. */
    public static boolean isCFArray(MemorySegment ref) {
        return cfGetTypeID(ref) == CF_ARRAY_TYPE_ID;
    }

    /** {@code true} if {@code ref} is a CFDictionary. */
    public static boolean isCFDictionary(MemorySegment ref) {
        return cfGetTypeID(ref) == CF_DICTIONARY_TYPE_ID;
    }

    /** Wraps {@code CFDictionaryGetCount}. */
    public static long cfDictionaryGetCount(MemorySegment dict) {
        try {
            return (long) H_CF_DICTIONARY_GET_COUNT.invokeExact(dict);
        } catch (Throwable t) {
            throw new DecodeException("CFDictionaryGetCount failed", t);
        }
    }

    /**
     * Wraps {@code CFDictionaryGetKeysAndValues}. {@code keysOut} and
     * {@code valuesOut} must each be a pointer buffer sized for at least
     * {@link #cfDictionaryGetCount} addresses.
     */
    public static void cfDictionaryGetKeysAndValues(MemorySegment dict,
                                                    MemorySegment keysOut,
                                                    MemorySegment valuesOut) {
        try {
            H_CF_DICTIONARY_GET_KEYS_AND_VALUES.invokeExact(dict, keysOut, valuesOut);
        } catch (Throwable t) {
            throw new DecodeException("CFDictionaryGetKeysAndValues failed", t);
        }
    }

    /** Wraps {@code CFBooleanGetValue}. */
    public static boolean cfBooleanGetValue(MemorySegment b) {
        try {
            return (boolean) H_CF_BOOLEAN_GET_VALUE.invokeExact(b);
        } catch (Throwable t) {
            throw new DecodeException("CFBooleanGetValue failed", t);
        }
    }

    /**
     * Wraps {@code CFNumberIsFloatType}. Used to pick the target Java type
     * when extracting a {@code CFNumber} — {@code true} → use
     * {@link #cfNumberGetDouble}, {@code false} → use {@link #cfNumberGetLongLong}.
     */
    public static boolean cfNumberIsFloatType(MemorySegment n) {
        try {
            return (boolean) H_CF_NUMBER_IS_FLOAT_TYPE.invokeExact(n);
        } catch (Throwable t) {
            throw new DecodeException("CFNumberIsFloatType failed", t);
        }
    }

    /**
     * Extracts a {@code CFNumber} as a 64-bit signed integer
     * ({@code kCFNumberLongLongType = 11}). Writes into {@code buf}
     * (an 8-byte JAVA_LONG segment).
     */
    public static boolean cfNumberGetLongLong(MemorySegment number, MemorySegment buf) {
        try {
            return (boolean) H_CF_NUMBER_GET_VALUE.invokeExact(number, (long) kCFNumberLongLongType, buf);
        } catch (Throwable t) {
            throw new DecodeException("CFNumberGetValue(LongLong) failed", t);
        }
    }

    /** Wraps {@code CFStringGetLength} (count of UTF-16 code units). */
    public static long cfStringGetLength(MemorySegment s) {
        try {
            return (long) H_CF_STRING_GET_LENGTH.invokeExact(s);
        } catch (Throwable t) {
            throw new DecodeException("CFStringGetLength failed", t);
        }
    }

    /**
     * Converts a {@code CFString} to a Java {@link String} via UTF-8.
     * Returns {@code null} if the string could not be extracted (rare —
     * happens when the buffer sizing heuristic is wrong).
     */
    public static String cfStringToJavaString(MemorySegment s) {
        try (Arena arena = Arena.ofConfined()) {
            long len = cfStringGetLength(s);
            long maxSize = (long) H_CF_STRING_GET_MAX_SIZE.invokeExact(len, kCFStringEncodingUTF8);
            long bufSize = maxSize + 1; // +1 for NUL
            MemorySegment buf = arena.allocate(bufSize);
            boolean ok = (boolean) H_CF_STRING_GET_CSTRING.invokeExact(
                    s, buf, bufSize, kCFStringEncodingUTF8);
            return ok ? buf.getString(0) : null;
        } catch (Throwable t) {
            throw new DecodeException("CFStringGetCString failed", t);
        }
    }

    /** Wraps {@code CFArrayGetCount}. */
    public static long cfArrayGetCount(MemorySegment array) {
        try {
            return (long) H_CF_ARRAY_GET_COUNT.invokeExact(array);
        } catch (Throwable t) {
            throw new DecodeException("CFArrayGetCount failed", t);
        }
    }

    /** Wraps {@code CFArrayGetValueAtIndex}. */
    public static MemorySegment cfArrayGetValueAtIndex(MemorySegment array, long index) {
        try {
            return (MemorySegment) H_CF_ARRAY_GET_VALUE_AT_INDEX.invokeExact(array, index);
        } catch (Throwable t) {
            throw new DecodeException("CFArrayGetValueAtIndex failed", t);
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    /** Resolves a {@code CF<Type>GetTypeID} symbol and invokes it to cache the ID. */
    private static long callTypeIdFn(String name) {
        MethodHandle mh = downcall(name, FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        try {
            return (long) mh.invokeExact();
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
}
