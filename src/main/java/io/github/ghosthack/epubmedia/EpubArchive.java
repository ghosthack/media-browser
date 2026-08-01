package io.github.ghosthack.epubmedia;

import io.github.ghosthack.epubmedia.internal.EpubParser;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * A bounded, read-only view of manifest-backed media physically stored in an EPUB package.
 *
 * <p>The selected package document supplies entry identity and media type. XHTML, CSS, fonts,
 * remote resources, and page rendering are outside this API. Entry bytes are the logical ZIP
 * member bytes and remain lazy until {@link #openStream(EpubEntry)}.</p>
 */
public final class EpubArchive implements AutoCloseable {
    private final Path source;
    private final EpubOpenOptions options;
    private final BasicFileAttributes sourceAttributes;
    private final ZipFile zip;
    private final EpubPackage publication;
    private final List<EpubEntry> entries;
    private final List<ZipEntry> zipEntries;
    private final Semaphore streamPermits;
    private final Set<EntryInputStream> openStreams = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private EpubArchive(
            Path source,
            EpubOpenOptions options,
            BasicFileAttributes sourceAttributes,
            ZipFile zip,
            EpubParser.Selected selected) {
        this.source = source;
        this.options = options;
        this.sourceAttributes = sourceAttributes;
        this.zip = zip;
        this.publication = selected.publication();
        this.entries = selected.entries();
        this.zipEntries = selected.zipEntries();
        this.streamPermits = new Semaphore(options.maxConcurrentStreams(), true);
    }

    /** Opens an EPUB with default finite budgets. */
    public static EpubArchive open(Path source) throws IOException {
        return open(source, EpubOpenOptions.defaults());
    }

    /** Opens an EPUB with explicit finite budgets. */
    public static EpubArchive open(Path source, EpubOpenOptions options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Path normalized = source.toAbsolutePath().normalize();
        BasicFileAttributes attributes =
                Files.readAttributes(normalized, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new EpubArchiveException("EPUB source is not a regular file: " + normalized);
        }
        if (attributes.size() > options.maxSourceBytes()) {
            throw new EpubArchiveException(
                    "EPUB source exceeds source-byte budget " + options.maxSourceBytes());
        }

        ZipFile zip = null;
        try {
            zip = new ZipFile(normalized.toFile(), ZipFile.OPEN_READ);
            List<ZipEntry> physical = validatePhysicalIndex(zip, options);
            EpubParser.Selected selected = EpubParser.parse(zip, physical, options);
            return new EpubArchive(normalized, options, attributes, zip, selected);
        } catch (ZipException e) {
            closeAfterFailure(zip, e);
            throw new EpubArchiveException("invalid EPUB ZIP structure: " + normalized, e);
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(zip, e);
            throw e;
        }
    }

    /** Cheap EPUB identity check used by consumers after extension gating. */
    public static boolean matches(Path source) {
        try (ZipFile zip = new ZipFile(source.toFile(), ZipFile.OPEN_READ)) {
            return EpubParser.hasEpubMimetype(zip);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Returns the normalized host path. */
    public Path source() {
        return source;
    }

    /** Returns immutable package-document metadata. */
    public EpubPackage publication() {
        return publication;
    }

    /** Returns the immutable manifest-selected media snapshot. */
    public List<EpubEntry> entries() {
        return entries;
    }

    /** Opens one entry's exact logical ZIP member bytes. */
    public InputStream openStream(EpubEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        validateEntry(entry);
        acquirePermit();
        try {
            synchronized (this) {
                ensureOpen();
                ensureSourceUnchanged();
                InputStream input = zip.getInputStream(zipEntries.get(entry.index()));
                EntryInputStream bounded = new EntryInputStream(input, entry);
                openStreams.add(bounded);
                return bounded;
            }
        } catch (IOException | RuntimeException e) {
            streamPermits.release();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        List<EntryInputStream> streams;
        synchronized (this) {
            if (closed) return;
            closed = true;
            streams = List.copyOf(openStreams);
        }
        IOException failure = null;
        for (EntryInputStream stream : streams) {
            try {
                stream.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        try {
            zip.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }

    private static List<ZipEntry> validatePhysicalIndex(ZipFile zip, EpubOpenOptions options)
            throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        long totalDeclared = 0;
        var enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            if (entries.size() >= options.maxZipEntries()) {
                throw new EpubArchiveException(
                        "EPUB ZIP entry count exceeds budget " + options.maxZipEntries());
            }
            ZipEntry entry = enumeration.nextElement();
            EpubParser.safePhysicalPath(entry.getName(), options.maxPathCharacters());
            long size = entry.getSize();
            if (!entry.isDirectory() && size >= 0) {
                if (size > options.maxEntryBytes()) {
                    throw new EpubArchiveException(
                            "EPUB member exceeds entry-byte budget: " + entry.getName());
                }
                try {
                    totalDeclared = Math.addExact(totalDeclared, size);
                } catch (ArithmeticException e) {
                    throw new EpubArchiveException("EPUB declared bytes overflow", e);
                }
                if (totalDeclared > options.maxTotalDeclaredBytes()) {
                    throw new EpubArchiveException(
                            "EPUB declared bytes exceed budget "
                                    + options.maxTotalDeclaredBytes());
                }
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    private void validateEntry(EpubEntry entry) throws IOException {
        int index = entry.index();
        if (index < 0 || index >= entries.size() || entries.get(index) != entry) {
            throw new EpubArchiveException("entry does not belong to this EPUB archive");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("EPUB archive is closed");
    }

    private void ensureSourceUnchanged() throws IOException {
        BasicFileAttributes current =
                Files.readAttributes(source, BasicFileAttributes.class);
        if (current.size() != sourceAttributes.size()
                || !current.lastModifiedTime().equals(sourceAttributes.lastModifiedTime())
                || !Objects.equals(current.fileKey(), sourceAttributes.fileKey())) {
            throw new EpubArchiveException("EPUB source changed after it was opened: " + source);
        }
    }

    private void acquirePermit() throws IOException {
        try {
            streamPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for an EPUB entry stream", e);
        }
    }

    private static void closeAfterFailure(ZipFile zip, Throwable failure) {
        if (zip == null) return;
        try {
            zip.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private final class EntryInputStream extends FilterInputStream {
        private final EpubEntry entry;
        private final AtomicBoolean released = new AtomicBoolean();
        private long bytesRead;

        EntryInputStream(InputStream input, EpubEntry entry) {
            super(input);
            this.entry = entry;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(int count) throws IOException {
            try {
                bytesRead = Math.addExact(bytesRead, count);
            } catch (ArithmeticException e) {
                throw new EpubArchiveException("EPUB entry output size overflow", e);
            }
            if (bytesRead > options.maxEntryBytes()) {
                throw new EpubArchiveException(
                        "EPUB entry exceeds output-byte budget: " + entry.packagePath());
            }
        }

        @Override
        public void close() throws IOException {
            if (!released.compareAndSet(false, true)) return;
            try {
                super.close();
            } finally {
                openStreams.remove(this);
                streamPermits.release();
            }
        }
    }
}
