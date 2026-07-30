package io.github.ghosthack.seven;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import io.github.ghosthack.seven.internal.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import io.github.ghosthack.seven.internal.commons.compress.archivers.sevenz.SevenZFile;
import io.github.ghosthack.seven.internal.commons.compress.archivers.sevenz.SevenZMethod;

/**
 * A read-only view of one 7z archive.
 *
 * <p>Opening the archive snapshots its entry index. Member streams lease
 * independently channeled decoder sessions, so unrelated reads can proceed
 * concurrently without sharing seek or solid-folder state. Sessions are
 * bounded and a session is reused only after its prior stream reached a
 * verified EOF.
 */
public final class SevenArchive implements AutoCloseable {
    private static final byte[] SIGNATURE = {
        0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c
    };

    private final Path source;
    private final SevenOpenOptions options;
    private final char[] password;
    private final BasicFileAttributes sourceAttributes;
    private final List<SevenEntry> entries;
    private final Map<String, List<SevenEntry>> entriesByName;
    private final boolean passwordProtected;
    private final Set<EntryInputStream> openStreams = ConcurrentHashMap.newKeySet();
    private final Object decoderLock = new Object();
    private final Deque<DecoderSession> idleDecoders = new ArrayDeque<>();
    private final Set<DecoderSession> allDecoders = ConcurrentHashMap.newKeySet();
    private final Semaphore decoderPermits;
    private final AtomicInteger decoderSessionCount = new AtomicInteger();
    private volatile boolean closed;

    private SevenArchive(
            Path source,
            SevenOpenOptions options,
            char[] password,
            BasicFileAttributes sourceAttributes,
            List<SevenEntry> entries,
            DecoderSession initialDecoder) {
        this.source = source;
        this.options = options;
        this.password = password;
        this.sourceAttributes = sourceAttributes;
        this.entries = List.copyOf(entries);
        this.decoderPermits = new Semaphore(options.maxDecoderSessions(), true);
        this.passwordProtected = entries.stream().anyMatch(SevenEntry::encrypted);
        this.allDecoders.add(initialDecoder);
        this.idleDecoders.add(initialDecoder);
        this.decoderSessionCount.incrementAndGet();

        Map<String, List<SevenEntry>> byName = new LinkedHashMap<>();
        for (SevenEntry entry : entries) {
            byName.computeIfAbsent(entry.name(), ignored -> new ArrayList<>()).add(entry);
        }
        byName.replaceAll((ignored, matches) -> List.copyOf(matches));
        this.entriesByName = Collections.unmodifiableMap(byName);
    }

    /** Opens an archive with the default resource budgets and no password. */
    public static SevenArchive open(Path source) throws IOException {
        return open(source, SevenOpenOptions.defaults(), null);
    }

    /** Opens an archive with explicit resource budgets and no password. */
    public static SevenArchive open(Path source, SevenOpenOptions options) throws IOException {
        return open(source, options, null);
    }

    /**
     * Opens an archive with explicit budgets and an optional password.
     *
     * <p>The password is defensively copied. The caller remains responsible for
     * wiping its own array. This archive wipes its copy on {@link #close()}.
     */
    public static SevenArchive open(Path source, SevenOpenOptions options, char[] password)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Path normalized = source.toAbsolutePath().normalize();
        BasicFileAttributes attributes =
                Files.readAttributes(normalized, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("7z source is not a regular file: " + normalized);
        }
        requireSignature(normalized);

