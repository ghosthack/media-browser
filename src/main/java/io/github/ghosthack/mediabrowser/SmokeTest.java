package io.github.ghosthack.mediabrowser;

import io.github.ghosthack.mediabrowser.gl.GlVideoRenderer;
import io.github.ghosthack.mediabrowser.media.MediaBackend;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.VideoStream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Headless check of the native facade (no JavaFX): classifies, probes and
 * decodes the visual of each file given on the command line. Video files are
 * additionally decoded for a few frames through the streaming decoder and the
 * LWJGL offscreen renderer (the playback pipeline minus the UI).
 *
 * <p>Run with: {@code java --enable-native-access=ALL-UNNAMED
 * --sun-misc-unsafe-memory-access=allow
 * -cp "target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
 * io.github.ghosthack.mediabrowser.SmokeTest <files...>}</p>
 */
public final class SmokeTest {

    public static void main(String[] args) {
        var backend = MediaBackend.fromSettings(
                System.getProperty("media.backend", "twelvemonkeys-ffmpeg-ffm"));
        try (MediaFacade facade = backend.create()) {
            System.out.println("backend: " + backend + " | native: " + facade.nativeVersions());
            for (String arg : args) {
                Path file = Path.of(arg);
                System.out.println("== " + file);
                // Per-file isolation: under the no-fallback windows-native design a
                // MediaException (a RuntimeException) is expected for unsupported or
                // missing-Store-codec files, so one bad file must not abort the run
                // and lose results for every later file.
                try {
                    var kind = facade.classify(file);
                    System.out.println("  classify: " + kind.map(Enum::toString).orElse("not media"));
                    if (kind.isEmpty()) continue;
                    facade.probe(file).describe()
                            .forEach((k, v) -> System.out.println("  " + k + ": " + v));
                    dumpMetadata(facade, file);
                    var visual = facade.loadVisual(file);
                    System.out.println(visual.frame()
                            .map(f -> "  visual: " + f.width() + "x" + f.height()
                                    + " (" + f.bgra().length + " bytes BGRA)")
                            .orElse("  visual: none"));
                    long t0 = System.nanoTime();
                    var thumb = facade.loadThumbnail(file, 300);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.println(thumb.frame()
                            .map(f -> "  thumbnail(300): " + f.width() + "x" + f.height()
                                    + " (" + f.bgra().length + " bytes BGRA) in " + ms + " ms")
                            .orElse("  thumbnail(300): none in " + ms + " ms"));
                    long t1 = System.nanoTime();
                    var fill = facade.loadThumbnail(file, 300,
                            io.github.ghosthack.mediabrowser.media.ThumbnailMode.FILL);
                    long fillMs = (System.nanoTime() - t1) / 1_000_000;
                    System.out.println(fill.frame()
                            .map(f -> "  thumbnail(300,FILL): " + f.width() + "x" + f.height()
                                    + " (" + f.bgra().length + " bytes BGRA) in " + fillMs + " ms")
                            .orElse("  thumbnail(300,FILL): none in " + fillMs + " ms"));
                    if (kind.get() == MediaKind.IMAGE
                            && visual.frame().isPresent() && thumb.frame().isPresent()) {
                        orientationParity(visual.frame().get(), thumb.frame().get());
                    }
                    if (kind.get() == MediaKind.VIDEO) {
                        playbackSmoke(facade, file);
                    }
                } catch (RuntimeException e) {
                    System.out.println("  error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Regression guard for the still decode pipeline. A facade may decode the
     * FIT thumbnail and the full visual through different code paths
     * (historically libvips' {@code vips_thumbnail} vs
     * {@code vips_image_new_from_file}; FFmpeg/ImageIO facades have analogous
     * splits), so an EXIF orientation (90°/180°/mirror) applied in one path but
     * not the other would silently diverge — exactly the bug this catches. Compares aspect
     * orientation and a coarse, scaler-tolerant content grid (the two come from
     * different scalers, so an exact pixel match is not expected) and prints OK
     * or a MISMATCH warning.
     */
    private static void orientationParity(RasterFrame visual, RasterFrame thumb) {
        boolean aspectOk = Integer.signum(visual.width() - visual.height())
                == Integer.signum(thumb.width() - thumb.height());
        double[] grid = {0.2, 0.4, 0.6, 0.8};
        double sum = 0;
        int n = 0;
        for (double v : grid) {
            for (double u : grid) {
                sum += Math.abs(luma(visual, u, v) - luma(thumb, u, v));
                n++;
            }
        }
        double meanDiff = sum / n;
        // 90° turns also swap the aspect (aspectOk catches them); the content grid
        // additionally catches 180° and mirror divergence that keeps the aspect.
        boolean ok = aspectOk && meanDiff <= 50;
        System.out.printf("  orientation-parity: %s (aspect %s, mean luma diff %.1f)%n",
                ok ? "OK" : "MISMATCH", aspectOk ? "match" : "DIVERGED", meanDiff);
    }

    /** Nearest-sampled luma at normalized {@code (u, v)} of a BGRA frame. */
    private static double luma(RasterFrame f, double u, double v) {
        int x = (int) Math.round(u * (f.width() - 1));
        int y = (int) Math.round(v * (f.height() - 1));
        int i = (y * f.width() + x) * 4;
        byte[] p = f.bgra();
        int b = p[i] & 0xff, g = p[i + 1] & 0xff, r = p[i + 2] & 0xff;
        return 0.114 * b + 0.587 * g + 0.299 * r;
    }

    /**
     * Reads the on-demand full metadata and prints it grouped, capping each
     * printed value (the model already caps {@code value()}; we trim further for
     * a one-line dump) and flagging binary/truncated rows. Confirms the native
     * read works headless per backend before any UI exists.
     */
    private static void dumpMetadata(MediaFacade facade, Path file) {
        long t0 = System.nanoTime();
        var md = facade.readMetadata(file);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int entries = md.entryCount();
        System.out.println("  metadata: " + md.groups().size() + " group(s), "
                + entries + (entries == 1 ? " entry" : " entries") + " in " + ms + " ms");
        for (var group : md.groups()) {
            System.out.println("    [" + group.name() + "]");
            for (var e : group.entries()) {
                String v = e.value().replace('\n', ' ').replace('\r', ' ');
                if (v.length() > 120) v = v.substring(0, 120) + "\u2026";
                String flags = (e.binary() ? " (binary)" : "")
                        + (e.truncated()
                                ? " (truncated, full " + e.fullValue().length() + " chars)" : "");
                System.out.println("      " + e.key() + " = " + v + flags);
            }
        }
    }

    /** Decodes a few frames through the video stream + GL renderer. */
    private static void playbackSmoke(MediaFacade facade, Path file) {
        try (var stream = facade.openVideo(file)) {
            System.out.println("  playback: " + stream.width() + "x" + stream.height()
                    + ", duration " + stream.durationMicros() / 1000 + " ms");
            GlVideoRenderer renderer;
            try {
                renderer = new GlVideoRenderer(stream.width(), stream.height());
            } catch (Throwable glUnavailable) {
                // GlVideoRenderer uses LWJGL CGL (macOS-windowless OpenGL only), so
                // off macOS it cannot be constructed. Fall back to verifying the
                // VideoStream contract directly so Phase 3 playback is still
                // exercised headless on the target OS.
                System.out.println("  playback: GL renderer unavailable ("
                        + glUnavailable + "); verifying VideoStream contract directly");
                playbackSmokeNoGl(stream);
                return;
            }
            // macOS GL path — unchanged.
            try (renderer) {
                ByteBuffer out = ByteBuffer.allocateDirect(stream.width() * stream.height() * 4);
                for (int i = 0; i < 5 && stream.next(); i++) {
                    renderer.render(stream.bgra(), out);
                    int mid = (stream.height() / 2 * stream.width() + stream.width() / 2) * 4;
                    System.out.printf("  frame %d: pts %d ms, center BGRA #%02x%02x%02x%02x%n",
                            i, stream.ptsMicros() / 1000, out.get(mid), out.get(mid + 1),
                            out.get(mid + 2), out.get(mid + 3));
                }
            }
        }
    }

    /**
     * Verifies the {@link VideoStream} contract directly, without the macOS-only
     * GL renderer: each decoded frame's BGRA buffer must be {@code width*height*4}
     * bytes and {@link VideoStream#ptsMicros()} must advance monotonically across
     * the first ~5 frames. Used on non-macOS (e.g. Windows 11) so Phase 3 playback
     * is exercised headless on the actual target OS.
     */
    private static void playbackSmokeNoGl(VideoStream stream) {
        long expected = (long) stream.width() * stream.height() * 4;
        long prevPts = Long.MIN_VALUE;
        for (int i = 0; i < 5 && stream.next(); i++) {
            MemorySegment bgra = stream.bgra();
            long size = bgra.byteSize();
            if (size != expected) {
                throw new IllegalStateException("bgra() is " + size
                        + " bytes, expected width*height*4 = " + expected);
            }
            int mid = (stream.height() / 2 * stream.width() + stream.width() / 2) * 4;
            int b = Byte.toUnsignedInt(bgra.get(ValueLayout.JAVA_BYTE, mid));
            int g = Byte.toUnsignedInt(bgra.get(ValueLayout.JAVA_BYTE, mid + 1));
            int r = Byte.toUnsignedInt(bgra.get(ValueLayout.JAVA_BYTE, mid + 2));
            int a = Byte.toUnsignedInt(bgra.get(ValueLayout.JAVA_BYTE, mid + 3));
            long pts = stream.ptsMicros();
            boolean monotonic = pts >= prevPts;
            System.out.printf("  frame %d: pts %d ms (%s), bgra %d bytes (ok),"
                            + " center BGRA #%02x%02x%02x%02x%n",
                    i, pts / 1000, monotonic ? "monotonic" : "NON-MONOTONIC",
                    size, b, g, r, a);
            prevPts = pts;
        }
    }
}
