/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.exception;

public class NotRarArchiveException extends RarException {
    public NotRarArchiveException(Throwable cause) {
        super(cause);
    }

    public NotRarArchiveException() {}
}
