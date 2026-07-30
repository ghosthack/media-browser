package io.github.ghosthack.cue;

import java.util.List;
import java.util.Objects;

/** Immutable layout metadata for one track. */
public record CueTrack(
        int index,
        int number,
        CueTrackMode mode,
        CueFile file,
        List<CueIndex> indices,
        long pregapFrames,
        long postgapFrames,
        long storedDataOffset,
        long storedDataBytes,
        long logicalBytes) {

    public CueTrack {
        if (index < 0) throw new IllegalArgumentException("index cannot be negative");
        if (number < 1 || number > 99) {
            throw new IllegalArgumentException("track number must be between 1 and 99");
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(file, "file");
        indices = List.copyOf(indices);
        if (pregapFrames < 0 || postgapFrames < 0) {
            throw new IllegalArgumentException("gap lengths cannot be negative");
        }
        if (storedDataOffset < 0 || storedDataBytes < 0 || logicalBytes < 0) {
            throw new IllegalArgumentException("track sizes and offsets cannot be negative");
        }
    }

    /** Whether this track can be opened as a normalized data channel. */
    public boolean supportedData() {
        return mode.supportedData();
    }

    /** Returns an index marker by number, if present. */
    public java.util.Optional<CueIndex> cueIndex(int number) {
        return indices.stream().filter(index -> index.number() == number).findFirst();
    }
}
