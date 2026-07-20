package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.DecodeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Thin Panama wrappers around {@code CGColorSpace*} constructors from
 * CoreGraphics.framework.
 * <p>
 * Returned {@code CGColorSpaceRef} values are owned by the caller — release
 * with
 * {@link io.github.ghosthack.panama.media.corefoundation.CoreFoundation#cfRelease}.
 */
public final class CGColorSpace {
    private CGColorSpace() {}

    private static final MethodHandle H_CREATE_DEVICE_RGB;

    static {
        MethodHandle createDeviceRGB = null;
        if (Frameworks.AVAILABLE) {
            try {
                // CGColorSpaceRef CGColorSpaceCreateDeviceRGB(void)
                createDeviceRGB = Frameworks.downcall("CGColorSpaceCreateDeviceRGB",
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
            } catch (Throwable ignored) { /* stays null */ }
        }
        H_CREATE_DEVICE_RGB = createDeviceRGB;
    }

    /**
     * Wraps {@code CGColorSpaceCreateDeviceRGB}. Caller owns the returned
     * color space and must {@code CFRelease} it when done.
     */
    public static MemorySegment createDeviceRGB() {
        try {
            return (MemorySegment) H_CREATE_DEVICE_RGB.invokeExact();
        } catch (Throwable t) {
            throw new DecodeException("CGColorSpaceCreateDeviceRGB failed", t);
        }
    }
}
