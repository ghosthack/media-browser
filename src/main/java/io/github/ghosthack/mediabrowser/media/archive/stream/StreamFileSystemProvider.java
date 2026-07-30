package io.github.ghosthack.mediabrowser.media.archive.stream;

import io.github.ghosthack.mediabrowser.media.archive.ArchiveFormat;
import io.github.ghosthack.seven.SevenArchive;
import io.github.ghosthack.seven.SevenEntry;
import io.github.ghosthack.unrar.RarArchive;
import io.github.ghosthack.unrar.RarEntry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer-owned NIO adapter over robust-unrar and robust-seven.
 *
 * <p>The vendored readers retain parsing, decoding and resource-budget policy;
 * this layer owns filesystem concerns: safe path normalization, synthesized
 * parent directories, deterministic duplicate handling and read-only NIO
 * behavior.
 */
public final class StreamFileSystemProvider extends FileSystemProvider {
    public static final String SCHEME = "stream-archive";
    private static final StreamFileSystemProvider INSTANCE = new StreamFileSystemProvider();
    private final Map<Path, StreamFileSystem> mounts = new ConcurrentHashMap<>();

    private StreamFileSystemProvider() {}

    public static StreamFileSystemProvider instance() {
        return INSTANCE;
    }

    @Override public String getScheme() { return SCHEME; }

    /** Mounts a RAR or 7z source with the matching vendored mechanics reader. */
    public StreamFileSystem newFileSystem(Path source, ArchiveFormat format) throws IOException {
        if (format != ArchiveFormat.RAR && format != ArchiveFormat.SEVEN_Z) {
            throw new IllegalArgumentException("not a streaming archive format: " + format);
        }
        Path key = source.toAbsolutePath().normalize();
        if (mounts.containsKey(key)) throw new FileSystemAlreadyExistsException(key.toString());

        StreamArchive archive = format == ArchiveFormat.RAR ? openRar(key) : openSeven(key);
        StreamFileSystem created = new StreamFileSystem(this, key, archive);
        StreamFileSystem raced = mounts.putIfAbsent(key, created);
        if (raced != null) {
            archive.close();
            throw new FileSystemAlreadyExistsException(key.toString());
        }
        return created;
    }

