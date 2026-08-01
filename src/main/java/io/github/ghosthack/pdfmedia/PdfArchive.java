package io.github.ghosthack.pdfmedia;

import io.github.ghosthack.pdfmedia.internal.EntryContent;
import io.github.ghosthack.pdfmedia.internal.PdfIndex;
import io.github.ghosthack.pdfmedia.internal.PdfMediaIndexer;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * A read-only, archive-like view of media physically embedded in one PDF.
 *
 * <p>Opening parses the PDF and snapshots a media index. Entry content stays lazy: attachments
 * retain their logical file bytes, while raster objects retain their raw encoded PDF stream
 * bytes and an immutable decoder descriptor. PDF pages are never rendered.</p>
 *
 * <p>The object model owned by PDFBox is not documented as concurrently mutable/readable, so
 * extraction work for one archive is serialized internally. Returned streams are bounded pipes:
 * callers do not need to hold an entire attachment or encoded raster in memory.</p>
 */
public final class PdfArchive implements AutoCloseable {
    private static final byte[] SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final int MAX_HEADER_OFFSET = 1024;

    private final Path source;
    private final PdfOpenOptions options;
    private final char[] password;
    private final BasicFileAttributes sourceAttributes;
    private final PDDocument document;
    private final boolean passwordProtected;
    private final List<PdfEntry> entries;
    private final List<EntryContent> contents;
    private final List<PdfMrcComposite> mrcComposites;
    private final Map<String, List<PdfEntry>> entriesByName;
    private final Semaphore streamPermits;
    private final Set<EntryInputStream> openStreams = ConcurrentHashMap.newKeySet();
    private final Object documentLock = new Object();
    private volatile boolean closed;

    private PdfArchive(
            Path source,
            PdfOpenOptions options,
            char[] password,
            BasicFileAttributes sourceAttributes,
            PDDocument document,
            PdfIndex index) {
        this.source = source;
        this.options = options;
        this.password = password;
        this.sourceAttributes = sourceAttributes;
        this.document = document;
        this.passwordProtected = document.isEncrypted();
        this.entries = index.entries();
        this.contents = index.contents();
        this.mrcComposites = index.mrcComposites();
        this.streamPermits = new Semaphore(options.maxConcurrentStreams(), true);

        Map<String, List<PdfEntry>> byName = new LinkedHashMap<>();
        for (PdfEntry entry : entries) {
            byName.computeIfAbsent(entry.name(), ignored -> new ArrayList<>()).add(entry);
        }
        byName.replaceAll((ignored, matches) -> List.copyOf(matches));
        this.entriesByName = Collections.unmodifiableMap(byName);
    }

    /** Opens a PDF with default budgets and no password. */
    public static PdfArchive open(Path source) throws IOException {
        return open(source, PdfOpenOptions.defaults(), null);
    }

    /** Opens a PDF with explicit budgets and no password. */
    public static PdfArchive open(Path source, PdfOpenOptions options) throws IOException {
        return open(source, options, null);
    }

    /**
     * Opens a PDF with explicit budgets and an optional password.
     *
     * <p>The supplied password is defensively copied and the copy is wiped on close. PDFBox
     * currently accepts passwords as immutable strings, so conversion at that dependency boundary
     * cannot itself be wiped by this library. The caller remains responsible for wiping its own
     * array.</p>
     */
    public static PdfArchive open(Path source, PdfOpenOptions options, char[] password)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Path normalized = source.toAbsolutePath().normalize();
        BasicFileAttributes attributes =
                Files.readAttributes(normalized, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("PDF source is not a regular file: " + normalized);
        }
        if (attributes.size() > options.maxSourceBytes()) {
            throw new PdfArchiveException(
                    "PDF source has "
                            + attributes.size()
                            + " bytes; source budget is "
                            + options.maxSourceBytes());
        }
        requireSignature(normalized);

