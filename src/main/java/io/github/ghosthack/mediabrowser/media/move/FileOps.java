package io.github.ghosthack.mediabrowser.media.move;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Filesystem primitives for the move feature: pure {@link java.nio.file} IO with
 * no UI and no threading. All methods are blocking and must be called off the FX
 * thread.
 *
 * <p>Ported from {@code iris94.services.FileSystemService}
 * ({@code moveItemWithAutoRename}, {@code createDirectoryRecursive},
 * {@code listSubdirectories}, {@code availableDestination}).
 */
public final class FileOps {

    private FileOps() {}

    /**
     * Move {@code source} into {@code targetDir}, keeping its name. On a name
     * collision the name gains a {@code " (N)"} suffix before the extension
     * ({@code "photo.jpg" → "photo (1).jpg"}), trying {@code N = 1..9999}.
     *
     * @return the final destination path the file now lives at
     * @throws IOException if the source is missing, no free name can be found,
     *                     or the move itself fails
     */
    public static Path moveWithAutoRename(Path source, Path targetDir) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source does not exist: " + source);
        }
        String fileName = source.getFileName().toString();
        Path destination = availableDestination(targetDir, fileName);
        Files.move(source, destination);
        return destination;
    }

    /**
     * Move-and-rename {@code source} to the exact path {@code target} (used for
     * single-file rename). Never overwrites: if {@code target} already exists
     * this throws {@link FileAlreadyExistsException}. Uses {@code ATOMIC_MOVE},
     * falling back to a plain move (still no overwrite) across devices.
     *
     * @return {@code target}
     */
    public static Path renameMove(Path source, Path target) throws IOException {
        // Guarantee no-overwrite regardless of platform: ATOMIC_MOVE maps to
        // rename(2) on POSIX, which would clobber an existing regular file.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException crossDevice) {
            // Cross-device fallback: plain move, no REPLACE_EXISTING → still
            // fails rather than overwrites if the target appeared in between.
            Files.move(source, target);
        }
        return target;
    }

    /** Create {@code dir} and any missing parents (like {@code mkdir -p}). */
    public static void createDirectoriesRecursive(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    /**
     * The immediate subdirectories of {@code dir} as fresh, unexpanded
     * {@link TreeNode}s, sorted by name case-insensitively. Hidden directories
     * (dot-prefixed or {@link Files#isHidden}) are excluded unless
     * {@code showHidden} is set. Returns an empty list for a non-directory or an
     * unreadable directory (never throws).
     */
    public static List<TreeNode> listSubdirectories(Path dir, boolean showHidden) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<TreeNode> nodes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                if (!showHidden && isHidden(entry)) {
                    continue;
                }
                Path name = entry.getFileName();
                nodes.add(new TreeNode(
                        name == null ? entry.toString() : name.toString(),
                        entry.toAbsolutePath().toString(),
                        false,
                        new ArrayList<>(),
                        false));
            }
        } catch (IOException e) {
            return List.of();
        }
        nodes.sort(Comparator.comparing(TreeNode::getName, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    /**
     * Find a non-existent destination in {@code targetDir} for {@code fileName},
     * appending {@code " (N)"} before the extension on collision.
     */
    private static Path availableDestination(Path targetDir, String fileName) throws IOException {
        Path candidate = targetDir.resolve(fileName);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }

        String baseName;
        String ext;
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            baseName = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        } else {
            baseName = fileName;
            ext = "";
        }

        for (int counter = 1; counter <= 9999; counter++) {
            candidate = targetDir.resolve(baseName + " (" + counter + ")" + ext);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("Could not find available target name for " + fileName);
    }

    /** Dot-prefixed names, or whatever the platform marks hidden, are hidden. */
    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        String s = name == null ? "" : name.toString();
        if (s.startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
    }
}
