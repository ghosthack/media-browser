/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar;

import io.github.ghosthack.unrar.internal.junrar.volume.Volume;

/**
 * @author alban
 */
public interface UnrarCallback {

    /**
     * @param nextVolume ,
     *
     * @return {@code true} if the next volume is ready to be processed,
     *         {@code false} otherwise.
     */
    boolean isNextVolumeReady(Volume nextVolume);

    /**
     * This method is invoked each time the progress of the current
     * volume changes.
     *
     * @param current .
     * @param total .
     *
     */
    void volumeProgressChanged(long current, long total);
}
