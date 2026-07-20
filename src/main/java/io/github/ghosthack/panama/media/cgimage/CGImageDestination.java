package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.DecodeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Panama wrappers for {@code CGImageDestination*} functions from
 * ImageIO.framework — the write side of the CG image pipeline.
 * <p>
 * Destinations are created with a target UTI (e.g. {@code "public.jpeg"});
 * feed them {@code CGImageRef}s via {@link #addImage} and commit with
 * {@link #finalize_}. Returned {@code CGImageDestinationRef} values are
 * caller-owned — release with
 * {@link io.github.ghosthack.panama.media.corefoundation.CoreFoundation#cfRelease}.
 */
public final class CGImageDestination {
    private CGImageDestination() {}

    private static final MethodHandle H_CREATE_WITH_URL;
    private static final MethodHandle H_CREATE_WITH_DATA;
    private static final MethodHandle H_ADD_IMAGE;
    private static final MethodHandle H_FINALIZE;

    private static final MemorySegment K_LOSSY_COMPRESSION_QUALITY;

    static {
        MethodHandle createWithURL = null;
        MethodHandle createWithData = null;
        MethodHandle addImage = null;
        MethodHandle finalize_ = null;
        MemorySegment kLossyQuality = null;

        if (Frameworks.AVAILABLE) {
            try {
                // CGImageDestinationRef CGImageDestinationCreateWithURL(
                //     CFURLRef, CFStringRef type, size_t count, CFDictionaryRef options)
                createWithURL = Frameworks.downcall("CGImageDestinationCreateWithURL",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                // CGImageDestinationRef CGImageDestinationCreateWithData(
                //     CFMutableDataRef, CFStringRef type, size_t count, CFDictionaryRef options)
                createWithData = Frameworks.downcall("CGImageDestinationCreateWithData",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                // void CGImageDestinationAddImage(CGImageDestinationRef, CGImageRef, CFDictionaryRef)
                addImage = Frameworks.downcall("CGImageDestinationAddImage",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // bool CGImageDestinationFinalize(CGImageDestinationRef)
                finalize_ = Frameworks.downcall("CGImageDestinationFinalize",
                        FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));

                kLossyQuality = Frameworks.constPtr("kCGImageDestinationLossyCompressionQuality");
            } catch (Throwable ignored) { /* handles stay null */ }
        }

        H_CREATE_WITH_URL = createWithURL;
        H_CREATE_WITH_DATA = createWithData;
        H_ADD_IMAGE = addImage;
        H_FINALIZE = finalize_;
        K_LOSSY_COMPRESSION_QUALITY = kLossyQuality;
    }

    /** Wraps {@code CGImageDestinationCreateWithURL}. */
    public static MemorySegment createWithURL(MemorySegment cfUrl, MemorySegment cfUti,
                                              long count, MemorySegment options) {
        try {
            return (MemorySegment) H_CREATE_WITH_URL.invokeExact(cfUrl, cfUti, count, options);
        } catch (Throwable t) {
            throw new DecodeException("CGImageDestinationCreateWithURL failed", t);
        }
    }

    /** Wraps {@code CGImageDestinationCreateWithData}. */
    public static MemorySegment createWithData(MemorySegment cfData, MemorySegment cfUti,
                                               long count, MemorySegment options) {
        try {
            return (MemorySegment) H_CREATE_WITH_DATA.invokeExact(cfData, cfUti, count, options);
        } catch (Throwable t) {
            throw new DecodeException("CGImageDestinationCreateWithData failed", t);
        }
    }

    /** Wraps {@code CGImageDestinationAddImage}. */
    public static void addImage(MemorySegment destination, MemorySegment image,
                                MemorySegment properties) {
        try {
            H_ADD_IMAGE.invokeExact(destination, image, properties);
        } catch (Throwable t) {
            throw new DecodeException("CGImageDestinationAddImage failed", t);
        }
    }

    /**
     * Wraps {@code CGImageDestinationFinalize}. Named with a trailing
     * underscore because {@code finalize} is reserved on {@link Object}.
     */
    public static boolean finalize_(MemorySegment destination) {
        try {
            return (boolean) H_FINALIZE.invokeExact(destination);
        } catch (Throwable t) {
            throw new DecodeException("CGImageDestinationFinalize failed", t);
        }
    }

    /**
     * {@code kCGImageDestinationLossyCompressionQuality} — CFNumber double
     * in {@code [0.0, 1.0]}.
     */
    public static MemorySegment kLossyCompressionQuality() {
        return K_LOSSY_COMPRESSION_QUALITY;
    }
}
