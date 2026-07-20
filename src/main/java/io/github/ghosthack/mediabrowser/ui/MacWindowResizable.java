package io.github.ghosthack.mediabrowser.ui;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Restores OS-level resizability for undecorated stages on macOS. JavaFX
 * creates them as borderless NSWindows without NSWindowStyleMaskResizable,
 * so the Accessibility API reports them as non-resizable and window managers
 * like Rectangle can move but not resize them. This ORs the missing bit into
 * the NSWindow style mask: window handles come from the Glass peers (needs
 * {@code --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED}, see
 * pom.xml) and the mask is changed through {@code objc_msgSend}. Fails soft:
 * on any error the windows merely stay externally non-resizable, reported
 * once on stderr.
 */
final class MacWindowResizable {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static final long RESIZABLE = 1 << 3; // NSWindowStyleMaskResizable

    private static boolean warned;

    private MacWindowResizable() {}

    /**
     * Re-applies the style-mask fix every time {@code stage} is shown —
     * hiding a stage destroys its peer, so each show realizes a fresh
     * NSWindow that again lacks the resizable bit.
     */
    static void installOnShown(Stage stage) {
        if (!IS_MAC) return; // NSWindow style masks + libobjc are macOS-only
        stage.showingProperty().addListener((obs, was, showing) -> {
            // deferred so it runs after the native window is fully realized
            if (showing) Platform.runLater(MacWindowResizable::makeAllResizable);
        });
    }

    /** Adds the resizable bit to every live Glass window; idempotent. */
    private static void makeAllResizable() {
        try {
            Class<?> glass = Class.forName("com.sun.glass.ui.Window");
            List<?> windows = (List<?>) glass.getMethod("getWindows").invoke(null);
            if (windows.isEmpty()) return;
            Method nativeHandle = handleAccessor(glass);

            var linker = Linker.nativeLinker();
            var objc = io.github.ghosthack.mediabrowser.MacObjc.lookup();
            MethodHandle getMask = linker.downcallHandle(objc.findOrThrow("objc_msgSend"),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
            MethodHandle setMask = linker.downcallHandle(objc.findOrThrow("objc_msgSend"),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
            MethodHandle selector = linker.downcallHandle(objc.findOrThrow("sel_registerName"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS));

            try (Arena arena = Arena.ofConfined()) {
                var selGet = (MemorySegment) selector.invokeExact(
                        arena.allocateFrom("styleMask"));
                var selSet = (MemorySegment) selector.invokeExact(
                        arena.allocateFrom("setStyleMask:"));
                for (Object w : windows) {
                    var nsWindow = MemorySegment.ofAddress((long) nativeHandle.invoke(w));
                    long mask = (long) getMask.invokeExact(nsWindow, selGet);
                    if ((mask & RESIZABLE) == 0) {
                        setMask.invokeExact(nsWindow, selSet, mask | RESIZABLE);
                    }
                }
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.err.println("Cannot make chromeless windows resizable for"
                        + " external window managers: " + t);
            }
        }
    }

    /** The Glass native window handle accessor; its name varies across versions. */
    private static Method handleAccessor(Class<?> glass) throws NoSuchMethodException {
        for (String name : new String[] {"getNativeHandle", "getNativeWindow", "getRawHandle"}) {
            try {
                return glass.getMethod(name);
            } catch (NoSuchMethodException e) {
                // try the next known name
            }
        }
        throw new NoSuchMethodException(glass + " has no native handle accessor");
    }
}
