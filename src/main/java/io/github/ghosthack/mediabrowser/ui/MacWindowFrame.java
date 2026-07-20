package io.github.ghosthack.mediabrowser.ui;

import javafx.stage.Screen;
import javafx.stage.Stage;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

/**
 * Lets an undecorated stage's macOS window grow past the screen edges so an
 * {@code F}-maximize can overscan its hairline border off-screen.
 *
 * <p>JavaFX's Glass {@code NSWindow} subclass overrides
 * {@code constrainFrameRect:toScreen:} to keep the window horizontally within
 * the display: it stores the requested (oversized) bounds but the native
 * {@code setFrame:} snaps the frame back inside the screen width, so a side
 * overscan always collapses to a flush edge. (Stock AppKit only constrains the
 * vertical, to keep a title bar reachable — this horizontal clamp is Glass's.)
 *
 * <p>{@link #ensureUnconstrained} replaces that method on the window's class
 * with an identity implementation (returns the frame unchanged) via the Objective-C
 * runtime: {@code object_getClass} + {@code class_replaceMethod}, the new
 * implementation being an FFM upcall stub matching the method's
 * {@code NSRect (id, SEL, NSRect, NSScreen*)} ABI. Done once; idempotent.
 *
 * <p>The NSWindow handle comes from the Glass peer (needs the {@code --add-exports}
 * in pom.xml). Fails soft: on any error the constraint stays and the window
 * merely keeps flush sides, reported once on stderr.
 */
final class MacWindowFrame {

