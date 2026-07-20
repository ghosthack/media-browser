package io.github.ghosthack.mediabrowser;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Fixes the macOS application menu (the bold first menu in the system menu bar,
 * with its {@code About/Hide/Quit} items) to show a friendly name.
 *
 * <p>For an un-bundled launch ({@code mvn javafx:run}) the bold title defaults to
 * the runtime's name ("java") and JavaFX/Glass bakes the {@code Application}
 * subclass's fully-qualified name into the Hide/Quit items. None of the usual
 * knobs help: {@code -Xdock:name} is only honoured by AWT (never started here),
 * and {@code NSProcessInfo.processName} drives only Activity Monitor, not the
 * menu. The menu name AppKit shows for the app menu also can't be set without a
 * proper {@code .app} bundle (its {@code CFBundleName}).
 *
 * <p>So we retitle the already-built menu directly through the Objective-C
 * runtime: set the app-menu item's title (which AppKit then displays in bold,
 * instead of substituting the app name) and rewrite the FQN inside the
 * Hide/Quit items, preserving their localized prefixes. Glass builds this app
 * menu once and reuses it across system-menu-bar swaps between windows, so a
 * single call after startup sticks. Must run on the JavaFX/AppKit thread, after
 * the toolkit is up. No-op off macOS; failures are swallowed (cosmetic only).
 *
 * <p>A packaged {@code .app} bundle would not need this.
 */
final class MacApplicationMenu {

    private MacApplicationMenu() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final AddressLayout ADDR = ValueLayout.ADDRESS;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;

    /**
     * Retitles the app menu to {@code displayName}, replacing every occurrence of
     * {@code bakedInName} (the Glass application name baked into Hide/Quit) with it.
     */
    static void setApplicationName(String bakedInName, String displayName) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        try (Arena arena = Arena.ofConfined()) {
            Objc objc = new Objc(arena);

            MemorySegment nsApp = objc.send(objc.cls("NSApplication"), "sharedApplication");
            MemorySegment mainMenu = objc.send(nsApp, "mainMenu");
            if (isNil(mainMenu)) return;

            MemorySegment appItem = objc.sendIndex(mainMenu, "itemAtIndex:", 0);
            if (isNil(appItem)) return;

            // The bold menu-bar title shows this item's title once it is non-default.
            objc.setObject(appItem, "setTitle:", objc.nsString(displayName));

            MemorySegment submenu = objc.send(appItem, "submenu");
            if (isNil(submenu)) return;
            objc.setObject(submenu, "setTitle:", objc.nsString(displayName));

            long count = objc.sendCount(submenu, "numberOfItems");
            for (long i = 0; i < count; i++) {
                MemorySegment item = objc.sendIndex(submenu, "itemAtIndex:", i);
                String title = objc.string(objc.send(item, "title"));
                if (title.contains(bakedInName)) {
                    objc.setObject(item, "setTitle:",
                            objc.nsString(title.replace(bakedInName, displayName)));
                }
            }
        } catch (Throwable ignored) {
            // Cosmetic only; leave the default menu name if anything goes wrong.
        }
    }

    private static boolean isNil(MemorySegment s) {
        return s == null || s.address() == 0;
    }

    /** Minimal Objective-C messaging helpers bound to one confined arena. */
    private static final class Objc {
        // Loaded lazily (only on macOS, only once setApplicationName runs) so the
        // enclosing class can be referenced safely on platforms without libobjc.
        private final SymbolLookup OBJC = MacObjc.lookup();
        private final Arena arena;
        private final MethodHandle getClass = handle("objc_getClass", FunctionDescriptor.of(ADDR, ADDR));
        private final MethodHandle selRegister = handle("sel_registerName", FunctionDescriptor.of(ADDR, ADDR));
        private final MethodHandle sendObj = handle("objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        private final MethodHandle sendObjLong = handle("objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR, I64));
        private final MethodHandle sendObjPtr = handle("objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR));
        private final MethodHandle sendLong = handle("objc_msgSend", FunctionDescriptor.of(I64, ADDR, ADDR));
        private final MethodHandle sendVoidObj = handle("objc_msgSend", FunctionDescriptor.ofVoid(ADDR, ADDR, ADDR));

        Objc(Arena arena) { this.arena = arena; }

        private MethodHandle handle(String name, FunctionDescriptor fd) {
            return LINKER.downcallHandle(OBJC.find(name).orElseThrow(), fd);
        }

        MemorySegment cls(String name) throws Throwable {
            return (MemorySegment) getClass.invoke(arena.allocateFrom(name));
        }
        private MemorySegment sel(String name) throws Throwable {
            return (MemorySegment) selRegister.invoke(arena.allocateFrom(name));
        }
        MemorySegment send(MemorySegment receiver, String selector) throws Throwable {
            return (MemorySegment) sendObj.invoke(receiver, sel(selector));
        }
        MemorySegment sendIndex(MemorySegment receiver, String selector, long index) throws Throwable {
            return (MemorySegment) sendObjLong.invoke(receiver, sel(selector), index);
        }
        long sendCount(MemorySegment receiver, String selector) throws Throwable {
            return (long) sendLong.invoke(receiver, sel(selector));
        }
        void setObject(MemorySegment receiver, String selector, MemorySegment value) throws Throwable {
            sendVoidObj.invoke(receiver, sel(selector), value);
        }
        MemorySegment nsString(String s) throws Throwable {
            return (MemorySegment) sendObjPtr.invoke(cls("NSString"),
                    sel("stringWithUTF8String:"), arena.allocateFrom(s));
        }
        String string(MemorySegment nsString) throws Throwable {
            if (isNil(nsString)) return "";
            MemorySegment cString = (MemorySegment) sendObj.invoke(nsString, sel("UTF8String"));
            return isNil(cString) ? "" : cString.reinterpret(Long.MAX_VALUE).getString(0);
        }
    }
}
