package io.github.ghosthack.cue;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;

/** A source file whose identity contributes to the opened CUE/BIN set. */
public record CueSource(
        Path path,
        long size,
        FileTime lastModifiedTime,
        Optional<String> fileKey,
        boolean cueSheet) {

    public CueSource {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (size < 0) throw new IllegalArgumentException("size cannot be negative");
        Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        fileKey = Objects.requireNonNull(fileKey, "fileKey");
    }
}