    /** {@code NSRect { CGFloat x, y, w, h; }} — an arm64 homogeneous float aggregate. */
    private static final GroupLayout NSRECT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"), JAVA_DOUBLE.withName("y"),
            JAVA_DOUBLE.withName("w"), JAVA_DOUBLE.withName("h"));

    /** {@code NSSize { CGFloat w, h; }}. */
    private static final GroupLayout NSSIZE = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("w"), JAVA_DOUBLE.withName("h"));

    /** ObjC type encoding for {@code NSRect constrainFrameRect:(NSRect) toScreen:(id)}. */
    private static final String CONSTRAIN_TYPES =
            "{CGRect={CGPoint=dd}{CGSize=dd}}@:{CGRect={CGPoint=dd}{CGSize=dd}}@";

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static volatile boolean unconstrained;
    private static boolean warned;
    private static boolean clampReported;

    private MacWindowFrame() {}

    /**
     * Neutralizes the horizontal screen-constraint on {@code stage}'s native
     * window class so a subsequent over-sized {@code Stage} bounds change is not
     * clamped back inside the screen. Returns {@code true} once the constraint
     * is disabled (or already was); {@code false} off macOS or on any failure.
     */
    static boolean ensureUnconstrained(Stage stage) {
        if (!IS_MAC) return false;
        try {
            MemorySegment nsWindow = nsWindowFor(stage);
            if (nsWindow == null || nsWindow.address() == 0) return false;

            var linker = Linker.nativeLinker();
            var objc = io.github.ghosthack.mediabrowser.MacObjc.lookup();
            MethodHandle selRegisterName = linker.downcallHandle(
                    objc.findOrThrow("sel_registerName"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
            MethodHandle setSize = linker.downcallHandle(objc.findOrThrow("objc_msgSend"),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, NSSIZE));

            try (Arena tmp = Arena.ofConfined()) {
                // (1) Position clamp: replace constrainFrameRect:toScreen: with
                //     an identity once, so a negative origin isn't pulled back.
                if (!unconstrained) {
                    MethodHandle objectGetClass = linker.downcallHandle(
                            objc.findOrThrow("object_getClass"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS));
                    MethodHandle classReplaceMethod = linker.downcallHandle(
                            objc.findOrThrow("class_replaceMethod"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
                    Arena global = Arena.global();
                    MemorySegment cls = (MemorySegment) objectGetClass.invokeExact(nsWindow);
                    MemorySegment sel = (MemorySegment) selRegisterName.invokeExact(
                            tmp.allocateFrom("constrainFrameRect:toScreen:"));
                    MethodHandle target = MethodHandles.lookup().findStatic(
                            MacWindowFrame.class, "constrainIdentity",
                            MethodType.methodType(MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class));
                    MemorySegment imp = linker.upcallStub(target,
                            FunctionDescriptor.of(NSRECT, ADDRESS, ADDRESS, NSRECT, ADDRESS),
                            global);
                    // Returns the previous IMP (null if none); discarded via invoke.
                    classReplaceMethod.invoke(cls, sel, imp,
                            global.allocateFrom(CONSTRAIN_TYPES));
                    unconstrained = true;
                }

                // (2) Size clamp: Glass caps the window's maxSize at the screen
                //     size, so the width can't exceed it. Lift it every maximize
                //     (Glass may re-apply it) so the frame can be wider.
                MemorySegment selSetMax = (MemorySegment) selRegisterName.invokeExact(
                        tmp.allocateFrom("setMaxSize:"));
                MemorySegment size = tmp.allocate(NSSIZE);
                size.set(JAVA_DOUBLE, 0, 1.0e6);
                size.set(JAVA_DOUBLE, 8, 1.0e6);
                setSize.invokeExact(nsWindow, selSetMax, size);
            }
            return true;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.err.println("Native window overscan unavailable: " + t);
            }
            return false;
        }
    }

    /**
     * Identity {@code constrainFrameRect:toScreen:}: returns the proposed frame
     * unchanged so AppKit/Glass never pulls the window back inside the screen.
     * Invoked by AppKit through the installed upcall stub; not called from Java.
     */
    @SuppressWarnings("unused")
    private static MemorySegment constrainIdentity(MemorySegment self, MemorySegment cmd,
                                                   MemorySegment frameRect, MemorySegment screen) {
        return frameRect;
    }

    /**
     * Sets the stage's native window frame to the visual-bounds rectangle
     * {@code [bx, by, bw, bh]} grown by {@code over} px on every edge, after
     * lifting both clamps. Because a direct {@code setFrame:} is immediate (and
     * Glass applies its own bounds asynchronously), call this <em>after</em>
     * Glass has settled so it has the last word. Warns once on stderr if the
     * frame still comes back clamped. No-op / {@code false} off macOS or on failure.
     */
    static boolean overscanFrame(Stage stage, double bx, double by,
                                 double bw, double bh, double over) {
        if (!IS_MAC || !ensureUnconstrained(stage)) return false;
        try {
            MemorySegment nsWindow = nsWindowFor(stage);
            if (nsWindow == null || nsWindow.address() == 0) return false;

            // JavaFX (top-left origin) -> Cocoa (bottom-left, Y from primary bottom).
            double primaryHeight = Screen.getPrimary().getBounds().getHeight();
            double fx = bx - over, fy = by - over, fw = bw + 2 * over, fh = bh + 2 * over;
            double cocoaX = fx, cocoaY = primaryHeight - (fy + fh);

            var linker = Linker.nativeLinker();
            var objc = io.github.ghosthack.mediabrowser.MacObjc.lookup();
            MethodHandle setFrame = linker.downcallHandle(objc.findOrThrow("objc_msgSend"),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, NSRECT, JAVA_BYTE));
            MethodHandle getFrame = linker.downcallHandle(objc.findOrThrow("objc_msgSend"),
                    FunctionDescriptor.of(NSRECT, ADDRESS, ADDRESS));
            MethodHandle selRegisterName = linker.downcallHandle(
                    objc.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                var selSet = (MemorySegment) selRegisterName.invokeExact(
                        arena.allocateFrom("setFrame:display:"));
                var selGet = (MemorySegment) selRegisterName.invokeExact(
                        arena.allocateFrom("frame"));
                MemorySegment rect = arena.allocate(NSRECT);
                rect.set(JAVA_DOUBLE, 0, cocoaX);
                rect.set(JAVA_DOUBLE, 8, cocoaY);
                rect.set(JAVA_DOUBLE, 16, fw);
                rect.set(JAVA_DOUBLE, 24, fh);
                setFrame.invokeExact(nsWindow, selSet, rect, (byte) 1); // display: YES
                MemorySegment got = (MemorySegment) getFrame.invoke(arena, nsWindow, selGet);
                double gx = got.get(JAVA_DOUBLE, 0), gw = got.get(JAVA_DOUBLE, 16);
                if ((Math.abs(gx - cocoaX) > 1 || Math.abs(gw - fw) > 1) && !clampReported) {
                    clampReported = true;
                    System.err.printf("Native window overscan partly clamped:"
                            + " set x=%.1f w=%.1f got x=%.1f w=%.1f%n", cocoaX, fw, gx, gw);
                }
            }
            return true;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.err.println("Native window overscan unavailable: " + t);
            }
            return false;
        }
    }

    /**
     * The stage's current native window frame as {@code {x, y, w, h}} in Cocoa
     * coordinates, or {@code null} off macOS / on failure. Read-only; used to
     * detect when Glass has applied its asynchronous maximize.
     */
    static double[] nativeFrame(Stage stage) {
        if (!IS_MAC) return null;
        try {
            MemorySegment nsWindow = nsWindowFor(stage);
            if (nsWindow == null || nsWindow.address() == 0) return null;
            var linker = Linker.nativeLinker();
            var objc = io.github.ghosthack.mediabrowser.MacObjc.lookup();
            MethodHandle getFrame = linker.downcallHandle(objc.findOrThrow("objc_msgSend"),
                    FunctionDescriptor.of(NSRECT, ADDRESS, ADDRESS));
            MethodHandle sel = linker.downcallHandle(objc.findOrThrow("sel_registerName"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                var selFrame = (MemorySegment) sel.invokeExact(arena.allocateFrom("frame"));
                MemorySegment f = (MemorySegment) getFrame.invoke(arena, nsWindow, selFrame);
                return new double[] {f.get(JAVA_DOUBLE, 0), f.get(JAVA_DOUBLE, 8),
                        f.get(JAVA_DOUBLE, 16), f.get(JAVA_DOUBLE, 24)};
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** The {@code NSWindow} pointer behind {@code stage}, via its Glass peer. */
    private static MemorySegment nsWindowFor(Stage stage) throws Exception {
        Class<?> helper = Class.forName("com.sun.javafx.stage.WindowHelper");
        Method getPeer = helper.getMethod("getPeer", javafx.stage.Window.class);
        Object tkStage = getPeer.invoke(null, stage); // com.sun.javafx.tk.quantum.WindowStage
        if (tkStage == null) return null;
        Method getPlatformWindow = tkStage.getClass().getMethod("getPlatformWindow");
        Object glassWindow = getPlatformWindow.invoke(tkStage); // com.sun.glass.ui.Window
        if (glassWindow == null) return null;
        long handle = (long) handleAccessor(glassWindow.getClass()).invoke(glassWindow);
        return MemorySegment.ofAddress(handle);
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
