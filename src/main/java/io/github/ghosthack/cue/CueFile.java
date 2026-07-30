package io.github.ghosthack.cue;

import java.nio.file.Path;
import java.util.Objects;

/** One resolved companion declared by a CUE {@code FILE} command. */
public record CueFile(
        int index,
        String declaredName,
        Path path,
        CueFileType type,
        long size) {

    public CueFile {
        if (index < 0) throw new IllegalArgumentException("index cannot be negative");
        Objects.requireNonNull(declaredName, "declaredName");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(type, "type");
        if (size < 0) throw new IllegalArgumentException("size cannot be negative");
    }
}
