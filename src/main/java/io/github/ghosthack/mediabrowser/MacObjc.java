package io.github.ghosthack.mediabrowser;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;

/**
 * Resolves the Objective-C runtime symbols ({@code objc_msgSend},
 * {@code sel_registerName}, {@code objc_getClass}, …) on macOS.
 *
 * <p>We resolve them through {@link SymbolLookup#libraryLookup} of the
 * <em>absolute</em> dyld-cache path {@code /usr/lib/libobjc.A.dylib}. On a
 * JDK 26 / Apple-silicon host an {@code ObjcProbe} run confirmed this resolves
 * {@code objc_msgSend} (and friends) reliably. It was chosen over the
 * alternatives because it is deterministic:
 * <ul>
 *   <li>{@link SymbolLookup#loaderLookup} (after {@link System#load}) only sees
 *       libraries registered against <em>this</em> class loader; if another
 *       loader (e.g. the CoreFoundation bindings) already loaded libobjc, our
 *       {@code System.load} no-ops and the loader lookup can come up empty.</li>
 *   <li>{@link SymbolLookup#libraryLookup} of the <em>bare</em> name depends on
 *       the dyld search path and has historically thrown {@code Cannot open
 *       library} on some hosts.</li>
 *   <li>{@link java.lang.foreign.Linker#defaultLookup} does not expose these
 *       symbols here.</li>
 * </ul>
 */
public final class MacObjc {

    private MacObjc() {}

    /** A lookup covering the Objective-C runtime symbols on macOS. */
    public static SymbolLookup lookup() {
        return SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    }
}
