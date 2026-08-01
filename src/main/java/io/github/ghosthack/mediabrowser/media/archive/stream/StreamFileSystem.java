package io.github.ghosthack.mediabrowser.media.archive.stream;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.List;
import java.util.Set;

/** A mounted streaming archive or embedded-media index exposed through read-only NIO. */
public final class StreamFileSystem extends FileSystem {
    private final StreamFileSystemProvider provider;
    private final Path source;
    private final StreamArchive archive;
    private final StreamPath root = new StreamPath(this, "/");
    private volatile boolean open = true;

    StreamFileSystem(StreamFileSystemProvider provider, Path source, StreamArchive archive) {
        this.provider = provider;
        this.source = source;
        this.archive = archive;
    }

    public Path source() {
        return source;
    }

    StreamArchive.Node entry(String path) throws IOException {
        ensureOpen();
        return archive.entry(path);
    }

    List<StreamArchive.Node> children(String path) throws IOException {
        ensureOpen();
        return archive.children(path);
    }

    java.io.InputStream open(StreamArchive.Node entry) throws IOException {
        ensureOpen();
        return archive.open(entry);
    }

    StreamPath rootPath() {
        return root;
    }

    private void ensureOpen() throws IOException {
        if (!open) throw new java.nio.file.ClosedFileSystemException();
    }

    @Override
    public StreamFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        if (!open) return;
        open = false;
        provider.removed(source);
        archive.close();
    }

    @Override public boolean isOpen() { return open; }
    @Override public boolean isReadOnly() { return true; }
    @Override public String getSeparator() { return "/"; }
    @Override public Iterable<Path> getRootDirectories() { return List.of(root); }
    @Override public Iterable<FileStore> getFileStores() { return List.of(); }
    @Override public Set<String> supportedFileAttributeViews() { return Set.of("basic"); }

    @Override
    public Path getPath(String first, String... more) {
        return new StreamPath(this, more.length == 0
                ? first : first + "/" + String.join("/", more));
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("path matching is not supported in archives");
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("archives have no user principals");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("an archive cannot change while mounted");
    }

    @Override
    public String toString() {
        return source + "!/";
    }
}
