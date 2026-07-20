package io.github.ghosthack.panama.media.core;

/**
 * Platform detection constants for panama-media modules.
 * <p>
 * Centralizes OS detection so each module does not repeat the same
 * {@link System#getProperty(String)} string-matching logic.
 */
public final class Platform {

    private Platform() {}

    /** {@code true} when running on macOS or Darwin. */
    public static final boolean IS_MAC = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("mac");

    /** {@code true} when running on Windows. */
    public static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith("win");

    /** {@code true} when running on Linux. */
    public static final boolean IS_LINUX = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("linux");
}
