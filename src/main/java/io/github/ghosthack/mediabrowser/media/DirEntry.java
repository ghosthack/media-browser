package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.Locale;

/**
 * One entry of a directory listing: the parent link ({@code ..}), a
 * subdirectory, a browsable archive, a viewable media file (classified by the
 * facade) or any other file.
 *
 * @param mediaKind         the media classification; non-null exactly for
 *                          {@link Type#MEDIA} entries
 * @param size              the file size in bytes; {@code 0} for {@link Type#PARENT}
 *                          and {@link Type#DIRECTORY} entries (and for files whose
 *                          size could not be read). An {@link Type#ARCHIVE} keeps
 *                          its real size — it is still a file on disk
 * @param lastModifiedMillis the file's last-modified time in millis since epoch;
 *                          {@code 0} when unknown or inapplicable
 */
public record DirEntry(Path path, Type type, MediaKind mediaKind, long size,
                       long lastModifiedMillis, FileTraits traits) {

    /** Compatibility constructor for synthetic entries and callers without traits. */
    public DirEntry(Path path, Type type, MediaKind mediaKind,
                    long size, long lastModifiedMillis) {
        this(path, type, mediaKind, size, lastModifiedMillis, FileTraits.NONE);
    }

    public DirEntry {
        traits = traits == null ? FileTraits.NONE : traits;
    }

    public enum Type {
        PARENT, DIRECTORY,
        /** A supported archive or disc container entered as though it were a folder. */
        ARCHIVE,
        MEDIA, OTHER
    }

    /** Whether the entry can be opened in the viewer. */
    public boolean viewable() {
        return type == Type.MEDIA;
    }

    /**
     * Whether activating the entry navigates into it rather than viewing it —
     * true for folders, the {@code ..} link, and archives, which the browser
     * presents as folders that happen to be one file on disk.
     */
    public boolean enterable() {
        return type == Type.PARENT || type == Type.DIRECTORY || type == Type.ARCHIVE;
    }

    public String displayName() {
        if (type == Type.PARENT) return "..";
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /**
     * The lower-cased file extension (without the dot), or an empty string for
     * the parent link, directories and extension-less names. For a dotfile
     * without another dot (e.g. {@code .zshrc}), the name after the leading
     * dot is used so generated file tiles can display a meaningful label.
     */
    public String extension() {
        if (type == Type.PARENT || type == Type.DIRECTORY) return "";
        String name = displayName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return dot == 0 && name.length() > 1
                ? name.substring(1).toLowerCase(Locale.ROOT)
                : "";
    }

    public MediaItem toMediaItem() {
        return new MediaItem(path, mediaKind);
    }
}
