/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar;

/**
 * The on-disk RAR format family, classified from the archive signature's version byte
 * (unrar {@code RARFORMAT}, {@code d861246:archive.cpp:100-126}). Exposed via
 * {@link Archive#getFormat()}.
 */
public enum RarFormat {

    /**
     * The classic format family (RAR 1.5 through 4.x, signature version byte {@code 0x00};
     * the old RAR 1.4 marker maps here too). unrar {@code RARFMT15}/{@code RARFMT14}.
     */
    RAR15,

    /**
     * The RAR 5.0 format (signature version byte {@code 0x01}). unrar {@code RARFMT50}.
     */
    RAR50
}
