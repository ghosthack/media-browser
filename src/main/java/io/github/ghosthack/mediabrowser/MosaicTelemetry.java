package io.github.ghosthack.mediabrowser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in low-overhead counters for diagnosing mosaic thumbnail/UI stalls.
 * Enable with {@code -Dmosaic.telemetry=true}.
 */
public final class MosaicTelemetry {

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("mosaic.telemetry", "false"));
    private static final long REPORT_INTERVAL_MS = longProperty(
            "mosaic.telemetry.intervalMs", 5_000, 1_000, 600_000);
    private static final long FX_STALL_NANOS = TimeUnit.MILLISECONDS.toNanos(longProperty(
            "mosaic.telemetry.fxStallMs", 50, 5, 10_000));

    private static final Sample FX_PULSE = new Sample();
    private static final Sample DRAW_QUEUE = new Sample();
    private static final Sample DRAW = new Sample();
    private static final LongAdder DRAW_TILES = new LongAdder();
    private static final Sample REPAINT = new Sample();
    private static final Sample DISPLAY_SCALE = new Sample();

    private static final LongAdder DRAW_COALESCED = new LongAdder();
    private static final LongAdder THUMB_REQUESTS = new LongAdder();
    private static final Sample THUMB_SUBMIT_FX = new Sample();
    private static final Sample THUMB_KEY_QUEUE = new Sample();
    private static final Sample THUMB_KEY_STAT = new Sample();
    private static final LongAdder THUMB_CACHE_HITS = new LongAdder();
    private static final LongAdder THUMB_IN_FLIGHT_JOINS = new LongAdder();
    private static final Sample THUMB_LOAD = new Sample();
    private static final Sample THUMB_TOTAL = new Sample();
    private static final Sample THUMB_FX_DELAY = new Sample();
    private static final Sample THUMB_FX_APPLY = new Sample();
    private static final Sample FX_IMAGE_CONVERT = new Sample();
    private static final AtomicLong THUMB_PENDING_MAX = new AtomicLong();

    private static final LongAdder FOLDER_REQUESTS = new LongAdder();
    private static final Sample FOLDER_TOTAL = new Sample();
    private static final Sample FOLDER_FX_DELAY = new Sample();
    private static final Sample FOLDER_FX_APPLY = new Sample();
    private static final AtomicLong FOLDER_PENDING_MAX = new AtomicLong();

    private static final LongAdder FX_STALLS = new LongAdder();
    private static final AtomicLong FX_STALL_MAX = new AtomicLong();

    private static final ScheduledExecutorService REPORTER;

    static {
        if (ENABLED) {
            REPORTER = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mosaic-telemetry");
                t.setDaemon(true);
                return t;
            });
            REPORTER.scheduleAtFixedRate(MosaicTelemetry::reportSafely,
                    REPORT_INTERVAL_MS, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            System.err.println("[mosaic.telemetry] enabled intervalMs=" + REPORT_INTERVAL_MS
                    + " fxStallMs=" + TimeUnit.NANOSECONDS.toMillis(FX_STALL_NANOS));
        } else {
            REPORTER = null;
        }
    }

    private MosaicTelemetry() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static long now() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static long elapsedSince(long startNanos) {
        return ENABLED && startNanos != 0L ? System.nanoTime() - startNanos : 0L;
    }

    public static void recordFxPulseInterval(long nanos) {
        if (!ENABLED || nanos <= 0) return;
        FX_PULSE.add(nanos);
        if (nanos >= FX_STALL_NANOS) {
            FX_STALLS.increment();
            updateMax(FX_STALL_MAX, nanos);
        }
    }

    public static void recordDrawQueued(long nanos) {
        if (ENABLED) DRAW_QUEUE.add(nanos);
    }

    public static void recordDrawCoalesced() {
        if (ENABLED) DRAW_COALESCED.increment();
    }

    public static void recordDraw(long nanos, int tiles) {
        if (!ENABLED) return;
        DRAW.add(nanos);
        DRAW_TILES.add(Math.max(0, tiles));
    }

    public static void recordRepaint(long nanos) {
        if (ENABLED) REPAINT.add(nanos);
    }

    public static void recordDisplayScale(long nanos) {
        if (ENABLED) DISPLAY_SCALE.add(nanos);
    }

    public static void recordThumbnailRequested(int pending) {
        if (!ENABLED) return;
        THUMB_REQUESTS.increment();
        updateMax(THUMB_PENDING_MAX, pending);
    }

    public static void recordThumbnailSubmitOnFx(long nanos) {
        if (ENABLED) THUMB_SUBMIT_FX.add(nanos);
    }

    public static void recordThumbnailKeyQueue(long nanos) {
        if (ENABLED) THUMB_KEY_QUEUE.add(nanos);
    }

    public static void recordThumbnailKeyStat(long nanos) {
        if (ENABLED) THUMB_KEY_STAT.add(nanos);
    }

    public static void recordThumbnailCacheHit() {
        if (ENABLED) THUMB_CACHE_HITS.increment();
    }

    public static void recordThumbnailInFlightJoin() {
        if (ENABLED) THUMB_IN_FLIGHT_JOINS.increment();
    }

    public static void recordThumbnailLoad(long nanos) {
        if (ENABLED) THUMB_LOAD.add(nanos);
    }

    public static void recordThumbnailTotal(long nanos) {
        if (ENABLED) THUMB_TOTAL.add(nanos);
    }

    public static void recordThumbnailFxDelay(long nanos) {
        if (ENABLED) THUMB_FX_DELAY.add(nanos);
    }

    public static void recordThumbnailFxApply(long nanos, int pending) {
        if (!ENABLED) return;
        THUMB_FX_APPLY.add(nanos);
        updateMax(THUMB_PENDING_MAX, pending);
    }

    public static void recordFxImageConvert(long nanos) {
        if (ENABLED) FX_IMAGE_CONVERT.add(nanos);
    }

    public static void recordFolderRequested(int pending) {
        if (!ENABLED) return;
        FOLDER_REQUESTS.increment();
        updateMax(FOLDER_PENDING_MAX, pending);
    }

    public static void recordFolderTotal(long nanos) {
        if (ENABLED) FOLDER_TOTAL.add(nanos);
    }

    public static void recordFolderFxDelay(long nanos) {
        if (ENABLED) FOLDER_FX_DELAY.add(nanos);
    }

    public static void recordFolderFxApply(long nanos, int pending) {
        if (!ENABLED) return;
        FOLDER_FX_APPLY.add(nanos);
        updateMax(FOLDER_PENDING_MAX, pending);
    }

    private static void reportSafely() {
        try {
            report();
        } catch (Throwable t) {
            System.err.println("[mosaic.telemetry] report failed: " + t);
        }
    }

    private static void report() {
        List<String> parts = new ArrayList<>();

        Snapshot pulse = FX_PULSE.drain();
        if (pulse.count > 0) parts.add("fxPulse " + pulse.format());
        long stalls = FX_STALLS.sumThenReset();
        long stallMax = FX_STALL_MAX.getAndSet(0);
        if (stalls > 0) {
            parts.add("fxStalls>" + ms(FX_STALL_NANOS) + "=" + stalls
                    + " max=" + ms(stallMax));
        }

        addSample(parts, "drawQueue", DRAW_QUEUE.drain());
        Snapshot draw = DRAW.drain();
        if (draw.count > 0) {
            long tiles = DRAW_TILES.sumThenReset();
            parts.add("draw " + draw.format() + " tiles=" + tiles);
        } else {
            DRAW_TILES.sumThenReset();
        }
        addCount(parts, "drawCoalesced", DRAW_COALESCED.sumThenReset());
        addSample(parts, "repaint", REPAINT.drain());
        addSample(parts, "displayScale", DISPLAY_SCALE.drain());

        addCount(parts, "thumbReq", THUMB_REQUESTS.sumThenReset());
        addSample(parts, "thumbSubmitFx", THUMB_SUBMIT_FX.drain());
        addSample(parts, "thumbKeyQueue", THUMB_KEY_QUEUE.drain());
        addSample(parts, "thumbKeyStat", THUMB_KEY_STAT.drain());
        addCount(parts, "thumbHit", THUMB_CACHE_HITS.sumThenReset());
        addCount(parts, "thumbJoin", THUMB_IN_FLIGHT_JOINS.sumThenReset());
        addSample(parts, "thumbLoad", THUMB_LOAD.drain());
        addSample(parts, "thumbTotal", THUMB_TOTAL.drain());
        addSample(parts, "thumbFxDelay", THUMB_FX_DELAY.drain());
        addSample(parts, "thumbFxApply", THUMB_FX_APPLY.drain());
        addSample(parts, "fxImageConvert", FX_IMAGE_CONVERT.drain());
        addCount(parts, "thumbPendingMax", THUMB_PENDING_MAX.getAndSet(0));

        addCount(parts, "folderReq", FOLDER_REQUESTS.sumThenReset());
        addSample(parts, "folderTotal", FOLDER_TOTAL.drain());
        addSample(parts, "folderFxDelay", FOLDER_FX_DELAY.drain());
        addSample(parts, "folderFxApply", FOLDER_FX_APPLY.drain());
        addCount(parts, "folderPendingMax", FOLDER_PENDING_MAX.getAndSet(0));

        if (!parts.isEmpty()) {
            System.err.println("[mosaic.telemetry] " + String.join(" | ", parts));
        }
    }

    private static void addSample(List<String> parts, String label, Snapshot snapshot) {
        if (snapshot.count > 0) parts.add(label + " " + snapshot.format());
    }

    private static void addCount(List<String> parts, String label, long value) {
        if (value > 0) parts.add(label + "=" + value);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    }

    private static void updateMax(AtomicLong max, long value) {
        long prev;
        do {
            prev = max.get();
            if (value <= prev) return;
        } while (!max.compareAndSet(prev, value));
    }

    private static long longProperty(String name, long fallback, long min, long max) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class Sample {
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        void add(long nanos) {
            if (nanos < 0) return;
            count.increment();
            total.add(nanos);
            updateMax(max, nanos);
        }

        Snapshot drain() {
            return new Snapshot(count.sumThenReset(), total.sumThenReset(), max.getAndSet(0));
        }
    }

    private record Snapshot(long count, long totalNanos, long maxNanos) {
        String format() {
            return "n=" + count
                    + " avg=" + ms(count == 0 ? 0 : totalNanos / count)
                    + " max=" + ms(maxNanos);
        }
    }
}
