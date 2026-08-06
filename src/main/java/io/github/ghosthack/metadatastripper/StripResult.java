package io.github.ghosthack.metadatastripper;

import java.nio.file.Path;
import java.util.Objects;

/** Outcome of one successful metadata-stripping copy. */
public record StripResult(
        Path input,
        Path output,
        MediaFormat format,
        long inputBytes,
        long outputBytes) {

    public StripResult {
        input = Objects.requireNonNull(input, "input");
        output = Objects.requireNonNull(output, "output");
        format = Objects.requireNonNull(format, "format");
        if (inputBytes < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("byte counts must be non-negative");
        }
    }

    /** Number of bytes removed from the container; zero means the copy was unchanged in size. */
    public long removedBytes() {
        return Math.max(0, inputBytes - outputBytes);
    }
}

