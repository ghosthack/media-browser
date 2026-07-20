package io.github.ghosthack.mediabrowser.album;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final Path DEFAULT_DIR =
            Path.of(System.getProperty("user.home"), ".media-browser");
    private static final String PREFIX = "album-";
    private static final String SUFFIX = ".csv";
    /** Matches {@code album-<digits>.csv} (the numbered files the menu lists). */
    private static final Pattern NUMBERED =
            Pattern.compile("\\Q" + PREFIX + "\\E(\\d+)\\Q" + SUFFIX + "\\E");

    private final Path dir;

    /** Uses the default application directory ({@code ~/.media-browser}). */
    public AlbumStore() {
        this(DEFAULT_DIR);
    }

    /** Uses an explicit directory — for tests. */
    public AlbumStore(Path dir) {
        this.dir = dir;
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
            stream.filter(p -> NUMBERED.matcher(fileName(p)).matches()).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(Comparator.comparingInt(AlbumStore::numberOf));
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
        Path file = dir.resolve(String.format("%s%03d%s", PREFIX, number, SUFFIX));
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        return toAlbum(file);
    }

    /** The album's members (absolute paths), in file order; empty when missing. */
    public List<Path> entries(Album album) {
        if (album == null || !Files.isRegularFile(album.file())) {
            return List.of();
        }
        try {
            List<Path> paths = new ArrayList<>();
            for (String line : Files.readAllLines(album.file(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    paths.add(Path.of(trimmed));
                }
            }
            return paths;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The number of members in {@code album}. */
    public int count(Album album) {
        return entries(album).size();
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
        LinkedHashSet<String> members = new LinkedHashSet<>();
        for (Path existing : entries(album)) {
            members.add(existing.toString());
        }
        int before = members.size();
        int skipped = 0;
        for (Path path : paths) {
            String absolute = path.toAbsolutePath().normalize().toString();
            if (!members.add(absolute)) {
                skipped++;
            }
        }
        int added = members.size() - before;
        if (added > 0) {
            Files.createDirectories(dir);
            Files.writeString(album.file(), String.join("\n", members) + "\n",
                    StandardCharsets.UTF_8);
        }
        return new AddResult(added, skipped);
    }

    /** The lowest album number not yet present on disk. */
    private int nextFreeNumber() {
        int max = -1;
        for (Album album : albums()) {
            max = Math.max(max, numberOf(album.file()));
        }
        return max + 1;
    }

    private static Album toAlbum(Path file) {
        String name = fileName(file);
        int dot = name.lastIndexOf('.');
        return new Album(file, dot > 0 ? name.substring(0, dot) : name);
    }

    private static int numberOf(Path file) {
        Matcher m = NUMBERED.matcher(fileName(file));
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? "" : name.toString();
    }
}
