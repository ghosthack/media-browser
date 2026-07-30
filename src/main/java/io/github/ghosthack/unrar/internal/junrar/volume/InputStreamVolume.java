/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.volume;

import io.github.ghosthack.unrar.internal.junrar.Archive;
import io.github.ghosthack.unrar.internal.junrar.io.SeekableReadOnlyByteChannel;
import io.github.ghosthack.unrar.internal.junrar.io.SeekableReadOnlyInputStream;
import java.io.IOException;
import java.io.InputStream;

public class InputStreamVolume implements Volume {

    private final Archive archive;
    private final InputStream inputStream;
    private final int position;

    public InputStreamVolume(
            final Archive archive, final InputStream inputStream, final int position) {
        this.archive = archive;
        this.inputStream = inputStream;
        this.position = position;
    }

    @Override
    public SeekableReadOnlyByteChannel getChannel() {
        return new SeekableReadOnlyInputStream(this.inputStream);
    }

    @Override
    public long getLength() {
        try {
            return inputStream.available();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public Archive getArchive() {
        return this.archive;
    }

    public int getPosition() {
        return position;
    }
}
