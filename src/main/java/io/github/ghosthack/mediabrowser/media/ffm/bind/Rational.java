package io.github.ghosthack.mediabrowser.media.ffm.bind;

/**
 * Plain-value view of an {@code AVRational} (a {@code num/den} pair), lifted out
 * of the version-specific {@code AVRational} struct so the decode logic never
 * touches a generated layout directly.
 */
public record Rational(int num, int den) {

    public static final Rational ZERO = new Rational(0, 0);

    /** True when both terms are positive (a usable, non-degenerate ratio). */
    public boolean isPositive() {
        return num > 0 && den > 0;
    }
}
