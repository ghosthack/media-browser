package io.github.ghosthack.unrar;

/** The RAR container generation detected from the archive signature. */
public enum RarFormat {
    /** RAR 1.5 through 4.x containers. */
    RAR4,

    /** RAR 5.x and newer containers, including RAR 7 compression variants. */
    RAR5
}
