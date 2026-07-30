package io.github.ghosthack.seven.internal.commons.compress.archivers.sevenz;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Small reader-only replacement for the Commons IO bounded stream used by the
 * vendored SevenZFile implementation.
 */
public final class BoundedInputStream extends FilterInputStream {
    private long count;
    private final long maxCount;
    private final boolean propagateClose;

    public BoundedInputStream(
            InputStream inputStream, long maxCount, boolean propagateClose) {
        super(Objects.requireNonNull(inputStream, "inputStream"));
        this.maxCount = maxCount;
        this.propagateClose = propagateClose;
    }

    @Override
    public int available() throws IOException {
        if (remainingLimit() == 0) {
            return 0;
        }
        return (int) Math.min(in.available(), remainingLimit());
    }

    @Override
    public void close() throws IOException {
        if (propagateClose) {
            super.close();
        }
    }

    public long getCount() {
        return count;
    }

    public long getRemaining() {
        return maxCount < 0 ? Long.MAX_VALUE : Math.max(0, maxCount - count);
    }

    @Override
    public int read() throws IOException {
        if (remainingLimit() == 0) {
            return -1;
        }
        int value = in.read();
        if (value >= 0) {
            count++;
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        long remaining = remainingLimit();
        if (remaining == 0) {
            return -1;
        }
        int read = in.read(bytes, offset, (int) Math.min(length, remaining));
        if (read > 0) {
            count += read;
        }
        return read;
    }

    @Override
    public long skip(long amount) throws IOException {
        long skipped = in.skip(Math.min(amount, remainingLimit()));
        count += skipped;
        return skipped;
    }

    private long remainingLimit() {
        return maxCount < 0 ? Long.MAX_VALUE : Math.max(0, maxCount - count);
    }
}
