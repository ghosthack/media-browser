package io.github.ghosthack.mediabrowser.media.archive.iso;

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

/**
 * A path inside an {@link IsoFileSystem}. Always {@code /}-separated,
 * case-sensitive for equality (two differently-cased strings are different
 * paths even though {@link IsoFileSystem#entry} will resolve either), and
 * immutable.
 */
public final class IsoPath implements Path {

    private final IsoFileSystem fileSystem;
    private final String path;
    private final List<String> names;

    IsoPath(IsoFileSystem fileSystem, String path) {
        this.fileSystem = fileSystem;
        this.path = clean(path);
        this.names = split(this.path);
    }

    /** Collapses repeated separators and drops a trailing one (root excepted). */
    private static String clean(String raw) {
        String collapsed = raw.replaceAll("/{2,}", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed.isEmpty() ? "" : collapsed;
    }

    private static List<String> split(String path) {
        var out = new ArrayList<String>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) out.add(part);
        }
        return List.copyOf(out);
    }

    /** The absolute, normalized string this path denotes inside the image. */
    String entryPath() {
        IsoPath absolute = (IsoPath) toAbsolutePath().normalize();
        return absolute.path.isEmpty() ? "/" : absolute.path;
    }

    @Override
    public IsoFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return path.startsWith("/");
    }

    @Override
    public Path getRoot() {
        return isAbsolute() ? fileSystem.rootPath() : null;
    }

    @Override
    public Path getFileName() {
        if (names.isEmpty()) return null;
        return new IsoPath(fileSystem, names.get(names.size() - 1));
    }

    @Override
    public Path getParent() {
        if (names.isEmpty()) return null;
        if (names.size() == 1) return isAbsolute() ? fileSystem.rootPath() : null;
        String parent = String.join("/", names.subList(0, names.size() - 1));
        return new IsoPath(fileSystem, isAbsolute() ? "/" + parent : parent);
    }

    @Override
    public int getNameCount() {
        return names.size();
    }

    @Override
    public Path getName(int index) {
        return new IsoPath(fileSystem, names.get(index));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new IsoPath(fileSystem, String.join("/", names.subList(beginIndex, endIndex)));
    }

    @Override
    public boolean startsWith(Path other) {
        IsoPath that = cast(other);
        if (that.isAbsolute() != isAbsolute() || that.names.size() > names.size()) return false;
        return names.subList(0, that.names.size()).equals(that.names);
    }

    @Override
    public boolean endsWith(Path other) {
        IsoPath that = cast(other);
        if (that.names.size() > names.size()) return false;
        if (that.isAbsolute() && !(names.size() == that.names.size() && isAbsolute())) return false;
        return names.subList(names.size() - that.names.size(), names.size()).equals(that.names);
    }

    @Override
    public Path normalize() {
        var out = new ArrayList<String>();
        for (String name : names) {
            if (".".equals(name)) continue;
            if ("..".equals(name)) {
                // Above an absolute root, ".." is simply the root; in a relative
                // path it has to be kept, since there is nothing to cancel it
                // against yet.
                if (!out.isEmpty() && !"..".equals(out.get(out.size() - 1))) {
                    out.remove(out.size() - 1);
                } else if (!isAbsolute()) {
                    out.add("..");
                }
                continue;
            }
            out.add(name);
        }
        String joined = String.join("/", out);
        return new IsoPath(fileSystem, isAbsolute() ? "/" + joined : joined);
    }

    @Override
    public Path resolve(Path other) {
        IsoPath that = cast(other);
        if (that.isAbsolute()) return that;
        if (that.path.isEmpty()) return this;
        if (path.isEmpty()) return that;
        return new IsoPath(fileSystem, path + "/" + that.path);
    }

    @Override
    public Path relativize(Path other) {
        IsoPath that = cast(other);
        if (isAbsolute() != that.isAbsolute()) {
            throw new IllegalArgumentException("cannot relativize across absolute and relative");
        }
        int common = 0;
        while (common < names.size() && common < that.names.size()
                && names.get(common).equals(that.names.get(common))) {
            common++;
        }
        var out = new ArrayList<String>();
        for (int i = common; i < names.size(); i++) out.add("..");
        out.addAll(that.names.subList(common, that.names.size()));
        return new IsoPath(fileSystem, String.join("/", out));
    }

    @Override
    public URI toUri() {
        return URI.create(IsoFileSystemProvider.SCHEME + ":"
                + fileSystem.source().toUri() + "!" + entryPath());
    }

    @Override
    public Path toAbsolutePath() {
        return isAbsolute() ? this : new IsoPath(fileSystem, "/" + path);
    }

    @Override
    public Path toRealPath(LinkOption... options) {
        return toAbsolutePath().normalize();
    }

    @Override
    public File toFile() {
        // Deliberate: an entry inside an image has no OS path. Callers that
        // need one materialize it through the archive entry cache instead.
        throw new UnsupportedOperationException("an ISO entry has no java.io.File");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                             WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("an ISO cannot change while mounted");
    }

    @Override
    public Iterator<Path> iterator() {
        var out = new ArrayList<Path>(names.size());
        for (String name : names) out.add(new IsoPath(fileSystem, name));
        return out.iterator();
    }

    @Override
    public int compareTo(Path other) {
        return path.compareTo(cast(other).path);
    }

    private IsoPath cast(Path other) {
        if (other instanceof IsoPath iso && iso.fileSystem == fileSystem) return iso;
        throw new ProviderMismatchException("not a path in " + fileSystem);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IsoPath that
                && that.fileSystem == fileSystem
                && that.path.equals(path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(fileSystem), path);
    }

    @Override
    public String toString() {
        return path;
    }
}
