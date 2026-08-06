package io.github.ghosthack.metadatastripper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class FormatDetector {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JXL_CONTAINER = {0, 0, 0, 12, 'J', 'X', 'L', ' ', 13, 10, (byte) 0x87, 10};

    private FormatDetector() {}

    static MediaFormat detect(Path path) throws IOException {
        byte[] header = new byte[16];
        int count;
        try (InputStream in = Files.newInputStream(path)) {
            count = in.readNBytes(header, 0, header.length);
        }
        if (count >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return MediaFormat.JPEG;
        }
        if (count >= PNG.length && Arrays.equals(Arrays.copyOf(header, PNG.length), PNG)) {
            return MediaFormat.PNG;
        }
        if (count >= 6 && (startsWith(header, "GIF87a") || startsWith(header, "GIF89a"))) {
            return MediaFormat.GIF;
        }
        if (count >= 12 && startsWith(header, "RIFF") && asciiAt(header, 8, "WEBP")) {
            return MediaFormat.WEBP;
        }
        if (count >= 2 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0x0a) {
            return MediaFormat.JXL;
        }
        if (count >= JXL_CONTAINER.length
                && Arrays.equals(Arrays.copyOf(header, JXL_CONTAINER.length), JXL_CONTAINER)) {
            return MediaFormat.JXL;
        }
        if (count >= 4 && startsWith(header, "fLaC")) {
            return MediaFormat.FLAC;
        }
        if (count >= 12 && startsWith(header, "RIFF") && asciiAt(header, 8, "WAVE")) {
            return MediaFormat.WAV;
        }
        if (count >= 3 && startsWith(header, "ID3")) {
            return MediaFormat.MP3;
        }
        if (count >= 2 && (header[0] & 0xff) == 0xff && (header[1] & 0xe0) == 0xe0) {
            return MediaFormat.MP3;
        }
        throw new UnsupportedFormatException("Unsupported input format: " + path);
    }

    private static boolean startsWith(byte[] bytes, String value) {
        return asciiAt(bytes, 0, value);
    }

    private static boolean asciiAt(byte[] bytes, int offset, String value) {
        if (offset + value.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if ((bytes[offset + i] & 0xff) != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}

