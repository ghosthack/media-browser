/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.volume;

import io.github.ghosthack.unrar.internal.junrar.Archive;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InputStreamVolumeManager implements VolumeManager {

    private final Map<Integer, InputStream> streams = new HashMap<>();

    public InputStreamVolumeManager(final InputStream is) {
        streams.put(1, is);
    }

    public InputStreamVolumeManager(List<InputStream> streams) {
        for (int i = 0; i < streams.size(); i++) {
            this.streams.put(i + 1, streams.get(i));
        }
    }

    @Override
    public Volume nextVolume(final Archive archive, final Volume lastVolume) {
        if (lastVolume == null) return new InputStreamVolume(archive, streams.get(1), 1);

        InputStreamVolume lastStreamVolume = (InputStreamVolume) lastVolume;
        int nextPosition = lastStreamVolume.getPosition() + 1;
        InputStream next = streams.get(nextPosition);
        if (next != null) return new InputStreamVolume(archive, next, nextPosition);
        return null;
    }
}
