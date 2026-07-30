package io.github.ghosthack.cue;

/** One CUE {@code INDEX} marker, measured in 75-Hz CD frames. */
public record CueIndex(int number, long frames) {
    public CueIndex {
        if (number < 0 || number > 99) {
            throw new IllegalArgumentException("index number must be between 0 and 99");
        }
        if (frames < 0) {
            throw new IllegalArgumentException("index frames cannot be negative");
        }
    }
}
