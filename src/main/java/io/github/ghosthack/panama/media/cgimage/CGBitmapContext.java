package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.DecodeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Panama wrappers for {@code CGBitmapContext*} functions. A bitmap context
 * renders into caller-provided pixel memory, which makes it the core
 * primitive for "decode into a specific buffer" and "rasterise a CGImage at
 * custom dimensions" workflows.
 * <p>
 * See {@link BitmapInfo} for the packed {@code CGBitmapInfo |
 * CGImageAlphaInfo} flags accepted by {@link #create}.
 */
public final class CGBitmapContext {
    private CGBitmapContext() {}

    private static final MethodHandle H_CREATE;
    private static final MethodHandle H_CREATE_IMAGE;

    static {
        MethodHandle create = null;
        MethodHandle createImage = null;
        if (Frameworks.AVAILABLE) {
            try {
                // CGContextRef CGBitmapContextCreate(
                //     void* data, size_t width, size_t height, size_t bitsPerComponent,
                //     size_t bytesPerRow, CGColorSpaceRef space, uint32_t bitmapInfo)
                create = Frameworks.downcall("CGBitmapContextCreate",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT));

                // CGImageRef CGBitmapContextCreateImage(CGContextRef)
                createImage = Frameworks.downcall("CGBitmapContextCreateImage",
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            } catch (Throwable ignored) { /* stays null */ }
        }
        H_CREATE = create;
        H_CREATE_IMAGE = createImage;
    }

    /**
     * Wraps {@code CGBitmapContextCreate}. The returned {@code CGContextRef}
     * is owned by the caller — {@code CFRelease} it when done. The caller
     * also owns {@code pixels}: the context writes into it directly, so the
     * backing memory must outlive every draw call.
     *
     * @param pixels           pointer to pixel memory; context writes here
     * @param width            pixel width
     * @param height           pixel height
     * @param bitsPerComponent typically 8
     * @param bytesPerRow      stride in bytes (often {@code width * 4})
     * @param colorSpace       a non-NULL {@code CGColorSpaceRef}
     * @param bitmapInfo       packed alpha + byte-order flags
     *                         (see {@link BitmapInfo})
     * @return a new {@code CGContextRef}, or {@code MemorySegment.NULL} on
     *         failure
     */
    public static MemorySegment create(MemorySegment pixels,
                                       long width, long height,
                                       long bitsPerComponent, long bytesPerRow,
                                       MemorySegment colorSpace, int bitmapInfo) {
        try {
            return (MemorySegment) H_CREATE.invokeExact(
                    pixels, width, height, bitsPerComponent, bytesPerRow,
                    colorSpace, bitmapInfo);
        } catch (Throwable t) {
            throw new DecodeException("CGBitmapContextCreate failed", t);
        }
    }

    /**
     * Wraps {@code CGBitmapContextCreateImage}. Returns a snapshot of the
     * context's current contents as a {@code CGImageRef}, owned by the
     * caller.
     */
    public static MemorySegment createImage(MemorySegment context) {
        try {
            return (MemorySegment) H_CREATE_IMAGE.invokeExact(context);
        } catch (Throwable t) {
            throw new DecodeException("CGBitmapContextCreateImage failed", t);
        }
    }

    /**
     * Packed {@code CGBitmapInfo} values passed as the last argument to
     * {@link #create}. Fields here spell out the exact constant values from
     * {@code CGImage.h} / {@code CGBitmapContext.h} to avoid magic numbers
     * at call sites.
     */
    public static final class BitmapInfo {
        private BitmapInfo() {}

        // CGImageAlphaInfo (low 5 bits, masked by kCGBitmapAlphaInfoMask = 0x1F)
        public static final int kCGImageAlphaNone               = 0;
        public static final int kCGImageAlphaPremultipliedLast  = 1;
        public static final int kCGImageAlphaPremultipliedFirst = 2;
        public static final int kCGImageAlphaLast               = 3;
        public static final int kCGImageAlphaFirst              = 4;
        public static final int kCGImageAlphaNoneSkipLast       = 5;
        public static final int kCGImageAlphaNoneSkipFirst      = 6;

        // CGBitmapByteOrder (bits 12–15, masked by kCGBitmapByteOrderMask = 0x7000)
        public static final int kCGBitmapByteOrderDefault    = 0;
        public static final int kCGBitmapByteOrder16Little   = 1 << 12;
        public static final int kCGBitmapByteOrder32Little   = 2 << 12;
        public static final int kCGBitmapByteOrder16Big      = 3 << 12;
        public static final int kCGBitmapByteOrder32Big      = 4 << 12;

        /**
         * {@code kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little}
         * — the native byte layout for BGRA premultiplied pixels on Intel
         * and Apple Silicon. This is the format the rest of {@code panama-media}
         * produces and consumes.
         */
        public static final int BGRA_PREMULTIPLIED_LITTLE_ENDIAN =
                kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little;
    }
}
