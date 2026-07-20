package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;

/**
 * One entry of a directory listing: the parent link ({@code ..}), a
 * subdirectory, a viewable media file (classified by the facade) or any
 * other file.
 *
 * @param mediaKind         the media classification; non-null exactly for
 *                          {@link Type#MEDIA} entries
 * @param size              the file size in bytes; {@code 0} for {@link Type#PARENT}
 *                          and {@link Type#DIRECTORY} entries (and for files whose
 *                          size could not be read)
 * @param lastModifiedMillis the file's last-modified time in millis since epoch;
 *                          {@code 0} when unknown or inapplicable
 */
public record DirEntry(Path path, Type type, MediaKind mediaKind, long size, long lastModifiedMillis) {

    public enum Type { PARENT, DIRECTORY, MEDIA, OTHER }

    /** Whether the entry can be opened in the viewer. */
    public boolean viewable() {
        return type == Type.MEDIA;
    }

    public String displayName() {
        if (type == Type.PARENT) return "..";
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /**
     * The lower-cased file extension (without the dot), or an empty string for
     * the parent link, directories and extension-less names. A leading dot
     * (e.g. {@code .bashrc}) counts as the base name, not an extension.
     */
    public String extension() {
        if (type == Type.PARENT || type == Type.DIRECTORY) return "";
        String name = displayName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    public MediaItem toMediaItem() {
        return new MediaItem(path, mediaKind);
    }
}
