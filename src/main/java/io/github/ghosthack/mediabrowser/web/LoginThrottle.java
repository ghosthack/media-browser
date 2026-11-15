package io.github.ghosthack.mediabrowser.web;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Slows passkey guessing without parking server threads. Each client address
 * gets {@link #FREE_FAILURES} wrong passkeys for free; every further one locks
 * it out for twice as long as the last (1 s, 2 s, 4 s … capped at
 * {@link #MAX_LOCKOUT}). A correct passkey clears the address. Independently,
 * more than {@link #GLOBAL_FAILURES} failures from all addresses within
 * {@link #GLOBAL_WINDOW} lock every login until the oldest of them ages out,
 * so spreading guesses over many addresses buys nothing.
 *
 * <p>A locked-out attempt is refused before its passkey is even compared
 * (the server answers 429 with {@code Retry-After}), so the lockout is real
 * and costs the server nothing.</p>
 */
final class LoginThrottle {

    static final int FREE_FAILURES = 3;
    static final Duration MAX_LOCKOUT = Duration.ofMinutes(5);
    static final int GLOBAL_FAILURES = 30;
    static final Duration GLOBAL_WINDOW = Duration.ofMinutes(10);
    /** Addresses remembered at most; the oldest is forgotten beyond this. */
    private static final int MAX_TRACKED = 1024;

    private record Failures(int count, long lockedUntil) {}

    private final LongSupplier nanoClock;
    private final Map<String, Failures> byAddress = new HashMap<>();
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

    LoginThrottle() {
        this(System::nanoTime);
    }

    LoginThrottle(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** How long {@code address} must still wait, or {@link Duration#ZERO} when it may try now. */
    synchronized Duration retryAfter(String address) {
        long now = nanoClock.getAsLong();
        long wait = 0;
        Failures f = byAddress.get(address);
        if (f != null) wait = Math.max(wait, f.lockedUntil - now);
        expireGlobal(now);
        if (recent.size() >= GLOBAL_FAILURES) {
            wait = Math.max(wait, recent.peekFirst() + GLOBAL_WINDOW.toNanos() - now);
        }
        return wait > 0 ? Duration.ofNanos(wait) : Duration.ZERO;
    }

    /** Records a wrong passkey from {@code address}. */
    synchronized void failed(String address) {
        long now = nanoClock.getAsLong();
        Failures f = byAddress.getOrDefault(address, new Failures(0, now));
        int count = f.count + 1;
        long lockedUntil = now;
        if (count > FREE_FAILURES) {
            int doublings = Math.min(count - FREE_FAILURES - 1, 20);
            long lockout = Math.min(Duration.ofSeconds(1).toNanos() << doublings, MAX_LOCKOUT.toNanos());
            lockedUntil = now + lockout;
        }
        if (byAddress.size() >= MAX_TRACKED && !byAddress.containsKey(address)) {
            byAddress.remove(byAddress.keySet().iterator().next());
        }
        byAddress.put(address, new Failures(count, lockedUntil));
        expireGlobal(now);
        recent.addLast(now);
    }

    /** A correct passkey from {@code address}: its count starts over. */
    synchronized void succeeded(String address) {
        byAddress.remove(address);
    }

    private void expireGlobal(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() >= GLOBAL_WINDOW.toNanos()) {
            recent.removeFirst();
        }
    }
}