        char[] ownedPassword = password == null ? null : password.clone();
        PDDocument document = null;
        try {
            document =
                    ownedPassword == null
                            ? Loader.loadPDF(normalized.toFile())
                            : Loader.loadPDF(normalized.toFile(), new String(ownedPassword));
            if (options.honorExtractionPermission()
                    && !document.getCurrentAccessPermission().canExtractContent()) {
                throw new PdfArchiveException(
                        "PDF security settings do not permit content extraction: " + normalized);
            }
            PdfIndex index = PdfMediaIndexer.index(document, options);
            return new PdfArchive(
                    normalized, options, ownedPassword, attributes, document, index);
        } catch (InvalidPasswordException e) {
            closeAfterFailedOpen(document, e);
            wipe(ownedPassword);
            throw new PdfArchiveException("PDF password required or incorrect: " + normalized, e);
        } catch (IOException | RuntimeException e) {
            closeAfterFailedOpen(document, e);
            wipe(ownedPassword);
            throw e;
        }
    }

    /**
     * Tests a byte prefix for a PDF header. The signature may occur within the first 1,024 bytes,
     * matching the tolerated leading-junk convention used by PDF readers.
     */
    public static boolean matches(byte[] prefix) {
        Objects.requireNonNull(prefix, "prefix");
        int last = Math.min(prefix.length - SIGNATURE.length, MAX_HEADER_OFFSET);
        for (int offset = 0; offset <= last; offset++) {
            boolean match = true;
            for (int index = 0; index < SIGNATURE.length; index++) {
                if (prefix[offset + index] != SIGNATURE[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** Returns the normalized source path. */
    public Path source() {
        return source;
    }

    /** Returns whether the opened document is encrypted. */
    public boolean passwordProtected() {
        return passwordProtected;
    }

    /** Returns the PDF page count as container metadata; pages are not entries. */
    public int pageCount() {
        return document.getNumberOfPages();
    }

    /** Returns the immutable media-entry snapshot in discovery order. */
    public List<PdfEntry> entries() {
        return entries;
    }

    /**
     * Returns recognized Mixed Raster Content page graphs.
     *
     * <p>Each graph references physical entries from {@link #entries()}; it has no standalone
     * encoded byte stream and does not replace or hide any layer.</p>
     */
    public List<PdfMrcComposite> mrcComposites() {
        return mrcComposites;
    }

    /** Returns every entry with the exact member name, preserving duplicate names. */
    public List<PdfEntry> entries(String exactName) {
        Objects.requireNonNull(exactName, "exactName");
        return entriesByName.getOrDefault(exactName, List.of());
    }

    /**
     * Opens one entry's bytes.
     *
     * <p>Embedded files retain their original bytes after the PDF's storage filters are removed.
     * Raster entries expose the raw encoded image stream after PDF document decryption but before
     * any filter in {@link PdfRasterDescriptor#filters()} is applied. Decoder-auxiliary entries
     * expose logical side data such as JBIG2 global segments. Output is checked against
     * {@link PdfOpenOptions#maxEntryBytes()} while streamed.</p>
     */
    public InputStream openStream(PdfEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        ensureOpen();
        validateEntryIdentity(entry);
        ensureSourceUnchanged();
        acquirePermit();

        PipedInputStream input = null;
        try {
            input = new PipedInputStream(options.pipeBufferBytes());
            PipedOutputStream output = new PipedOutputStream(input);
            EntryInputStream stream =
                    new EntryInputStream(input, output, contents.get(entry.index()), entry);
            openStreams.add(stream);
            if (closed && openStreams.remove(stream)) {
                stream.close();
                throw new IOException("PDF archive is closed");
            }
            stream.start();
            return stream;
        } catch (IOException | RuntimeException e) {
            streamPermits.release();
            if (input != null) {
                try {
                    input.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw e;
        }
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
        synchronized (documentLock) {
            try {
                document.close();
            } catch (IOException e) {
                failure = accumulate(failure, e);
            }
        }
        wipe(password);
        if (failure != null) {
            throw failure;
        }
    }

    private void acquirePermit() throws IOException {
        try {
            streamPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for a PDF extraction stream", e);
        }
        if (closed) {
            streamPermits.release();
            throw new IOException("PDF archive is closed");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("PDF archive is closed");
        }
    }

    private void validateEntryIdentity(PdfEntry entry) throws PdfArchiveException {
        if (entry.index() >= entries.size() || entries.get(entry.index()) != entry) {
            throw new PdfArchiveException("entry does not belong to this PDF archive");
        }
    }

    private void ensureSourceUnchanged() throws IOException {
        BasicFileAttributes current =
                Files.readAttributes(source, BasicFileAttributes.class);
        boolean fileKeyChanged =
                sourceAttributes.fileKey() != null
                        && current.fileKey() != null
                        && !sourceAttributes.fileKey().equals(current.fileKey());
        if (fileKeyChanged
                || current.size() != sourceAttributes.size()
                || !current.lastModifiedTime().equals(sourceAttributes.lastModifiedTime())) {
            throw new PdfArchiveException("PDF source changed after it was opened: " + source);
        }
    }

    private static void requireSignature(Path source) throws IOException {
        byte[] prefix;
        try (InputStream input = Files.newInputStream(source)) {
            prefix = input.readNBytes(MAX_HEADER_OFFSET + SIGNATURE.length);
        }
        if (!matches(prefix)) {
            throw new PdfArchiveException("not a PDF file: " + source);
        }
    }

    private static void closeAfterFailedOpen(PDDocument document, Throwable primary) {
        if (document == null) {
            return;
        }
        try {
            document.close();
        } catch (IOException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private static IOException accumulate(IOException current, IOException added) {
        if (current == null) {
            return added;
        }
        current.addSuppressed(added);
        return current;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private final class EntryInputStream extends FilterInputStream {
        private final PipedOutputStream producerOutput;
        private final EntryContent content;
        private final PdfEntry entry;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean streamClosed = new AtomicBoolean();
        private volatile Thread producer;

        EntryInputStream(
                PipedInputStream input,
                PipedOutputStream producerOutput,
                EntryContent content,
                PdfEntry entry) {
            super(input);
            this.producerOutput = producerOutput;
            this.content = content;
            this.entry = entry;
        }

        void start() {
            producer =
                    Thread.ofVirtual()
                            .name("pdf-media-" + entry.index())
                            .start(this::produce);
        }

        private void produce() {
            OutputStream output =
                    new LimitedOutputStream(producerOutput, options.maxEntryBytes(), entry.name());
            try {
                synchronized (documentLock) {
                    if (closed || streamClosed.get()) {
                        output.close();
                        return;
                    }
                    content.writeTo(output);
                }
                output.close();
            } catch (Throwable thrown) {
                if (!streamClosed.get()) {
                    // Publish the producer failure before closing the pipe. Otherwise a reader
                    // awakened by EOF can race ahead of this catch block and mistake failure for
                    // successful completion.
                    failure.compareAndSet(null, thrown);
                }
                try {
                    output.close();
                } catch (IOException closeFailure) {
                    thrown.addSuppressed(closeFailure);
                }
            } finally {
                streamPermits.release();
            }
        }

        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                return value < 0 ? finishRead(value) : value;
            } catch (IOException e) {
                throw preferredFailure(e);
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                int count = super.read(bytes, offset, length);
                return count < 0 ? finishRead(count) : count;
            } catch (IOException e) {
                throw preferredFailure(e);
            }
        }

        private int finishRead(int eof) throws IOException {
            Throwable thrown = failure.get();
            if (thrown != null) {
                throw asIOException(thrown);
            }
            return eof;
        }

        private IOException preferredFailure(IOException pipeFailure) {
            Throwable thrown = failure.get();
            if (thrown == null) {
                return pipeFailure;
            }
            IOException preferred = asIOException(thrown);
            if (preferred != pipeFailure) {
                preferred.addSuppressed(pipeFailure);
            }
            return preferred;
        }

        @Override
        public void close() throws IOException {
            if (!streamClosed.compareAndSet(false, true)) {
                return;
            }
            openStreams.remove(this);
            Thread running = producer;
            if (running != null) {
                running.interrupt();
            }
            IOException failure = null;
            try {
                super.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                producerOutput.close();
            } catch (IOException e) {
                failure = accumulate(failure, e);
            }
            if (failure != null) {
                throw failure;
            }
        }

        private IOException asIOException(Throwable thrown) {
            if (thrown instanceof IOException io) {
                return io;
            }
            return new PdfArchiveException(
                    "cannot extract PDF entry " + entry.name(), thrown);
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private final String entryName;
        private long count;

        LimitedOutputStream(OutputStream output, long limit, String entryName) {
            super(output);
            this.limit = limit;
            this.entryName = entryName;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            out.write(bytes, offset, length);
            count += length;
        }

        private void requireCapacity(int next) throws PdfArchiveException {
            if (next > limit - count) {
                throw new PdfArchiveException(
                        "PDF entry "
                                + entryName
                                + " exceeds output budget "
                                + limit
                                + " bytes");
            }
        }
    }
}
