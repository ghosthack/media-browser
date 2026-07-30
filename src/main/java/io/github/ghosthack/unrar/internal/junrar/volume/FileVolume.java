/*
 * Derived from junrar and the UnRAR lineage.
 * These sources may not be used to develop a RAR-compatible archiver.
 * See LICENSE, NOTICE.md, and PROVENANCE.toml.
 */
package io.github.ghosthack.unrar.internal.junrar.volume;

import io.github.ghosthack.unrar.internal.junrar.Archive;
import io.github.ghosthack.unrar.internal.junrar.io.SeekableReadOnlyByteChannel;
import io.github.ghosthack.unrar.internal.junrar.io.SeekableReadOnlyFile;
import java.io.File;
import java.io.IOException;

/**
 * @author <a href="http://www.rogiel.com">Rogiel</a>
 *
 */
public class FileVolume implements Volume {
    private final Archive archive;
    private final File file;

    /**
     * @param archive .
     * @param file .
     */
    public FileVolume(Archive archive, File file) {
        this.archive = archive;
        this.file = file;
    }

    @Override
    public SeekableReadOnlyByteChannel getChannel() throws IOException {
        return new SeekableReadOnlyFile(file);
    }

    @Override
    public long getLength() {
        return file.length();
    }

    @Override
    public Archive getArchive() {
        return archive;
    }

    /**
     * @return the file
     */
    public File getFile() {
        return file;
    }
}
