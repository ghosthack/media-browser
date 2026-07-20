package io.github.ghosthack.panama.media.avfoundation;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import io.github.ghosthack.panama.media.corefoundation.CoreFoundation;
import io.github.ghosthack.panama.media.core.BgraRotation;
import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.ImageDimensions;
import io.github.ghosthack.panama.media.core.Platform;
import io.github.ghosthack.panama.media.core.PixelFormat;

/**
 * Panama FFM bindings to macOS AVFoundation, CoreMedia, CoreVideo, and
 * CoreGraphics for video frame extraction.
 * <p>
 * This class provides pipeline-oriented video decoding that returns raw
 * pixel data in Arena-managed memory.
 * <p>
 * Extracted frames are always in BGRA format with 4 bytes per pixel.
 * <p>
 * Thread-safe when each thread uses its own {@link Arena}.
 */
public final class AVFoundation {

    private AVFoundation() {}

    // -- OS guard ------------------------------------------------------------

    private static final boolean IS_MACOS = Platform.IS_MAC;

    // -- Constants -----------------------------------------------------------

    /** Bytes per pixel for 32-bit output. */
    private static final int RGBA_BPP = 4;

    /** BGRA premultiplied — native format on macOS.
     *  kCGImageAlphaPremultipliedFirst (2) | kCGBitmapByteOrder32Little (8192). */
    private static final int BITMAP_INFO = 2 | (2 << 12);

    /** kCVPixelFormatType_32BGRA = 'BGRA' */
    public static final int kCVPixelFormatType_32BGRA = 0x42475241;

    // -- Struct layouts ------------------------------------------------------

    /**
     * CMTime struct layout (24 bytes, pass-by-value).
     * <pre>
     * typedef struct {
     *     int64_t  value;      // 8 bytes
     *     int32_t  timescale;  // 4 bytes
     *     uint32_t flags;      // 4 bytes
     *     int64_t  epoch;      // 8 bytes
     * } CMTime;
     * </pre>
     */
    public static final StructLayout CMTIME = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("value"),
            ValueLayout.JAVA_INT.withName("timescale"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_LONG.withName("epoch")
    );

