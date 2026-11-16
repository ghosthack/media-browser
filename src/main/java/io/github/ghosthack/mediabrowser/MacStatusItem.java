package io.github.ghosthack.mediabrowser;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The macOS menu-bar item shown while the app keeps serving the phone page
 * with its window closed (docs/web-mode.md, "Lifecycle"): an {@code
 * NSStatusItem} with a template SF Symbol and an {@code NSMenu} — "Serving
 * the phone page on port N", Open, Quit — built through the Objective-C
 * runtime. AppKit's own menu, not AWT's tray: JavaFX owns the main event
 * loop on macOS, where an AWT {@code TrayIcon}'s menu never opens. The menu
 * items target a tiny runtime class whose two methods are FFM upcalls.
 *
 * <p>Every call here must run on the AppKit main thread, which on macOS is
 * the JavaFX application thread; the item's actions arrive there too.</p>
 */
final class MacStatusItem {

    /** What the menu does; called on the JavaFX (= AppKit main) thread. */
    interface Actions {
        void open();

        void quit();
    }

    private static final AddressLayout ADDR = ValueLayout.ADDRESS;
    private static final double VARIABLE_LENGTH = -1.0;   // NSVariableStatusItemLength
    private static final String TARGET_CLASS = "MediaBrowserStatusItemTarget";

    private static volatile Actions actions;
    private static volatile Runnable onSystemQuit;
    private static Objc objc;               // bound once, on the main thread
    private static MemorySegment target;    // the menu items' target, kept for the process
    private static long openSel;
    private static long quitSel;
    private static long activeSel;

    private final MemorySegment item;

    private MacStatusItem(MemorySegment item) {
        this.item = item;
    }

    /** Adds the item for a phone page served on {@code port}; null if AppKit refused. */
    static MacStatusItem show(int port, Actions onAction) {
        try {
            Objc o = objc();
            actions = onAction;
            MemorySegment statusBar = o.send(o.cls("NSStatusBar"), "systemStatusBar");
            MemorySegment item = o.sendDouble(statusBar, "statusItemWithLength:", VARIABLE_LENGTH);
            if (isNil(item)) return null;
            o.send(item, "retain");                  // the status bar does not keep it
            MemorySegment button = o.send(item, "button");
            if (!isNil(button)) {
                MemorySegment image = o.send2(o.cls("NSImage"),
                        "imageWithSystemSymbolName:accessibilityDescription:",
                        o.nsString("square.grid.2x2"), o.nsString("Media Browser"));
                if (!isNil(image)) {
                    o.sendBool(image, "setTemplate:", true);
                    o.send1(button, "setImage:", image);
                } else {
                    o.send1(button, "setTitle:", o.nsString("MB"));
                }
                o.send1(button, "setToolTip:", o.nsString("Media Browser — phone page on port " + port));
            }
            MemorySegment menu = o.send1(o.send(o.cls("NSMenu"), "alloc"), "initWithTitle:", o.nsString(""));
            o.sendBool(menu, "setAutoenablesItems:", false);
            MemorySegment serving = menuItem(o, "Serving the phone page on port " + port, null);
            o.sendBool(serving, "setEnabled:", false);
            o.send1(menu, "addItem:", serving);
            o.send1(menu, "addItem:", o.send(o.cls("NSMenuItem"), "separatorItem"));
            o.send1(menu, "addItem:", menuItem(o, "Open Media Browser", "mediaBrowserOpen:"));
            o.send1(menu, "addItem:", menuItem(o, "Quit Media Browser", "mediaBrowserQuit:"));
            o.send1(item, "setMenu:", menu);
            return new MacStatusItem(item);
        } catch (Throwable t) {
            System.err.println("[MacStatusItem] could not add the menu-bar item: " + t);
            return null;
        }
    }

    /**
     * In the Dock and the app switcher ({@code NSApplicationActivationPolicyRegular})
     * or only in the menu bar ({@code …Accessory}): while the window is closed
     * the app leaves the Dock, where a click could not bring it back (JavaFX
     * offers no reopen event), and returns, frontmost, on Open.
     */
    static void setInDock(boolean inDock) {
        try {
            Objc o = objc();
            MemorySegment app = o.send(o.cls("NSApplication"), "sharedApplication");
            o.sendLong(app, "setActivationPolicy:", inDock ? 0L : 1L);
            if (inDock) o.sendBool(app, "activateIgnoringOtherApps:", true);
        } catch (Throwable t) {
            System.err.println("[MacStatusItem] could not change the Dock presence: " + t);
        }
    }

    /** Takes the item out of the menu bar; its actions (and the reopen on activation) stop. */
    void remove() {
        actions = null;
        try {
            Objc o = objc();
            o.send1(o.send(o.cls("NSStatusBar"), "systemStatusBar"), "removeStatusItem:", item);
            o.send(item, "release");
        } catch (Throwable t) {
            System.err.println("[MacStatusItem] could not remove the menu-bar item: " + t);
        }
    }

