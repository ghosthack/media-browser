package io.github.ghosthack.mediabrowser.album;

import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages the user's albums: numbered {@code album-NNN.csv} files in the
 * application directory ({@code ~/.media-browser} — the same place
 * {@code app.properties} lives). Each file lists the absolute paths of its
 * members, one per line. The store is a thin, stateless facade over those
 * files; recency (the "recent albums" the menu surfaces) lives in
 * {@code AppSettings}, not here.
 */
public final class AlbumStore {

    private static final String PREFIX = "album-";
    private static final String SUFFIX = ".csv";
    /** Matches {@code album-<digits>.csv} (the numbered files the menu lists). */
    private static final Pattern NUMBERED =
            Pattern.compile("\\Q" + PREFIX + "\\E(\\d+)\\Q" + SUFFIX + "\\E");

    /** Writes and syncs the temp file's bytes; a seam for tests. */
    @FunctionalInterface
    interface TempWriter {
        void write(Path temp, byte[] bytes) throws IOException;
    }

    /** The rename that publishes a written temp file; a seam for tests. */
    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private final Path dir;
    private final TempWriter writer;
    private final Mover mover;

    /**
     * Uses {@code dir}: the app passes its application directory. There is no
     * default — a hard-coded {@code ~/.media-browser} would bypass the
     * directory override tests rely on to stay out of the real home.
     */
    public AlbumStore(Path dir) {
        this(dir, Files::move);
    }

    /** Uses an explicit directory and rename — for tests of failing writes. */
    AlbumStore(Path dir, Mover mover) {
        this(dir, AlbumStore::writeSynced, mover);
    }

    /** Uses an explicit directory, temp-file write and rename — for tests of failing writes. */
    AlbumStore(Path dir, TempWriter writer, Mover mover) {
        this.dir = dir;
        this.writer = writer;
        this.mover = mover;
    }

    /** The directory the album files live in. */
    public Path directory() {
        return dir;
    }

    /** Outcome of {@link #addPaths}: how many new members were written and skipped. */
    public record AddResult(int added, int skipped) {}

