/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.exception;

/**
 * Thrown at extraction time when a file header declares a compression method junrar does not
 * support. Method 36 ("alternative hash", RAR 3.6.1 beta) was dropped upstream in unrar 5.0.0
 * (reports/unrar-delta-map.md &sect;3); junrar drops it too and refuses the member with this
 * typed error rather than silently mis-decoding it as method 29. Extraction-time, one entry per
 * offending file -- NOT part of {@code Archive.setChannel}'s rethrow filter (MIGRATION_MANUAL
 * &sect;4.9).
 */
public class UnsupportedRarMethodException extends RarException {
    public UnsupportedRarMethodException(Throwable cause) {
        super(cause);
    }

    public UnsupportedRarMethodException() {}

    public UnsupportedRarMethodException(String message) {
        super(message);
    }
}
