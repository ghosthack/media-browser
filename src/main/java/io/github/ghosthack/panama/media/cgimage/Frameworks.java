package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.Platform;
import io.github.ghosthack.panama.media.corefoundation.CoreFoundation;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Loads {@code ImageIO.framework} and {@code CoreGraphics.framework} once for
 * the whole module and exposes a shared {@link SymbolLookup}. All downcalls
 * in this package resolve through {@link #downcall(String, FunctionDescriptor)}.
 * <p>
 * Package-private: siblings ({@link CGImageSource}, {@link CGImageDestination},
 * {@link CGImage}, {@link CGColorSpace}, {@link CGBitmapContext},
 * {@link CGContext}, {@link CGGeometry}) call into this class during their
 * static init. {@code System.load} against a previously-loaded path is a
 * no-op, so centralising the load keeps error reporting consistent and
 * avoids N failure messages when the platform is unsupported.
 */
final class Frameworks {
    private Frameworks() {}

    static final boolean AVAILABLE;
    static final String LOAD_ERROR;
    static final SymbolLookup LOOKUP;
    static final Linker LINKER = Linker.nativeLinker();

    static {
        boolean available = false;
        String loadError = null;
        SymbolLookup lookup = null;

        if (Platform.IS_MAC) {
            try {
                // System.load via absolute path — framework files may not exist
                // on disk on macOS 11+, but dyld resolves them from the shared
                // cache.
                System.load("/System/Library/Frameworks/ImageIO.framework/ImageIO");
                System.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
                lookup = SymbolLookup.loaderLookup();
                available = true;
            } catch (Throwable t) {
                loadError = t.getMessage();
            }
        } else {
            loadError = "Not macOS";
        }

        AVAILABLE = available;
        LOAD_ERROR = loadError;
        LOOKUP = lookup;
    }

    /**
     * Resolves {@code symbol} and binds it to a downcall handle with {@code fd}.
     * Only safe to call when {@link #AVAILABLE} is {@code true}.
     */
    static MethodHandle downcall(String symbol, FunctionDescriptor fd) {
        return LINKER.downcallHandle(LOOKUP.findOrThrow(symbol), fd);
    }

    /**
     * Resolves a CoreFoundation-style string constant (e.g.
     * {@code kCGImagePropertyPixelWidth}) to its {@code MemorySegment} value.
     * Delegates to {@link CoreFoundation#loadConstPtr(String)} so the whole
     * module stays consistent with how CF constants are resolved.
     */
    static MemorySegment constPtr(String name) {
        return CoreFoundation.loadConstPtr(name);
    }

    static void ensureAvailable() {
        if (!AVAILABLE)
            throw new IllegalStateException(
                    "Apple ImageIO/CoreGraphics not available"
                            + (LOAD_ERROR != null ? ": " + LOAD_ERROR : ""));
    }
}
