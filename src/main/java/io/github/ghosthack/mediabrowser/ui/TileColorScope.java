package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.color.ColorPolicy;

import java.util.function.Supplier;

/**
 * The color policy a view's own tile images were filled under. The service's
 * thumbnail cache keys renditions by {@link ColorPolicy.Snapshot#signature()},
 * but a view that keeps converted FX images of its own must drop them when the
 * policy changes (a viewer's transient Color/Gamut override, its reset, or
 * <b>Apply always</b>), or it goes on painting the other policy's pixels.
 *
 * <p>FX-thread confined. {@link #changed()} is cheap enough to call per tile
 * per frame; {@link #epoch()} tags requests so a completion rendered before
 * the change is dropped instead of re-filling the cleared cache.</p>
 */
final class TileColorScope {

    private final Supplier<ColorPolicy.Snapshot> policy;
    private ColorPolicy.Snapshot seen;
    private String signature;
    private int epoch;

    TileColorScope() {
        this(ColorPolicy::snapshot);
    }

    TileColorScope(Supplier<ColorPolicy.Snapshot> policy) {
        this.policy = policy;
        this.seen = policy.get();
        this.signature = seen.signature();
    }

    /**
     * True, once, when the policy now renders different pixels from the one
     * the tiles were filled under: the caller drops its tile images and
     * in-flight requests. A change that does not alter the pixels (gamut while
     * unmanaged) is not one.
     */
    boolean changed() {
        ColorPolicy.Snapshot now = policy.get();
        if (now == seen || now.equals(seen)) return false;
        seen = now;
        String nowSignature = now.signature();
        if (nowSignature.equals(signature)) return false;
        signature = nowSignature;
        epoch++;
        return true;
    }

    /** Bumped by every {@link #changed()} that returned true. */
    int epoch() {
        return epoch;
    }
}