    /**
     * CGSize struct layout (16 bytes).
     * <pre>
     * typedef struct {
     *     CGFloat width;   // double on 64-bit
     *     CGFloat height;  // double on 64-bit
     * } CGSize;
     * </pre>
     */
    private static final StructLayout CGSIZE = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("width"),
            ValueLayout.JAVA_DOUBLE.withName("height")
    );

    /**
     * CGRect struct layout (32 bytes, 4 doubles).
     * <pre>
     * typedef struct {
     *     CGPoint origin;  // x, y
     *     CGSize  size;    // width, height
     * } CGRect;
     * </pre>
     */
    private static final StructLayout CGRECT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("x"),
            ValueLayout.JAVA_DOUBLE.withName("y"),
            ValueLayout.JAVA_DOUBLE.withName("width"),
            ValueLayout.JAVA_DOUBLE.withName("height")
    );

    /**
     * CGAffineTransform struct layout (48 bytes, 6 CGFloat/doubles). Used to read
     * an {@code AVAssetTrack}'s {@code preferredTransform}, which encodes the
     * container's display rotation.
     * <pre>
     * typedef struct {
     *     CGFloat a, b, c, d;  // 2x2 linear part
     *     CGFloat tx, ty;      // translation
     * } CGAffineTransform;
     * </pre>
     */
    private static final StructLayout CGAFFINETRANSFORM = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("a"),
            ValueLayout.JAVA_DOUBLE.withName("b"),
            ValueLayout.JAVA_DOUBLE.withName("c"),
            ValueLayout.JAVA_DOUBLE.withName("d"),
            ValueLayout.JAVA_DOUBLE.withName("tx"),
            ValueLayout.JAVA_DOUBLE.withName("ty")
    );

    // -- Native handles (resolved once at class-load time) -------------------

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;

    // CoreMedia
    private static final MethodHandle H_CM_TIME_MAKE;

    // CoreGraphics
    private static final MethodHandle H_CG_IMAGE_GET_WIDTH;
    private static final MethodHandle H_CG_IMAGE_GET_HEIGHT;
    private static final MethodHandle H_CG_COLORSPACE_CREATE_DEVICE_RGB;
    private static final MethodHandle H_CG_BITMAP_CONTEXT_CREATE;
    private static final MethodHandle H_CG_CONTEXT_DRAW_IMAGE;

    // CoreVideo pixel buffer access
    private static final MethodHandle H_CV_PIXEL_BUFFER_GET_WIDTH;
    private static final MethodHandle H_CV_PIXEL_BUFFER_GET_HEIGHT;
    private static final MethodHandle H_CV_PIXEL_BUFFER_LOCK_BASE_ADDRESS;
    private static final MethodHandle H_CV_PIXEL_BUFFER_UNLOCK_BASE_ADDRESS;
    private static final MethodHandle H_CV_PIXEL_BUFFER_GET_BASE_ADDRESS;
    private static final MethodHandle H_CV_PIXEL_BUFFER_GET_BYTES_PER_ROW;

    // CoreFoundation
    private static final MethodHandle H_CF_RELEASE;

    // CoreVideo constants
    private static final MemorySegment K_CV_PIXEL_BUFFER_PIXEL_FORMAT_TYPE_KEY;

    // AVFoundation constant
    private static final MemorySegment AV_MEDIA_TYPE_VIDEO;

    // ObjC msgSend variants
    /** (id, SEL) -> id */
    private static final MethodHandle MSG_SEND;
    /** (id, SEL, id) -> id */
    private static final MethodHandle MSG_SEND_PTR;
    /** (id, SEL, id, id) -> id */
    private static final MethodHandle MSG_SEND_PTR_PTR;
    /** (id, SEL, byte) -> void */
    private static final MethodHandle MSG_SEND_BOOL;
    /** (id, SEL, CMTime) -> void */
    private static final MethodHandle MSG_SEND_CMTIME_VOID;
    /** (id, SEL, CGSize) -> void */
    private static final MethodHandle MSG_SEND_CGSIZE_VOID;
    /** (id, SEL, CMTime, ptr, ptr) -> id */
    private static final MethodHandle MSG_SEND_CMTIME_PTR_PTR;
    /** (id, SEL) -> CMTime */
    private static final MethodHandle MSG_SEND_RET_CMTIME;
    /** (id, SEL) -> CGSize */
    private static final MethodHandle MSG_SEND_RET_CGSIZE;
    /** (id, SEL) -> CGAffineTransform */
    private static final MethodHandle MSG_SEND_RET_CGAFFINETRANSFORM;
    /** (id, SEL) -> float */
    private static final MethodHandle MSG_SEND_RET_FLOAT;

    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        MethodHandle cmTimeMake = null;
        MethodHandle cgImageGetWidth = null;
        MethodHandle cgImageGetHeight = null;
        MethodHandle cgColorSpaceCreateDeviceRGB = null;
        MethodHandle cgBitmapContextCreate = null;
        MethodHandle cgContextDrawImage = null;
        MethodHandle cfRelease = null;
        MethodHandle cvPixelBufferGetWidth = null;
        MethodHandle cvPixelBufferGetHeight = null;
        MethodHandle cvPixelBufferLockBaseAddress = null;
        MethodHandle cvPixelBufferUnlockBaseAddress = null;
        MethodHandle cvPixelBufferGetBaseAddress = null;
        MethodHandle cvPixelBufferGetBytesPerRow = null;
        MemorySegment kCVPixelBufferPixelFormatTypeKey = null;
        MemorySegment avMediaTypeVideo = null;
        MethodHandle msgSend = null;
        MethodHandle msgSend_ptr = null;
        MethodHandle msgSend_ptr_ptr = null;
        MethodHandle msgSend_bool = null;
        MethodHandle msgSend_cmtime_void = null;
        MethodHandle msgSend_cgsize_void = null;
        MethodHandle msgSend_cmtime_ptr_ptr = null;
        MethodHandle msgSend_ret_cmtime = null;
        MethodHandle msgSend_ret_cgsize = null;
        MethodHandle msgSend_ret_cgaffinetransform = null;
        MethodHandle msgSend_ret_float = null;
        boolean available = false;
        String loadError = null;
        SymbolLookup lookup = null;

        if (IS_MACOS) {
            try {
                // Load frameworks (CoreFoundation and libobjc are loaded by corefoundation dep)
                System.load("/System/Library/Frameworks/CoreMedia.framework/CoreMedia");
                System.load("/System/Library/Frameworks/CoreVideo.framework/CoreVideo");
                System.load("/System/Library/Frameworks/AVFoundation.framework/AVFoundation");
                System.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");

                lookup = SymbolLookup.loaderLookup();

                // -- CoreMedia -----------------------------------------------

                cmTimeMake = LINKER.downcallHandle(
                        lookup.findOrThrow("CMTimeMake"),
                        FunctionDescriptor.of(CMTIME, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

                // -- CoreGraphics --------------------------------------------

                cgImageGetWidth = LINKER.downcallHandle(
                        lookup.findOrThrow("CGImageGetWidth"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                cgImageGetHeight = LINKER.downcallHandle(
                        lookup.findOrThrow("CGImageGetHeight"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                cgColorSpaceCreateDeviceRGB = LINKER.downcallHandle(
                        lookup.findOrThrow("CGColorSpaceCreateDeviceRGB"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));

                cgBitmapContextCreate = LINKER.downcallHandle(
                        lookup.findOrThrow("CGBitmapContextCreate"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

                cgContextDrawImage = LINKER.downcallHandle(
                        lookup.findOrThrow("CGContextDrawImage"),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, CGRECT, ValueLayout.ADDRESS));

                // -- CoreFoundation (for CFRelease of CGImage) ---------------

                cfRelease = LINKER.downcallHandle(
                        lookup.findOrThrow("CFRelease"),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

                // -- CoreVideo constants -------------------------------------

                kCVPixelBufferPixelFormatTypeKey = CoreFoundation.loadConstPtr(
                        "kCVPixelBufferPixelFormatTypeKey");

                // -- CoreVideo pixel buffer access ---------------------------

                cvPixelBufferGetWidth = LINKER.downcallHandle(
                        lookup.findOrThrow("CVPixelBufferGetWidth"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                cvPixelBufferGetHeight = LINKER.downcallHandle(
                        lookup.findOrThrow("CVPixelBufferGetHeight"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                cvPixelBufferLockBaseAddress = LINKER.downcallHandle(
                        lookup.findOrThrow("CVPixelBufferLockBaseAddress"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

                cvPixelBufferUnlockBaseAddress = LINKER.downcallHandle(
                        lookup.findOrThrow("CVPixelBufferUnlockBaseAddress"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

                cvPixelBufferGetBaseAddress = LINKER.downcallHandle(
                        lookup.findOrThrow("CVPixelBufferGetBaseAddress"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                cvPixelBufferGetBytesPerRow = LINKER.downcallHandle(
                        lookup.findOrThrow("CVPixelBufferGetBytesPerRow"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                // -- AVFoundation constant -----------------------------------

                MemorySegment avMediaTypeSym = lookup.findOrThrow("AVMediaTypeVideo");
                avMediaTypeVideo = avMediaTypeSym
                        .reinterpret(ValueLayout.ADDRESS.byteSize())
                        .get(ValueLayout.ADDRESS, 0);

                // -- ObjC msgSend variants -----------------------------------

                // (id, SEL) -> id
                msgSend = CoreFoundation.msgSend(
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // (id, SEL, id) -> id
                msgSend_ptr = CoreFoundation.msgSend(
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));

                // (id, SEL, id, id) -> id
                msgSend_ptr_ptr = CoreFoundation.msgSend(
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // (id, SEL, byte) -> void
                msgSend_bool = CoreFoundation.msgSend(
                        FunctionDescriptor.ofVoid(
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_BYTE));

                // (id, SEL, CMTime) -> void
                msgSend_cmtime_void = CoreFoundation.msgSend(
                        FunctionDescriptor.ofVoid(
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, CMTIME));

                // (id, SEL, CGSize) -> void  (e.g. -[AVAssetImageGenerator setMaximumSize:])
                msgSend_cgsize_void = CoreFoundation.msgSend(
                        FunctionDescriptor.ofVoid(
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, CGSIZE));

                // (id, SEL, CMTime, ptr, ptr) -> id
                msgSend_cmtime_ptr_ptr = CoreFoundation.msgSend(
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                CMTIME, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // (id, SEL) -> CMTime
                msgSend_ret_cmtime = CoreFoundation.msgSend(
                        FunctionDescriptor.of(CMTIME,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // (id, SEL) -> CGSize
                msgSend_ret_cgsize = CoreFoundation.msgSend(
                        FunctionDescriptor.of(CGSIZE,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // (id, SEL) -> CGAffineTransform  (e.g. -[AVAssetTrack preferredTransform])
                msgSend_ret_cgaffinetransform = CoreFoundation.msgSend(
                        FunctionDescriptor.of(CGAFFINETRANSFORM,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // (id, SEL) -> float
                msgSend_ret_float = CoreFoundation.msgSend(
                        FunctionDescriptor.of(ValueLayout.JAVA_FLOAT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                available = true;
            } catch (Throwable t) {
                loadError = t.getMessage();
            }
        } else {
            loadError = "Not macOS";
        }

        LOOKUP = lookup;
        H_CM_TIME_MAKE = cmTimeMake;
        H_CG_IMAGE_GET_WIDTH = cgImageGetWidth;
        H_CG_IMAGE_GET_HEIGHT = cgImageGetHeight;
        H_CG_COLORSPACE_CREATE_DEVICE_RGB = cgColorSpaceCreateDeviceRGB;
        H_CG_BITMAP_CONTEXT_CREATE = cgBitmapContextCreate;
        H_CG_CONTEXT_DRAW_IMAGE = cgContextDrawImage;
        H_CF_RELEASE = cfRelease;
        H_CV_PIXEL_BUFFER_GET_WIDTH = cvPixelBufferGetWidth;
        H_CV_PIXEL_BUFFER_GET_HEIGHT = cvPixelBufferGetHeight;
        H_CV_PIXEL_BUFFER_LOCK_BASE_ADDRESS = cvPixelBufferLockBaseAddress;
        H_CV_PIXEL_BUFFER_UNLOCK_BASE_ADDRESS = cvPixelBufferUnlockBaseAddress;
        H_CV_PIXEL_BUFFER_GET_BASE_ADDRESS = cvPixelBufferGetBaseAddress;
        H_CV_PIXEL_BUFFER_GET_BYTES_PER_ROW = cvPixelBufferGetBytesPerRow;
        K_CV_PIXEL_BUFFER_PIXEL_FORMAT_TYPE_KEY = kCVPixelBufferPixelFormatTypeKey;
        AV_MEDIA_TYPE_VIDEO = avMediaTypeVideo;
        MSG_SEND = msgSend;
        MSG_SEND_PTR = msgSend_ptr;
        MSG_SEND_PTR_PTR = msgSend_ptr_ptr;
        MSG_SEND_BOOL = msgSend_bool;
        MSG_SEND_CMTIME_VOID = msgSend_cmtime_void;
        MSG_SEND_CGSIZE_VOID = msgSend_cgsize_void;
        MSG_SEND_CMTIME_PTR_PTR = msgSend_cmtime_ptr_ptr;
        MSG_SEND_RET_CMTIME = msgSend_ret_cmtime;
        MSG_SEND_RET_CGSIZE = msgSend_ret_cgsize;
        MSG_SEND_RET_CGAFFINETRANSFORM = msgSend_ret_cgaffinetransform;
        MSG_SEND_RET_FLOAT = msgSend_ret_float;
        AVAILABLE = available;
        LOAD_ERROR = loadError;
    }

    // -- Streaming reader handles (AVAssetReader frame pull) -----------------

    /** CVImageBufferRef CMSampleBufferGetImageBuffer(CMSampleBufferRef) */
    private static final MethodHandle H_CM_SAMPLE_BUFFER_GET_IMAGE_BUFFER;
    /** CMTime CMSampleBufferGetPresentationTimeStamp(CMSampleBufferRef) */
    private static final MethodHandle H_CM_SAMPLE_BUFFER_GET_PTS;
    /** (id, SEL, id) -> void */
    private static final MethodHandle MSG_SEND_PTR_VOID;
    /** (id, SEL) -> BOOL */
    private static final MethodHandle MSG_SEND_RET_BOOL;
    /** (id, SEL) -> NSInteger */
    private static final MethodHandle MSG_SEND_RET_LONG;
    /** const AudioStreamBasicDescription* CMAudioFormatDescriptionGetStreamBasicDescription(CMAudioFormatDescriptionRef) */
    private static final MethodHandle H_CM_AUDIO_FORMAT_DESC_GET_ASBD;
    /** AVMediaTypeAudio constant (CFStringRef) */
    private static final MemorySegment AV_MEDIA_TYPE_AUDIO;

    static {
        MethodHandle imgBuf = null, pts = null, ptrVoid = null, retBool = null, retLong = null, asbd = null;
        MemorySegment audioType = null;
        if (AVAILABLE) {
            try {
                // CoreMedia accessors (plain C — no ObjC runtime needed)
                imgBuf = LINKER.downcallHandle(LOOKUP.findOrThrow("CMSampleBufferGetImageBuffer"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                pts = LINKER.downcallHandle(LOOKUP.findOrThrow("CMSampleBufferGetPresentationTimeStamp"),
                        FunctionDescriptor.of(CMTIME, ValueLayout.ADDRESS));
                asbd = LINKER.downcallHandle(
                        LOOKUP.findOrThrow("CMAudioFormatDescriptionGetStreamBasicDescription"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // (id, SEL, id) -> void   [addOutput:]
                ptrVoid = CoreFoundation.msgSend(FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                // (id, SEL) -> BOOL       [startReading]
                retBool = CoreFoundation.msgSend(FunctionDescriptor.of(
                        ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                // (id, SEL) -> NSInteger  [status]
                retLong = CoreFoundation.msgSend(FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                audioType = LOOKUP.findOrThrow("AVMediaTypeAudio")
                        .reinterpret(ValueLayout.ADDRESS.byteSize())
                        .get(ValueLayout.ADDRESS, 0);
            } catch (Throwable ignored) {
                // leave null; openVideo()/getAudioInfo() report unavailability
            }
        }
        H_CM_SAMPLE_BUFFER_GET_IMAGE_BUFFER = imgBuf;
        H_CM_SAMPLE_BUFFER_GET_PTS = pts;
        MSG_SEND_PTR_VOID = ptrVoid;
        MSG_SEND_RET_BOOL = retBool;
        MSG_SEND_RET_LONG = retLong;
        H_CM_AUDIO_FORMAT_DESC_GET_ASBD = asbd;
        AV_MEDIA_TYPE_AUDIO = audioType;
    }

    // -- Public API ----------------------------------------------------------

    /**
     * Returns {@code true} if the native AVFoundation frameworks were loaded
     * successfully. Always {@code false} on non-macOS platforms.
     */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Returns video metadata for the file at the given path.
     *
     * @param arena arena for temporary native allocations (must outlive this call)
     * @param path  absolute file system path to the video file
     * @return video metadata including dimensions, duration, and frame rate
     * @throws IllegalStateException    if AVFoundation is not available
     * @throws IllegalArgumentException if the video file cannot be read or has no video track
     */
    public static VideoInfo getVideoInfo(Arena arena, String path) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            // 1. Create CFString from path and NSURL
            MemorySegment cfPath = CoreFoundation.cfStringCreate(temp, path);
            if (MemorySegment.NULL.equals(cfPath))
                throw new DecodeException("CFStringCreate failed for: " + path);

            try {
                // 2. NSURL fileURLWithPath:
                MemorySegment nsurlClass = CoreFoundation.objcGetClass(temp, "NSURL");
                MemorySegment selFileURL = CoreFoundation.selRegisterName(temp, "fileURLWithPath:");
                MemorySegment url = (MemorySegment) MSG_SEND_PTR.invokeExact(
                        nsurlClass, selFileURL, cfPath);
                if (MemorySegment.NULL.equals(url))
                    throw new DecodeException("NSURL fileURLWithPath: returned nil");

                // 3. AVURLAsset URLAssetWithURL:options:
                MemorySegment avAssetClass = CoreFoundation.objcGetClass(temp, "AVURLAsset");
                MemorySegment selURLAsset = CoreFoundation.selRegisterName(temp, "URLAssetWithURL:options:");
                MemorySegment asset = (MemorySegment) MSG_SEND_PTR_PTR.invokeExact(
                        avAssetClass, selURLAsset, url, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(asset))
                    throw new DecodeException(
                            "AVURLAsset creation failed for: " + path);

                // 4. [asset duration] -> CMTime, convert to millis
                MemorySegment selDuration = CoreFoundation.selRegisterName(temp, "duration");
                MemorySegment durationTime = (MemorySegment) MSG_SEND_RET_CMTIME.invokeExact(
                        (SegmentAllocator) temp, asset, selDuration);
                long durationMillis = cmTimeToMillis(durationTime);

                // 5. [asset tracksWithMediaType:AVMediaTypeVideo]
                MemorySegment selTracks = CoreFoundation.selRegisterName(temp, "tracksWithMediaType:");
                MemorySegment tracks = (MemorySegment) MSG_SEND_PTR.invokeExact(
                        asset, selTracks, AV_MEDIA_TYPE_VIDEO);

                int width = 0;
                int height = 0;
                double frameRate = 0.0;

                if (!MemorySegment.NULL.equals(tracks)) {
                    // 6. [tracks firstObject] -> track
                    MemorySegment selFirstObject = CoreFoundation.selRegisterName(temp, "firstObject");
                    MemorySegment track = (MemorySegment) MSG_SEND.invokeExact(
                            tracks, selFirstObject);

                    if (!MemorySegment.NULL.equals(track)) {
                        // 7. [track naturalSize] -> CGSize
                        MemorySegment selNaturalSize = CoreFoundation.selRegisterName(temp, "naturalSize");
                        MemorySegment size = (MemorySegment) MSG_SEND_RET_CGSIZE.invokeExact(
                                (SegmentAllocator) temp, track, selNaturalSize);
                        double w = size.get(ValueLayout.JAVA_DOUBLE, 0);
                        double h = size.get(ValueLayout.JAVA_DOUBLE, 8);
                        width = (int) Math.round(w);
                        height = (int) Math.round(h);

                        // 8. [track nominalFrameRate] -> float
                        MemorySegment selFrameRate = CoreFoundation.selRegisterName(temp, "nominalFrameRate");
                        frameRate = (float) MSG_SEND_RET_FLOAT.invokeExact(
                                track, selFrameRate);
                    }
                }

                return new VideoInfo(width, height, durationMillis, frameRate);

            } finally {
                cfRelease(cfPath);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("Video info extraction failed: " + path, t);
        }
    }

    /**
     * Returns audio metadata for the file at the given path: whether it has an
     * audio track and, for the first audio track, its sample rate, channel
     * count, and codec (read from the track's {@code AudioStreamBasicDescription}).
     *
     * @param arena arena for temporary native allocations (must outlive this call)
     * @param path  absolute file system path to the media file
     * @throws IllegalStateException if AVFoundation is not available
     * @throws DecodeException       if the file cannot be opened as media
     */
    public static AudioInfo getAudioInfo(Arena arena, String path) {
        ensureAvailable();
        if (H_CM_AUDIO_FORMAT_DESC_GET_ASBD == null || AV_MEDIA_TYPE_AUDIO == null)
            throw new IllegalStateException("AVFoundation audio handles unavailable");
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cfPath = CoreFoundation.cfStringCreate(temp, path);
            if (MemorySegment.NULL.equals(cfPath))
                throw new DecodeException("CFStringCreate failed for: " + path);
            try {
                MemorySegment url = (MemorySegment) MSG_SEND_PTR.invokeExact(
                        CoreFoundation.objcGetClass(temp, "NSURL"),
                        CoreFoundation.selRegisterName(temp, "fileURLWithPath:"), cfPath);
                if (MemorySegment.NULL.equals(url))
                    throw new DecodeException("NSURL fileURLWithPath: returned nil");

                MemorySegment asset = (MemorySegment) MSG_SEND_PTR_PTR.invokeExact(
                        CoreFoundation.objcGetClass(temp, "AVURLAsset"),
                        CoreFoundation.selRegisterName(temp, "URLAssetWithURL:options:"),
                        url, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(asset))
                    throw new DecodeException("AVURLAsset creation failed for: " + path);

                MemorySegment durationTime = (MemorySegment) MSG_SEND_RET_CMTIME.invokeExact(
                        (SegmentAllocator) temp, asset,
                        CoreFoundation.selRegisterName(temp, "duration"));
                long durationMillis = cmTimeToMillis(durationTime);

                MemorySegment tracks = (MemorySegment) MSG_SEND_PTR.invokeExact(asset,
                        CoreFoundation.selRegisterName(temp, "tracksWithMediaType:"),
                        AV_MEDIA_TYPE_AUDIO);
                if (MemorySegment.NULL.equals(tracks))
                    return new AudioInfo(false, durationMillis, 0, 0, null);
                MemorySegment track = (MemorySegment) MSG_SEND.invokeExact(tracks,
                        CoreFoundation.selRegisterName(temp, "firstObject"));
                if (MemorySegment.NULL.equals(track))
                    return new AudioInfo(false, durationMillis, 0, 0, null);

                double sampleRate = 0;
                int channels = 0;
                String codec = null;
                MemorySegment formatDescs = (MemorySegment) MSG_SEND.invokeExact(track,
                        CoreFoundation.selRegisterName(temp, "formatDescriptions"));
                if (!MemorySegment.NULL.equals(formatDescs)) {
                    MemorySegment fd = (MemorySegment) MSG_SEND.invokeExact(formatDescs,
                            CoreFoundation.selRegisterName(temp, "firstObject"));
                    if (!MemorySegment.NULL.equals(fd)) {
                        MemorySegment asbd = (MemorySegment)
                                H_CM_AUDIO_FORMAT_DESC_GET_ASBD.invokeExact(fd);
                        if (!MemorySegment.NULL.equals(asbd)) {
                            asbd = asbd.reinterpret(40); // sizeof(AudioStreamBasicDescription)
                            sampleRate = asbd.get(ValueLayout.JAVA_DOUBLE, 0);   // mSampleRate
                            int formatId = asbd.get(ValueLayout.JAVA_INT, 8);    // mFormatID
                            channels = asbd.get(ValueLayout.JAVA_INT, 28);       // mChannelsPerFrame
                            codec = audioCodecName(formatId);
                        }
                    }
                }
                return new AudioInfo(true, durationMillis, sampleRate, channels, codec);
            } finally {
                cfRelease(cfPath);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("Audio info extraction failed: " + path, t);
        }
    }

    /** Maps a CoreAudio {@code AudioFormatID} FourCC to a short display name. */
    private static String audioCodecName(int formatId) {
        return switch (formatId) {
            case 0x6161_6320 -> "AAC";   // 'aac '
            case 0x6c70_636d -> "PCM";   // 'lpcm'
            case 0x2e6d_7033 -> "MP3";   // '.mp3'
            case 0x616c_6163 -> "ALAC";  // 'alac'
            case 0x666c_6163 -> "FLAC";  // 'flac'
            case 0x6f70_7573 -> "Opus";  // 'opus'
            case 0x6163_2d33 -> "AC-3";  // 'ac-3'
            default -> fourCC(formatId);
        };
    }

    /** Renders a 32-bit FourCC as its (trimmed) 4-character string, or null. */
    private static String fourCC(int id) {
        char[] c = {
                (char) ((id >>> 24) & 0xff), (char) ((id >>> 16) & 0xff),
                (char) ((id >>> 8) & 0xff), (char) (id & 0xff)
        };
        for (char ch : c) {
            if (ch < 0x20 || ch > 0x7e) return null; // non-printable → unknown
        }
        String s = new String(c).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Extracts a single video frame at the given time position.
     * <p>
    * The returned pixel data is in BGRA format (4 bytes per pixel,
    * blue byte first) and is allocated in the caller's {@code arena}.
     *
     * @param arena    the arena that owns the output pixel memory
     * @param path     absolute file system path to the video file
     * @param timeMillis time position in milliseconds
    * @return decoded frame with BGRA pixels
     * @throws IllegalStateException    if AVFoundation is not available
     * @throws IllegalArgumentException if frame extraction fails
     */
    public static DecodedImage<PixelFormat> extractFrame(Arena arena, String path,
                                                                  long timeMillis) {
        return extractFrame(arena, path, timeMillis, 0);
    }

    /**
     * Extracts a single video frame at the given time position, capped to an
     * aspect-preserving {@code maxEdge} box via
     * {@code -[AVAssetImageGenerator setMaximumSize:]} (a native downscale
     * during decode, far cheaper than decoding full-size then shrinking).
     *
     * @param arena      the arena that owns the output pixel memory
     * @param path       absolute file system path to the video file
     * @param timeMillis time position in milliseconds
     * @param maxEdge    longest-edge cap in pixels; {@code <= 0} keeps full size
     * @return decoded frame with BGRA pixels, no larger than {@code maxEdge} on
     *         its longest edge (AVFoundation never upscales)
     * @throws IllegalStateException    if AVFoundation is not available
     * @throws IllegalArgumentException if frame extraction fails
     */
    public static DecodedImage<PixelFormat> extractFrame(Arena arena, String path,
                                                                  long timeMillis, int maxEdge) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            // 1. Create CFString from path
            MemorySegment cfPath = CoreFoundation.cfStringCreate(temp, path);
            if (MemorySegment.NULL.equals(cfPath))
                throw new DecodeException("CFStringCreate failed for: " + path);

            MemorySegment cgImage = MemorySegment.NULL;
            try {
                // 2. NSURL fileURLWithPath:
                MemorySegment nsurlClass = CoreFoundation.objcGetClass(temp, "NSURL");
                MemorySegment selFileURL = CoreFoundation.selRegisterName(temp, "fileURLWithPath:");
                MemorySegment url = (MemorySegment) MSG_SEND_PTR.invokeExact(
                        nsurlClass, selFileURL, cfPath);
                if (MemorySegment.NULL.equals(url))
                    throw new DecodeException("NSURL fileURLWithPath: returned nil");

                // 3. AVURLAsset URLAssetWithURL:options:
                MemorySegment avAssetClass = CoreFoundation.objcGetClass(temp, "AVURLAsset");
                MemorySegment selURLAsset = CoreFoundation.selRegisterName(temp, "URLAssetWithURL:options:");
                MemorySegment asset = (MemorySegment) MSG_SEND_PTR_PTR.invokeExact(
                        avAssetClass, selURLAsset, url, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(asset))
                    throw new DecodeException(
                            "AVURLAsset creation failed for: " + path);

                // 4. AVAssetImageGenerator assetImageGeneratorWithAsset:
                MemorySegment genClass = CoreFoundation.objcGetClass(temp, "AVAssetImageGenerator");
                MemorySegment selGenWithAsset = CoreFoundation.selRegisterName(temp,
                        "assetImageGeneratorWithAsset:");
                MemorySegment generator = (MemorySegment) MSG_SEND_PTR.invokeExact(
                        genClass, selGenWithAsset, asset);
                if (MemorySegment.NULL.equals(generator))
                    throw new DecodeException(
                            "AVAssetImageGenerator creation failed");

                // 5. setAppliesPreferredTrackTransform: YES
                MemorySegment selSetTransform = CoreFoundation.selRegisterName(temp,
                        "setAppliesPreferredTrackTransform:");
                MSG_SEND_BOOL.invokeExact(generator, selSetTransform, (byte) 1);

                // 5b. setMaximumSize: (aspect-preserving downscale during decode)
                if (maxEdge > 0) {
                    MemorySegment selSetMaxSize = CoreFoundation.selRegisterName(temp,
                            "setMaximumSize:");
                    MemorySegment maxSize = cgSizeMake(temp, maxEdge, maxEdge);
                    MSG_SEND_CGSIZE_VOID.invokeExact(generator, selSetMaxSize, maxSize);
                }

                // 6. Set tolerances: 1-second window for keyframe seek
                MemorySegment selSetBefore = CoreFoundation.selRegisterName(temp,
                        "setRequestedTimeToleranceBefore:");
                MemorySegment selSetAfter = CoreFoundation.selRegisterName(temp,
                        "setRequestedTimeToleranceAfter:");

                MemorySegment toleranceBefore = (MemorySegment) H_CM_TIME_MAKE.invokeExact(
                        (SegmentAllocator) temp, 1L, 1);
                MemorySegment toleranceAfter = (MemorySegment) H_CM_TIME_MAKE.invokeExact(
                        (SegmentAllocator) temp, 1L, 1);
                MSG_SEND_CMTIME_VOID.invokeExact(generator, selSetBefore, toleranceBefore);
                MSG_SEND_CMTIME_VOID.invokeExact(generator, selSetAfter, toleranceAfter);

                // 7. Create CMTime from timeMillis
                MemorySegment cmTime = (MemorySegment) H_CM_TIME_MAKE.invokeExact(
                        (SegmentAllocator) temp, timeMillis, 1000);

                // 8. copyCGImageAtTime:actualTime:error:
                MemorySegment selCopy = CoreFoundation.selRegisterName(temp,
                        "copyCGImageAtTime:actualTime:error:");
                MemorySegment errorPtr = temp.allocate(ValueLayout.ADDRESS);
                errorPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);

                cgImage = (MemorySegment) MSG_SEND_CMTIME_PTR_PTR.invokeExact(
                        generator, selCopy,
                        cmTime,
                        MemorySegment.NULL,  // actualTime (don't need it)
                        errorPtr);

                // 9. If NULL, retry with 1-hour tolerance (handles long GOPs)
                if (MemorySegment.NULL.equals(cgImage)) {
                    MemorySegment largeTolerance = (MemorySegment) H_CM_TIME_MAKE.invokeExact(
                            (SegmentAllocator) temp, 3600L, 1);
                    MSG_SEND_CMTIME_VOID.invokeExact(generator, selSetBefore, largeTolerance);
                    MSG_SEND_CMTIME_VOID.invokeExact(generator, selSetAfter, largeTolerance);

                    errorPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                    cgImage = (MemorySegment) MSG_SEND_CMTIME_PTR_PTR.invokeExact(
                            generator, selCopy,
                            cmTime,
                            MemorySegment.NULL,
                            errorPtr);
                }

                if (MemorySegment.NULL.equals(cgImage))
                    throw new DecodeException(
                            "copyCGImageAtTime returned NULL for time " + timeMillis + "ms");

                // 10. Convert CGImage to DecodedImage (BGRA)
                return cgImageToDecodedImage(arena, temp, cgImage);

            } finally {
                // 11. cgImage is a CF object returned by "copy" -- we own it
                cfRelease(cgImage);
                // url, asset, generator are autoreleased ObjC objects
                cfRelease(cfPath);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("Video frame extraction failed: " + path, t);
        }
    }

    // -- CoreVideo pixel buffer access ---------------------------------------

    /**
     * Returns the width in pixels of the given CVPixelBuffer.
     */
    public static long cvPixelBufferGetWidth(MemorySegment pixelBuffer) {
        ensureAvailable();
        try {
            return (long) H_CV_PIXEL_BUFFER_GET_WIDTH.invokeExact(pixelBuffer);
        } catch (Throwable t) {
            throw new DecodeException("CVPixelBufferGetWidth failed", t);
        }
    }

    /**
     * Returns the height in pixels of the given CVPixelBuffer.
     */
    public static long cvPixelBufferGetHeight(MemorySegment pixelBuffer) {
        ensureAvailable();
        try {
            return (long) H_CV_PIXEL_BUFFER_GET_HEIGHT.invokeExact(pixelBuffer);
        } catch (Throwable t) {
            throw new DecodeException("CVPixelBufferGetHeight failed", t);
        }
    }

    /**
     * Locks the base address of a CVPixelBuffer for CPU access.
     *
     * @param pixelBuffer the CVPixelBufferRef
     * @param flags       lock flags (0 for read-write, 1 for read-only)
     * @return kCVReturnSuccess (0) on success
     */
    public static int cvPixelBufferLockBaseAddress(MemorySegment pixelBuffer, long flags) {
        ensureAvailable();
        try {
            return (int) H_CV_PIXEL_BUFFER_LOCK_BASE_ADDRESS.invokeExact(pixelBuffer, flags);
        } catch (Throwable t) {
            throw new DecodeException("CVPixelBufferLockBaseAddress failed", t);
        }
    }

    /**
     * Unlocks the base address of a CVPixelBuffer.
     *
     * @param pixelBuffer the CVPixelBufferRef
     * @param flags       lock flags (must match the flags passed to lock)
     * @return kCVReturnSuccess (0) on success
     */
    public static int cvPixelBufferUnlockBaseAddress(MemorySegment pixelBuffer, long flags) {
        ensureAvailable();
        try {
            return (int) H_CV_PIXEL_BUFFER_UNLOCK_BASE_ADDRESS.invokeExact(pixelBuffer, flags);
        } catch (Throwable t) {
            throw new DecodeException("CVPixelBufferUnlockBaseAddress failed", t);
        }
    }

    /**
     * Returns a pointer to the base address of the pixel data.
     * The buffer must be locked before calling this method.
     */
    public static MemorySegment cvPixelBufferGetBaseAddress(MemorySegment pixelBuffer) {
        ensureAvailable();
        try {
            return (MemorySegment) H_CV_PIXEL_BUFFER_GET_BASE_ADDRESS.invokeExact(pixelBuffer);
        } catch (Throwable t) {
            throw new DecodeException("CVPixelBufferGetBaseAddress failed", t);
        }
    }

    /**
     * Returns the number of bytes per row of the pixel data.
     */
    public static long cvPixelBufferGetBytesPerRow(MemorySegment pixelBuffer) {
        ensureAvailable();
        try {
            return (long) H_CV_PIXEL_BUFFER_GET_BYTES_PER_ROW.invokeExact(pixelBuffer);
        } catch (Throwable t) {
            throw new DecodeException("CVPixelBufferGetBytesPerRow failed", t);
        }
    }

    // -- CoreVideo constants -------------------------------------------------

    /**
     * Returns the {@code kCVPixelBufferPixelFormatTypeKey} constant,
     * used as a dictionary key when configuring pixel buffer output settings.
     */
    public static MemorySegment kCVPixelBufferPixelFormatTypeKey() {
        ensureAvailable();
        return K_CV_PIXEL_BUFFER_PIXEL_FORMAT_TYPE_KEY;
    }

    // -- CoreMedia time ------------------------------------------------------

    /**
     * Creates a CMTime value with the given numerator and timescale.
     *
     * @param arena     arena used for the returned struct allocation
     * @param value     the time value (numerator)
     * @param timescale the timescale (denominator, e.g. 600 for 600ths of a second)
     * @return a CMTime memory segment (24 bytes)
     */
    public static MemorySegment cmTimeMake(Arena arena, long value, int timescale) {
        ensureAvailable();
        try {
            return (MemorySegment) H_CM_TIME_MAKE.invokeExact((SegmentAllocator) arena, value, timescale);
        } catch (Throwable t) {
            throw new DecodeException("CMTimeMake failed", t);
        }
    }

    /**
     * Builds a {@code CGSize} struct (two 64-bit {@code CGFloat}s) in the given
     * allocator. {@code CGSizeMake} is a static-inline C function with no
     * dlsym-able symbol, so the struct is populated directly.
     *
     * @param allocator arena/allocator that owns the returned struct
     * @param width     the size width
     * @param height    the size height
     * @return a CGSize memory segment (16 bytes)
     */
    private static MemorySegment cgSizeMake(SegmentAllocator allocator, double width, double height) {
        MemorySegment size = allocator.allocate(CGSIZE);
        size.set(ValueLayout.JAVA_DOUBLE, 0, width);
        size.set(ValueLayout.JAVA_DOUBLE, 8, height);
        return size;
    }

    // -- Internal implementation ---------------------------------------------

    /**
    * Converts a CGImageRef to a DecodedImage with BGRA pixels.
     *
     * @param arena   the arena that owns the output pixel memory
     * @param temp    temporary arena for intermediate allocations
     * @param cgImage a valid, non-NULL CGImageRef
    * @return decoded image with BGRA pixels
     */
    private static DecodedImage<PixelFormat> cgImageToDecodedImage(
            Arena arena, Arena temp, MemorySegment cgImage) throws Throwable {
        MemorySegment colorSpace = MemorySegment.NULL;
        MemorySegment ctx = MemorySegment.NULL;
        try {
            long w = (long) H_CG_IMAGE_GET_WIDTH.invokeExact(cgImage);
            long h = (long) H_CG_IMAGE_GET_HEIGHT.invokeExact(cgImage);
            if (w <= 0 || h <= 0)
                throw new DecodeException(
                        "Invalid CGImage dimensions: " + w + "x" + h);

            int iw = (int) w;
            int ih = (int) h;
            ImageDimensions.validateDimensions(iw, ih);

            colorSpace = (MemorySegment) H_CG_COLORSPACE_CREATE_DEVICE_RGB.invokeExact();

            long bytesPerRow = w * RGBA_BPP;
            long bufferSize = bytesPerRow * h;
            MemorySegment pixelData = temp.allocate(bufferSize, 16);

            // CGBitmapContextCreate with BGRA output (native macOS format)
            ctx = (MemorySegment) H_CG_BITMAP_CONTEXT_CREATE.invokeExact(
                    pixelData, w, h, 8L, bytesPerRow,
                    colorSpace, BITMAP_INFO);
            if (MemorySegment.NULL.equals(ctx))
                throw new DecodeException("CGBitmapContextCreate returned NULL");

            // Draw the CGImage into the bitmap context
            MemorySegment rect = temp.allocate(CGRECT);
            rect.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);            // origin.x
            rect.set(ValueLayout.JAVA_DOUBLE, 8, 0.0);            // origin.y
            rect.set(ValueLayout.JAVA_DOUBLE, 16, (double) w);    // size.width
            rect.set(ValueLayout.JAVA_DOUBLE, 24, (double) h);    // size.height
            H_CG_CONTEXT_DRAW_IMAGE.invokeExact(ctx, rect, cgImage);

            // Copy pixels to Arena-allocated output buffer
            int dstStride = iw * RGBA_BPP;
            long outputSize = (long) dstStride * ih;
            MemorySegment output = arena.allocate(ValueLayout.JAVA_BYTE, outputSize);
            MemorySegment.copy(pixelData, 0, output, 0, outputSize);

            return new DecodedImage<>(output, iw, ih, dstStride, PixelFormat.BGRA);

        } finally {
            cfRelease(ctx);
            cfRelease(colorSpace);
        }
    }

    /**
     * Converts a CMTime struct to milliseconds.
     *
     * @param cmTime a CMTime memory segment (24 bytes)
     * @return duration in milliseconds, or 0 if the time is invalid
     */
    private static long cmTimeToMillis(MemorySegment cmTime) {
        long value = cmTime.get(ValueLayout.JAVA_LONG, 0);
        int timescale = cmTime.get(ValueLayout.JAVA_INT, 8);
        int flags = cmTime.get(ValueLayout.JAVA_INT, 12);
        // flags bit 0 = kCMTimeFlags_Valid; bits 2,3,4 = PositiveInfinity,
        // NegativeInfinity, Indefinite
        if ((flags & 1) == 0 || timescale <= 0 || (flags & (4 | 8 | 16)) != 0) return 0;
        long seconds = value / timescale;
        long remainder = value % timescale;
        return seconds * 1000L + (remainder * 1000L) / timescale;
    }

    /**
     * Safely releases a CoreFoundation / CoreGraphics object.
     */
    private static void cfRelease(MemorySegment ref) {
        if (ref != null && !MemorySegment.NULL.equals(ref)) {
            try {
                H_CF_RELEASE.invokeExact(ref);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void ensureAvailable() {
        if (!AVAILABLE)
            throw new IllegalStateException(
                    "AVFoundation is not available"
                            + (LOAD_ERROR != null ? ": " + LOAD_ERROR : ""));
    }

    /**
     * Container display rotation as 90&deg; clockwise quarter-turns (0..3) from
     * the first two members {@code (a, b)} of an {@code AVAssetTrack}'s
     * {@code preferredTransform} (a CGAffineTransform). {@code atan2(b, a)} is the
     * rotation angle; rounding to the nearest quarter-turn yields 0/1/2/3 for
     * 0&deg;/90&deg;/180&deg;/270&deg; clockwise.
     *
     * <p>This is the rotation {@code AVAssetImageGenerator} bakes into the poster
     * via {@code setAppliesPreferredTrackTransform:YES}, but {@code AVAssetReader}
     * omits — so {@link FrameStream} reapplies it to each live frame, keeping
     * playback upright and matching the thumbnail.</p>
     */
    static int rotationQuarterTurns(double a, double b) {
        if (a == 0 && b == 0) return 0; // degenerate / identity-less transform
        double degrees = Math.toDegrees(Math.atan2(b, a));
        int q = (int) Math.round(degrees / 90.0);
        return ((q % 4) + 4) % 4;
    }

    // -- Streaming video frame reader ----------------------------------------

    /**
     * Opens a sequential BGRA frame reader over the file's first video track,
     * backed by {@code AVAssetReader} + {@code AVAssetReaderTrackOutput}
     * configured for {@code kCVPixelFormatType_32BGRA}.
     *
     * <p>The returned reader is confined to the calling thread (it owns a
     * confined {@link Arena}); all of its methods, including
     * {@link FrameStream#close()}, must be called on that thread.</p>
     *
     * @param path absolute file system path to the video file
     * @throws IllegalStateException if AVFoundation is not available
     * @throws DecodeException       if the file has no video track or the
     *                               reader cannot be started
     */
    public static FrameStream openVideo(String path) {
        ensureAvailable();
        if (MSG_SEND_RET_BOOL == null || H_CM_SAMPLE_BUFFER_GET_PTS == null)
            throw new IllegalStateException("AVFoundation streaming handles unavailable");
        return new FrameStream(path);
    }

    /**
     * Sequential video frame reader: each {@link #next()} pulls the following
     * frame into a reusable BGRA buffer ({@code width * height * 4} bytes,
     * tightly packed, top-down). Confined to its creating thread.
     */
    public static final class FrameStream implements AutoCloseable {

        private final Arena arena = Arena.ofConfined();
        private final MemorySegment ptsScratch = arena.allocate(CMTIME);
        private final SegmentAllocator ptsAllocator = (size, align) -> ptsScratch;

        private MemorySegment reader = MemorySegment.NULL;   // retained (CFRetain)
        private MemorySegment output = MemorySegment.NULL;   // retained (CFRetain)
        private MemorySegment selCopyNext = MemorySegment.NULL;
        private MemorySegment selStatus = MemorySegment.NULL;
        private MemorySegment bgra = MemorySegment.NULL;

        private int width;
        private int height;
        private int rawWidth;       // decoded pixel width  (before rotation)
        private int rawHeight;      // decoded pixel height (before rotation)
        private int rotationTurns;  // container display rotation, 90 CW quarter-turns
        private int[] rotSrc;       // reused source scratch for the rotation copy
        private int[] rotDst;       // reused destination scratch for the rotation copy
        private long durationMicros = -1;
        private long ptsMicros = -1;
        private boolean primed;   // frame 0 already buffered in bgra by the constructor
        private boolean ended;
        private boolean closed;

        private FrameStream(String path) {
            boolean ok = false;
            MemorySegment pool = CoreFoundation.autoreleasePoolPush();
            try {
                MemorySegment cfPath = CoreFoundation.cfStringCreate(arena, path);
                MemorySegment url = (MemorySegment) MSG_SEND_PTR.invokeExact(
                        CoreFoundation.objcGetClass(arena, "NSURL"),
                        CoreFoundation.selRegisterName(arena, "fileURLWithPath:"), cfPath);
                CoreFoundation.cfRelease(cfPath);
                if (MemorySegment.NULL.equals(url))
                    throw new DecodeException("NSURL fileURLWithPath: returned nil for " + path);

                MemorySegment asset = (MemorySegment) MSG_SEND_PTR_PTR.invokeExact(
                        CoreFoundation.objcGetClass(arena, "AVURLAsset"),
                        CoreFoundation.selRegisterName(arena, "URLAssetWithURL:options:"),
                        url, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(asset))
                    throw new DecodeException("AVURLAsset creation failed for " + path);

                MemorySegment durTime = (MemorySegment) MSG_SEND_RET_CMTIME.invokeExact(
                        (SegmentAllocator) arena, asset,
                        CoreFoundation.selRegisterName(arena, "duration"));
                durationMicros = cmTimeToMicros(durTime);

                MemorySegment tracks = (MemorySegment) MSG_SEND_PTR.invokeExact(asset,
                        CoreFoundation.selRegisterName(arena, "tracksWithMediaType:"),
                        AV_MEDIA_TYPE_VIDEO);
                MemorySegment track = MemorySegment.NULL;
                if (!MemorySegment.NULL.equals(tracks))
                    track = (MemorySegment) MSG_SEND.invokeExact(tracks,
                            CoreFoundation.selRegisterName(arena, "firstObject"));
                if (MemorySegment.NULL.equals(track))
                    throw new DecodeException("no video track in " + path);

                MemorySegment size = (MemorySegment) MSG_SEND_RET_CGSIZE.invokeExact(
                        (SegmentAllocator) arena, track,
                        CoreFoundation.selRegisterName(arena, "naturalSize"));
                int naturalW = (int) Math.round(size.get(ValueLayout.JAVA_DOUBLE, 0));
                int naturalH = (int) Math.round(size.get(ValueLayout.JAVA_DOUBLE, 8));

                // The track's preferredTransform encodes the container's display
                // rotation (the same transform AVAssetImageGenerator bakes into the
                // poster via setAppliesPreferredTrackTransform:YES). AVAssetReader
                // hands back *un-transformed* pixels, so reproduce that rotation
                // here (see copyBgra) — otherwise a portrait phone clip plays
                // sideways even though its thumbnail is upright.
                MemorySegment xform = (MemorySegment) MSG_SEND_RET_CGAFFINETRANSFORM.invokeExact(
                        (SegmentAllocator) arena, track,
                        CoreFoundation.selRegisterName(arena, "preferredTransform"));
                rotationTurns = rotationQuarterTurns(
                        xform.get(ValueLayout.JAVA_DOUBLE, 0),   // a
                        xform.get(ValueLayout.JAVA_DOUBLE, 8));  // b

                MemorySegment errPtr = arena.allocate(ValueLayout.ADDRESS);
                errPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                MemorySegment rdr = (MemorySegment) MSG_SEND_PTR_PTR.invokeExact(
                        CoreFoundation.objcGetClass(arena, "AVAssetReader"),
                        CoreFoundation.selRegisterName(arena, "assetReaderWithAsset:error:"),
                        asset, errPtr);
                if (MemorySegment.NULL.equals(rdr))
                    throw new DecodeException("AVAssetReader creation failed for " + path);

                // outputSettings = { kCVPixelBufferPixelFormatTypeKey : 32BGRA }
                MemorySegment fmt = CoreFoundation.cfNumberCreateInt(arena, kCVPixelFormatType_32BGRA);
                MemorySegment settings = CoreFoundation.cfDictionaryCreate(arena,
                        new MemorySegment[] { K_CV_PIXEL_BUFFER_PIXEL_FORMAT_TYPE_KEY },
                        new MemorySegment[] { fmt });
                MemorySegment out = (MemorySegment) MSG_SEND_PTR_PTR.invokeExact(
                        CoreFoundation.objcGetClass(arena, "AVAssetReaderTrackOutput"),
                        CoreFoundation.selRegisterName(arena,
                                "assetReaderTrackOutputWithTrack:outputSettings:"),
                        track, settings);
                CoreFoundation.cfRelease(settings);
                CoreFoundation.cfRelease(fmt);
                if (MemorySegment.NULL.equals(out))
                    throw new DecodeException("AVAssetReaderTrackOutput creation failed for " + path);

                MSG_SEND_PTR_VOID.invokeExact(rdr,
                        CoreFoundation.selRegisterName(arena, "addOutput:"), out);

                byte started = (byte) MSG_SEND_RET_BOOL.invokeExact(rdr,
                        CoreFoundation.selRegisterName(arena, "startReading"));
                if (started == 0)
                    throw new DecodeException("AVAssetReader startReading failed for " + path);

                // Retain the objects kept past the autorelease-pool drain below.
                reader = CoreFoundation.cfRetain(rdr);
                output = CoreFoundation.cfRetain(out);
                selCopyNext = CoreFoundation.selRegisterName(arena, "copyNextSampleBuffer");
                selStatus = CoreFoundation.selRegisterName(arena, "status");

                // AVFoundation's naturalSize is the track's *display* size (pixel
                // aspect ratio / clean aperture applied), which can differ from the
                // actual decoded CVPixelBuffer — e.g. a 176x144 QCIF clip whose
                // naturalSize is 320x240. The presentation pipeline (GL renderer,
                // pooled PixelBuffer slots) is sized from width()/height() before the
                // first frame, so it must use the true pixel dimensions or it shrinks
                // every live frame into a corner of an oversized image. Prime frame 0
                // and adopt its real size; fall back to naturalSize for an empty stream.
                if (!primeFirstFrame()) {
                    boolean swap = (rotationTurns & 1) == 1;
                    width = swap ? naturalH : naturalW;
                    height = swap ? naturalW : naturalH;
                    if (width <= 0 || height <= 0)
                        throw new DecodeException("bad video dimensions " + width + "x" + height);
                    ImageDimensions.validateDimensions(width, height);
                    bgra = arena.allocate((long) width * height * 4);
                    ended = true;
                }
                ok = true;
            } catch (DecodeException e) {
                throw e;
            } catch (Throwable t) {
                throw new DecodeException("AVAssetReader open failed for " + path, t);
            } finally {
                CoreFoundation.autoreleasePoolPop(pool);
                if (!ok) close();
            }
        }

        /** Frame width in pixels (fixed for the stream). */
        public int width() { return width; }

        /** Frame height in pixels (fixed for the stream). */
        public int height() { return height; }

        /** Stream duration in microseconds, or -1 when unknown. */
        public long durationMicros() { return durationMicros; }

        /** Presentation time of the last decoded frame, in microseconds. */
        public long ptsMicros() { return ptsMicros; }

        /** Reusable BGRA buffer holding the last frame ({@code width*height*4} bytes). */
        public MemorySegment bgra() { return bgra; }

        /**
         * Pulls and buffers frame 0, adopting the decoded CVPixelBuffer's actual
         * pixel dimensions (which can differ from the track's naturalSize) as the
         * stream's {@link #width()}/{@link #height()}. The frame is left in
         * {@link #bgra} and handed out by the first {@link #next()} call. Returns
         * {@code false} for an empty stream (no readable frame).
         */
        private boolean primeFirstFrame() {
            MemorySegment pool = CoreFoundation.autoreleasePoolPush();
            try {
                MemorySegment sample = (MemorySegment) MSG_SEND.invokeExact(output, selCopyNext);
                if (MemorySegment.NULL.equals(sample)) {
                    long status = (long) MSG_SEND_RET_LONG.invokeExact(reader, selStatus);
                    if (status == 3)  // AVAssetReaderStatusFailed
                        throw new DecodeException("AVAssetReader failed before first frame");
                    return false;
                }
                try {
                    MemorySegment imageBuffer =
                            (MemorySegment) H_CM_SAMPLE_BUFFER_GET_IMAGE_BUFFER.invokeExact(sample);
                    if (MemorySegment.NULL.equals(imageBuffer))
                        throw new DecodeException("sample buffer has no image buffer");
                    rawWidth = (int) cvPixelBufferGetWidth(imageBuffer);
                    rawHeight = (int) cvPixelBufferGetHeight(imageBuffer);
                    boolean swap = (rotationTurns & 1) == 1;
                    width = swap ? rawHeight : rawWidth;
                    height = swap ? rawWidth : rawHeight;
                    if (width <= 0 || height <= 0)
                        throw new DecodeException("bad video dimensions " + width + "x" + height);
                    ImageDimensions.validateDimensions(width, height);
                    bgra = arena.allocate((long) width * height * 4);
                    MemorySegment ptsTime = (MemorySegment)
                            H_CM_SAMPLE_BUFFER_GET_PTS.invokeExact(ptsAllocator, sample);
                    ptsMicros = cmTimeToMicros(ptsTime);
                    copyBgra(imageBuffer);
                    primed = true;
                    return true;
                } finally {
                    CoreFoundation.cfRelease(sample);
                }
            } catch (DecodeException e) {
                throw e;
            } catch (Throwable t) {
                throw new DecodeException("frame read failed", t);
            } finally {
                CoreFoundation.autoreleasePoolPop(pool);
            }
        }

        /** Pulls the next frame into {@link #bgra()}; {@code false} at end of stream. */
        public boolean next() {
            if (closed) return false;
            if (primed) {  // frame 0, already buffered by primeFirstFrame()
                primed = false;
                return true;
            }
            if (ended) return false;
            MemorySegment pool = CoreFoundation.autoreleasePoolPush();
            try {
                MemorySegment sample = (MemorySegment) MSG_SEND.invokeExact(output, selCopyNext);
                if (MemorySegment.NULL.equals(sample)) {
                    long status = (long) MSG_SEND_RET_LONG.invokeExact(reader, selStatus);
                    ended = true;
                    if (status == 3)  // AVAssetReaderStatusFailed
                        throw new DecodeException("AVAssetReader failed mid-stream");
                    return false;
                }
                try {
                    MemorySegment imageBuffer =
                            (MemorySegment) H_CM_SAMPLE_BUFFER_GET_IMAGE_BUFFER.invokeExact(sample);
                    if (MemorySegment.NULL.equals(imageBuffer))
                        throw new DecodeException("sample buffer has no image buffer");
                    MemorySegment ptsTime = (MemorySegment)
                            H_CM_SAMPLE_BUFFER_GET_PTS.invokeExact(ptsAllocator, sample);
                    ptsMicros = cmTimeToMicros(ptsTime);
                    copyBgra(imageBuffer);
                    return true;
                } finally {
                    CoreFoundation.cfRelease(sample);
                }
            } catch (DecodeException e) {
                throw e;
            } catch (Throwable t) {
                throw new DecodeException("frame read failed", t);
            } finally {
                CoreFoundation.autoreleasePoolPop(pool);
            }
        }

        /**
         * Copies the locked CVPixelBuffer into {@link #bgra}, removing row stride
         * and applying the container's {@link #rotationTurns} display rotation so
         * live frames are upright (matching the transform-baked poster).
         */
        private void copyBgra(MemorySegment imageBuffer) {
            int rc = cvPixelBufferLockBaseAddress(imageBuffer, 1);  // kCVPixelBufferLock_ReadOnly
            if (rc != 0)
                throw new DecodeException("CVPixelBufferLockBaseAddress failed: " + rc);
            try {
                long srcStride = cvPixelBufferGetBytesPerRow(imageBuffer);
                int srcH = (int) cvPixelBufferGetHeight(imageBuffer);
                MemorySegment base = cvPixelBufferGetBaseAddress(imageBuffer)
                        .reinterpret(srcStride * srcH);
                if (rotationTurns != 0) {
                    rotateCopy(base, srcStride, srcH);
                    return;
                }
                long dstStride = (long) width * 4;
                if (srcStride == dstStride && srcH == height) {
                    MemorySegment.copy(base, 0, bgra, 0, dstStride * height);
                } else {
                    long rowBytes = Math.min(dstStride, srcStride);
                    int rows = Math.min(height, srcH);
                    for (int y = 0; y < rows; y++)
                        MemorySegment.copy(base, y * srcStride, bgra, y * dstStride, rowBytes);
                }
            } finally {
                cvPixelBufferUnlockBaseAddress(imageBuffer, 1);
            }
        }

        /**
         * Copies the locked CVPixelBuffer into {@link #bgra} while applying the
         * container's {@link #rotationTurns} 90&deg; clockwise rotation, via the
         * shared {@link BgraRotation#rotate} permutation (pinned to match
         * {@code RasterFrames.rotateCw} / {@code VideoPresenter.rotateInto}, so
         * playback, the poster and the user rotation layer all agree). The source
         * is bulk-read honoring the buffer's row stride, then bulk-written.
         */
        private void rotateCopy(MemorySegment base, long srcStride, int srcH) {
            int strideInts = (int) (srcStride / 4);
            int srcInts = strideInts * srcH;
            if (rotSrc == null || rotSrc.length < srcInts) rotSrc = new int[srcInts];
            MemorySegment.copy(base, ValueLayout.JAVA_INT, 0, rotSrc, 0, srcInts);
            int outInts = width * height;
            if (rotDst == null || rotDst.length < outInts) rotDst = new int[outInts];
            BgraRotation.rotate(rotSrc, strideInts, rawWidth, rawHeight, rotationTurns, rotDst);
            MemorySegment.copy(rotDst, 0, bgra, ValueLayout.JAVA_INT, 0, outInts);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!MemorySegment.NULL.equals(reader)) {
                try {
                    MemorySegment ignored = (MemorySegment) MSG_SEND.invokeExact(reader,
                            CoreFoundation.selRegisterName(arena, "cancelReading"));
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
            CoreFoundation.cfRelease(output);
            CoreFoundation.cfRelease(reader);
            arena.close();
        }
    }

    /** Converts a CMTime to microseconds, or -1 if invalid/indefinite. */
    private static long cmTimeToMicros(MemorySegment cmTime) {
        long value = cmTime.get(ValueLayout.JAVA_LONG, 0);
        int timescale = cmTime.get(ValueLayout.JAVA_INT, 8);
        int flags = cmTime.get(ValueLayout.JAVA_INT, 12);
        if ((flags & 1) == 0 || timescale <= 0 || (flags & (4 | 8 | 16)) != 0) return -1;
        long seconds = value / timescale;
        long remainder = value % timescale;
        return seconds * 1_000_000L + (remainder * 1_000_000L) / timescale;
    }
}
