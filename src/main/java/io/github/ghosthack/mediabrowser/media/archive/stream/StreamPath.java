package io.github.ghosthack.mediabrowser.media.archive.stream;

import java.io.File;
import java.net.URI;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Immutable slash-separated path inside a streaming archive filesystem. */
public final class StreamPath implements Path {
    private final StreamFileSystem fileSystem;
    private final String path;
    private final List<String> names;

    StreamPath(StreamFileSystem fileSystem, String path) {
        this.fileSystem = fileSystem;
        this.path = clean(path);
        this.names = split(this.path);
    }

    private static String clean(String raw) {
        String collapsed = raw.replaceAll("/{2,}", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    private static List<String> split(String path) {
        var out = new ArrayList<String>();
        for (String part : path.split("/")) if (!part.isEmpty()) out.add(part);
        return List.copyOf(out);
    }

    String entryPath() {
        StreamPath absolute = (StreamPath) toAbsolutePath().normalize();
        return absolute.path.isEmpty() ? "/" : absolute.path;
    }

    @Override public StreamFileSystem getFileSystem() { return fileSystem; }
    @Override public boolean isAbsolute() { return path.startsWith("/"); }
    @Override public Path getRoot() { return isAbsolute() ? fileSystem.rootPath() : null; }

    @Override
    public Path getFileName() {
        return names.isEmpty() ? null : new StreamPath(fileSystem, names.getLast());
    }

    @Override
    public Path getParent() {
        if (names.isEmpty()) return null;
        if (names.size() == 1) return isAbsolute() ? fileSystem.rootPath() : null;
        String parent = String.join("/", names.subList(0, names.size() - 1));
        return new StreamPath(fileSystem, isAbsolute() ? "/" + parent : parent);
    }

    @Override public int getNameCount() { return names.size(); }
    @Override public Path getName(int index) { return new StreamPath(fileSystem, names.get(index)); }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new StreamPath(fileSystem, String.join("/", names.subList(beginIndex, endIndex)));
    }

    @Override
    public boolean startsWith(Path other) {
        StreamPath that = cast(other);
        return that.isAbsolute() == isAbsolute() && that.names.size() <= names.size()
                && names.subList(0, that.names.size()).equals(that.names);
    }

    @Override
    public boolean endsWith(Path other) {
        StreamPath that = cast(other);
        if (that.names.size() > names.size()) return false;
        if (that.isAbsolute() && !(isAbsolute() && names.size() == that.names.size())) return false;
        return names.subList(names.size() - that.names.size(), names.size()).equals(that.names);
    }

    @Override
    public Path normalize() {
        var result = new ArrayList<String>();
        for (String name : names) {
            if (".".equals(name)) continue;
            if ("..".equals(name)) {
                if (!result.isEmpty() && !"..".equals(result.getLast())) result.removeLast();
                else if (!isAbsolute()) result.add("..");
            } else {
                result.add(name);
            }
        }
        String joined = String.join("/", result);
        return new StreamPath(fileSystem, isAbsolute() ? "/" + joined : joined);
    }

    @Override
    public Path resolve(Path other) {
        StreamPath that = cast(other);
        if (that.isAbsolute()) return that;
        if (that.path.isEmpty()) return this;
        if (path.isEmpty()) return that;
        return new StreamPath(fileSystem, path + "/" + that.path);
    }

    @Override
    public Path relativize(Path other) {
        StreamPath that = cast(other);
        if (isAbsolute() != that.isAbsolute()) {
            throw new IllegalArgumentException("cannot relativize absolute and relative paths");
        }
        int common = 0;
        while (common < names.size() && common < that.names.size()
                && names.get(common).equals(that.names.get(common))) common++;
        var result = new ArrayList<String>();
        for (int index = common; index < names.size(); index++) result.add("..");
        result.addAll(that.names.subList(common, that.names.size()));
        return new StreamPath(fileSystem, String.join("/", result));
    }

    @Override
    public URI toUri() {
        return URI.create(StreamFileSystemProvider.SCHEME + ":"
                + fileSystem.source().toUri() + "!" + entryPath());
    }

    @Override public Path toAbsolutePath() {
        return isAbsolute() ? this : new StreamPath(fileSystem, "/" + path);
    }
    @Override public Path toRealPath(LinkOption... options) { return toAbsolutePath().normalize(); }
    @Override public File toFile() {
        throw new UnsupportedOperationException("an archive entry has no java.io.File");
    }
    @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                                       WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("an archive cannot change while mounted");
    }

    @Override
    public Iterator<Path> iterator() {
        return names.stream().<Path>map(name -> new StreamPath(fileSystem, name)).iterator();
    }

    @Override public int compareTo(Path other) { return path.compareTo(cast(other).path); }

    private StreamPath cast(Path other) {
        if (other instanceof StreamPath stream && stream.fileSystem == fileSystem) return stream;
        throw new ProviderMismatchException("not a path in " + fileSystem);
    }

    @Override public boolean equals(Object value) {
        return value instanceof StreamPath that
                && that.fileSystem == fileSystem && that.path.equals(path);
    }
    @Override public int hashCode() {
        return Objects.hash(System.identityHashCode(fileSystem), path);
    }
    @Override public String toString() { return path; }
}
