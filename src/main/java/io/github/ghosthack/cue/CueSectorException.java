package io.github.ghosthack.cue;

/** A physical sector cannot be represented by the track's normalized data view. */
public final class CueSectorException extends CueArchiveException {
    public CueSectorException(String message) {
        super(message);
    }
}
