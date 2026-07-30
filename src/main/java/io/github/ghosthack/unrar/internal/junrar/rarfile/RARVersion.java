/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.rarfile;

/**
 * Known versions of the rar file format.
 */
public enum RARVersion {
    OLD,
    V4,
    V5;

    /**
     * Checks if the version passed is the old rar format.
     * @param version Version to check if it is the old format.
     * @return <code>true</code> if the format is the old format. Otherwise false.
     */
    public static boolean isOldFormat(final RARVersion version) {
        return version == OLD;
    }
}