        char[] ownedPassword = password == null ? null : password.clone();
        DecoderSession decoder = null;
        try {
            decoder = openDecoder(normalized, options, ownedPassword);
            List<SevenEntry> entries =
                    snapshotEntries(decoder.archive.entries(), options);
            return new SevenArchive(
                    normalized, options, ownedPassword, attributes, entries, decoder);
        } catch (SevenArchiveException e) {
            closeAfterFailedOpen(decoder, e);
            wipe(ownedPassword);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeAfterFailedOpen(decoder, e);
            wipe(ownedPassword);
            throw new SevenArchiveException("cannot open 7z archive: " + normalized, e);
        }
    }

    /**
     * Tests a byte prefix for the six-byte 7z signature.
     *
     * <p>This is intended for a consumer's cheap format-sniffing path.
     */
    public static boolean matches(byte[] prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.length < SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < SIGNATURE.length; index++) {
            if (prefix[index] != SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    /** Returns the normalized source path. */
    public Path source() {
        return source;
    }

    /** Returns whether at least one indexed entry uses 7z AES encryption. */
    public boolean passwordProtected() {
        return passwordProtected;
    }

    /** Returns the immutable entry snapshot in archive order. */
    public List<SevenEntry> entries() {
        return entries;
    }

    /** Returns every entry with the exact raw archive name, preserving duplicates. */
    public List<SevenEntry> entries(String exactName) {
        Objects.requireNonNull(exactName, "exactName");
        return entriesByName.getOrDefault(exactName, List.of());
    }

    /**
     * Opens the uncompressed bytes of a file entry.
     *
     * <p>Compressed contents are inherently streaming. Closing before EOF
     * discards that decoder session so partial solid-folder state is never
     * reused.
     */
    public InputStream openStream(SevenEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        ensureOpen();
        validateEntryIdentity(entry);
        if (!entry.isRegularFile()) {
            throw new SevenArchiveException(
                    "cannot open a non-file entry: " + entry.name());
        }
        enforceDeclaredEntryBudget(entry);
        ensureSourceUnchanged();

        DecoderLease lease = acquireDecoder();
        EntryInputStream stream;
        try {
            InputStream contents =
                    lease.session.archive.getInputStream(entry.index());
            stream =
                    new EntryInputStream(
                            contents,
                            lease.session.archive::getCompressedCount,
                            lease,
                            entry);
        } catch (IOException | RuntimeException e) {
            IOException closeFailure = lease.release(false);
            if (closeFailure != null) {
                e.addSuppressed(closeFailure);
            }
            throw new SevenArchiveException(
                    "cannot open 7z entry: " + entry.name(), e);
        }

        stream.onClosed = () -> openStreams.remove(stream);
        openStreams.add(stream);
        if (closed && openStreams.remove(stream)) {
            stream.close();
            throw new IOException("7z archive is closed");
        }
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
                failure = accumulate(failure, e);
            }
        }
        openStreams.clear();
        for (DecoderSession decoder : List.copyOf(allDecoders)) {
            try {
                closeDecoder(decoder);
            } catch (IOException e) {
                failure = accumulate(failure, e);
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
            throw new IOException("interrupted while waiting for a 7z decoder session", e);
        }

        boolean leased = false;
        try {
            ensureOpen();
            DecoderSession decoder;
            synchronized (decoderLock) {
                decoder = idleDecoders.pollFirst();
            }
            if (decoder == null) {
                decoder = openDecoder(source, options, password);
                allDecoders.add(decoder);
                decoderSessionCount.incrementAndGet();
            }
            if (closed) {
                closeDecoder(decoder);
                throw new IOException("7z archive is closed");
            }
            if (decoder.archive.entryCount() != entries.size()) {
                closeDecoder(decoder);
                throw new SevenArchiveException(
                        "archive changed while opening a decoder session: " + source);
            }
            leased = true;
            return new DecoderLease(decoder);
        } finally {
            if (!leased) {
                decoderPermits.release();
            }
        }
    }

    private void closeDecoder(DecoderSession decoder) throws IOException {
        synchronized (decoderLock) {
            idleDecoders.remove(decoder);
        }
        if (allDecoders.remove(decoder)) {
            decoder.archive.close();
        }
    }

    private static DecoderSession openDecoder(
            Path source, SevenOpenOptions options, char[] password) throws IOException {
        SeekableByteChannel channel =
                Files.newByteChannel(source, StandardOpenOption.READ);
        try {
            SevenZFile archive =
                    SevenZFile.open(
                            channel,
                            source.toString(),
                            password,
                            toMemoryLimitKiB(options.maxDecoderMemoryBytes()));
            return new DecoderSession(archive);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static int toMemoryLimitKiB(long bytes) {
        long kibibytes = Math.max(1L, (bytes + 1023L) / 1024L);
        return (int) Math.min(Integer.MAX_VALUE, kibibytes);
    }

    private static List<SevenEntry> snapshotEntries(
            List<SevenZArchiveEntry> decoderEntries, SevenOpenOptions options)
            throws SevenArchiveException {
        if (decoderEntries.size() > options.maxEntries()) {
            throw new SevenArchiveException(
                    "archive has "
                            + decoderEntries.size()
                            + " entries; budget is "
                            + options.maxEntries());
        }

        long totalSize = 0;
        List<SevenEntry> result = new ArrayList<>(decoderEntries.size());
        for (int index = 0; index < decoderEntries.size(); index++) {
            SevenZArchiveEntry entry = decoderEntries.get(index);
            String name = entry.getName();
            if (name == null) {
                throw new SevenArchiveException("entry " + index + " has no name");
            }
            long size = entry.getSize();
            if (size < 0) {
                throw new SevenArchiveException("entry has an invalid size: " + name);
            }
            try {
                totalSize = Math.addExact(totalSize, size);
            } catch (ArithmeticException e) {
                throw new SevenArchiveException("declared uncompressed sizes overflow", e);
            }
            if (totalSize > options.maxTotalUncompressedBytes()) {
                throw new SevenArchiveException(
                        "archive declares "
                                + totalSize
                                + " uncompressed bytes; budget is "
                                + options.maxTotalUncompressedBytes());
            }

            List<SevenMethod> methods = snapshotMethods(entry);
            SevenEntry.Kind kind =
                    entry.isAntiItem()
                            ? SevenEntry.Kind.ANTI_ITEM
                            : entry.isDirectory()
                                    ? SevenEntry.Kind.DIRECTORY
                                    : SevenEntry.Kind.FILE;
            result.add(
                    new SevenEntry(
                            index,
                            name,
                            kind,
                            size,
                            entry.hasStream(),
                            entry.getHasCrc()
                                    ? OptionalLong.of(entry.getCrcValue())
                                    : OptionalLong.empty(),
                            entry.getHasCreationDate()
                                    ? Optional.of(entry.getCreationTime())
                                    : Optional.empty(),
                            entry.getHasAccessDate()
                                    ? Optional.of(entry.getAccessTime())
                                    : Optional.empty(),
                            entry.getHasLastModifiedDate()
                                    ? Optional.of(entry.getLastModifiedTime())
                                    : Optional.empty(),
                            entry.getHasWindowsAttributes()
                                    ? OptionalInt.of(entry.getWindowsAttributes())
                                    : OptionalInt.empty(),
                            methods,
                            methods.contains(SevenMethod.AES256_SHA256)));
        }
        return result;
    }

    private static List<SevenMethod> snapshotMethods(SevenZArchiveEntry entry) {
        List<SevenMethod> methods = new ArrayList<>();
        for (SevenZMethod method : entry.getContentMethods()) {
            methods.add(
                    switch (method) {
                        case COPY -> SevenMethod.COPY;
                        case LZMA -> SevenMethod.LZMA;
                        case LZMA2 -> SevenMethod.LZMA2;
                        case DEFLATE -> SevenMethod.DEFLATE;
                        case DEFLATE64 -> SevenMethod.DEFLATE64;
                        case BZIP2 -> SevenMethod.BZIP2;
                        case AES256SHA256 -> SevenMethod.AES256_SHA256;
                        case BCJ_X86_FILTER -> SevenMethod.BCJ_X86;
                        case BCJ_PPC_FILTER -> SevenMethod.BCJ_POWER_PC;
                        case BCJ_IA64_FILTER -> SevenMethod.BCJ_IA64;
                        case BCJ_ARM_FILTER -> SevenMethod.BCJ_ARM;
                        case BCJ_ARM_THUMB_FILTER -> SevenMethod.BCJ_ARM_THUMB;
                        case BCJ_SPARC_FILTER -> SevenMethod.BCJ_SPARC;
                        case DELTA_FILTER -> SevenMethod.DELTA;
                    });
        }
        return List.copyOf(methods);
    }

    private void validateEntryIdentity(SevenEntry entry) throws SevenArchiveException {
        if (entry.index() >= entries.size() || !entries.get(entry.index()).equals(entry)) {
            throw new SevenArchiveException("entry does not belong to this archive");
        }
    }

    private void enforceDeclaredEntryBudget(SevenEntry entry) throws SevenArchiveException {
        if (entry.uncompressedSize() > options.maxEntryUncompressedBytes()) {
            throw new SevenArchiveException(
                    "entry declares "
                            + entry.uncompressedSize()
                            + " uncompressed bytes; per-entry budget is "
                            + options.maxEntryUncompressedBytes());
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("7z archive is closed");
        }
    }

    private void ensureSourceUnchanged() throws IOException {
        BasicFileAttributes current = Files.readAttributes(source, BasicFileAttributes.class);
        Object originalKey = sourceAttributes.fileKey();
        Object currentKey = current.fileKey();
        if (current.size() != sourceAttributes.size()
                || !current.lastModifiedTime().equals(sourceAttributes.lastModifiedTime())
                || originalKey != null && currentKey != null && !originalKey.equals(currentKey)) {
            throw new SevenArchiveException("archive changed after it was opened: " + source);
        }
    }

    private static void requireSignature(Path source) throws IOException {
        ByteBuffer signature = ByteBuffer.allocate(SIGNATURE.length);
        try (SeekableByteChannel channel =
                Files.newByteChannel(source, StandardOpenOption.READ)) {
            while (signature.hasRemaining()) {
                int count = channel.read(signature);
                if (count < 0) {
                    break;
                }
            }
        }
        if (signature.hasRemaining() || !matches(signature.array())) {
            throw new SevenArchiveException("not a 7z archive: " + source);
        }
    }

    private static void closeAfterFailedOpen(DecoderSession decoder, Throwable failure) {
        if (decoder == null) {
            return;
        }
        try {
            decoder.archive.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static IOException accumulate(IOException current, IOException addition) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    int decoderSessionCount() {
        return decoderSessionCount.get();
    }

    private record DecoderSession(SevenZFile archive) {}

    private final class DecoderLease {
        private final DecoderSession session;
        private final AtomicBoolean released = new AtomicBoolean();

        private DecoderLease(DecoderSession session) {
            this.session = session;
        }

        private IOException release(boolean healthy) {
            if (!released.compareAndSet(false, true)) {
                return null;
            }
            try {
                if (healthy && !closed) {
                    synchronized (decoderLock) {
                        idleDecoders.addLast(session);
                    }
                } else {
                    closeDecoder(session);
                }
                return null;
            } catch (IOException e) {
                return e;
            } finally {
                decoderPermits.release();
            }
        }
    }

    private final class EntryInputStream extends FilterInputStream {
        private final LongSupplier compressedCount;
        private final DecoderLease lease;
        private final SevenEntry entry;
        private long outputBytes;
        private boolean eof;
        private boolean failed;
        private boolean closed;
        private IOException readFailure;
        private Runnable onClosed = () -> {};

        private EntryInputStream(
                InputStream contents,
                LongSupplier compressedCount,
                DecoderLease lease,
                SevenEntry entry) {
            super(contents);
            this.compressedCount = compressedCount;
            this.lease = lease;
            this.entry = entry;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            ensureReadable();
            try {
                int count = in.read(bytes, offset, length);
                if (count < 0) {
                    verifyEof();
                    eof = true;
                    return -1;
                }
                outputBytes = Math.addExact(outputBytes, count);
                enforceStreamingBudgets();
                return count;
            } catch (IOException e) {
                throw fail("failed while reading 7z entry: " + entry.name(), e);
            } catch (ArithmeticException e) {
                throw fail("entry output size overflow: " + entry.name(), e);
            } catch (RuntimeException e) {
                throw fail("failed while reading 7z entry: " + entry.name(), e);
            }
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            long skipped = 0;
            byte[] buffer = new byte[(int) Math.min(8192L, count)];
            while (skipped < count) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readLimit) {
            // Deliberately unsupported: rewinding would invalidate stream budgets.
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset is not supported for 7z entry streams");
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                in.close();
            } catch (IOException e) {
                failed = true;
                failure = e;
            }
            IOException releaseFailure = lease.release(eof && !failed);
            if (releaseFailure != null) {
                failure = accumulate(failure, releaseFailure);
            }
            onClosed.run();
            if (failure != null) {
                throw failure;
            }
        }

        private void ensureReadable() throws IOException {
            if (closed) {
                throw new IOException("7z entry stream is closed");
            }
            if (readFailure != null) {
                throw readFailure;
            }
        }

        private void verifyEof() throws IOException {
            if (outputBytes != entry.uncompressedSize()) {
                throw new SevenArchiveException(
                        "entry ended after "
                                + outputBytes
                                + " bytes; expected "
                                + entry.uncompressedSize()
                                + ": "
                                + entry.name());
            }
        }

        private void enforceStreamingBudgets() throws SevenArchiveException {
            if (outputBytes > options.maxEntryUncompressedBytes()) {
                throw new SevenArchiveException(
                        "entry exceeded the uncompressed-byte budget: " + entry.name());
            }
            long compressedBytes = compressedCount.getAsLong();
            if (compressedBytes == 0) {
                // Some filter stacks can emit buffered output before their
                // compressed-input counter advances.
                return;
            }
            double ratio = (double) outputBytes / compressedBytes;
            if (ratio > options.maxCompressionRatio()) {
                throw new SevenArchiveException(
                        "entry compression ratio "
                                + ratio
                                + " exceeds budget "
                                + options.maxCompressionRatio()
                                + ": "
                                + entry.name());
            }
        }

        private IOException fail(String message, Throwable cause) {
            if (readFailure == null) {
                failed = true;
                readFailure =
                        cause instanceof SevenArchiveException archiveFailure
                                ? archiveFailure
                                : new SevenArchiveException(message, cause);
                try {
                    in.close();
                } catch (IOException closeFailure) {
                    readFailure.addSuppressed(closeFailure);
                }
                IOException releaseFailure = lease.release(false);
                if (releaseFailure != null) {
                    readFailure.addSuppressed(releaseFailure);
                }
            }
            return readFailure;
        }
    }
}