    private static StreamArchive openRar(Path source) throws IOException {
        RarArchive reader = RarArchive.open(source);
        try {
            StreamArchive archive = new StreamArchive(reader);
            for (RarEntry entry : reader.entries()) {
                archive.add(entry.name(), entry.kind() == RarEntry.Kind.DIRECTORY,
                        entry.uncompressedSize(), entry.lastModifiedTime().orElse(null),
                        entry.index(), () -> reader.openStream(entry));
            }
            return archive;
        } catch (RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    private static StreamArchive openSeven(Path source) throws IOException {
        SevenArchive reader = SevenArchive.open(source);
        try {
            StreamArchive archive = new StreamArchive(reader);
            for (SevenEntry entry : reader.entries()) {
                if (entry.kind() == SevenEntry.Kind.ANTI_ITEM) continue;
                archive.add(entry.name(), entry.kind() == SevenEntry.Kind.DIRECTORY,
                        entry.uncompressedSize(), entry.lastModifiedTime().orElse(null),
                        entry.index(), () -> reader.openStream(entry));
            }
            return archive;
        } catch (RuntimeException e) {
            reader.close();
            throw e;
        }
    }

    @Override
    public StreamFileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        Object value = env.get("format");
        if (!(value instanceof ArchiveFormat format)) {
            throw new IllegalArgumentException("env.format must be RAR or SEVEN_Z");
        }
        return newFileSystem(path, format);
    }

    public StreamFileSystem mounted(Path source) {
        return mounts.get(source.toAbsolutePath().normalize());
    }

    void removed(Path source) {
        mounts.remove(source);
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return newFileSystem(sourceOf(uri), env);
    }

    @Override
    public StreamFileSystem getFileSystem(URI uri) {
        StreamFileSystem result = mounted(sourceOf(uri));
        if (result == null) throw new FileSystemNotFoundException(uri.toString());
        return result;
    }

    @Override
    public Path getPath(URI uri) {
        return getFileSystem(uri).getPath(entryOf(uri));
    }

    private static Path sourceOf(URI uri) {
        String rest = uri.getSchemeSpecificPart();
        int bang = rest.indexOf("!/");
        return Path.of(URI.create(bang < 0 ? rest : rest.substring(0, bang)));
    }

    private static String entryOf(URI uri) {
        String rest = uri.getSchemeSpecificPart();
        int bang = rest.indexOf("!/");
        return bang < 0 ? "/" : rest.substring(bang + 1);
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        requireReadOnly(Set.of(options));
        StreamPath stream = cast(path);
        return stream.getFileSystem().open(
                stream.getFileSystem().entry(stream.entryPath()));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        requireReadOnly(options);
        StreamPath stream = cast(path);
        StreamArchive.Node entry = stream.getFileSystem().entry(stream.entryPath());
        if (entry.directory) throw new IOException("is a directory: " + path);
        return new EntryChannel(stream.getFileSystem(), entry);
    }

    private static void requireReadOnly(Set<? extends OpenOption> options) {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ) throw new ReadOnlyFileSystemException();
        }
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
            DirectoryStream.Filter<? super Path> filter) throws IOException {
        StreamPath stream = cast(dir);
        StreamFileSystem fs = stream.getFileSystem();
        String at = stream.entryPath();
        var children = new ArrayList<Path>();
        String prefix = "/".equals(at) ? "/" : at + "/";
        for (StreamArchive.Node child : fs.children(at)) {
            Path candidate = fs.getPath(prefix + child.name);
            if (filter == null || filter.accept(candidate)) children.add(candidate);
        }
        return new DirectoryStream<>() {
            @Override public java.util.Iterator<Path> iterator() { return children.iterator(); }
            @Override public void close() {}
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(
            Path path, Class<A> type, LinkOption... options) throws IOException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("unsupported attributes: " + type);
        }
        StreamPath stream = cast(path);
        return (A) new Attributes(stream.getFileSystem().entry(stream.entryPath()));
    }

    @Override
    public Map<String, Object> readAttributes(
            Path path, String attributes, LinkOption... options) throws IOException {
        BasicFileAttributes basic = readAttributes(path, BasicFileAttributes.class, options);
        var result = new LinkedHashMap<String, Object>();
        result.put("size", basic.size());
        result.put("creationTime", basic.creationTime());
        result.put("lastAccessTime", basic.lastAccessTime());
        result.put("lastModifiedTime", basic.lastModifiedTime());
        result.put("isRegularFile", basic.isRegularFile());
        result.put("isDirectory", basic.isDirectory());
        result.put("isSymbolicLink", false);
        result.put("isOther", false);
        result.put("fileKey", basic.fileKey());
        return result;
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        if (type != BasicFileAttributeView.class) return null;
        @SuppressWarnings("unchecked")
        V view = (V) new BasicFileAttributeView() {
            @Override public String name() { return "basic"; }
            @Override public BasicFileAttributes readAttributes() throws IOException {
                return StreamFileSystemProvider.this.readAttributes(
                        path, BasicFileAttributes.class);
            }
            @Override public void setTimes(FileTime modified, FileTime access, FileTime created) {
                throw new ReadOnlyFileSystemException();
            }
        };
        return view;
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        for (AccessMode mode : modes) {
            if (mode != AccessMode.READ) throw new AccessDeniedException(path.toString());
        }
        StreamPath stream = cast(path);
        stream.getFileSystem().entry(stream.entryPath());
    }

    @Override public boolean isSameFile(Path path, Path other) { return path.equals(other); }
    @Override public boolean isHidden(Path path) { return false; }
    @Override public FileStore getFileStore(Path path) throws IOException {
        throw new IOException("no file store for an archive entry");
    }
    @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new ReadOnlyFileSystemException();
    }
    @Override public void delete(Path path) { throw new ReadOnlyFileSystemException(); }
    @Override public void copy(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }
    @Override public void move(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }
    @Override public void setAttribute(
            Path path, String attribute, Object value, LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    private static StreamPath cast(Path path) {
        if (path instanceof StreamPath stream) return stream;
        throw new ProviderMismatchException(String.valueOf(path));
    }

    /** Reopens and skips a streaming decoder when a caller seeks backwards. */
    private static final class EntryChannel implements SeekableByteChannel {
        private final StreamFileSystem fileSystem;
        private final StreamArchive.Node entry;
        private InputStream input;
        private long streamPosition;
        private long position;
        private boolean open = true;

        EntryChannel(StreamFileSystem fileSystem, StreamArchive.Node entry) {
            this.fileSystem = fileSystem;
            this.entry = entry;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (!destination.hasRemaining()) return 0;
            if (position >= entry.size) return -1;
            align();
            byte[] buffer = new byte[(int) Math.min(64 * 1024L,
                    Math.min(destination.remaining(), entry.size - position))];
            int count = input.read(buffer);
            if (count < 0) return -1;
            destination.put(buffer, 0, count);
            position += count;
            streamPosition += count;
            return count;
        }

        private void align() throws IOException {
            if (input == null || position < streamPosition) {
                if (input != null) input.close();
                input = fileSystem.open(entry);
                streamPosition = 0;
            }
            long skip = position - streamPosition;
            if (skip > 0) {
                input.skipNBytes(skip);
                streamPosition = position;
            }
        }

        private void ensureOpen() throws ClosedChannelException {
            if (!open) throw new ClosedChannelException();
        }

        @Override public int write(ByteBuffer source) { throw new NonWritableChannelException(); }
        @Override public long position() throws IOException {
            ensureOpen();
            return position;
        }
        @Override public SeekableByteChannel position(long value) throws IOException {
            ensureOpen();
            if (value < 0) throw new IllegalArgumentException("negative position");
            position = value;
            return this;
        }
        @Override public long size() throws IOException {
            ensureOpen();
            return entry.size;
        }
        @Override public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }
        @Override public boolean isOpen() { return open; }
        @Override public void close() throws IOException {
            open = false;
            if (input != null) input.close();
        }
    }

    private record Attributes(StreamArchive.Node entry) implements BasicFileAttributes {
        @Override public FileTime lastModifiedTime() { return entry.modified; }
        @Override public FileTime lastAccessTime() { return entry.modified; }
        @Override public FileTime creationTime() { return entry.modified; }
        @Override public boolean isRegularFile() { return !entry.directory; }
        @Override public boolean isDirectory() { return entry.directory; }
        @Override public boolean isSymbolicLink() { return false; }
        @Override public boolean isOther() { return false; }
        @Override public long size() { return entry.size; }
        @Override public Object fileKey() { return entry.key; }
    }
}
