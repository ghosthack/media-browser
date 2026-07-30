package io.github.ghosthack.mediabrowser.media.archive.iso;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Positional byte source consumed by the ISO 9660 parser. */
interface IsoData extends AutoCloseable {
    long size() throws IOException;
    int read(ByteBuffer target, long position) throws IOException;
    @Override void close() throws IOException;

    static IsoData open(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        return new IsoData() {
            @Override public long size() throws IOException { return channel.size(); }
            @Override public int read(ByteBuffer target, long position) throws IOException {
                return channel.read(target, position);
            }
            @Override public void close() throws IOException { channel.close(); }
        };
    }
}
