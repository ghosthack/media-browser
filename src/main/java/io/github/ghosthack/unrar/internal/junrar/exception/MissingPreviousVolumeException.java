/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.exception;

/**
 * Extraction started from a mid-set volume: the entry continues data split from a previous
 * volume ({@code HFL_SPLITBEFORE}) that this extraction never saw (RAR5 multi-volume, M3.9
 * issue #30; unrar {@code UIERROR_NEEDPREVVOL} "You need to start extraction from a previous
 * volume", {@code 8f437ab:extract.cpp:475}). RAR3/RAR4 keep their historical untyped behavior.
 */
public class MissingPreviousVolumeException extends RarException {
    public MissingPreviousVolumeException() {
        super();
    }

    public MissingPreviousVolumeException(final String message) {
        super(message);
    }

    public MissingPreviousVolumeException(final Throwable cause) {
        super(cause);
    }
}
