package io.github.ghosthack.mediabrowser.media.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** App-facing seam for payload-preserving zero-metadata exports. */
@FunctionalInterface
public interface ZeroMetadataStripper {
    Result stripDetailed(Path input) throws IOException;

    /** Backend-neutral result used by the UI and the OSS/full-build adapters. */
    record Result(Path input, Path output, long inputBytes, long outputBytes) {
        public Result {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            if (inputBytes < 0 || outputBytes < 0) {
                throw new IllegalArgumentException("Byte counts must be non-negative");
            }
        }

        public long removedBytes() {
            return Math.max(0, inputBytes - outputBytes);
        }
    }
}
