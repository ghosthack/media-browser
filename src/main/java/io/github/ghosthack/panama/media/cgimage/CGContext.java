package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.DecodeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Panama wrappers for {@code CGContext*} drawing operations. See
 * {@link CGBitmapContext} for the bitmap-specific constructors.
 * <p>
 * {@link InterpolationQuality} wraps the {@code CGInterpolationQuality} enum
 * accepted by {@link #setInterpolationQuality}; it is already a public
 * sibling type — see that class for the numeric values.
 */
public final class CGContext {
    private CGContext() {}

    private static final MethodHandle H_DRAW_IMAGE;
    private static final MethodHandle H_SET_INTERPOLATION_QUALITY;

    static {
        MethodHandle drawImage = null;
        MethodHandle setInterpolation = null;
        if (Frameworks.AVAILABLE) {
            try {
                // void CGContextDrawImage(CGContextRef, CGRect, CGImageRef)
                drawImage = Frameworks.downcall("CGContextDrawImage",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                                CGGeometry.CGRect.LAYOUT, ValueLayout.ADDRESS));

                // void CGContextSetInterpolationQuality(CGContextRef, CGInterpolationQuality)
                setInterpolation = Frameworks.downcall("CGContextSetInterpolationQuality",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            } catch (Throwable ignored) { /* stays null */ }
        }
        H_DRAW_IMAGE = drawImage;
        H_SET_INTERPOLATION_QUALITY = setInterpolation;
    }

    /**
     * Wraps {@code CGContextDrawImage}. {@code rect} must point at a
     * {@link CGGeometry.CGRect} struct (allocate via
     * {@link CGGeometry.CGRect#allocate}).
     */
    public static void drawImage(MemorySegment context,
                                  MemorySegment rect,
                                  MemorySegment image) {
        try {
            H_DRAW_IMAGE.invokeExact(context, rect, image);
        } catch (Throwable t) {
            throw new DecodeException("CGContextDrawImage failed", t);
        }
    }

    /** Wraps {@code CGContextSetInterpolationQuality}. */
    public static void setInterpolationQuality(MemorySegment context, int quality) {
        try {
            H_SET_INTERPOLATION_QUALITY.invokeExact(context, quality);
        } catch (Throwable t) {
            throw new DecodeException("CGContextSetInterpolationQuality failed", t);
        }
    }
}
