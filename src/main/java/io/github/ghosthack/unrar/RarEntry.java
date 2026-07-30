package io.github.ghosthack.unrar;

import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable snapshot of one archive member's metadata.
 *
 * <p>{@link #name()} is an archive name. It must not be treated as a safe host
 * file-system path.
 */
public record RarEntry(
        int index,
        String name,
        Kind kind,
        long compressedSize,
        long uncompressedSize,
        boolean uncompressedSizeKnown,
        Optional<FileTime> lastModifiedTime,
        boolean encrypted,
        boolean solid,
        boolean splitBefore,
        boolean splitAfter) {

    /** The kind of archive member. */
    public enum Kind {
        FILE,
        DIRECTORY
    }

    public RarEntry {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        if (compressedSize < 0 || uncompressedSize < 0) {
            throw new IllegalArgumentException("entry sizes must be non-negative");
        }
    }

    /** Returns whether this member contains bytes that can be opened. */
    public boolean isRegularFile() {
        return kind == Kind.FILE;
    }
}
