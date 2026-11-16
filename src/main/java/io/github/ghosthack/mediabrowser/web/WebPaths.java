package io.github.ghosthack.mediabrowser.web;

import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The phone page's fence: turns an {@link ArchivePaths} locator sent by the
 * page into a path, refusing anything that is not inside the web root.
 *
 * <p>The root is held as its real path, and every locator the server hands
 * out is formatted from paths under it, so a well-behaved page only ever
 * sends locators that start with the root. A locator is accepted when:</p>
 * <ol>
 *   <li>it is absolute, has no {@code .} or {@code ..} segment and no NUL, and
 *       lexically starts with the real root — before anything touches the
 *       disk or mounts an archive;</li>
 *   <li>every prefix of it ending at a {@code !/} that names an existing file
 *       resolves, symlinks followed, inside the root — so no archive outside
 *       the root is ever mounted;</li>
 *   <li>for a plain path, its real path (symlinks followed) is inside the root;
 *       for an archive entry, the archive file's real path is (archives hold no
 *       symlinks, and nested archives are never mounted).</li>
 * </ol>
 * <p>A plain path is returned as its real path, so a symlink inside the root
 * pointing elsewhere inside it is served under its target's locator.</p>
 */
final class WebPaths {

    private final Path root;

    /** @throws IOException when {@code root} does not exist or is not a folder */
    WebPaths(Path root) throws IOException {
        Path real = root.toRealPath();
        if (!Files.isDirectory(real)) throw new IOException("not a folder: " + root);
        this.root = real;
    }

    /** The root, as its real path. */
    Path root() {
        return root;
    }

    /** The locator the page uses for {@code path}. */
    static String locator(Path path) {
        return ArchivePaths.format(path);
    }

    /** Whether a plain (non-archive) path's real path lies inside the root. */
    boolean contains(Path real) {
        return real.startsWith(root);
    }

    /**
     * The path {@code text} names, inside the root; the root itself for
     * {@code null} or blank text.
     */
    /** Names go through Win32, which rewrites a trailing dot or space away. */
    private static final boolean WIN32_NAMES = java.io.File.separatorChar == '\\';

    Path resolve(String text) throws WebException {
        if (text == null || text.isBlank()) return root;
        if (text.indexOf('\0') >= 0) throw WebException.badRequest("not a path");
        Path lexical;
        try {
            lexical = Path.of(text);
        } catch (InvalidPathException e) {
            throw WebException.badRequest("not a path: " + e.getReason());
        }
        if (!lexical.isAbsolute()) throw WebException.outside("not inside the web root");
        for (Path segment : lexical) {
            String s = segment.toString();
            if (s.equals("..") || s.equals(".")) {
                throw WebException.outside("not inside the web root");
            }
        }
        if (WIN32_NAMES) {
            // Win32 drops a trailing dot or space when it opens a name, so
            // "Dots." would reach a sibling "Dots": such a name is unreachable
            // here, never a stand-in for another.
            for (Path segment : lexical) {
                String s = segment.toString();
                if (s.endsWith(".") || s.endsWith(" ")) {
                    throw WebException.notFound("\"" + s + "\" ends in a dot or a space;"
                            + " Windows cannot open it by that name");
                }
            }
        }
        if (!lexical.startsWith(root)) throw WebException.outside("not inside the web root");

        // Containers named on the way must be inside the root before parse()
        // mounts any of them.
        for (int mark = text.indexOf(ArchivePaths.SEPARATOR); mark >= 0;
                mark = text.indexOf(ArchivePaths.SEPARATOR, mark + 1)) {
            Path prefix;
            try {
                prefix = Path.of(text.substring(0, mark));
            } catch (InvalidPathException e) {
                continue;
            }
            if (Files.isRegularFile(prefix) && !contains(realPath(prefix))) {
                throw WebException.outside("not inside the web root");
            }
        }

        Optional<Path> parsed = ArchivePaths.parse(text);
        if (parsed.isEmpty()) throw WebException.notFound("no such file or folder");
        Path path = parsed.get();
        Optional<Path> host = ArchivePaths.hostArchive(path);
        if (host.isPresent()) {
            if (!contains(realPath(host.get()))) throw WebException.outside("not inside the web root");
            return path;
        }
        // parse() trims, as suits a typed address; a locator is exact ("Trip "
        // and "Trip" are two folders), so a plain path is the text as sent.
        Path real = realPath(lexical);
        if (!contains(real)) throw WebException.outside("not inside the web root");
        return real;
    }

    private static Path realPath(Path path) throws WebException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException e) {
            throw WebException.notFound("no such file or folder");
        } catch (IOException e) {
            throw new WebException(404, "not-found", "cannot read: " + e.getMessage());
        }
    }
}