    private static MemorySegment menuItem(Objc o, String title, String action) throws Throwable {
        MemorySegment item = o.send3(o.send(o.cls("NSMenuItem"), "alloc"),
                "initWithTitle:action:keyEquivalent:", o.nsString(title),
                action == null ? MemorySegment.NULL : o.sel(action), o.nsString(""));
        if (action != null) o.send1(item, "setTarget:", target);
        return item;
    }

    /**
     * Takes over the {@code aevt/quit} Apple Event — what logout, restart,
     * shutdown, a script or another app sends — so it always runs {@code
     * quit}, whether or not a window is open. AppKit's own handler would ask
     * JavaFX, which only closes windows: with the app already in the menu bar
     * there are none, and the quit was refused ("User canceled"), holding up
     * a logout. The app menu's Quit (Cmd+Q) is not an Apple Event and is
     * untouched. Call once, on the main thread, after the toolkit started.
     */
    static void quitOnSystemQuit(Runnable quit) {
        try {
            Objc o = objc();
            onSystemQuit = quit;
            MemorySegment manager = o.send(o.cls("NSAppleEventManager"), "sharedAppleEventManager");
            o.setEventHandler(manager, target, o.sel("mediaBrowserHandleQuit:withReplyEvent:"),
                    0x61657674 /* 'aevt' */, 0x71756974 /* 'quit' */);
        } catch (Throwable t) {
            System.err.println("[MacStatusItem] could not take over the quit Apple Event: " + t);
        }
    }

    /** {@code -(void)handleQuit:(NSAppleEventDescriptor*)event withReplyEvent:(NSAppleEventDescriptor*)reply}. */
    @SuppressWarnings("unused")   // reached from native code
    private static void onQuitEvent(MemorySegment self, MemorySegment cmd, MemorySegment event,
                                    MemorySegment reply) {
        try {
            Runnable quit = onSystemQuit;
            if (quit != null) quit.run();
        } catch (Throwable t) {
            System.err.println("[MacStatusItem] quit failed: " + t);
        }
    }

    // ------------------------------------------------------------------ the target class

    /** The upcall behind both menu actions: {@code -(void)action:(id)sender}. */
    @SuppressWarnings("unused")   // reached from native code
    private static void onAction(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try {
            Actions a = actions;
            if (a == null) return;
            // Becoming active while hidden (a Dock, Finder or Launchpad click)
            // is a reopen: JavaFX has no event for it and no window to show.
            if (cmd.address() == openSel || cmd.address() == activeSel) a.open();
            else if (cmd.address() == quitSel) a.quit();
        } catch (Throwable t) {
            // never let an exception unwind into AppKit (it would end the process)
            System.err.println("[MacStatusItem] menu action failed: " + t);
        }
    }

    private static synchronized Objc objc() throws Throwable {
        if (objc != null) return objc;
        Objc o = new Objc();
        MemorySegment cls = o.cls(TARGET_CLASS);
        if (isNil(cls)) {
            cls = o.allocateClassPair(o.cls("NSObject"), TARGET_CLASS);
            MemorySegment imp = Linker.nativeLinker().upcallStub(
                    MethodHandles.lookup().findStatic(MacStatusItem.class, "onAction",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDR, ADDR, ADDR), Arena.global());
            o.addMethod(cls, o.sel("mediaBrowserOpen:"), imp, "v@:@");
            o.addMethod(cls, o.sel("mediaBrowserQuit:"), imp, "v@:@");
            o.addMethod(cls, o.sel("mediaBrowserDidBecomeActive:"), imp, "v@:@");
            MemorySegment quitImp = Linker.nativeLinker().upcallStub(
                    MethodHandles.lookup().findStatic(MacStatusItem.class, "onQuitEvent",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDR, ADDR, ADDR, ADDR), Arena.global());
            o.addMethod(cls, o.sel("mediaBrowserHandleQuit:withReplyEvent:"), quitImp, "v@:@@");
            o.registerClassPair(cls);
        }
        openSel = o.sel("mediaBrowserOpen:").address();
        quitSel = o.sel("mediaBrowserQuit:").address();
        activeSel = o.sel("mediaBrowserDidBecomeActive:").address();
        target = o.send(o.send(cls, "alloc"), "init");
        // addObserver:selector:name:object:
        o.send4(o.send(o.cls("NSNotificationCenter"), "defaultCenter"),
                "addObserver:selector:name:object:", target, o.sel("mediaBrowserDidBecomeActive:"),
                o.nsString("NSApplicationDidBecomeActiveNotification"), MemorySegment.NULL);
        objc = o;
        return o;
    }

    private static boolean isNil(MemorySegment s) {
        return s == null || s.address() == 0;
    }

