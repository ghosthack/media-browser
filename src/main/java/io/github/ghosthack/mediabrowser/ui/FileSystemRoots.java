package io.github.ghosthack.mediabrowser.ui;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Toolkit-free filesystem-root discovery helpers used by the drive menu. */
final class FileSystemRoots {

    private FileSystemRoots() {}

    /**
     * Returns user-visible filesystem roots for the current platform.
     *
     * <p>Java exposes Windows drive letters through {@code getRootDirectories},
     * but on macOS that API returns only {@code /}; removable and secondary
     * volumes are mounted below {@code /Volumes}, so add its real directory
     * children as roots too. The startup disk's {@code Macintosh HD -> /}
     * compatibility link is skipped along with any other aliases.</p>
     */
    static List<Path> discover() {
        return discover(System.getProperty("os.name", ""),
                FileSystems.getDefault().getRootDirectories(), Path.of("/Volumes"));
    }

    static List<Path> discover(String osName, Iterable<Path> systemRoots, Path macVolumes) {
        List<Path> discovered = new ArrayList<>();
        try {
            systemRoots.forEach(discovered::add);
        } catch (SecurityException ignored) {
            // A restricted runtime may still allow the macOS volume directory.
        }

        if (isMac(osName)) {
            try (Stream<Path> volumes = Files.list(macVolumes)) {
                volumes.filter(Files::isDirectory)
                        .filter(volume -> !Files.isSymbolicLink(volume))
                        .forEach(discovered::add);
            } catch (IOException | SecurityException ignored) {
                // Missing, unreadable, or concurrently detached volumes simply
                // do not appear in this refresh of the menu.
            }
        }
        return sorted(discovered);
    }

    private static boolean isMac(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return normalized.contains("mac") || normalized.contains("darwin");
    }

    /** Stable ordering keeps Windows drive letters predictable across refreshes. */
    static List<Path> sorted(Iterable<Path> discovered) {
        List<Path> roots = new ArrayList<>();
        discovered.forEach(roots::add);
        roots.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(roots);
    }
}
