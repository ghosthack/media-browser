/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.volume;

import io.github.ghosthack.unrar.internal.junrar.Archive;
import io.github.ghosthack.unrar.internal.junrar.io.SeekableReadOnlyByteChannel;
import java.io.IOException;

/**
 * @author <a href="http://www.rogiel.com">Rogiel</a>
 *
 */
public interface Volume {
    /**
     * @return SeekableReadOnlyByteChannel the channel
     * @throws IOException .
     */
    SeekableReadOnlyByteChannel getChannel() throws IOException;

    /**
     * @return the data length
     */
    long getLength();

    /**
     * @return the archive this volume belongs to
     */
    Archive getArchive();
}
