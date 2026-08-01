package io.github.ghosthack.mediabrowser.media.archive.iso;

import io.github.ghosthack.iso9660.IsoEntry;
import io.github.ghosthack.iso9660.IsoImage;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
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
 * The {@code java.nio.file} provider for ISO 9660 images.
 *
 * <p>Not registered as an installed provider: nothing gains from
 * {@code FileSystems.newFileSystem} discovering it, and staying out of the
 * global registry keeps a malformed {@code .iso} from becoming a
 * whole-JVM concern. It is used directly — {@code ArchiveMounts} calls
 * {@link #newFileSystem(Path, Map)} — and once a path exists, every
 * {@code Files.*} call routes back here through
 * {@code path.getFileSystem().provider()}, which is what makes ISO entries
 * work as ordinary paths everywhere else in the app.</p>
 *
 * <p>Every mutating operation throws {@link ReadOnlyFileSystemException}
 * rather than failing later or partially: a disc image is immutable, and the
 * app's move/rename features must hear that as a clear refusal.</p>
 */
public final class IsoFileSystemProvider extends FileSystemProvider {

    /** URI scheme for image entries: {@code iso:file:///disc.iso!/DIR/FILE}. */
    public static final String SCHEME = "iso";

    private static final IsoFileSystemProvider INSTANCE = new IsoFileSystemProvider();

    private final Map<Path, IsoFileSystem> mounts = new ConcurrentHashMap<>();

    private IsoFileSystemProvider() {}

    public static IsoFileSystemProvider instance() {
        return INSTANCE;
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    /**
     * Mounts {@code file} as a read-only filesystem.
     *
     * @throws FileSystemAlreadyExistsException if it is already mounted — the
     *         caller ({@code ArchiveMounts}) keeps one mount per image so the
     *         parsed directory caches are shared rather than duplicated
     */
    @Override
    public IsoFileSystem newFileSystem(Path file, Map<String, ?> env) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        if (mounts.containsKey(key)) throw new FileSystemAlreadyExistsException(key.toString());
        IsoImage image = IsoImage.open(key);
        return publish(key, image);
    }

    /** Mounts the sole ISO 9660 data track described by a CUE sheet. */
    public IsoFileSystem newCueFileSystem(Path cue) throws IOException {
        Path key = cue.toAbsolutePath().normalize();
        if (mounts.containsKey(key)) throw new FileSystemAlreadyExistsException(key.toString());
        IsoImage image = CueIsoData.openImage(key);
        return publish(key, image);
    }

    private IsoFileSystem publish(Path key, IsoImage image) throws IOException {
        IsoFileSystem created = new IsoFileSystem(this, key, image);
        IsoFileSystem raced = mounts.putIfAbsent(key, created);
        if (raced != null) {
            image.close();
            throw new FileSystemAlreadyExistsException(key.toString());
        }
        return created;
    }

    /** The mount for {@code file}, or null when it is not mounted. */
    public IsoFileSystem mounted(Path file) {
        return mounts.get(file.toAbsolutePath().normalize());
    }

    void removed(Path source) {
        mounts.remove(source);
    }

    @Override
    public IsoFileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return newFileSystem(imageOf(uri), env);
    }

    @Override
    public IsoFileSystem getFileSystem(URI uri) {
        IsoFileSystem mount = mounted(imageOf(uri));
        if (mount == null) throw new FileSystemNotFoundException(uri.toString());
        return mount;
    }

    @Override
    public Path getPath(URI uri) {
        return getFileSystem(uri).getPath(entryOf(uri));
    }

    /** The image file named by an {@code iso:file:///x.iso!/entry} URI. */
    private static Path imageOf(URI uri) {
        String rest = uri.getSchemeSpecificPart();
        int bang = rest.indexOf("!/");
        return Path.of(URI.create(bang < 0 ? rest : rest.substring(0, bang)));
    }

    private static String entryOf(URI uri) {
        String rest = uri.getSchemeSpecificPart();
        int bang = rest.indexOf("!/");
        return bang < 0 ? "/" : rest.substring(bang + 1);
    }

    // --- reading ----------------------------------------------------------

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ) {
                throw new ReadOnlyFileSystemException();
            }
        }
        IsoPath iso = cast(path);
        IsoEntry entry = iso.getFileSystem().entry(iso.entryPath());
        if (entry.directory()) throw new IOException("is a directory: " + path);
        return new EntryChannel(iso.getFileSystem().image(), entry);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
                                                    DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        IsoPath iso = cast(dir);
        IsoFileSystem fs = iso.getFileSystem();
        String at = iso.entryPath();
        IsoEntry entry = fs.entry(at);
        if (!entry.directory()) throw new NotDirectoryException(dir.toString());
        var children = new ArrayList<Path>();
        String prefix = "/".equals(at) ? "/" : at + "/";
        for (IsoEntry child : fs.list(at)) {
            Path candidate = fs.getPath(prefix + child.name());
            if (filter == null || filter.accept(candidate)) children.add(candidate);
        }
        return new DirectoryStream<>() {
            @Override
            public java.util.Iterator<Path> iterator() {
                return children.iterator();
            }

            @Override
            public void close() {}
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                            LinkOption... options)
            throws IOException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("unsupported attributes: " + type);
        }
        IsoPath iso = cast(path);
        return (A) new Attributes(iso.getFileSystem().entry(iso.entryPath()));
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        BasicFileAttributes basic = readAttributes(path, BasicFileAttributes.class, options);
        var out = new LinkedHashMap<String, Object>();
        out.put("size", basic.size());
        out.put("creationTime", basic.creationTime());
        out.put("lastAccessTime", basic.lastAccessTime());
        out.put("lastModifiedTime", basic.lastModifiedTime());
        out.put("isRegularFile", basic.isRegularFile());
        out.put("isDirectory", basic.isDirectory());
        out.put("isSymbolicLink", false);
        out.put("isOther", false);
        out.put("fileKey", basic.fileKey());
        return out;
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                LinkOption... options) {
        if (type != BasicFileAttributeView.class) return null;
        @SuppressWarnings("unchecked")
        V view = (V) new BasicFileAttributeView() {
            @Override
            public String name() {
                return "basic";
            }

            @Override
            public BasicFileAttributes readAttributes() throws IOException {
                return IsoFileSystemProvider.this.readAttributes(path, BasicFileAttributes.class);
            }

            @Override
            public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime,
                                 FileTime createTime) {
                throw new ReadOnlyFileSystemException();
            }
        };
        return view;
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        for (AccessMode mode : modes) {
            if (mode == AccessMode.WRITE) throw new AccessDeniedException(path.toString());
            if (mode == AccessMode.EXECUTE) throw new AccessDeniedException(path.toString());
        }
        IsoPath iso = cast(path);
        iso.getFileSystem().entry(iso.entryPath());   // throws NoSuchFileException if absent
    }

    @Override
    public boolean isSameFile(Path path, Path other) {
        return path.equals(other);
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw new IOException("no file store for an ISO entry");
    }

    // --- writing: refused -------------------------------------------------

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(Path path) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    private static IsoPath cast(Path path) {
        if (path instanceof IsoPath iso) return iso;
        throw new ProviderMismatchException(String.valueOf(path));
    }

    /** Read-only channel over one entry's extents. */
    private static final class EntryChannel implements SeekableByteChannel {
        private final IsoImage image;
        private final IsoEntry entry;
        private long position;
        private boolean open = true;

        EntryChannel(IsoImage image, IsoEntry entry) {
            this.image = image;
            this.entry = entry;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            if (!open) throw new java.nio.channels.ClosedChannelException();
            int n = image.read(entry, position, destination);
            if (n > 0) position += n;
            return n;
        }

        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return entry.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    /** {@link BasicFileAttributes} over one {@link IsoEntry}. */
    private record Attributes(IsoEntry entry) implements BasicFileAttributes {
        @Override
        public FileTime lastModifiedTime() {
            return entry.lastModifiedTime().orElse(FileTime.fromMillis(0));
        }

        @Override
        public FileTime lastAccessTime() {
            return lastModifiedTime();
        }

        @Override
        public FileTime creationTime() {
            return lastModifiedTime();
        }

        @Override
        public boolean isRegularFile() {
            return !entry.directory();
        }

        @Override
        public boolean isDirectory() {
            return entry.directory();
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public long size() {
            return entry.size();
        }

        @Override
        public Object fileKey() {
            return entry.extents().isEmpty() ? null : entry.extents().get(0).offset();
        }
    }
}
