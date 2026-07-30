package io.github.ghosthack.seven.internal.commons.compress.archivers.sevenz;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;

/**
 * Reader-only checksum verifier matching the subset SevenZFile uses from
 * Commons IO.
 */
final class ChecksumInputStream extends FilterInputStream {
    private long count;
    private final long countThreshold;
    private final long expectedChecksumValue;
    private final Checksum checksum;

    ChecksumInputStream(
            InputStream inputStream,
            Checksum checksum,
            long countThreshold,
            long expectedChecksumValue) {
        super(new CheckedInputStream(
                Objects.requireNonNull(inputStream, "inputStream"),
                Objects.requireNonNull(checksum, "checksum")));
        this.checksum = checksum;
        this.countThreshold = countThreshold;
        this.expectedChecksumValue = expectedChecksumValue;
    }

    long getRemaining() {
        return countThreshold - count;
    }

    @Override
    public int read() throws IOException {
        int value = in.read();
        verifyAfterRead(value < 0 ? -1 : 1);
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int read = in.read(bytes, offset, length);
        verifyAfterRead(read);
        return read;
    }

    private void verifyAfterRead(int read) throws IOException {
        if (read > 0) {
            count += read;
        }
        if ((countThreshold > 0 && count >= countThreshold) || read < 0) {
            if (checksum.getValue() != expectedChecksumValue) {
                throw new IOException("Checksum verification failed.");
            }
        }
    }
}
