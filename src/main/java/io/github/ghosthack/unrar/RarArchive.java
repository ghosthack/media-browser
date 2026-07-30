package io.github.ghosthack.unrar;

import io.github.ghosthack.unrar.internal.junrar.Archive;
import io.github.ghosthack.unrar.internal.junrar.ArchiveOptions;
import io.github.ghosthack.unrar.internal.junrar.exception.RarException;
import io.github.ghosthack.unrar.internal.junrar.rarfile.FileHeader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A read-only view of one RAR archive.
 *
 * <p>The archive keeps a bounded pool of decoder sessions. Each session retains
 * its parsed header index across successful member reads, so a mounted archive
 * does not reparse for ordinary sequential access. Concurrent streams lease
 * independent sessions and therefore cannot corrupt one another's solid-stream
 * state.
 */
public final class RarArchive implements AutoCloseable {
    private final Path source;
    private final RarOpenOptions options;
    private final char[] password;
    private final BasicFileAttributes sourceAttributes;
    private final RarFormat format;
    private final boolean passwordProtected;
    private final List<RarEntry> entries;
    private final Map<String, List<RarEntry>> entriesByName;
    private final Set<EntryInputStream> openStreams = ConcurrentHashMap.newKeySet();
    private final Object decoderLock = new Object();
    private final Deque<Archive> idleDecoders = new ArrayDeque<>();
    private final Set<Archive> allDecoders = ConcurrentHashMap.newKeySet();
    private final Semaphore decoderPermits;
    private final boolean reusableSessions;
    private final Archive indexTemplate;
    private final AtomicInteger decoderSessionCount = new AtomicInteger();
    private volatile boolean closed;

    private RarArchive(
            Path source,
            RarOpenOptions options,
            char[] password,
            BasicFileAttributes sourceAttributes,
            RarFormat format,
            boolean passwordProtected,
            List<RarEntry> entries,
            Archive initialDecoder,
            boolean reusableSessions) {
        this.source = source;
        this.options = options;
        this.password = password;
        this.sourceAttributes = sourceAttributes;
        this.format = format;
        this.passwordProtected = passwordProtected;
        this.entries = List.copyOf(entries);
        this.decoderPermits = new Semaphore(options.maxDecoderSessions(), true);
        this.reusableSessions = reusableSessions;
        this.indexTemplate = initialDecoder;

        Map<String, List<RarEntry>> byName = new LinkedHashMap<>();
        for (RarEntry entry : entries) {
            byName.computeIfAbsent(entry.name(), ignored -> new ArrayList<>()).add(entry);
        }
        byName.replaceAll((ignored, matches) -> List.copyOf(matches));
        this.entriesByName = Collections.unmodifiableMap(byName);
    }

    /** Opens an archive with the default resource budgets and no password. */
    public static RarArchive open(Path source) throws IOException {
        return open(source, RarOpenOptions.defaults(), null);
    }

    /** Opens an archive with explicit resource budgets and no password. */
    public static RarArchive open(Path source, RarOpenOptions options) throws IOException {
        return open(source, options, null);
    }

    /**
     * Opens an archive with explicit budgets and an optional password.
     *
     * <p>The password is defensively copied. The caller remains responsible for
     * wiping its own array. This archive wipes its copy on {@link #close()}.
     */
    public static RarArchive open(Path source, RarOpenOptions options, char[] password)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Path normalized = source.toAbsolutePath().normalize();
        BasicFileAttributes attributes =
                Files.readAttributes(normalized, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("RAR source is not a regular file: " + normalized);
        }

