package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;

/** A media file discovered in a directory, classified by the facade. */
public record MediaItem(Path path, MediaKind kind) {

    public String fileName() {
        return path.getFileName().toString();
    }

    @Override
    public String toString() {
        return "[" + kind.badge() + "] " + fileName();
    }
}