    /** All albums, ordered by their number ascending; empty when none exist. */
    public List<Album> albums() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            // A numbered name beyond int range (a stray album-99999999999.csv)
            // is not an album: skipping it keeps one odd file from failing
            // every album listing.
            stream.filter(p -> numberOf(p) >= 0).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // File name breaks ties (album-1.csv vs album-001.csv) so the order
        // never depends on directory listing order.
        files.sort(Comparator.comparingInt(AlbumStore::numberOf)
                .thenComparing(AlbumStore::fileName));
        List<Album> albums = new ArrayList<>(files.size());
        for (Path file : files) {
            albums.add(toAlbum(file));
        }
        return albums;
    }

    /** The album backed by {@code fileName}, or {@code null} when it is absent. */
    public Album byFileName(String fileName) {
        for (Album album : albums()) {
            if (album.fileName().equals(fileName)) {
                return album;
            }
        }
        return null;
    }

    /**
     * Creates the next-numbered empty album file ({@code album-NNN.csv}, the
     * lowest free number) and returns it, creating the application directory if
     * needed.
     */
    public Album createAlbum() throws IOException {
        Files.createDirectories(dir);
        int number = nextFreeNumber();
        // Locale.ROOT so the number is always ASCII digits: under a default
        // locale with non-Latin digits (fa-IR, ar-EG) the formatted name would
        // not match NUMBERED, and every album written became invisible.
        Path file = dir.resolve(String.format(Locale.ROOT, "%s%03d%s", PREFIX, number, SUFFIX));
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        return toAlbum(file);
    }

    /**
     * An album's members: the paths that resolved, in file order, and how many
     * {@code !/} lines named no one file (an archive gone or unreadable, or a
     * locator that also names a real file), for the listing to report as
     * unavailable rather than substitute a guess.
     */
    public record Members(List<Path> paths, int unresolved) {
        public Members {
            paths = List.copyOf(paths);
        }
    }

    /** The album's members (absolute paths), in file order; empty when missing. */
    public List<Path> entries(Album album) {
        return members(album).paths();
    }

    /** The album's members, with the locators that resolve to no one file counted. */
    public Members members(Album album) {
        if (album == null || !Files.isRegularFile(album.file())) {
            return new Members(List.of(), 0);
        }
        try {
            List<Path> paths = new ArrayList<>();
            int unresolved = 0;
            for (String line : Files.readAllLines(album.file(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.contains(ArchivePaths.SEPARATOR)) {
                    // A member inside an archive, or a plain path through a
                    // directory whose name ends in '!' (…/Help!/track.mp3):
                    // parse tells them apart, mounting a container on the way.
                    // When it cannot name one file — the archive is gone or
                    // unreadable, or the text names both an archive entry and
                    // a real file — no guess is substituted: the literal path
                    // would silently be the disk file in the ambiguous case.
                    var parsed = ArchivePaths.parse(trimmed);
                    if (parsed.isPresent()) paths.add(parsed.get());
                    else unresolved++;
                } else {
                    try {
                        paths.add(Path.of(trimmed));
                    } catch (InvalidPathException ignored) {
                        // One unparseable line (a stray NUL, a Windows path read
                        // on POSIX) drops that member rather than failing the
                        // whole album, matching how a deleted member behaves.
                    }
                }
            }
            return new Members(paths, unresolved);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The number of members in {@code album}, counting those that resolve to no file. */
    public int count(Album album) {
        Members members = members(album);
        return members.paths().size() + members.unresolved();
    }

    /**
     * Adds {@code paths} (normalised to absolute) to {@code album}, skipping any
     * already present, and rewrites the file as one absolute path per line. The
     * file is left untouched when every path is already a member.
     *
     * @return how many paths were newly added and how many were skipped as
     *         duplicates
     */
    public AddResult addPaths(Album album, List<Path> paths) throws IOException {
        // Persistence must be seeded from the on-disk representation, not from
        // entries(): that display model intentionally omits unparseable paths
        // and archive locators whose container cannot be mounted right now.
        // Rewriting from it would turn a transient offline drive into permanent
        // album data loss.
        LinkedHashSet<String> members = new LinkedHashSet<>(storedLines(album));
        LinkedHashSet<String> knownMembers = new LinkedHashSet<>();
        for (String member : members) {
            knownMembers.add(member.trim());
        }
        int added = 0;
        int skipped = 0;
        for (Path path : paths) {
            // Stored as a locator, so a member inside an archive names both the
            // container and the entry; identical to the plain path outside one.
            String absolute = ArchivePaths.format(path.toAbsolutePath().normalize());
            if (!knownMembers.add(absolute)) {
                skipped++;
            } else {
                members.add(absolute);
                added++;
            }
        }
        if (added > 0) {
            writeAtomically(album.file(), String.join("\n", members) + "\n");
        }
        return new AddResult(added, skipped);
    }

    /**
     * Temp sibling + file sync + atomic rename, mirroring {@code AppSettings}:
     * a crash, full disk or failed rename leaves the previous album file
     * untouched — never truncated. Filesystems without atomic rename fall back
     * to a plain replace only after the temp file is fully durable.
     */
    private void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, fileName(target) + ".", ".tmp");
        try {
            writer.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                mover.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                mover.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable failure) {
            // A cleanup failure rides along as suppressed: the write's own
            // error (a full disk, say) is the one the caller must see.
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void writeSynced(Path temp, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
    }

    /** Every nonblank stored member, without resolving or mounting it. */
    private static List<String> storedLines(Album album) throws IOException {
        if (album == null || !Files.isRegularFile(album.file())) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        for (String line : Files.readAllLines(album.file(), StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) {
                members.add(line);
            }
        }
        return members;
    }

    /** The lowest album number not yet present on disk. */
    private int nextFreeNumber() throws IOException {
        int max = -1;
        for (Album album : albums()) {
            max = Math.max(max, numberOf(album.file()));
        }
        if (max == Integer.MAX_VALUE) {
            // max + 1 would wrap negative and name a file NUMBERED never lists.
            throw new IOException("No album number left after "
                    + PREFIX + max + SUFFIX + " in " + dir);
        }
        return max + 1;
    }

    private static Album toAlbum(Path file) {
        String name = fileName(file);
        int dot = name.lastIndexOf('.');
        return new Album(file, dot > 0 ? name.substring(0, dot) : name);
    }

    /** The album number in {@code file}'s name, or -1 when it is no album name or exceeds int. */
    private static int numberOf(Path file) {
        Matcher m = NUMBERED.matcher(fileName(file));
        if (!m.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException outOfRange) {
            return -1;
        }
    }

    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? "" : name.toString();
    }
}