        char[] ownedPassword = password == null ? null : password.clone();
        Archive archive = null;
        try {
            archive = openDecoder(normalized, options, ownedPassword);
            List<FileHeader> headers = archive.getFileHeaders();
            if (headers.size() > options.maxEntries()) {
                throw new RarArchiveException(
                        "archive has "
                                + headers.size()
                                + " entries; budget is "
                                + options.maxEntries());
            }

            List<RarEntry> entries = snapshotEntries(headers, options);
            RarFormat format =
                    switch (archive.getFormat()) {
                        case RAR15 -> RarFormat.RAR4;
                        case RAR50 -> RarFormat.RAR5;
                    };
            boolean encrypted = archive.isPasswordProtected();
            return new RarArchive(
                    normalized,
                    options,
                    ownedPassword,
                    attributes,
                    format,
                    encrypted,
                    entries,
                    archive,
                    !archive.isMultiVolume());
        } catch (RarException e) {
            closeAfterFailedOpen(archive, e);
            wipe(ownedPassword);
            throw new RarArchiveException("cannot open RAR archive: " + normalized, e);
        } catch (IOException | RuntimeException e) {
            closeAfterFailedOpen(archive, e);
            wipe(ownedPassword);
            throw e;
        }
    }

    /** Returns the normalized source path. */
    public Path source() {
        return source;
    }

    /** Returns the detected container format. */
    public RarFormat format() {
        return format;
    }

    /** Returns whether archive headers or at least one member are password protected. */
    public boolean passwordProtected() {
        return passwordProtected;
    }

    /** Returns the immutable entry snapshot in archive order. */
    public List<RarEntry> entries() {
        return entries;
    }

    /** Returns every entry with the exact archive name, preserving duplicate names. */
    public List<RarEntry> entries(String exactName) {
        Objects.requireNonNull(exactName, "exactName");
        return entriesByName.getOrDefault(exactName, List.of());
    }

    /**
     * Opens the uncompressed bytes of a regular file entry.
     *
     * <p>Extraction runs on a virtual thread and any decoder or CRC failure is
     * reported by the returned stream instead of being converted to an early EOF.
     */
    public InputStream openStream(RarEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        ensureOpen();
        validateEntryIdentity(entry);
        if (!entry.isRegularFile()) {
            throw new RarArchiveException("cannot open a directory entry: " + entry.name());
        }
        enforceContentBudget(entry);
        ensureSourceUnchanged();

        DecoderLease lease = acquireDecoder();
        Archive decoder = lease.archive;
        List<FileHeader> headers = decoder.getFileHeaders();
        if (entry.index() >= headers.size()) {
            lease.release(false);
            throw new RarArchiveException("archive changed while opening entry: " + entry.name());
        }

        EntryInputStream stream;
        try {
            stream =
                    new EntryInputStream(
                            lease,
                            headers.get(entry.index()),
                            entry,
                            options.pipeBufferBytes(),
                            headers.get(entry.index()).isUnpSizeUnknown()
                                    ? options.maxEntryUncompressedBytes()
                                    : entry.uncompressedSize());
        } catch (IOException | RuntimeException e) {
            IOException closeFailure = lease.release(false);
            if (closeFailure != null) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        stream.onClosed = () -> openStreams.remove(stream);
        openStreams.add(stream);
        if (closed && openStreams.remove(stream)) {
            stream.close();
            throw new IOException("RAR archive is closed");
        }
        stream.start();
        return stream;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (EntryInputStream stream : List.copyOf(openStreams)) {
            try {
                stream.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        openStreams.clear();
        for (Archive decoder : List.copyOf(allDecoders)) {
            try {
                closeDecoder(decoder);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        try {
            indexTemplate.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        wipe(password);
        if (failure != null) {
            throw failure;
        }
    }

    private DecoderLease acquireDecoder() throws IOException {
        try {
            decoderPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for a decoder session", e);
        }

        boolean leased = false;
        try {
            ensureOpen();
            Archive decoder;
            synchronized (decoderLock) {
                decoder = idleDecoders.pollFirst();
            }
            if (decoder == null) {
                decoder = forkDecoder(indexTemplate, source, options, password);
                allDecoders.add(decoder);
                decoderSessionCount.incrementAndGet();
            }
            if (closed) {
                closeDecoder(decoder);
                throw new IOException("RAR archive is closed");
            }
            leased = true;
            return new DecoderLease(decoder);
        } finally {
            if (!leased) {
                decoderPermits.release();
            }
        }
    }

    int decoderSessionCount() {
        return decoderSessionCount.get();
    }

    private void closeDecoder(Archive decoder) throws IOException {
        synchronized (decoderLock) {
            idleDecoders.remove(decoder);
        }
        if (allDecoders.remove(decoder)) {
            decoder.close();
        }
    }

    private static void closeAfterFailedOpen(Archive archive, Throwable failure) {
        if (archive == null) {
            return;
        }
        try {
            archive.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Archive openDecoder(Path source, RarOpenOptions options, char[] password)
            throws IOException, RarException {
        ArchiveOptions archiveOptions =
                ArchiveOptions.builder()
                        .password(password)
                        .maxDictionarySize(options.maxDictionaryBytes())
                        .maxHeaders(
                                options.maxEntries() > Integer.MAX_VALUE - 4_096
                                        ? Integer.MAX_VALUE
                                        : options.maxEntries() + 4_096)
                        .build();
        try (archiveOptions) {
            return new Archive(source.toFile(), archiveOptions);
        }
    }

    private static Archive forkDecoder(
            Archive template, Path source, RarOpenOptions options, char[] password)
            throws IOException {
        ArchiveOptions archiveOptions =
                ArchiveOptions.builder()
                        .password(password)
                        .maxDictionarySize(options.maxDictionaryBytes())
                        .maxHeaders(
                                options.maxEntries() > Integer.MAX_VALUE - 4_096
                                        ? Integer.MAX_VALUE
                                        : options.maxEntries() + 4_096)
                        .build();
        try (archiveOptions) {
            return template.fork(source.toFile(), archiveOptions);
        }
    }

    private static List<RarEntry> snapshotEntries(
            List<FileHeader> headers, RarOpenOptions options) throws RarArchiveException {
        List<RarEntry> result = new ArrayList<>(headers.size());
        long totalSize = 0;
        for (int index = 0; index < headers.size(); index++) {
            FileHeader header = headers.get(index);
            long compressed = header.getFullPackSize();
            long uncompressed = header.getFullUnpackSize();
            if (compressed < 0 || uncompressed < 0) {
                throw new RarArchiveException(
                        "entry has an invalid negative size: " + header.getFileName());
            }
            if (!header.isUnpSizeUnknown()) {
                try {
                    totalSize = Math.addExact(totalSize, uncompressed);
                } catch (ArithmeticException e) {
                    throw new RarArchiveException("declared uncompressed sizes overflow", e);
                }
                if (totalSize > options.maxTotalUncompressedBytes()) {
                    throw new RarArchiveException(
                            "archive declares "
                                    + totalSize
                                    + " uncompressed bytes; budget is "
                                    + options.maxTotalUncompressedBytes());
                }
            }
            result.add(
                    new RarEntry(
                            index,
                            header.getFileName(),
                            header.isDirectory()
                                    ? RarEntry.Kind.DIRECTORY
                                    : RarEntry.Kind.FILE,
                            compressed,
                            uncompressed,
                            !header.isUnpSizeUnknown(),
                            Optional.ofNullable(header.getLastModifiedTime()),
                            header.isEncrypted(),
                            header.isSolid(),
                            header.isSplitBefore(),
                            header.isSplitAfter()));
        }
        return result;
    }

    private void validateEntryIdentity(RarEntry entry) throws RarArchiveException {
        if (entry.index() >= entries.size() || !entries.get(entry.index()).equals(entry)) {
            throw new RarArchiveException("entry does not belong to this archive");
        }
    }

    private void enforceContentBudget(RarEntry entry) throws RarArchiveException {
        if (entry.uncompressedSizeKnown()
                && entry.uncompressedSize() > options.maxEntryUncompressedBytes()) {
            throw new RarArchiveException(
                    "entry declares "
                            + entry.uncompressedSize()
                            + " uncompressed bytes; per-entry budget is "
                            + options.maxEntryUncompressedBytes());
        }
        double ratio =
                !entry.uncompressedSizeKnown()
                        ? 1.0
                        :
                entry.compressedSize() == 0
                        ? (entry.uncompressedSize() == 0 ? 1.0 : Double.POSITIVE_INFINITY)
                        : (double) entry.uncompressedSize() / entry.compressedSize();
        if (ratio > options.maxCompressionRatio()) {
            throw new RarArchiveException(
                    "entry compression ratio "
                            + ratio
                            + " exceeds budget "
                            + options.maxCompressionRatio());
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("RAR archive is closed");
        }
    }

    private void ensureSourceUnchanged() throws IOException {
        BasicFileAttributes current = Files.readAttributes(source, BasicFileAttributes.class);
        if (current.size() != sourceAttributes.size()
                || !current.lastModifiedTime().equals(sourceAttributes.lastModifiedTime())) {
            throw new RarArchiveException("archive changed after it was opened: " + source);
        }
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private final class DecoderLease {
        private final Archive archive;
        private final AtomicBoolean released = new AtomicBoolean();

        private DecoderLease(Archive archive) {
            this.archive = archive;
        }

        private IOException release(boolean healthy) {
            if (!released.compareAndSet(false, true)) {
                return null;
            }
            try {
                if (healthy && reusableSessions && !closed) {
                    synchronized (decoderLock) {
                        idleDecoders.addLast(archive);
                    }
                } else {
                    closeDecoder(archive);
                }
                return null;
            } catch (IOException e) {
                return e;
            } finally {
                decoderPermits.release();
            }
        }
    }

    private static final class EntryInputStream extends FilterInputStream {
        private final DecoderLease lease;
        private final FileHeader header;
        private final RarEntry entry;
        private final long outputLimit;
        private final PipedOutputStream producerOutput;
        private final Thread producer;
        private volatile Throwable producerFailure;
        private volatile boolean closed;
        private Runnable onClosed;

        EntryInputStream(
                DecoderLease lease,
                FileHeader header,
                RarEntry entry,
                int pipeBufferBytes,
                long outputLimit)
                throws IOException {
            super(new PipedInputStream(pipeBufferBytes));
            this.lease = lease;
            this.header = header;
            this.entry = entry;
            this.outputLimit = outputLimit;
            this.onClosed = () -> {};
            this.producerOutput = new PipedOutputStream((PipedInputStream) in);
            this.producer =
                    Thread.ofVirtual()
                            .name("robust-unrar-" + entry.index())
                            .unstarted(this::extract);
        }

        void start() {
            producer.start();
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            return checkEnd(value);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            return checkEnd(count);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                super.close();
            } catch (IOException e) {
                failure = e;
            }
            producer.interrupt();
            IOException leaseFailure = lease.release(false);
            if (leaseFailure != null) {
                if (failure == null) {
                    failure = leaseFailure;
                } else {
                    failure.addSuppressed(leaseFailure);
                }
            }
            onClosed.run();
            if (failure != null) {
                throw failure;
            }
        }

        private int checkEnd(int result) throws IOException {
            if (result == -1 && producerFailure != null && !closed) {
                throw new RarArchiveException(
                        "failed while reading archive entry: " + entry.name(), producerFailure);
            }
            return result;
        }

        private void extract() {
            try (OutputStream bounded =
                    new BoundedOutputStream(producerOutput, outputLimit)) {
                lease.archive.extractFile(header, bounded);
            } catch (Throwable failure) {
                producerFailure = failure;
                try {
                    producerOutput.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            } finally {
                IOException closeFailure = lease.release(producerFailure == null);
                if (closeFailure != null) {
                    if (producerFailure == null) {
                        producerFailure = closeFailure;
                    } else {
                        producerFailure.addSuppressed(closeFailure);
                    }
                }
            }
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long maximum;
        private long written;

        BoundedOutputStream(OutputStream delegate, long maximum) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            delegate.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void reserve(int count) throws IOException {
            if (count > maximum - written) {
                throw new RarArchiveException(
                        "decoder produced more bytes than the entry declared");
            }
            written += count;
        }
    }
}
