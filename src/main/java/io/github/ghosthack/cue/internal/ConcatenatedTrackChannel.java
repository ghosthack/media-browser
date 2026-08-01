package io.github.ghosthack.cue.internal;

import io.github.ghosthack.cue.CueArchiveException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Objects;

/**
 * One logical disc-data address space assembled from physically contiguous
 * normalized data tracks.
 */
public final class ConcatenatedTrackChannel implements SeekableByteChannel {
    private final List<SeekableByteChannel> tracks;
    private final long[] starts;
    private final long size;
    private long position;
    private boolean open = true;

    public ConcatenatedTrackChannel(List<SeekableByteChannel> tracks) throws IOException {
        this.tracks = List.copyOf(tracks);
        if (this.tracks.isEmpty()) {
            throw new IllegalArgumentException("tracks must not be empty");
        }
        starts = new long[this.tracks.size()];
        long total = 0;
        try {
            for (int i = 0; i < this.tracks.size(); i++) {
                SeekableByteChannel track =
                        Objects.requireNonNull(this.tracks.get(i), "track");
                starts[i] = total;
                total = Math.addExact(total, track.size());
            }
        } catch (ArithmeticException e) {
            throw new CueArchiveException("concatenated track size overflows", e);
        }
        size = total;
    }

    @Override
    public synchronized int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) return 0;
        if (position >= size) return -1;

        int total = 0;
        while (target.hasRemaining() && position < size) {
            int index = trackAt(position);
            SeekableByteChannel track = tracks.get(index);
            long within = position - starts[index];
            long available = track.size() - within;
            int amount = (int) Math.min(target.remaining(), available);
            int oldLimit = target.limit();
            target.limit(target.position() + amount);
            int read;
            try {
                track.position(within);
                read = track.read(target);
            } finally {
                target.limit(oldLimit);
            }
            if (read < 0) {
                throw new CueArchiveException(
                        "contiguous CUE data track ended before its declared size");
            }
            if (read == 0) {
                throw new CueArchiveException(
                        "contiguous CUE data track made no progress");
            }
            position += read;
            total += read;
        }
        return total;
    }

    private int trackAt(long logicalPosition) {
        for (int i = starts.length - 1; i >= 0; i--) {
            if (logicalPosition >= starts[i]) return i;
        }
        throw new AssertionError("negative logical position");
    }

    @Override
    public int write(ByteBuffer source) throws IOException {
        ensureOpen();
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized long position() throws IOException {
        ensureOpen();
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) throw new IllegalArgumentException("negative position");
        position = newPosition;
        return this;
    }

    @Override
    public synchronized long size() throws IOException {
        ensureOpen();
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized boolean isOpen() {
        if (!open) return false;
        return tracks.stream().allMatch(SeekableByteChannel::isOpen);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!open) return;
        open = false;
        IOException failure = null;
        for (SeekableByteChannel track : tracks) {
            try {
                track.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) throw new IOException("concatenated CUE data channel is closed");
    }
}
