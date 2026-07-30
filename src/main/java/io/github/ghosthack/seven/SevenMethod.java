package io.github.ghosthack.seven;

/**
 * A compression, encryption, or executable-transform method in a 7z coder chain.
 *
 * <p>Methods are listed in archive order. Encryption is represented explicitly
 * so callers can describe an entry without depending on decoder implementation
 * classes.
 */
public enum SevenMethod {
    COPY,
    LZMA,
    LZMA2,
    DEFLATE,
    DEFLATE64,
    BZIP2,
    AES256_SHA256,
    BCJ_X86,
    BCJ_POWER_PC,
    BCJ_IA64,
    BCJ_ARM,
    BCJ_ARM_THUMB,
    BCJ_SPARC,
    DELTA
}

