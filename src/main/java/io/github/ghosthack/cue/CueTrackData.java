package io.github.ghosthack.cue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/**
 * A thread-safe positional view of normalized CUE data.
 *
 * <p>This is the narrow adapter expected by filesystem parsers such as an ISO
 * reader: logical offsets start at the selected track {@code INDEX 01}, each
 * logical sector is 2048 bytes, and callers need not understand raw-sector
 * headers. An ISO volume view can span physically contiguous data tracks.
 */
public final class CueTrackData implements AutoCloseable {
    private final SeekableByteChannel channel;

    CueTrackData(SeekableByteChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /** Returns the normalized logical byte length. */
    public synchronized long size() throws IOException {
        return channel.size();
    }

    /**
     * Reads at a logical position without retaining that position between calls.
     *
     * @return bytes read, or {@code -1} when {@code position} is at or beyond EOF
     */
    public synchronized int read(long position, ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (position < 0) throw new IllegalArgumentException("negative position");
        channel.position(position);
        return channel.read(target);
    }

    /** Fills {@code target} from a logical position or fails on premature EOF. */
    public synchronized void readFully(long position, ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (position < 0) throw new IllegalArgumentException("negative position");
        channel.position(position);
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                throw new CueArchiveException(
                        "read past the end of normalized CUE track data");
            }
            if (read == 0) {
                throw new CueArchiveException("CUE track data channel made no progress");
            }
        }
    }

    /** Returns whether this view and its source handle remain open. */
    public synchronized boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }
}
