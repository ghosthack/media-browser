package io.github.ghosthack.mediabrowser.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;

/**
 * Cheap, listing-time filesystem traits used by visual presentation. They are
 * captured off the FX thread so the mosaic paint loop never performs file I/O.
 */
public record FileTraits(
        boolean hidden,
        boolean system,
        boolean junk,
        boolean executable,
        boolean symbolicLink,
        boolean brokenLink,
        boolean readable) {

    public static final FileTraits NONE =
            new FileTraits(false, false, false, false, false, false, true);

    /** Compatibility constructor for callers that do not distinguish junk. */
    public FileTraits(boolean hidden, boolean system, boolean executable,
                      boolean symbolicLink, boolean brokenLink, boolean readable) {
        this(hidden, system, false, executable, symbolicLink, brokenLink, readable);
    }

    /**
     * Reads traits defensively. Unsupported attribute views simply contribute
     * no platform-specific flags.
     */
    public static FileTraits read(Path path, boolean knownJunk) {
        if (path == null) return NONE;
        Path name = path.getFileName();
        boolean hidden = name != null && name.toString().startsWith(".");
        boolean system = false;
        boolean symbolic = Files.isSymbolicLink(path);
        boolean broken = symbolic && !Files.exists(path);
        boolean executable = false;
        boolean readable = true;
        try {
            executable = Files.isExecutable(path);
            readable = Files.isReadable(path);
        } catch (RuntimeException ignored) {
            readable = false;
        }
        if (path.getFileSystem().supportedFileAttributeViews().contains("dos")) {
            try {
                DosFileAttributes dos = Files.readAttributes(
                        path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                hidden |= dos.isHidden();
                system |= dos.isSystem();
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                // Keep the portable traits already collected.
            }
        } else if (!hidden) {
            try {
                hidden = Files.isHidden(path);
            } catch (IOException | RuntimeException ignored) {
                // The leading-dot rule above is still useful on every platform.
            }
        }
        return new FileTraits(
                hidden, system, knownJunk, executable, symbolic, broken, readable);
    }
}
