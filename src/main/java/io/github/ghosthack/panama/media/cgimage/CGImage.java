package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.DecodeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Thin Panama wrappers around the {@code CGImage*} accessors from
 * CoreGraphics.framework. These operate on {@code CGImageRef} values
 * produced by e.g. {@link CGImageSource#createThumbnailAtIndex} or
 * {@link CGBitmapContext#createImage}.
 * <p>
 * Inputs are opaque {@code CGImageRef} pointers. The caller owns the
 * reference and must {@code CFRelease} it when done (see
 * {@link io.github.ghosthack.panama.media.corefoundation.CoreFoundation#cfRelease}).
 */
public final class CGImage {
    private CGImage() {}

    private static final MethodHandle H_GET_WIDTH;
    private static final MethodHandle H_GET_HEIGHT;

    static {
        MethodHandle getWidth = null;
        MethodHandle getHeight = null;
        if (Frameworks.AVAILABLE) {
            try {
                // size_t CGImageGetWidth(CGImageRef)
                getWidth = Frameworks.downcall("CGImageGetWidth",
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                // size_t CGImageGetHeight(CGImageRef)
                getHeight = Frameworks.downcall("CGImageGetHeight",
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            } catch (Throwable ignored) { /* stays null */ }
        }
        H_GET_WIDTH = getWidth;
        H_GET_HEIGHT = getHeight;
    }

    /** Wraps {@code CGImageGetWidth}. */
    public static long getWidth(MemorySegment image) {
        try {
            return (long) H_GET_WIDTH.invokeExact(image);
        } catch (Throwable t) {
            throw new DecodeException("CGImageGetWidth failed", t);
        }
    }

    /** Wraps {@code CGImageGetHeight}. */
    public static long getHeight(MemorySegment image) {
        try {
            return (long) H_GET_HEIGHT.invokeExact(image);
        } catch (Throwable t) {
            throw new DecodeException("CGImageGetHeight failed", t);
        }
    }
}
