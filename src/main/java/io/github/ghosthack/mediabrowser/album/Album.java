package io.github.ghosthack.mediabrowser.album;

import java.nio.file.Path;

/**
 * One album: a {@code album-NNN.csv} file in the application directory together
 * with its display name (the file's base name, e.g. {@code album-000}). The
 * file lists the absolute paths of its members, one per line.
 *
 * @param file the backing CSV file
 * @param name the display name (the file's base name, without the extension)
 */
public record Album(Path file, String name) {

    /** The backing file's name, e.g. {@code album-000.csv} — the recents key. */
    public String fileName() {
        Path n = file.getFileName();
        return n == null ? file.toString() : n.toString();
    }
}
