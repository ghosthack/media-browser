package io.github.ghosthack.mediabrowser.ui;

import java.lang.reflect.Method;

/**
 * Waits for JavaFX's render thread to finish (paint <em>and present</em>)
 * every render job queued so far.
 *
 * <p>Why: a JavaFX pulse runs animation timers, then the scene pulse
 * listeners, then hands the frame to the render thread — and, with the
 * default multithreaded renderer, does <em>not</em> wait for that frame
 * before the next pulse starts ({@code QuantumToolkit.pulse}). So "two
 * pulses have started" says nothing about whether anything has reached the
 * window yet; on a cold start (shader compilation, driver warm-up) the
 * first frame can still be in flight tens of milliseconds later. {@link
 * WindowChrome#showWithoutFlash} needs "a frame has been presented" before
 * it makes the window visible, or the reveal exposes the never-painted
 * native surface — white — until that first present lands. That was the
 * sporadic startup white flash on Windows (2026-09).
 *
 * <p>The render thread is a single FIFO executor, so a no-op job run
 * through the public {@code QuantumToolkit.runInRenderThreadAndWait}
 * returns only after every earlier job — including its present — has
 * completed. Internal API, reached reflectively (the module-path run adds
 * {@code --add-exports javafx.graphics/com.sun.javafx.tk.quantum}); if it
 * is ever unavailable this says so once and returns, leaving the caller
 * with the plain pulse-count behaviour.
 */
final class RenderSync {
    private static final Method RUN_IN_RENDER_THREAD_AND_WAIT;
    private static boolean warned;

    static {
        Method m = null;
        try {
            m = Class.forName("com.sun.javafx.tk.quantum.QuantumToolkit")
                    .getMethod("runInRenderThreadAndWait", Runnable.class);
        } catch (Throwable t) {
            System.err.println("RenderSync unavailable (" + t + "); window reveals cannot wait for a"
                    + " presented frame and may flash");
        }
        RUN_IN_RENDER_THREAD_AND_WAIT = m;
    }

    private RenderSync() {}

    /** Blocks the caller (the FX thread) until all currently queued render jobs have been presented. */
    static void awaitQueuedRenders() {
        if (RUN_IN_RENDER_THREAD_AND_WAIT == null) return;
        try {
            RUN_IN_RENDER_THREAD_AND_WAIT.invoke(null, (Runnable) () -> { });
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.err.println("RenderSync failed (" + t + "); window reveals cannot wait for a"
                        + " presented frame and may flash");
            }
        }
    }
}
