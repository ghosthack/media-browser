package io.github.ghosthack.mediabrowser.media.archive.stream;

import io.github.ghosthack.mediabrowser.media.archive.ArchiveFormat;
import io.github.ghosthack.epubmedia.EpubArchive;
import io.github.ghosthack.epubmedia.EpubEntry;
import io.github.ghosthack.pdfmedia.PdfArchive;
import io.github.ghosthack.pdfmedia.PdfEntry;
import io.github.ghosthack.pdfmedia.PdfFilter;
import io.github.ghosthack.pdfmedia.PdfMrcComposite;
import io.github.ghosthack.pdfmedia.PdfRasterDescriptor;
import io.github.ghosthack.seven.SevenArchive;
import io.github.ghosthack.seven.SevenEntry;
import io.github.ghosthack.unrar.RarArchive;
import io.github.ghosthack.unrar.RarEntry;

import java.awt.image.BufferedImage;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.InflaterInputStream;

/**
 * Consumer-owned NIO adapter over robust-unrar, robust-seven, pdf-media, and epub-media.
 *
 * <p>The media-inspection readers retain parsing, decoding and resource-budget policy;
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

    /** Mounts a streaming source with the matching media-inspection reader. */
    public StreamFileSystem newFileSystem(Path source, ArchiveFormat format) throws IOException {
        if (format != ArchiveFormat.RAR
                && format != ArchiveFormat.SEVEN_Z
                && format != ArchiveFormat.PDF
                && format != ArchiveFormat.EPUB) {
            throw new IllegalArgumentException("not a streaming archive format: " + format);
        }
        Path key = source.toAbsolutePath().normalize();
        if (mounts.containsKey(key)) throw new FileSystemAlreadyExistsException(key.toString());

        StreamArchive archive = switch (format) {
            case RAR -> openRar(key);
            case SEVEN_Z -> openSeven(key);
            case PDF -> openPdf(key);
            case EPUB -> openEpub(key);
            default -> throw new IllegalArgumentException(
                    "not a streaming archive format: " + format);
        };
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

    private static StreamArchive openEpub(Path source) throws IOException {
        EpubArchive reader = EpubArchive.open(source);
        try {
            StreamArchive archive = new StreamArchive(reader);
            Set<String> usedNames = new HashSet<>();
            for (EpubEntry entry : reader.entries()) {
                String name = uniqueName(entry.packagePath(), usedNames);
                archive.add(
                        name,
                        false,
                        entry.uncompressedSize(),
                        null,
                        entry,
                        () -> reader.openStream(entry));
            }
            return archive;
        } catch (RuntimeException e) {
            try {
                reader.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Adapts the PDF media index to a flat directory. PDF entry names are
     * metadata, not paths, so only their leaf is retained and collisions are
     * made visible rather than silently dropping later physical objects.
     *
     * <p>A raster receives a conventional image extension only when its raw
     * stream is already a complete standalone JPEG or JPEG 2000 bitstream.
     * PDF-native streams, including JBIG2, retain {@code .pdfimg}: direct NIO
     * access always exposes the physical source bytes. A JBIG2 raster also
     * carries a private PNG presentation opener used only when a media backend
     * needs a standalone file to display it.</p>
     */
    private static StreamArchive openPdf(Path source) throws IOException {
        PdfArchive reader = PdfArchive.open(source);
        try {
            StreamArchive archive = new StreamArchive(reader);
            Set<String> usedNames = new HashSet<>();
            for (PdfEntry entry : reader.entries()) {
                String name = uniquePdfName(entry, usedNames);
                boolean decodedJbig2 = rasterEncodedAs(entry, PdfFilter.Decoder.JBIG2);
                boolean decodedFlate = PdfFlateImages.supports(entry);
                String wrappedRasterExtension = flateWrappedRasterExtension(entry);
                archive.add(name, false, entry.declaredSize().orElse(-1), null, entry,
                        () -> reader.openStream(entry),
                        decodedJbig2 || decodedFlate ? "png" : wrappedRasterExtension,
                        decodedJbig2
                                ? () -> PdfJbig2Images.openPng(reader, entry)
                                : decodedFlate
                                        ? () -> PdfFlateImages.openPng(reader, entry)
                                        : wrappedRasterExtension == null
                                                ? null
                                                : () -> new InflaterInputStream(
                                                        reader.openStream(entry)),
                        decodedJbig2
                                ? () -> PdfJbig2Images.decode(reader, entry)
                                : decodedFlate
                                        ? () -> PdfFlateImages.decode(reader, entry) : null);
            }
            for (PdfMrcComposite composite : reader.mrcComposites()) {
                if (!supportsMrc(reader, composite)) continue;
                String preferred =
                        String.format(Locale.ROOT, "page-%04d.mrc", composite.pageIndex() + 1);
                String name = uniqueName(preferred, usedNames);
                archive.add(name, false, 0, null, composite,
                        () -> {
                            throw new IOException(
                                    "PDF MRC composite has no standalone byte stream: " + name);
                        });
            }
            return archive;
        } catch (RuntimeException e) {
            try {
                reader.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static String uniquePdfName(PdfEntry entry, Set<String> usedNames) {
        String original = pdfLeafName(entry.name(), entry.index());
        String preferred = directRasterName(original, entry);
        String candidate = preferred;
        int copy = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = numberedName(preferred, copy++);
        }
        return candidate;
    }

    private static String uniqueName(String preferred, Set<String> usedNames) {
        String candidate = preferred;
        int copy = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = numberedName(preferred, copy++);
        }
        return candidate;
    }

    private static String pdfLeafName(String rawName, int index) {
        String[] parts = rawName.replace('\\', '/').split("/+");
        String leaf = "";
        for (int at = parts.length - 1; at >= 0; at--) {
            if (!parts[at].isBlank() && !".".equals(parts[at]) && !"..".equals(parts[at])) {
                leaf = parts[at];
                break;
            }
        }
        leaf = leaf.replaceAll("[\\p{Cntrl}]", "_").trim();
        return leaf.isEmpty() ? "entry-" + (index + 1) : leaf;
    }

    private static String directRasterName(String name, PdfEntry entry) {
        if (!entry.isRaster()) return name;
        if (rasterEncodedAs(entry, PdfFilter.Decoder.JPEG)) {
            return replaceExtension(name, "jpg");
        }
        if (rasterEncodedAs(entry, PdfFilter.Decoder.JPEG_2000)) {
            return replaceExtension(name, "jp2");
        }
        return name;
    }

    /**
     * Decoder-only rendition for an archive entry, when the raw mounted bytes
     * are not themselves a standalone format understood by media backends.
     */
    public record Presentation(String extension, InputStream input) {}

    public Optional<Presentation> openPresentation(Path path) throws IOException {
        if (!(path instanceof StreamPath stream)
                || stream.getFileSystem().provider() != this) {
            return Optional.empty();
        }
        StreamArchive.Node node =
                stream.getFileSystem().entry(stream.entryPath());
        if (node.presentationOpener == null) return Optional.empty();
        return Optional.of(new Presentation(
                node.presentationExtension, node.presentationOpener.open()));
    }

    /** The physical PDF layers referenced by a virtual MRC entry. */
    public record PdfMrcView(
            PdfMrcComposite descriptor,
            Path background,
            PdfEntry backgroundEntry,
            Path foreground,
            PdfEntry foregroundEntry,
            Path mask,
            PdfEntry maskEntry) {}

    public Optional<PdfMrcView> pdfMrcView(Path path) throws IOException {
        if (!(path instanceof StreamPath stream)
                || stream.getFileSystem().provider() != this) {
            return Optional.empty();
        }
        StreamFileSystem fileSystem = stream.getFileSystem();
        StreamArchive.Node compositeNode = fileSystem.entry(stream.entryPath());
        if (!(compositeNode.key instanceof PdfMrcComposite descriptor)) {
            return Optional.empty();
        }
        Layer background = layer(fileSystem, descriptor.backgroundEntryIndex());
        Layer foreground = layer(fileSystem, descriptor.foregroundEntryIndex());
        Layer mask = layer(fileSystem, descriptor.maskEntryIndex());
        return Optional.of(new PdfMrcView(
                descriptor,
                background.path,
                background.entry,
                foreground.path,
                foreground.entry,
                mask.path,
                mask.entry));
    }

    /** Decodes a PDF-native raster directly, without creating a presentation file. */
    public Optional<BufferedImage> decodePdfRaster(Path path) throws IOException {
        if (!(path instanceof StreamPath stream)
                || stream.getFileSystem().provider() != this) {
            return Optional.empty();
        }
        StreamArchive.Node node = stream.getFileSystem().entry(stream.entryPath());
        return node.rasterOpener == null
                ? Optional.empty() : Optional.of(node.rasterOpener.open());
    }

    /** Whether this consumer can turn a single Flate PDF raster into a viewable image. */
    public static boolean supportsPdfFlateRaster(PdfRasterDescriptor raster) {
        return PdfFlateImages.supports(raster);
    }

    private Layer layer(StreamFileSystem fileSystem, int entryIndex) throws IOException {
        for (StreamArchive.Node node : fileSystem.children("/")) {
            if (node.key instanceof PdfEntry entry && entry.index() == entryIndex) {
                return new Layer(fileSystem.getPath(node.path), entry);
            }
        }
        throw new IOException("PDF MRC layer entry is not mounted: " + entryIndex);
    }

    private record Layer(Path path, PdfEntry entry) {}

    private static boolean rasterEncodedAs(PdfEntry entry, PdfFilter.Decoder decoder) {
        if (!entry.isRaster()) return false;
        List<PdfFilter.Decoder> decoders =
                entry.raster().orElseThrow().decoderStack();
        return decoders.equals(List.of(decoder));
    }

    /**
     * A few PDF producers zlib-wrap an otherwise standalone JPEG or JPEG 2000
     * stream. Keep the physical bytes mounted as {@code .pdfimg}, but offer a
     * private decoder rendition with only that outer storage filter removed.
     */
    private static String flateWrappedRasterExtension(PdfEntry entry) {
        if (!entry.isRaster()) return null;
        var raster = entry.raster().orElseThrow();
        List<PdfFilter.Decoder> decoders = raster.decoderStack();
        if (decoders.size() != 2 || decoders.getFirst() != PdfFilter.Decoder.FLATE) {
            return null;
        }
        Object predictor = raster.filters().getFirst().parameters().get("Predictor");
        if (predictor instanceof Number number && number.intValue() != 1) return null;
        return switch (decoders.getLast()) {
            case JPEG -> "jpg";
            case JPEG_2000 -> "jp2";
            default -> null;
        };
    }

    private static boolean supportsMrc(PdfArchive archive, PdfMrcComposite composite) {
        PdfEntry background = archive.entries().get(composite.backgroundEntryIndex());
        PdfEntry foreground = archive.entries().get(composite.foregroundEntryIndex());
        PdfEntry mask = archive.entries().get(composite.maskEntryIndex());
        return directlyDecodableColorLayer(background)
                && directlyDecodableColorLayer(foreground)
                && rasterEncodedAs(mask, PdfFilter.Decoder.JBIG2);
    }

    private static boolean directlyDecodableColorLayer(PdfEntry entry) {
        return rasterEncodedAs(entry, PdfFilter.Decoder.JPEG)
                || rasterEncodedAs(entry, PdfFilter.Decoder.JPEG_2000);
    }

    private static String replaceExtension(String name, String extension) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "." + extension;
    }

    private static String numberedName(String name, int copy) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return stem + " (" + copy + ")" + extension;
    }

    @Override
    public StreamFileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        Object value = env.get("format");
        if (!(value instanceof ArchiveFormat format)) {
            throw new IllegalArgumentException("env.format must be RAR, SEVEN_Z, PDF or EPUB");
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
        if (entry.key instanceof PdfMrcComposite) {
            throw new IOException(
                    "PDF MRC composite has no standalone byte stream: " + entry.name);
        }
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
            boolean sizeKnown = entry.size >= 0;
            if (sizeKnown && position >= entry.size) return -1;
            align();
            byte[] buffer = new byte[(int) Math.min(64 * 1024L,
                    sizeKnown
                            ? Math.min(destination.remaining(), entry.size - position)
                            : destination.remaining())];
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
            return visibleSize(entry);
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
        @Override public long size() { return visibleSize(entry); }
        @Override public Object fileKey() { return entry.key; }
    }

    /**
     * Directory scans reject zero-byte media before opening it. An unknown PDF
     * entry size therefore uses a one-byte presence hint; materialization still
     * streams to EOF and produces the exact real size.
     */
    private static long visibleSize(StreamArchive.Node entry) {
        if (entry.key instanceof PdfMrcComposite) return 0;
        return entry.size >= 0 ? entry.size : 1;
    }
}
