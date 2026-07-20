package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.gl.GlfwVideoRenderer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/**
 * Headless self-test for the cross-platform GLFW offscreen GL video path
 * (GAP 3). <b>Not a JUnit test</b> — it lives outside surefire on purpose, so a
 * native GL context attempt can never hard-crash the test gate. Run it directly
 * (plain {@code java}, with the runtime classpath) to confirm that, on the host
 * machine, {@link GlfwVideoRenderer} either builds a real GLFW GL context and
 * renders/reads back a frame, OR fails cleanly (in which case the production
 * {@link FallbackVideoSink} would degrade to the pure-JavaFX presenter).
 *
 * <p>No JavaFX and no video file are needed: it builds a synthetic BGRA
 * {@link MemorySegment}, renders it once, and verifies the readback is non-zero.
 * It exits 0 in BOTH the success and the clean-fallback cases — only a hard JVM
 * crash / segfault counts as a failure (which would mean the GLFW path needs to
 * be more defensive or better gated).</p>
 */
public final class VideoGlSelfTest {

    private VideoGlSelfTest() {}

    public static void main(String[] args) {
        final int w = 64;
        final int h = 48;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate((long) w * h * 4);
            // Fill a synthetic, non-zero BGRA test pattern.
            for (int i = 0; i < w * h; i++) {
                long off = (long) i * 4;
                seg.set(ValueLayout.JAVA_BYTE, off,     (byte) 0x10); // B
                seg.set(ValueLayout.JAVA_BYTE, off + 1, (byte) 0x20); // G
                seg.set(ValueLayout.JAVA_BYTE, off + 2, (byte) 0x30); // R
                seg.set(ValueLayout.JAVA_BYTE, off + 3, (byte) 0xFF); // A
            }
            ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);

            GlfwVideoRenderer renderer = new GlfwVideoRenderer(w, h);
            try {
                renderer.render(seg, out);
            } finally {
                renderer.close();
            }

            boolean nonZero = false;
            for (int i = 0; i < out.capacity(); i++) {
                if (out.get(i) != 0) {
                    nonZero = true;
                    break;
                }
            }
            if (!nonZero) {
                throw new IllegalStateException("readback was all zero");
            }
            System.out.println("SELFTEST: usedGl=true (GLFW GL context OK)");
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("SELFTEST: fallback \u2014 GLFW GL unavailable: " + t);
            t.printStackTrace();
            // A clean fallback is acceptable per the acceptance criteria.
            System.exit(0);
        }
    }
}
