package io.github.ghosthack.seven;

import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * An immutable snapshot of one 7z member's metadata.
 *
 * <p>{@link #name()} is the raw archive name. It is not a safe host file-system
 * path. Duplicate names remain distinct through {@link #index()}.
 */
public record SevenEntry(
        int index,
        String name,
        Kind kind,
        long uncompressedSize,
        boolean hasStream,
        OptionalLong crc32,
        Optional<FileTime> creationTime,
        Optional<FileTime> lastAccessTime,
        Optional<FileTime> lastModifiedTime,
        OptionalInt windowsAttributes,
        List<SevenMethod> methods,
        boolean encrypted) {

    /** The semantic kind recorded by the container. */
    public enum Kind {
        FILE,
        DIRECTORY,
        /** A differential-backup marker that removes a prior member. */
        ANTI_ITEM
    }

    public SevenEntry {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("uncompressedSize must be non-negative");
        }
        Objects.requireNonNull(crc32, "crc32");
        Objects.requireNonNull(creationTime, "creationTime");
        Objects.requireNonNull(lastAccessTime, "lastAccessTime");
        Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        Objects.requireNonNull(windowsAttributes, "windowsAttributes");
        methods = List.copyOf(methods);
        if (kind != Kind.FILE && hasStream) {
            throw new IllegalArgumentException("only file entries may have a content stream");
        }
    }

    /** Returns whether this member can be opened as a byte stream. */
    public boolean isRegularFile() {
        return kind == Kind.FILE;
    }
}

