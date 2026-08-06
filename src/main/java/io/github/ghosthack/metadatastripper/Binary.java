package io.github.ghosthack.metadatastripper;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

final class Binary {
    private static final int BUFFER_SIZE = 64 * 1024;

    private Binary() {}

    static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Unexpected end of file");
            }
            offset += count;
        }
        return bytes;
    }

    static void copyExactly(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new EOFException("Unexpected end of file");
            }
            out.write(buffer, 0, count);
            remaining -= count;
        }
    }

    static void copyExactly(InputStream in, RandomAccessFile out, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new EOFException("Unexpected end of file");
            }
            out.write(buffer, 0, count);
            remaining -= count;
        }
    }

    static void skipExactly(InputStream in, long length) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped == 0) {
                if (in.read() < 0) {
                    throw new EOFException("Unexpected end of file");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    static long readUInt32BE(InputStream in) throws IOException {
        byte[] b = readExactly(in, 4);
        return ((long) (b[0] & 0xff) << 24)
                | ((long) (b[1] & 0xff) << 16)
                | ((long) (b[2] & 0xff) << 8)
                | (b[3] & 0xffL);
    }

    static long readUInt32LE(InputStream in) throws IOException {
        byte[] b = readExactly(in, 4);
        return (b[0] & 0xffL)
                | ((long) (b[1] & 0xff) << 8)
                | ((long) (b[2] & 0xff) << 16)
                | ((long) (b[3] & 0xff) << 24);
    }

    static long uint32LE(byte[] b, int offset) {
        return (b[offset] & 0xffL)
                | ((long) (b[offset + 1] & 0xff) << 8)
                | ((long) (b[offset + 2] & 0xff) << 16)
                | ((long) (b[offset + 3] & 0xff) << 24);
    }

    static long uint64BE(byte[] b, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (b[offset + i] & 0xffL);
        }
        return value;
    }

    static void writeUInt32BE(OutputStream out, long value) throws IOException {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    static void writeUInt32LE(RandomAccessFile out, long value) throws IOException {
        out.write((int) value);
        out.write((int) (value >>> 8));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 24));
    }

    static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}

