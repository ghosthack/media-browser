package io.github.ghosthack.mediabrowser.ui;

import javafx.application.Platform;
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
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Windows only: the two native-surface details JavaFX leaves to the window
 * class defaults, both of which otherwise produce a white frame on a dark
 * theme.
 *
 * <p><b>Reveal without un-layering</b> ({@link #revealOpaque}). {@link
 * WindowChrome#showWithoutFlash} shows a stage at opacity 0 and reveals it
 * a couple of pulses later. Glass implements opacity on Windows with
 * {@code WS_EX_LAYERED} + {@code SetLayeredWindowAttributes}, and
 * {@code GlassWindow::SetAlpha} <em>removes</em> the layered style the
 * moment the alpha reaches exactly 255 — i.e. on {@code setOpacity(1)}.
 * Removing that style makes Windows hand the window a fresh surface, which
 * is white until JavaFX's next present; the {@code WM_PAINT} it raises is
 * only serviced on a later FX pulse, so on a busy cold start the white was
 * measured on screen for ~40ms (an in-app pixel probe, since removed).
 * Sporadic because the exposure lasts "until the next presented frame": a
 * view that keeps presenting (the viewer loading images) covers it, a
 * static one (the mosaic right after its first draw) does not. The only
 * way to make it never happen is to never strip the style from a visible
 * window: reveal to {@value #REVEAL_OPACITY} — Glass truncates the alpha
 * ({@code BYTE(255.f * value)}), so anything below 1.0 stays layered — and
 * then set the layered alpha to 255 ourselves so the composition is
 * exactly opaque (no 1/255 blend with whatever is behind the window). The
 * stage's opacity property reads 0.999 afterwards; nothing in the app
 * reads it. Glass un-layers on full-screen entry and re-applies its 254
 * on exit, so the exit is followed by a re-apply here.
 *
 * <p><b>Class background brush</b> ({@link #install}). The Glass window
 * class is registered with a {@code NULL} {@code hbrBackground} and its
 * window procedure has no {@code WM_ERASEBKGND} handler, so whenever
 * Windows does erase a JavaFX window (a resize exposing a new strip) it
 * shows white until the next present. Glass's Java side has a scene-fill
 * → class-brush path ({@code WindowStage.initPlatformWindow} → {@code
 * Window.setBackground}) but {@code WinWindow._setBackground} is a
 * deliberate no-op (JDK-8171852); this does what its disabled {@code
 * _setBackground2} does: {@code SetClassLongPtr(hwnd, GCLP_HBRBACKGROUND,
 * CreateSolidBrush(fill))}, re-applied on theme changes. The brush is per
 * window class, i.e. shared by every Glass window in the process; the
 * last stage to (re)apply wins.
 *
 * <p>Any failure is reported once and the caller falls back to plain
 * JavaFX behaviour; nothing here affects correctness, only whether a
 * native surface can briefly show white.
 */
final class WinWindowSurface {
    private static final int GCLP_HBRBACKGROUND = -10;
    private static final int LWA_ALPHA = 0x2;
    /** Below 1.0 so Glass never strips WS_EX_LAYERED; the real alpha is set to 255 natively. */
    static final double REVEAL_OPACITY = 0.999;

    private static MethodHandle setClassLongPtr;
    private static MethodHandle createSolidBrush;
    private static MethodHandle deleteObject;
    private static MethodHandle setLayeredWindowAttributes;
    private static boolean unavailable;
    /** The brush this class installed last; deleted once replaced (never a foreign brush). */
    private static MemorySegment ownBrush = MemorySegment.NULL;

    private WinWindowSurface() {}

    /**
     * Reveals a showing, opacity-0 {@code stage} fully opaque without Glass
     * ever removing {@code WS_EX_LAYERED} (see the class comment).
     *
     * @return {@code false} if the native call is unavailable or failed, in
     *         which case nothing was changed and the caller should fall back
     *         to {@code stage.setOpacity(1)}
     */
    static boolean revealOpaque(Stage stage) {
        if (unavailable || !stage.isShowing()) return false;
        try {
            if (setClassLongPtr == null) bind();
            MemorySegment hwnd = hwndFor(stage);
            if (hwnd == null) return false;
            stage.setOpacity(REVEAL_OPACITY);
            fullyOpaque(hwnd);
            // Glass un-layers on full-screen entry and re-applies its own
            // (254) alpha on exit; restore 255 after that exit has landed.
            stage.fullScreenProperty().addListener((o, was, full) -> {
                if (!full) Platform.runLater(() -> {
                    try {
                        MemorySegment h = hwndFor(stage);
                        if (h != null && stage.getOpacity() == REVEAL_OPACITY) fullyOpaque(h);
                    } catch (Throwable t) {
                        System.err.println("Opaque reveal re-apply after full screen failed: " + t);
                    }
                });
            });
            return true;
        } catch (Throwable t) {
            unavailable = true;
            System.err.println("Opaque reveal unavailable; falling back to setOpacity(1): " + t);
            return false;
        }
    }

    private static void fullyOpaque(MemorySegment hwnd) throws Throwable {
        int ok = (int) setLayeredWindowAttributes.invokeExact(hwnd, 0, (byte) 0xFF, LWA_ALPHA);
        if (ok == 0) throw new IllegalStateException("SetLayeredWindowAttributes failed");
    }

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
        setLayeredWindowAttributes = linker.downcallHandle(user32.findOrThrow("SetLayeredWindowAttributes"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_BYTE, JAVA_INT));
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
