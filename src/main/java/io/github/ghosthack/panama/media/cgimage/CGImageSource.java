package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.DecodeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Panama wrappers for {@code CGImageSource*} read-side functions from
 * ImageIO.framework, plus the CF string constants used as option / property
 * dictionary keys. Together with {@link CGImageDestination}, {@link CGImage},
 * {@link CGColorSpace}, {@link CGBitmapContext}, {@link CGContext}, and
 * {@link CGGeometry}, this is the full primitive set used by the
 * {@link ImageIO} facade.
 * <p>
 * Returned {@code CGImageSourceRef} / {@code CGImageRef} / {@code CFDictionaryRef}
 * values are caller-owned unless documented otherwise. Release with
 * {@link io.github.ghosthack.panama.media.corefoundation.CoreFoundation#cfRelease}.
 * <p>
 * {@code options} parameters accept {@code MemorySegment.NULL} to request
 * default behaviour.
 */
public final class CGImageSource {
    private CGImageSource() {}

    // ── Status codes (CGImageSourceStatus) ───────────────────────────────

    public static final int STATUS_UNEXPECTED_EOF      = -5;
    public static final int STATUS_INVALID_DATA        = -4;
    public static final int STATUS_UNKNOWN_TYPE        = -3;
    public static final int STATUS_READING_HEADER      = -2;
    public static final int STATUS_INCOMPLETE          = -1;
    public static final int STATUS_COMPLETE            = 0;

    // ── Method handles ───────────────────────────────────────────────────

    private static final MethodHandle H_CREATE_WITH_DATA;
    private static final MethodHandle H_CREATE_WITH_URL;
    private static final MethodHandle H_GET_STATUS;
    private static final MethodHandle H_GET_COUNT;
    private static final MethodHandle H_COPY_PROPERTIES_AT_INDEX;
    private static final MethodHandle H_CREATE_IMAGE_AT_INDEX;
    private static final MethodHandle H_CREATE_THUMBNAIL_AT_INDEX;

    // ── CF constants (loaded lazily via Frameworks.constPtr) ─────────────

    private static final MemorySegment K_PIXEL_WIDTH;
    private static final MemorySegment K_PIXEL_HEIGHT;
    private static final MemorySegment K_ORIENTATION;

    private static final MemorySegment K_CREATE_THUMBNAIL_FROM_IMAGE_ALWAYS;
    private static final MemorySegment K_CREATE_THUMBNAIL_WITH_TRANSFORM;
    private static final MemorySegment K_THUMBNAIL_MAX_PIXEL_SIZE;

    private static final MemorySegment K_GIF_DICTIONARY;
    private static final MemorySegment K_GIF_DELAY_TIME;
    private static final MemorySegment K_GIF_UNCLAMPED_DELAY_TIME;
    private static final MemorySegment K_GIF_LOOP_COUNT;

    private static final MemorySegment K_DEPTH;
    private static final MemorySegment K_COLOR_MODEL;
    private static final MemorySegment K_HAS_ALPHA;
    private static final MemorySegment K_PROFILE_NAME;

    private static final MemorySegment K_EXIF_DICTIONARY;
    private static final MemorySegment K_TIFF_DICTIONARY;
    private static final MemorySegment K_GPS_DICTIONARY;

    static {
        MethodHandle createWithData = null;
        MethodHandle createWithURL = null;
        MethodHandle getStatus = null;
        MethodHandle getCount = null;
        MethodHandle copyProps = null;
        MethodHandle createImage = null;
        MethodHandle createThumb = null;

        MemorySegment kPixelWidth = null;
        MemorySegment kPixelHeight = null;
        MemorySegment kOrientation = null;
        MemorySegment kCreateThumbAlways = null;
        MemorySegment kCreateThumbTransform = null;
        MemorySegment kThumbMaxPixelSize = null;
        MemorySegment kGifDict = null;
        MemorySegment kGifDelay = null;
        MemorySegment kGifUnclampedDelay = null;
        MemorySegment kGifLoop = null;

        MemorySegment kDepth = null;
        MemorySegment kColorModel = null;
        MemorySegment kHasAlpha = null;
        MemorySegment kProfileName = null;

        MemorySegment kExifDict = null;
        MemorySegment kTiffDict = null;
        MemorySegment kGpsDict = null;

        if (Frameworks.AVAILABLE) {
            try {
                // CGImageSourceRef CGImageSourceCreateWithData(CFDataRef, CFDictionaryRef)
                createWithData = Frameworks.downcall("CGImageSourceCreateWithData",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // CGImageSourceRef CGImageSourceCreateWithURL(CFURLRef, CFDictionaryRef)
                createWithURL = Frameworks.downcall("CGImageSourceCreateWithURL",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // CGImageSourceStatus CGImageSourceGetStatus(CGImageSourceRef)
                getStatus = Frameworks.downcall("CGImageSourceGetStatus",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                // size_t CGImageSourceGetCount(CGImageSourceRef)
                getCount = Frameworks.downcall("CGImageSourceGetCount",
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                // CFDictionaryRef CGImageSourceCopyPropertiesAtIndex(CGImageSourceRef, size_t, CFDictionaryRef)
                copyProps = Frameworks.downcall("CGImageSourceCopyPropertiesAtIndex",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS));

                // CGImageRef CGImageSourceCreateImageAtIndex(CGImageSourceRef, size_t, CFDictionaryRef)
                createImage = Frameworks.downcall("CGImageSourceCreateImageAtIndex",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS));

                // CGImageRef CGImageSourceCreateThumbnailAtIndex(CGImageSourceRef, size_t, CFDictionaryRef)
                createThumb = Frameworks.downcall("CGImageSourceCreateThumbnailAtIndex",
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS));

                kPixelWidth            = Frameworks.constPtr("kCGImagePropertyPixelWidth");
                kPixelHeight           = Frameworks.constPtr("kCGImagePropertyPixelHeight");
                kOrientation           = Frameworks.constPtr("kCGImagePropertyOrientation");
                kCreateThumbAlways     = Frameworks.constPtr("kCGImageSourceCreateThumbnailFromImageAlways");
                kCreateThumbTransform  = Frameworks.constPtr("kCGImageSourceCreateThumbnailWithTransform");
                kThumbMaxPixelSize     = Frameworks.constPtr("kCGImageSourceThumbnailMaxPixelSize");
                kGifDict               = Frameworks.constPtr("kCGImagePropertyGIFDictionary");
                kGifDelay              = Frameworks.constPtr("kCGImagePropertyGIFDelayTime");
                kGifUnclampedDelay     = Frameworks.constPtr("kCGImagePropertyGIFUnclampedDelayTime");
                kGifLoop               = Frameworks.constPtr("kCGImagePropertyGIFLoopCount");

                kDepth                 = Frameworks.constPtr("kCGImagePropertyDepth");
                kColorModel            = Frameworks.constPtr("kCGImagePropertyColorModel");
                kHasAlpha              = Frameworks.constPtr("kCGImagePropertyHasAlpha");
                kProfileName           = Frameworks.constPtr("kCGImagePropertyProfileName");

                kExifDict              = Frameworks.constPtr("kCGImagePropertyExifDictionary");
                kTiffDict              = Frameworks.constPtr("kCGImagePropertyTIFFDictionary");
                kGpsDict               = Frameworks.constPtr("kCGImagePropertyGPSDictionary");
            } catch (Throwable ignored) { /* handles stay null */ }
        }

        H_CREATE_WITH_DATA = createWithData;
        H_CREATE_WITH_URL = createWithURL;
        H_GET_STATUS = getStatus;
        H_GET_COUNT = getCount;
        H_COPY_PROPERTIES_AT_INDEX = copyProps;
        H_CREATE_IMAGE_AT_INDEX = createImage;
        H_CREATE_THUMBNAIL_AT_INDEX = createThumb;

        K_PIXEL_WIDTH = kPixelWidth;
        K_PIXEL_HEIGHT = kPixelHeight;
        K_ORIENTATION = kOrientation;
        K_CREATE_THUMBNAIL_FROM_IMAGE_ALWAYS = kCreateThumbAlways;
        K_CREATE_THUMBNAIL_WITH_TRANSFORM    = kCreateThumbTransform;
        K_THUMBNAIL_MAX_PIXEL_SIZE           = kThumbMaxPixelSize;
        K_GIF_DICTIONARY = kGifDict;
        K_GIF_DELAY_TIME = kGifDelay;
        K_GIF_UNCLAMPED_DELAY_TIME = kGifUnclampedDelay;
        K_GIF_LOOP_COUNT = kGifLoop;

        K_DEPTH = kDepth;
        K_COLOR_MODEL = kColorModel;
        K_HAS_ALPHA = kHasAlpha;
        K_PROFILE_NAME = kProfileName;

        K_EXIF_DICTIONARY = kExifDict;
        K_TIFF_DICTIONARY = kTiffDict;
        K_GPS_DICTIONARY = kGpsDict;
    }

    // ── Functions ────────────────────────────────────────────────────────

    /** Wraps {@code CGImageSourceCreateWithData}. */
    public static MemorySegment createWithData(MemorySegment cfData, MemorySegment options) {
        try {
            return (MemorySegment) H_CREATE_WITH_DATA.invokeExact(cfData, options);
        } catch (Throwable t) {
            throw new DecodeException("CGImageSourceCreateWithData failed", t);
        }
    }

    /** Wraps {@code CGImageSourceCreateWithURL}. */
    public static MemorySegment createWithURL(MemorySegment cfUrl, MemorySegment options) {
        try {
            return (MemorySegment) H_CREATE_WITH_URL.invokeExact(cfUrl, options);
        } catch (Throwable t) {
            throw new DecodeException("CGImageSourceCreateWithURL failed", t);
        }
    }

    /** Wraps {@code CGImageSourceGetStatus}. */
    public static int getStatus(MemorySegment source) {
        try {
            return (int) H_GET_STATUS.invokeExact(source);
        } catch (Throwable t) {
            throw new DecodeException("CGImageSourceGetStatus failed", t);
        }
    }

    /** Wraps {@code CGImageSourceGetCount}. */
    public static long getCount(MemorySegment source) {
        try {
            return (long) H_GET_COUNT.invokeExact(source);
        } catch (Throwable t) {
            throw new DecodeException("CGImageSourceGetCount failed", t);
        }
    }

    /**
     * Wraps {@code CGImageSourceCopyPropertiesAtIndex}. Returns a
     * caller-owned {@code CFDictionaryRef}.
     */
    public static MemorySegment copyPropertiesAtIndex(MemorySegment source, long index,
                                                      MemorySegment options) {
        try {
            return (MemorySegment) H_COPY_PROPERTIES_AT_INDEX.invokeExact(source, index, options);
        } catch (Throwable t) {
            throw new DecodeException("CGImageSourceCopyPropertiesAtIndex failed", t);
        }
    }

    /**
     * Wraps {@code CGImageSourceCreateImageAtIndex}. Returns a caller-owned
     * {@code CGImageRef}.
     */
    public static MemorySegment createImageAtIndex(MemorySegment source, long index,
                                                   MemorySegment options) {
        try {
            return (MemorySegment) H_CREATE_IMAGE_AT_INDEX.invokeExact(source, index, options);
        } catch (Throwable t) {
            throw new DecodeException("CGImageSourceCreateImageAtIndex failed", t);
        }
    }

    /**
     * Wraps {@code CGImageSourceCreateThumbnailAtIndex}. Returns a
     * caller-owned {@code CGImageRef}.
     * <p>
     * Recognised option keys include {@link #kThumbnailMaxPixelSize},
     * {@link #kCreateThumbnailFromImageAlways}, and
     * {@link #kCreateThumbnailWithTransform} — build the options dictionary
     * via
     * {@link io.github.ghosthack.panama.media.corefoundation.CoreFoundation#cfDictionaryCreate}.
     */
    public static MemorySegment createThumbnailAtIndex(MemorySegment source, long index,
                                                       MemorySegment options) {
        try {
            return (MemorySegment) H_CREATE_THUMBNAIL_AT_INDEX.invokeExact(source, index, options);
        } catch (Throwable t) {
            throw new DecodeException("CGImageSourceCreateThumbnailAtIndex failed", t);
        }
    }

    // ── CF constant accessors ────────────────────────────────────────────

    /** {@code kCGImagePropertyPixelWidth} — key into the property dictionary. */
    public static MemorySegment kPixelWidth() { return K_PIXEL_WIDTH; }

    /** {@code kCGImagePropertyPixelHeight} — key into the property dictionary. */
    public static MemorySegment kPixelHeight() { return K_PIXEL_HEIGHT; }

    /** {@code kCGImagePropertyOrientation} — EXIF orientation, values 1–8. */
    public static MemorySegment kOrientation() { return K_ORIENTATION; }

    /** {@code kCGImageSourceCreateThumbnailFromImageAlways} — boolean option. */
    public static MemorySegment kCreateThumbnailFromImageAlways() {
        return K_CREATE_THUMBNAIL_FROM_IMAGE_ALWAYS;
    }

    /** {@code kCGImageSourceCreateThumbnailWithTransform} — boolean option. */
    public static MemorySegment kCreateThumbnailWithTransform() {
        return K_CREATE_THUMBNAIL_WITH_TRANSFORM;
    }

    /** {@code kCGImageSourceThumbnailMaxPixelSize} — CFNumber option. */
    public static MemorySegment kThumbnailMaxPixelSize() {
        return K_THUMBNAIL_MAX_PIXEL_SIZE;
    }

    /** {@code kCGImagePropertyGIFDictionary} — GIF-subdictionary key. */
    public static MemorySegment kGIFDictionary() { return K_GIF_DICTIONARY; }

    /** {@code kCGImagePropertyGIFDelayTime} — frame delay in seconds (CFNumber, clamped). */
    public static MemorySegment kGIFDelayTime() { return K_GIF_DELAY_TIME; }

    /** {@code kCGImagePropertyGIFUnclampedDelayTime} — frame delay (CFNumber). */
    public static MemorySegment kGIFUnclampedDelayTime() { return K_GIF_UNCLAMPED_DELAY_TIME; }

    /** {@code kCGImagePropertyGIFLoopCount} — loop iterations (CFNumber). */
    public static MemorySegment kGIFLoopCount() { return K_GIF_LOOP_COUNT; }

    /** {@code kCGImagePropertyDepth} — bits per component (CFNumber). */
    public static MemorySegment kDepth() { return K_DEPTH; }

    /** {@code kCGImagePropertyColorModel} — {@code "RGB"}, {@code "Gray"}, … (CFString). */
    public static MemorySegment kColorModel() { return K_COLOR_MODEL; }

    /** {@code kCGImagePropertyHasAlpha} — alpha channel flag (CFBoolean). */
    public static MemorySegment kHasAlpha() { return K_HAS_ALPHA; }

    /** {@code kCGImagePropertyProfileName} — ICC profile name (CFString). */
    public static MemorySegment kProfileName() { return K_PROFILE_NAME; }

    /** {@code kCGImagePropertyExifDictionary} — sub-dict with EXIF tags. */
    public static MemorySegment kExifDictionary() { return K_EXIF_DICTIONARY; }

    /** {@code kCGImagePropertyTIFFDictionary} — sub-dict with TIFF tags. */
    public static MemorySegment kTIFFDictionary() { return K_TIFF_DICTIONARY; }

    /** {@code kCGImagePropertyGPSDictionary} — sub-dict with GPS tags. */
    public static MemorySegment kGPSDictionary() { return K_GPS_DICTIONARY; }
}
