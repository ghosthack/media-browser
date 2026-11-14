package io.github.ghosthack.mediabrowser.ui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Latest-wins gate for navigations whose target is resolved off the FX thread
 * — a chain-collapse descent, an archive mount — before the browser moves.
 *
 * <p>That work queues on the single file-op executor behind any move in
 * flight, so its result can land long after the user has gone elsewhere.
 * A result is stale, and dropped, once a newer resolution has been requested
 * or the user has left the location it was requested from — even to come
 * back: A, then B, then back to A still drops a request made from the first
 * visit to A. Leaving is counted by a navigation epoch the owner bumps through
 * {@link #locationChanged()} on every navigation. A refresh of the same
 * location (a move's re-list) does not count as leaving it, so a click made
 * during a move still lands once the move finishes.</p>
 *
 * <p>FX-thread confined: requests are made and outcomes delivered there.</p>
 */
final class NavigationRequests {

    private static final Object UNSEEN = new Object();

    private final Supplier<?> location;
    private Object seen = UNSEEN;
    private int epoch;
    private int latest;

    /** @param location the current location, compared with {@link Objects#equals} */
    NavigationRequests(Supplier<?> location) {
        this.location = Objects.requireNonNull(location);
    }

    /**
     * Notes a navigation: bumps the epoch, staling every pending request, unless
     * the location is the one already seen (a same-location refresh). The owner
     * calls this each time it sets its location, so a round trip that ends where
     * it started still counts as having left.
     */
    void locationChanged() {
        Object now = location.get();
        if (Objects.equals(now, seen)) return;
        seen = now;
        epoch++;
    }

    /**
     * Hands {@code work}'s outcome to {@code onCurrent} on {@code fx}, unless
     * by then another resolution was requested or the location changed.
     */
    <T> void resolve(CompletableFuture<T> work, Executor fx,
                     BiConsumer<? super T, ? super Throwable> onCurrent) {
        locationChanged();
        int ticket = ++latest;
        int from = epoch;
        work.whenComplete((value, error) -> fx.execute(() -> {
            locationChanged();   // also catches an owner that changed without noting it
            if (ticket != latest || from != epoch) return;
            onCurrent.accept(value, error);
        }));
    }
}
