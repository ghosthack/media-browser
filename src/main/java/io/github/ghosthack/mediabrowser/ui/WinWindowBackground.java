package io.github.ghosthack.mediabrowser.ui;

import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Windows only: gives the JavaFX window class a background brush equal to a
 * stage's scene fill, so every native (re)paint of the window's surface
 * starts from the theme colour instead of white.
 *
 * <p>Why this exists: the Glass window class is registered with a
 * {@code NULL} {@code hbrBackground}, and its window procedure has no
 * {@code WM_ERASEBKGND} handler, so Windows never erases a JavaFX window —
 * the surface simply holds whatever it holds until JavaFX presents. That is
 * fine while the window is stable, but whenever Windows hands the window a
 * <em>fresh</em> surface it is white until the next present: on the
 * {@link WindowChrome#showWithoutFlash} reveal, {@code setOpacity(1)} strips
 * {@code WS_EX_LAYERED} ({@code GlassWindow::SetAlpha}), Windows re-creates
 * the surface, and the {@code WM_PAINT} it raises is only serviced on a
 * later FX pulse — a sporadic white frame on a dark theme, longest exactly
 * when the FX thread is busiest (cold start). Glass's Java side even has a
 * class-brush setter fed from the scene fill ({@code
 * WindowStage.initPlatformWindow} → {@code Window.setBackground}), but
 * {@code WinWindow._setBackground} is a deliberate no-op (JDK-8171852).
 * This does what Glass's disabled {@code _setBackground2} does:
 * {@code SetClassLongPtr(hwnd, GCLP_HBRBACKGROUND, CreateSolidBrush(fill))}.
 * With a brush in place, Windows erases the fresh surface in the fill
 * colour before JavaFX's next present, and the same holds for the
 * newly-exposed strip while resizing a dark window.
 *
 * <p>The brush is per window class, i.e. shared by every Glass window in
 * the process; the last stage to (re)apply wins, which is the right answer
 * for one theme at a time. Re-applied whenever the scene fill changes
 * (theme switch). Any failure is reported once and ignored: nothing here
 * affects correctness, only the colour of a native erase.
 */
final class WinWindowBackground {
    private static final int GCLP_HBRBACKGROUND = -10;

    private static MethodHandle setClassLongPtr;
    private static MethodHandle createSolidBrush;
    private static MethodHandle deleteObject;
    private static boolean unavailable;
    /** The brush this class installed last; deleted once replaced (never a foreign brush). */
    private static MemorySegment ownBrush = MemorySegment.NULL;

    private WinWindowBackground() {}

    /**
     * Installs the scene-fill brush for {@code stage} (which must already be
     * showing, so its native window exists) and keeps it in step with later
     * fill changes.
     */
    static void install(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return;
        apply(stage, scene.getFill());
        scene.fillProperty().addListener((o, was, fill) -> apply(stage, fill));
    }

    private static void apply(Stage stage, Paint fill) {
        if (unavailable || !(fill instanceof Color c) || !stage.isShowing()) return;
        try {
            if (setClassLongPtr == null) bind();
            MemorySegment hwnd = hwndFor(stage);
            if (hwnd == null) return;
            int colorref = (to255(c.getBlue()) << 16) | (to255(c.getGreen()) << 8) | to255(c.getRed());
            MemorySegment brush = (MemorySegment) createSolidBrush.invokeExact(colorref);
            if (brush.equals(MemorySegment.NULL)) throw new IllegalStateException("CreateSolidBrush failed");
            long previous = (long) setClassLongPtr.invokeExact(hwnd, GCLP_HBRBACKGROUND, brush.address());
            if (previous != 0 && previous == ownBrush.address()) {
                int ignored = (int) deleteObject.invokeExact(ownBrush);
            }
            ownBrush = brush;
        } catch (Throwable t) {
            unavailable = true;
            System.err.println("Window background brush unavailable; native erases stay white: " + t);
        }
    }

    private static int to255(double channel) {
        return (int) Math.round(Math.clamp(channel, 0, 1) * 255);
    }

    private static void bind() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
        SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32.dll", Arena.global());
        setClassLongPtr = linker.downcallHandle(user32.findOrThrow("SetClassLongPtrW"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
        createSolidBrush = linker.downcallHandle(gdi32.findOrThrow("CreateSolidBrush"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        deleteObject = linker.downcallHandle(gdi32.findOrThrow("DeleteObject"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    /** The {@code HWND} behind {@code stage}, via its Glass peer (mirrors {@code MacWindowFrame}). */
    private static MemorySegment hwndFor(Stage stage) throws Exception {
        Class<?> helper = Class.forName("com.sun.javafx.stage.WindowHelper");
        Method getPeer = helper.getMethod("getPeer", javafx.stage.Window.class);
        Object tkStage = getPeer.invoke(null, stage);
        if (tkStage == null) return null;
        Object glassWindow = tkStage.getClass().getMethod("getPlatformWindow").invoke(tkStage);
        if (glassWindow == null) return null;
        Method accessor = null;
        for (String name : new String[] {"getNativeHandle", "getNativeWindow", "getRawHandle"}) {
            try {
                accessor = glassWindow.getClass().getMethod(name);
                break;
            } catch (NoSuchMethodException e) {
                // try the next known name
            }
        }
        if (accessor == null) throw new NoSuchMethodException(glassWindow.getClass() + " has no native handle accessor");
        long handle = (long) accessor.invoke(glassWindow);
        return handle == 0 ? null : MemorySegment.ofAddress(handle);
    }
}
