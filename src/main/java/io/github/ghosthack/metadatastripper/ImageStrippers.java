package io.github.ghosthack.metadatastripper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

final class ImageStrippers {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
    };
    private static final Set<String> PNG_RENDERING_CHUNKS = Set.of(
            "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "bKGD", "hIST", "tRNS", "sPLT",
            "acTL", "fcTL", "fdAT"
    );
    private static final Set<String> WEBP_MEDIA_CHUNKS = Set.of(
            "VP8X", "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF"
    );
    private static final Set<String> JXL_MEDIA_BOXES = Set.of(
            "JXL ", "ftyp", "jxll", "jxlc", "jxlp", "jxli"
    );

    private ImageStrippers() {}

    static void stripJpeg(Path input, Path output) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(output,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            int first = readByte(in);
            int second = readByte(in);
            if (first != 0xff || second != 0xd8) {
                throw new IOException("Invalid JPEG start marker");
            }
            out.write(first);
            out.write(second);

            boolean inEntropyData = false;
            while (true) {
                int marker = inEntropyData ? nextEntropyMarker(in, out) : nextMarker(in);
                inEntropyData = false;

                if (marker == 0xd9) {
                    out.write(0xff);
                    out.write(marker);
                    return;
                }
                if (isStandaloneJpegMarker(marker)) {
                    out.write(0xff);
                    out.write(marker);
                    continue;
                }

                int lengthHigh = readByte(in);
                int lengthLow = readByte(in);
                int segmentLength = (lengthHigh << 8) | lengthLow;
                if (segmentLength < 2) {
                    throw new IOException("Invalid JPEG segment length for marker 0x"
                            + Integer.toHexString(marker));
                }
                int payloadLength = segmentLength - 2;
                if (isJpegMetadataMarker(marker)) {
                    Binary.skipExactly(in, payloadLength);
                } else {
                    out.write(0xff);
                    out.write(marker);
                    out.write(lengthHigh);
                    out.write(lengthLow);
                    Binary.copyExactly(in, out, payloadLength);
                }
                if (marker == 0xda) {
                    inEntropyData = true;
                }
            }
        } catch (EOFException e) {
            throw new IOException("Truncated JPEG", e);
        }
    }

    private static int nextMarker(InputStream in) throws IOException {
        if (readByte(in) != 0xff) {
            throw new IOException("Expected JPEG marker");
        }
        int marker;
        do {
            marker = readByte(in);
        } while (marker == 0xff);
        if (marker == 0x00) {
            throw new IOException("Unexpected stuffed byte outside JPEG scan");
        }
        return marker;
    }

    private static int nextEntropyMarker(InputStream in, OutputStream out) throws IOException {
        while (true) {
            int value = readByte(in);
            if (value != 0xff) {
                out.write(value);
                continue;
            }

            int fillCount = 1;
            int next = readByte(in);
            while (next == 0xff) {
                fillCount++;
                next = readByte(in);
            }
            if (next == 0x00 || (next >= 0xd0 && next <= 0xd7)) {
                for (int i = 0; i < fillCount; i++) {
                    out.write(0xff);
                }
                out.write(next);
                continue;
            }
            return next;
        }
    }

    private static boolean isStandaloneJpegMarker(int marker) {
        return marker == 0x01 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7);
    }

    private static boolean isJpegMetadataMarker(int marker) {
        // APP14 carries the Adobe color-transform flag needed to render CMYK/YCCK correctly.
        return marker == 0xfe || (marker >= 0xe0 && marker <= 0xef && marker != 0xee);
    }

    static void stripPng(Path input, Path output) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(output,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            byte[] signature = Binary.readExactly(in, PNG_SIGNATURE.length);
            if (!java.util.Arrays.equals(signature, PNG_SIGNATURE)) {
                throw new IOException("Invalid PNG signature");
            }
            out.write(signature);

            boolean sawHeader = false;
            while (true) {
                long length = Binary.readUInt32BE(in);
                byte[] typeBytes = Binary.readExactly(in, 4);
                String type = Binary.ascii(typeBytes);
                if (!sawHeader && !"IHDR".equals(type)) {
                    throw new IOException("PNG does not begin with IHDR");
                }
                sawHeader = true;

                boolean keep = isCriticalPngChunk(typeBytes) || PNG_RENDERING_CHUNKS.contains(type);
                if (keep) {
                    Binary.writeUInt32BE(out, length);
                    out.write(typeBytes);
                    Binary.copyExactly(in, out, length);
                    Binary.copyExactly(in, out, 4); // Existing CRC remains valid.
                } else {
                    Binary.skipExactly(in, length + 4);
                }
                if ("IEND".equals(type)) {
                    return;
                }
            }
        } catch (EOFException e) {
            throw new IOException("Truncated PNG", e);
        }
    }

    private static boolean isCriticalPngChunk(byte[] type) {
        return (type[0] & 0x20) == 0;
    }

    static void stripGif(Path input, Path output) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(output,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            byte[] logicalHeader = Binary.readExactly(in, 13);
            String signature = Binary.ascii(java.util.Arrays.copyOf(logicalHeader, 6));
            if (!signature.equals("GIF87a") && !signature.equals("GIF89a")) {
                throw new IOException("Invalid GIF signature");
            }
            out.write(logicalHeader);
            if ((logicalHeader[10] & 0x80) != 0) {
                int colorTableLength = 3 * (1 << ((logicalHeader[10] & 0x07) + 1));
                Binary.copyExactly(in, out, colorTableLength);
            }

            while (true) {
                int introducer = readByte(in);
                switch (introducer) {
                    case 0x3b -> {
                        out.write(introducer);
                        return;
                    }
                    case 0x2c -> copyGifImage(in, out, introducer);
                    case 0x21 -> copyOrDropGifExtension(in, out);
                    default -> throw new IOException("Invalid GIF block introducer 0x"
                            + Integer.toHexString(introducer));
                }
            }
        } catch (EOFException e) {
            throw new IOException("Truncated GIF", e);
        }
    }

    private static void copyGifImage(InputStream in, OutputStream out, int introducer) throws IOException {
        out.write(introducer);
        byte[] descriptor = Binary.readExactly(in, 9);
        out.write(descriptor);
        if ((descriptor[8] & 0x80) != 0) {
            int colorTableLength = 3 * (1 << ((descriptor[8] & 0x07) + 1));
            Binary.copyExactly(in, out, colorTableLength);
        }
        out.write(readByte(in)); // LZW minimum code size.
        copyGifSubBlocks(in, out);
    }

    private static void copyOrDropGifExtension(InputStream in, OutputStream out) throws IOException {
        int label = readByte(in);
        if (label == 0xf9 || label == 0x01) {
            out.write(0x21);
            out.write(label);
            copyGifSubBlocks(in, out);
            return;
        }
        if (label == 0xff) {
            int firstLength = readByte(in);
            byte[] identifier = Binary.readExactly(in, firstLength);
            String application = Binary.ascii(identifier);
            boolean keep = application.equals("NETSCAPE2.0") || application.equals("ANIMEXTS1.0");
            if (keep) {
                out.write(0x21);
                out.write(label);
                out.write(firstLength);
                out.write(identifier);
                copyGifSubBlocks(in, out);
            } else {
                skipGifSubBlocks(in);
            }
            return;
        }
        // Comments, XMP application blocks, and unknown extensions are non-rendering metadata.
        skipGifSubBlocks(in);
    }

    private static void copyGifSubBlocks(InputStream in, OutputStream out) throws IOException {
        while (true) {
            int length = readByte(in);
            out.write(length);
            if (length == 0) {
                return;
            }
            Binary.copyExactly(in, out, length);
        }
    }

    private static void skipGifSubBlocks(InputStream in) throws IOException {
        while (true) {
            int length = readByte(in);
            if (length == 0) {
                return;
            }
            Binary.skipExactly(in, length);
        }
    }

    static void stripWebp(Path input, Path output) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw")) {
            out.setLength(0);
            byte[] riff = Binary.readExactly(in, 4);
            long riffSize = Binary.readUInt32LE(in);
            byte[] webp = Binary.readExactly(in, 4);
            if (!Binary.ascii(riff).equals("RIFF") || !Binary.ascii(webp).equals("WEBP") || riffSize < 4) {
                throw new IOException("Invalid WebP RIFF header");
            }
            out.write(riff);
            Binary.writeUInt32LE(out, 0);
            out.write(webp);

            long remaining = riffSize - 4;
            while (remaining > 0) {
                if (remaining < 8) {
                    throw new IOException("Truncated WebP chunk header");
                }
                byte[] typeBytes = Binary.readExactly(in, 4);
                String type = Binary.ascii(typeBytes);
                long length = Binary.readUInt32LE(in);
                long paddedLength = length + (length & 1);
                remaining -= 8;
                if (paddedLength > remaining) {
                    throw new IOException("WebP chunk exceeds RIFF boundary: " + type);
                }

                boolean keep = WEBP_MEDIA_CHUNKS.contains(type);
                if (keep) {
                    out.write(typeBytes);
                    Binary.writeUInt32LE(out, length);
                    if ("VP8X".equals(type)) {
                        if (length != 10) {
                            throw new IOException("Invalid VP8X chunk length");
                        }
                        byte[] payload = Binary.readExactly(in, 10);
                        payload[0] &= (byte) ~0x2c; // Clear ICC, EXIF, and XMP-present flags.
                        out.write(payload);
                    } else {
                        Binary.copyExactly(in, out, length);
                    }
                    if ((length & 1) != 0) {
                        out.write(readByte(in));
                    }
                } else {
                    Binary.skipExactly(in, paddedLength);
                }
                remaining -= paddedLength;
            }

            long outputSize = out.length();
            if (outputSize - 8 > 0xffff_ffffL) {
                throw new IOException("Stripped WebP exceeds the RIFF size limit");
            }
            out.seek(4);
            Binary.writeUInt32LE(out, outputSize - 8);
        } catch (EOFException e) {
            throw new IOException("Truncated WebP", e);
        }
    }

    static void stripJxl(Path input, Path output) throws IOException {
        try (InputStream probe = new BufferedInputStream(Files.newInputStream(input))) {
            byte[] prefix = Binary.readExactly(probe, 2);
            if ((prefix[0] & 0xff) == 0xff && (prefix[1] & 0xff) == 0x0a) {
                Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }

        long fileSize = Files.size(input);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(output,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            long remaining = fileSize;
            boolean firstBox = true;
            while (remaining > 0) {
                if (remaining < 8) {
                    throw new IOException("Truncated JPEG XL box header");
                }
                byte[] header = Binary.readExactly(in, 8);
                long size32 = ((long) (header[0] & 0xff) << 24)
                        | ((long) (header[1] & 0xff) << 16)
                        | ((long) (header[2] & 0xff) << 8)
                        | (header[3] & 0xffL);
                String type = Binary.ascii(java.util.Arrays.copyOfRange(header, 4, 8));
                int headerLength = 8;
                byte[] extended = null;
                long boxSize;
                if (size32 == 1) {
                    extended = Binary.readExactly(in, 8);
                    headerLength = 16;
                    boxSize = Binary.uint64BE(extended, 0);
                    if (boxSize < 0) {
                        throw new IOException("JPEG XL box is too large");
                    }
                } else if (size32 == 0) {
                    boxSize = remaining;
                } else {
                    boxSize = size32;
                }
                if (boxSize < headerLength || boxSize > remaining) {
                    throw new IOException("Invalid JPEG XL box size for " + type);
                }
                long payloadLength = boxSize - headerLength;
                if (firstBox && (!"JXL ".equals(type) || boxSize != 12)) {
                    throw new IOException("JPEG XL container is missing its signature box");
                }
                boolean keep = JXL_MEDIA_BOXES.contains(type);
                if (keep) {
                    out.write(header);
                    if (extended != null) {
                        out.write(extended);
                    }
                    Binary.copyExactly(in, out, payloadLength);
                } else {
                    // Exif, XML, JUMBF, Brotli metadata, JPEG reconstruction, and custom boxes are dropped.
                    Binary.skipExactly(in, payloadLength);
                }
                firstBox = false;
                remaining -= boxSize;
            }
        } catch (EOFException e) {
            throw new IOException("Truncated JPEG XL container", e);
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of file");
        }
        return value;
    }
}

