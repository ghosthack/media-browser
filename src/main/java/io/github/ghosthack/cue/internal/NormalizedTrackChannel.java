package io.github.ghosthack.cue.internal;

import io.github.ghosthack.cue.CueArchiveException;
import io.github.ghosthack.cue.CueSectorException;
import io.github.ghosthack.cue.CueTrack;
import io.github.ghosthack.cue.CueTrackMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/** Maps normalized logical-sector positions onto a physical raw track. */
public final class NormalizedTrackChannel implements SeekableByteChannel {
    private static final int LOGICAL_SECTOR_BYTES = 2048;

    private final CueTrack track;
    private final FileChannel source;
    private final Runnable onClose;
    private long position;
    private boolean open = true;

    public NormalizedTrackChannel(CueTrack track, FileChannel source, Runnable onClose) {
        this.track = Objects.requireNonNull(track, "track");
        this.source = Objects.requireNonNull(source, "source");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    @Override
    public synchronized int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        if (!destination.hasRemaining()) return 0;
        if (position >= track.logicalBytes()) return -1;

        int requested =
                (int) Math.min(destination.remaining(), track.logicalBytes() - position);
        int total = 0;
        while (total < requested) {
            long logicalSector = position / LOGICAL_SECTOR_BYTES;
            int withinSector = (int) (position % LOGICAL_SECTOR_BYTES);
            int amount = Math.min(requested - total, LOGICAL_SECTOR_BYTES - withinSector);
            validateMode2Sector(logicalSector);
            long physical =
                    physicalOffset(logicalSector, track.mode().payloadOffset() + withinSector);
            readFully(physical, destination, amount);
            position += amount;
            total += amount;
        }
        return total;
    }

    private void validateMode2Sector(long logicalSector) throws IOException {
        CueTrackMode mode = track.mode();
        if (mode != CueTrackMode.MODE2_2352 && mode != CueTrackMode.MODE2_2336) return;
        int subheaderOffset = mode == CueTrackMode.MODE2_2352 ? 16 : 0;
        ByteBuffer subheader = ByteBuffer.allocate(8);
        readFully(physicalOffset(logicalSector, subheaderOffset), subheader, 8);
        byte[] bytes = subheader.array();
        for (int index = 0; index < 4; index++) {
            if (bytes[index] != bytes[index + 4]) {
                throw new CueSectorException(
                        "Mode 2 sector "
                                + logicalSector
                                + " has mismatched subheader copies in track "
                                + track.number());
            }
        }
        if ((bytes[2] & 0x20) != 0) {
            throw new CueSectorException(
                    "Mode 2 Form 2 sector "
                            + logicalSector
                            + " cannot be exposed as 2048-byte data");
        }
    }

    private long physicalOffset(long logicalSector, int withinPhysicalSector)
            throws IOException {
        try {
            return Math.addExact(
                    track.storedDataOffset(),
                    Math.addExact(
                            Math.multiplyExact(
                                    logicalSector, track.mode().storedSectorBytes()),
                            withinPhysicalSector));
        } catch (ArithmeticException e) {
            throw new CueArchiveException("physical sector offset overflows", e);
        }
    }

    private void readFully(long sourcePosition, ByteBuffer destination, int amount)
            throws IOException {
        int oldLimit = destination.limit();
        destination.limit(destination.position() + amount);
        try {
            long cursor = sourcePosition;
            while (destination.hasRemaining()) {
                int read = source.read(destination, cursor);
                if (read < 0) {
                    throw new CueArchiveException(
                            "companion file was truncated while reading track "
                                    + track.number());
                }
                if (read == 0) {
                    throw new CueArchiveException(
                            "companion channel made no progress while reading track "
                                    + track.number());
                }
                cursor += read;
            }
        } finally {
            destination.limit(oldLimit);
        }
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
    public long size() throws IOException {
        ensureOpen();
        return track.logicalBytes();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized boolean isOpen() {
        return open && source.isOpen();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!open) return;
        open = false;
        try {
            source.close();
        } finally {
            onClose.run();
        }
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) throw new IOException("CUE data-track channel is closed");
    }
}
