package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable diagnostic account of one foreground media-engine operation.
 *
 * <p>The engine is the user-selected implementation. Attempts are the ordered
 * internal strategies it used, including failed strategies that preceded the
 * successful one. This makes compatibility routing observable without making
 * implementation strategies separate engine choices.</p>
 */
public record MediaEngineTrace(
        String engine,
        Path path,
        String operation,
        Outcome outcome,
        long elapsedNanos,
        List<Attempt> attempts,
        Timings timings) {

    public MediaEngineTrace {
        engine = Objects.requireNonNull(engine, "engine");
        path = Objects.requireNonNull(path, "path");
        operation = Objects.requireNonNull(operation, "operation");
        outcome = Objects.requireNonNull(outcome, "outcome");
        attempts = List.copyOf(attempts);
        timings = timings == null ? Timings.UNKNOWN : timings;
    }

    /** Compatibility constructor for callers that do not provide phase timing. */
    public MediaEngineTrace(String engine, Path path, String operation, Outcome outcome,
                            long elapsedNanos, List<Attempt> attempts) {
        this(engine, path, operation, outcome, elapsedNanos, attempts, Timings.UNKNOWN);
    }

    /** End-to-end timing split; negative means unavailable or not yet complete. */
    public record Timings(long queueNanos, long engineNanos, long postProcessNanos,
                          long timeToDisplayNanos) {
        public static final Timings UNKNOWN = new Timings(-1, -1, -1, -1);

        public Timings withTimeToDisplay(long nanos) {
            return new Timings(queueNanos, engineNanos, postProcessNanos,
                    Math.max(0L, nanos));
        }
    }

    public MediaEngineTrace withTimeToDisplay(long nanos) {
        return new MediaEngineTrace(engine, path, operation, outcome, elapsedNanos,
                attempts, timings.withTimeToDisplay(nanos));
    }

    public enum Outcome {
        SUCCEEDED,
        /** Strategy did not claim the input; capability routing, not failure. */
        DECLINED,
        FAILED
    }

    /** One concrete strategy attempt in execution order. */
    public record Attempt(
            String strategy,
            Outcome outcome,
            long elapsedNanos,
            String detail) {
        public Attempt {
            strategy = Objects.requireNonNull(strategy, "strategy");
            outcome = Objects.requireNonNull(outcome, "outcome");
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Thread-confined recorder passed down through a foreground decode.
     * Disabled recorders let direct facade callers retain the legacy API
     * without allocating a diagnostic trace.
     */
    public static final class Recorder {
        private static final Recorder DISABLED =
                new Recorder("disabled", Path.of("."), "disabled", false, 0L);

        private final String engine;
        private final Path path;
        private final String operation;
        private final boolean enabled;
        private final long requestedNanos;
        private final long startedNanos;
        private final List<Attempt> attempts = new ArrayList<>();
        private long engineNanos = -1;
        private long postProcessNanos = -1;

        private Recorder(String engine, Path path, String operation, boolean enabled,
                         long requestedNanos) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.path = Objects.requireNonNull(path, "path");
            this.operation = Objects.requireNonNull(operation, "operation");
            this.enabled = enabled;
            this.startedNanos = System.nanoTime();
            this.requestedNanos = requestedNanos > 0 ? requestedNanos : this.startedNanos;
        }

        public static Recorder recording(String engine, Path path, String operation) {
            return new Recorder(engine, path, operation, true, System.nanoTime());
        }

        /** Records time already spent waiting before execution began. */
        public static Recorder recording(String engine, Path path, String operation,
                                         long requestedNanos) {
            return new Recorder(engine, path, operation, true, requestedNanos);
        }

        public static Recorder disabled() {
            return DISABLED;
        }

        /** Timestamp token to pass to {@link #succeeded} or {@link #failed}. */
        public long beginAttempt() {
            return enabled ? System.nanoTime() : 0L;
        }

        public void succeeded(String strategy, long attemptStartedNanos, String detail) {
            add(strategy, Outcome.SUCCEEDED, attemptStartedNanos, detail);
        }

        public void declined(String strategy, long attemptStartedNanos, String detail) {
            add(strategy, Outcome.DECLINED, attemptStartedNanos, detail);
        }

        public void failed(String strategy, long attemptStartedNanos, Throwable failure) {
            add(strategy, Outcome.FAILED, attemptStartedNanos, failureDetail(failure));
        }

        public void failed(String strategy, long attemptStartedNanos, String detail) {
            add(strategy, Outcome.FAILED, attemptStartedNanos, detail);
        }

        /** Adds an attempt whose duration was measured by a lower layer. */
        public void record(String strategy, Outcome outcome, long elapsedNanos, String detail) {
            if (!enabled) return;
            attempts.add(new Attempt(
                    strategy, outcome, Math.max(0L, elapsedNanos), detail));
        }

        public boolean hasAttempts() {
            return enabled && !attempts.isEmpty();
        }

        public void engineWork(long elapsedNanos) {
            if (enabled) engineNanos = Math.max(0L, elapsedNanos);
        }

        public void postProcess(long elapsedNanos) {
            if (enabled) postProcessNanos = Math.max(0L, elapsedNanos);
        }

        public MediaEngineTrace finish(Throwable failure) {
            if (!enabled) throw new IllegalStateException("disabled trace recorder");
            return new MediaEngineTrace(
                    engine,
                    path,
                    operation,
                    failure == null ? Outcome.SUCCEEDED : Outcome.FAILED,
                    Math.max(0L, System.nanoTime() - requestedNanos),
                    attempts,
                    new Timings(Math.max(0L, startedNanos - requestedNanos),
                            engineNanos, postProcessNanos, -1L));
        }

        private void add(String strategy, Outcome outcome, long attemptStartedNanos,
                         String detail) {
            if (!enabled) return;
            attempts.add(new Attempt(
                    strategy,
                    outcome,
                    Math.max(0L, System.nanoTime() - attemptStartedNanos),
                    detail));
        }
    }

    /** Compact, single-line failure text suitable for a diagnostic table. */
    public static String failureDetail(Throwable failure) {
        if (failure == null) return "Unknown failure";
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String text = root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= 180 ? text : text.substring(0, 179) + "…";
    }
}