    /** Objective-C messaging over the runtime, with C strings in the global arena (few, once each). */
    private static final class Objc {
        private final Linker linker = Linker.nativeLinker();
        private final SymbolLookup lib = MacObjc.lookup();
        private final MethodHandle getClass = h("objc_getClass", FunctionDescriptor.of(ADDR, ADDR));
        private final MethodHandle selRegister = h("sel_registerName", FunctionDescriptor.of(ADDR, ADDR));
        private final MethodHandle allocPair = h("objc_allocateClassPair",
                FunctionDescriptor.of(ADDR, ADDR, ADDR, ValueLayout.JAVA_LONG));
        private final MethodHandle registerPair = h("objc_registerClassPair", FunctionDescriptor.ofVoid(ADDR));
        private final MethodHandle classAddMethod = h("class_addMethod",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ADDR, ADDR, ADDR, ADDR));
        private final MethodHandle msg0 = h("objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        private final MethodHandle msg1 = h("objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR));
        private final MethodHandle msg2 = h("objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, ADDR));
        private final MethodHandle msg3 = h("objc_msgSend",
                FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        private final MethodHandle msg4 = h("objc_msgSend",
                FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        private final MethodHandle msgSetHandler = h("objc_msgSend", FunctionDescriptor.ofVoid(
                ADDR, ADDR, ADDR, ADDR, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private final MethodHandle msgDouble = h("objc_msgSend",
                FunctionDescriptor.of(ADDR, ADDR, ADDR, ValueLayout.JAVA_DOUBLE));
        private final MethodHandle msgBool = h("objc_msgSend",
                FunctionDescriptor.ofVoid(ADDR, ADDR, ValueLayout.JAVA_BOOLEAN));
        private final MethodHandle msgLong = h("objc_msgSend",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ADDR, ADDR, ValueLayout.JAVA_LONG));

        private MethodHandle h(String name, FunctionDescriptor fd) {
            return linker.downcallHandle(lib.find(name).orElseThrow(), fd);
        }

        private static MemorySegment c(String s) {
            return Arena.global().allocateFrom(s);
        }

        MemorySegment cls(String name) throws Throwable { return (MemorySegment) getClass.invoke(c(name)); }
        MemorySegment sel(String name) throws Throwable { return (MemorySegment) selRegister.invoke(c(name)); }
        MemorySegment send(MemorySegment r, String s) throws Throwable {
            return (MemorySegment) msg0.invoke(r, sel(s));
        }
        MemorySegment send1(MemorySegment r, String s, MemorySegment a) throws Throwable {
            return (MemorySegment) msg1.invoke(r, sel(s), a);
        }
        MemorySegment send2(MemorySegment r, String s, MemorySegment a, MemorySegment b) throws Throwable {
            return (MemorySegment) msg2.invoke(r, sel(s), a, b);
        }
        MemorySegment send3(MemorySegment r, String s, MemorySegment a, MemorySegment b, MemorySegment d)
                throws Throwable {
            return (MemorySegment) msg3.invoke(r, sel(s), a, b, d);
        }
        MemorySegment send4(MemorySegment r, String s, MemorySegment a, MemorySegment b, MemorySegment d,
                            MemorySegment e) throws Throwable {
            return (MemorySegment) msg4.invoke(r, sel(s), a, b, d, e);
        }
        void setEventHandler(MemorySegment manager, MemorySegment handler, MemorySegment selector,
                             int eventClass, int eventId) throws Throwable {
            msgSetHandler.invoke(manager, sel("setEventHandler:andSelector:forEventClass:andEventID:"),
                    handler, selector, eventClass, eventId);
        }
        MemorySegment sendDouble(MemorySegment r, String s, double v) throws Throwable {
            return (MemorySegment) msgDouble.invoke(r, sel(s), v);
        }
        void sendBool(MemorySegment r, String s, boolean v) throws Throwable {
            msgBool.invoke(r, sel(s), v);
        }
        boolean sendLong(MemorySegment r, String s, long v) throws Throwable {
            return (boolean) msgLong.invoke(r, sel(s), v);
        }
        MemorySegment nsString(String s) throws Throwable {
            return send1(cls("NSString"), "stringWithUTF8String:", c(s));
        }
        MemorySegment allocateClassPair(MemorySegment superclass, String name) throws Throwable {
            return (MemorySegment) allocPair.invoke(superclass, c(name), 0L);
        }
        void addMethod(MemorySegment cls, MemorySegment sel, MemorySegment imp, String types) throws Throwable {
            if (!(boolean) classAddMethod.invoke(cls, sel, imp, c(types))) {
                throw new IllegalStateException("class_addMethod refused " + types);
            }
        }
        void registerClassPair(MemorySegment cls) throws Throwable { registerPair.invoke(cls); }
    }
}
