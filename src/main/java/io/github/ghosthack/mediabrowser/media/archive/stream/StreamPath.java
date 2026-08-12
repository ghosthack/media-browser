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
    /** Split lazily: directory walks mostly need only the canonical absolute string. */
    private volatile List<String> names;

    StreamPath(StreamFileSystem fileSystem, String path) {
        this.fileSystem = fileSystem;
        this.path = clean(path);
    }

    private static String clean(String raw) {
        int length = raw.length();
        if (length == 0) return raw;
        StringBuilder clean = null;
        boolean previousSlash = false;
        for (int at = 0; at < length; at++) {
            char value = raw.charAt(at);
            boolean slash = value == '/';
            if (slash && previousSlash) {
                if (clean == null) clean = new StringBuilder(raw.substring(0, at));
                continue;
            }
            if (clean != null) clean.append(value);
            previousSlash = slash;
        }
        String collapsed = clean == null ? raw : clean.toString();
        return collapsed.length() > 1 && collapsed.endsWith("/")
                ? collapsed.substring(0, collapsed.length() - 1) : collapsed;
    }

    private static List<String> split(String path) {
        var out = new ArrayList<String>();
        int start = path.startsWith("/") ? 1 : 0;
        for (int at = start; at <= path.length(); at++) {
            if (at < path.length() && path.charAt(at) != '/') continue;
            if (at > start) out.add(path.substring(start, at));
            start = at + 1;
        }
        return List.copyOf(out);
    }

    private List<String> names() {
        List<String> existing = names;
        if (existing != null) return existing;
        List<String> created = split(path);
        names = created;
        return created;
    }

    String entryPath() {
        // Provider-created paths are already absolute and canonical. Keep the
        // hot listing/stat path allocation-free; only user-composed dot paths
        // need component normalization.
        if (path.isEmpty() || "/".equals(path)) return "/";
        if (path.indexOf("/./") < 0 && path.indexOf("/../") < 0
                && !path.endsWith("/.") && !path.endsWith("/..")
                && !path.equals(".") && !path.equals("..")) {
            return isAbsolute() ? path : "/" + path;
        }
        List<String> parts = names();
        var normalized = new ArrayList<String>();
        for (String name : parts) {
            if (".".equals(name)) continue;
            if ("..".equals(name)) {
                if (!normalized.isEmpty()) normalized.removeLast();
            } else {
                normalized.add(name);
            }
        }
        return "/" + String.join("/", normalized);
    }

    @Override public StreamFileSystem getFileSystem() { return fileSystem; }
    @Override public boolean isAbsolute() { return path.startsWith("/"); }
    @Override public Path getRoot() { return isAbsolute() ? fileSystem.rootPath() : null; }

    @Override
    public Path getFileName() {
        List<String> parts = names();
        return parts.isEmpty() ? null : new StreamPath(fileSystem, parts.getLast());
    }

    @Override
    public Path getParent() {
        List<String> parts = names();
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return isAbsolute() ? fileSystem.rootPath() : null;
        String parent = String.join("/", parts.subList(0, parts.size() - 1));
        return new StreamPath(fileSystem, isAbsolute() ? "/" + parent : parent);
    }

    @Override public int getNameCount() { return names().size(); }
    @Override public Path getName(int index) { return new StreamPath(fileSystem, names().get(index)); }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new StreamPath(fileSystem, String.join("/", names().subList(beginIndex, endIndex)));
    }

    @Override
    public boolean startsWith(Path other) {
        StreamPath that = cast(other);
        List<String> ours = names();
        List<String> theirs = that.names();
        return that.isAbsolute() == isAbsolute() && theirs.size() <= ours.size()
                && ours.subList(0, theirs.size()).equals(theirs);
    }

    @Override
    public boolean endsWith(Path other) {
        StreamPath that = cast(other);
        List<String> ours = names();
        List<String> theirs = that.names();
        if (theirs.size() > ours.size()) return false;
        if (that.isAbsolute() && !(isAbsolute() && ours.size() == theirs.size())) return false;
        return ours.subList(ours.size() - theirs.size(), ours.size()).equals(theirs);
    }

    @Override
    public Path normalize() {
        var result = new ArrayList<String>();
        for (String name : names()) {
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
        List<String> ours = names();
        List<String> theirs = that.names();
        int common = 0;
        while (common < ours.size() && common < theirs.size()
                && ours.get(common).equals(theirs.get(common))) common++;
        var result = new ArrayList<String>();
        for (int index = common; index < ours.size(); index++) result.add("..");
        result.addAll(theirs.subList(common, theirs.size()));
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
        return names().stream().<Path>map(name -> new StreamPath(fileSystem, name)).iterator();
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
