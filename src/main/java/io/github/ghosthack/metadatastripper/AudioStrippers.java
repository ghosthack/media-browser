package io.github.ghosthack.metadatastripper;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class AudioStrippers {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Set<String> WAV_MEDIA_CHUNKS = Set.of(
            "fmt ", "fact", "data", "wavl", "slnt"
    );

    private AudioStrippers() {}

    static void stripMp3(Path input, Path output) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(input.toFile(), "r");
             RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw")) {
            out.setLength(0);
            long start = leadingId3End(in);
            long end = trailingMetadataStart(in, start);
            if (end <= start) {
                throw new IOException("MP3 contains no audio payload after metadata removal");
            }
            copyRange(in, out, start, end - start);
        }
    }

    private static long leadingId3End(RandomAccessFile in) throws IOException {
        long position = 0;
        while (position + 10 <= in.length()) {
            byte[] header = readAt(in, position, 10);
            if (!asciiAt(header, 0, "ID3")) {
                break;
            }
            for (int i = 6; i < 10; i++) {
                if ((header[i] & 0x80) != 0) {
                    throw new IOException("Invalid ID3 synchsafe size");
                }
            }
            long payloadSize = ((long) header[6] << 21)
                    | ((long) header[7] << 14)
                    | ((long) header[8] << 7)
                    | header[9];
            long totalSize = 10 + payloadSize + (((header[5] & 0x10) != 0) ? 10 : 0);
            if (totalSize > in.length() - position) {
                throw new IOException("ID3 tag exceeds file size");
            }
            position += totalSize;
        }
        return position;
    }

    private static long trailingMetadataStart(RandomAccessFile in, long minimum) throws IOException {
        long end = in.length();
        boolean changed;
        do {
            changed = false;
            if (end - minimum >= 128 && asciiAt(readAt(in, end - 128, 3), 0, "TAG")) {
                end -= 128;
                changed = true;
                continue;
            }
            if (end - minimum >= 32 && asciiAt(readAt(in, end - 32, 8), 0, "APETAGEX")) {
                byte[] footer = readAt(in, end - 32, 32);
                long version = Binary.uint32LE(footer, 8);
                long tagSize = Binary.uint32LE(footer, 12);
                if ((version == 1000 || version == 2000) && tagSize >= 32 && tagSize <= end - minimum) {
                    long start = end - tagSize;
                    if (start - minimum >= 32 && asciiAt(readAt(in, start - 32, 8), 0, "APETAGEX")) {
                        start -= 32;
                    }
                    end = start;
                    changed = true;
                    continue;
                }
            }
            if (end - minimum >= 15 && asciiAt(readAt(in, end - 9, 9), 0, "LYRICS200")) {
                byte[] sizeBytes = readAt(in, end - 15, 6);
                String sizeText = new String(sizeBytes, StandardCharsets.ISO_8859_1);
                if (sizeText.chars().allMatch(Character::isDigit)) {
                    long lyricsSize = Long.parseLong(sizeText);
                    long start = end - 15 - lyricsSize;
                    if (start >= minimum && start + 11 <= end
                            && asciiAt(readAt(in, start, 11), 0, "LYRICSBEGIN")) {
                        end = start;
                        changed = true;
                    }
                }
            }
        } while (changed);
        return end;
    }

    static void stripFlac(Path input, Path output) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw")) {
            out.setLength(0);
            byte[] signature = Binary.readExactly(in, 4);
            if (!Binary.ascii(signature).equals("fLaC")) {
                throw new IOException("Invalid FLAC signature");
            }
            out.write(signature);

            long lastKeptHeader = -1;
            boolean sawStreamInfo = false;
            boolean last;
            do {
                int header = readByte(in);
                last = (header & 0x80) != 0;
                int type = header & 0x7f;
                int length = (readByte(in) << 16) | (readByte(in) << 8) | readByte(in);
                if (type == 127) {
                    throw new IOException("Invalid FLAC metadata block type");
                }
                if (!sawStreamInfo && (type != 0 || length != 34)) {
                    throw new IOException("FLAC does not begin with a valid STREAMINFO block");
                }
                sawStreamInfo = true;

                // STREAMINFO and SEEKTABLE are playback/navigation structures.
                // CUESHEET can contain catalog and ISRC identifiers, so it is metadata here.
                boolean keep = type == 0 || type == 3;
                if (keep) {
                    lastKeptHeader = out.getFilePointer();
                    out.write(type); // Clear the last-block bit until all metadata is inspected.
                    out.write(length >>> 16);
                    out.write(length >>> 8);
                    out.write(length);
                    Binary.copyExactly(in, out, length);
                } else {
                    Binary.skipExactly(in, length);
                }
            } while (!last);

            if (lastKeptHeader < 0) {
                throw new IOException("FLAC has no STREAMINFO block");
            }
            long audioStart = out.getFilePointer();
            out.seek(lastKeptHeader);
            int finalHeader = out.readUnsignedByte() | 0x80;
            out.seek(lastKeptHeader);
            out.write(finalHeader);
            out.seek(audioStart);
            copyRemaining(in, out);
        } catch (EOFException e) {
            throw new IOException("Truncated FLAC", e);
        }
    }

    static void stripWav(Path input, Path output) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw")) {
            out.setLength(0);
            byte[] riff = Binary.readExactly(in, 4);
            long riffSize = Binary.readUInt32LE(in);
            byte[] wave = Binary.readExactly(in, 4);
            if (!Binary.ascii(riff).equals("RIFF") || !Binary.ascii(wave).equals("WAVE") || riffSize < 4) {
                throw new IOException("Invalid WAV RIFF header (RF64 is not supported)");
            }
            out.write(riff);
            Binary.writeUInt32LE(out, 0);
            out.write(wave);

            long remaining = riffSize - 4;
            while (remaining > 0) {
                if (remaining < 8) {
                    throw new IOException("Truncated WAV chunk header");
                }
                byte[] typeBytes = Binary.readExactly(in, 4);
                String type = Binary.ascii(typeBytes);
                long length = Binary.readUInt32LE(in);
                long paddedLength = length + (length & 1);
                remaining -= 8;
                if (paddedLength > remaining) {
                    throw new IOException("WAV chunk exceeds RIFF boundary: " + type);
                }

                boolean kept;
                if ("LIST".equals(type)) {
                    kept = stripWavList(in, out, typeBytes, length);
                } else if (WAV_MEDIA_CHUNKS.contains(type)) {
                    out.write(typeBytes);
                    Binary.writeUInt32LE(out, length);
                    Binary.copyExactly(in, out, length);
                    kept = true;
                } else {
                    Binary.skipExactly(in, length);
                    kept = false;
                }
                if ((length & 1) != 0) {
                    int padding = readByte(in);
                    if (kept) {
                        out.write(padding);
                    }
                }
                remaining -= paddedLength;
            }

            long outputSize = out.length();
            if (outputSize - 8 > 0xffff_ffffL) {
                throw new IOException("Stripped WAV exceeds the RIFF size limit");
            }
            out.seek(4);
            Binary.writeUInt32LE(out, outputSize - 8);
        } catch (EOFException e) {
            throw new IOException("Truncated WAV", e);
        }
    }

    private static boolean stripWavList(InputStream in, RandomAccessFile out, byte[] typeBytes, long length)
            throws IOException {
        if (length < 4) {
            Binary.skipExactly(in, length);
            return false;
        }
        byte[] listType = Binary.readExactly(in, 4);
        boolean keep = Binary.ascii(listType).equals("wavl");
        if (keep) {
            out.write(typeBytes);
            Binary.writeUInt32LE(out, length);
            out.write(listType);
            Binary.copyExactly(in, out, length - 4);
        } else {
            Binary.skipExactly(in, length - 4);
        }
        return keep;
    }

    private static void copyRange(RandomAccessFile in, RandomAccessFile out, long start, long length)
            throws IOException {
        in.seek(start);
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

    private static void copyRemaining(InputStream in, RandomAccessFile out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (count > 0) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static byte[] readAt(RandomAccessFile in, long offset, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.seek(offset);
        in.readFully(bytes);
        return bytes;
    }

    private static boolean asciiAt(byte[] bytes, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if ((bytes[offset + i] & 0xff) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of file");
        }
        return value;
    }
}

