package io.github.ghosthack.mediabrowser.gl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Panama downcalls for binding VideoToolbox output surfaces into the CGL
 * context: CoreVideo's {@code CVPixelBufferGetIOSurface}/{@code
 * CVPixelBufferGetPixelFormatType} and the OpenGL framework's
 * {@code CGLTexImageIOSurface2D} (absent from LWJGL's CGL binding, which
 * only covers PBuffers). macOS-only by construction; {@link #AVAILABLE} is
 * false anywhere the frameworks don't resolve, and callers fall back to the
 * CPU path.
 */
final class MacVideoSurfaces {

    /** {@code kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange} ('420v'). */
    static final int PIXEL_FORMAT_NV12_VIDEO = 0x34323076;
    /** {@code kCVPixelFormatType_420YpCbCr8BiPlanarFullRange} ('420f'). */
    static final int PIXEL_FORMAT_NV12_FULL = 0x34323066;

    static final boolean AVAILABLE;
    private static final MethodHandle GET_IOSURFACE;
    private static final MethodHandle GET_PIXEL_FORMAT_TYPE;
    private static final MethodHandle TEX_IMAGE_IOSURFACE_2D;

    static {
        MethodHandle ioSurface = null;
        MethodHandle formatType = null;
        MethodHandle texImage = null;
        boolean ok = false;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup cv = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/CoreVideo.framework/CoreVideo",
                    Arena.global());
            SymbolLookup gl = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/OpenGL.framework/OpenGL",
                    Arena.global());
            ioSurface = linker.downcallHandle(
                    cv.find("CVPixelBufferGetIOSurface").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            formatType = linker.downcallHandle(
                    cv.find("CVPixelBufferGetPixelFormatType").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            texImage = linker.downcallHandle(
                    gl.find("CGLTexImageIOSurface2D").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,   // CGLContextObj
                            ValueLayout.JAVA_INT,  // target
                            ValueLayout.JAVA_INT,  // internal_format
                            ValueLayout.JAVA_INT,  // width
                            ValueLayout.JAVA_INT,  // height
                            ValueLayout.JAVA_INT,  // format
                            ValueLayout.JAVA_INT,  // type
                            ValueLayout.ADDRESS,   // IOSurfaceRef
                            ValueLayout.JAVA_INT)); // plane
            ok = true;
        } catch (Throwable t) {
            // Not macOS (or frameworks unresolvable): zero-copy simply absent.
        }
        GET_IOSURFACE = ioSurface;
        GET_PIXEL_FORMAT_TYPE = formatType;
        TEX_IMAGE_IOSURFACE_2D = texImage;
        AVAILABLE = ok;
    }

    private MacVideoSurfaces() {}

    /** The IOSurface backing {@code cvPixelBuffer}, or 0 (not IOSurface-backed). */
    static long ioSurface(long cvPixelBuffer) {
        try {
            return ((MemorySegment) GET_IOSURFACE.invokeExact(
                    MemorySegment.ofAddress(cvPixelBuffer))).address();
        } catch (Throwable t) {
            throw new IllegalStateException("CVPixelBufferGetIOSurface failed", t);
        }
    }

    /** The pixel buffer's OSType format code (e.g. {@link #PIXEL_FORMAT_NV12_VIDEO}). */
    static int pixelFormatType(long cvPixelBuffer) {
        try {
            return (int) GET_PIXEL_FORMAT_TYPE.invokeExact(
                    MemorySegment.ofAddress(cvPixelBuffer));
        } catch (Throwable t) {
            throw new IllegalStateException("CVPixelBufferGetPixelFormatType failed", t);
        }
    }

    /**
     * Binds one plane of {@code ioSurface} to the currently bound rectangle
     * texture. Returns the CGL error (0 = success).
     */
    static int texImageIoSurface2D(long cglContext, int target, int internalFormat,
                                   int width, int height, int format, int type,
                                   long ioSurface, int plane) {
        try {
            return (int) TEX_IMAGE_IOSURFACE_2D.invokeExact(
                    MemorySegment.ofAddress(cglContext), target, internalFormat,
                    width, height, format, type,
                    MemorySegment.ofAddress(ioSurface), plane);
        } catch (Throwable t) {
            throw new IllegalStateException("CGLTexImageIOSurface2D failed", t);
        }
    }
}
