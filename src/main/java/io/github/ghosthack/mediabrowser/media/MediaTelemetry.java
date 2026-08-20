package io.github.ghosthack.mediabrowser.media;

/**
 * Telemetry seam for the media layer. The thumbnail pipeline reports its
 * timings here; a host that wants them installs a {@link Sink} at startup
 * (the JavaFX app forwards to its opt-in {@code -Dmosaic.telemetry}
 * counters). Without a sink every call is a no-op and {@link #now()}
 * returns 0, so the disabled path stays free of clock reads — and the
 * media layer never depends on a host-side reporter class.
 */
public final class MediaTelemetry {

    /**
     * Receives thumbnail-pipeline events. Implementations must be cheap and
     * thread-safe: calls arrive from the thumbnail worker pool, and
     * {@link #enabled()} gates every {@link System#nanoTime()} read.
     */
    public interface Sink {
        boolean enabled();
        void recordThumbnailKeyQueue(long nanos);
        void recordThumbnailKeyStat(long nanos);
        void recordThumbnailCacheHit();
        void recordThumbnailInFlightJoin();
        void recordThumbnailLoad(long nanos);
    }

    private static final Sink NO_OP = new Sink() {
        @Override public boolean enabled() { return false; }
        @Override public void recordThumbnailKeyQueue(long nanos) {}
        @Override public void recordThumbnailKeyStat(long nanos) {}
        @Override public void recordThumbnailCacheHit() {}
        @Override public void recordThumbnailInFlightJoin() {}
        @Override public void recordThumbnailLoad(long nanos) {}
    };

    private static volatile Sink sink = NO_OP;

    private MediaTelemetry() {}

    /** Installs the host's sink; {@code null} restores the no-op. */
    public static void install(Sink s) {
        sink = s == null ? NO_OP : s;
    }

    public static long now() {
        return sink.enabled() ? System.nanoTime() : 0L;
    }

    public static long elapsedSince(long startNanos) {
        return startNanos != 0L && sink.enabled() ? System.nanoTime() - startNanos : 0L;
    }

    public static void recordThumbnailKeyQueue(long nanos) {
        sink.recordThumbnailKeyQueue(nanos);
    }

    public static void recordThumbnailKeyStat(long nanos) {
        sink.recordThumbnailKeyStat(nanos);
    }

    public static void recordThumbnailCacheHit() {
        sink.recordThumbnailCacheHit();
    }

    public static void recordThumbnailInFlightJoin() {
        sink.recordThumbnailInFlightJoin();
    }

    public static void recordThumbnailLoad(long nanos) {
        sink.recordThumbnailLoad(nanos);
    }
}
