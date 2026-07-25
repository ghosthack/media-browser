package io.github.ghosthack.mediabrowser.media.ffm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the display orientation Kodak Photo CD stores in its Image Pack header.
 *
 * <p>A {@code .pcd} image pack records how the frame should be presented in the
 * low two bits of the byte at {@code 0x0E02}. FFmpeg's {@code photocd} decoder
 * never looks at it and hands back the raw landscape raster, so a portrait
 * original — most of a typical disc — decodes lying on its side. This is the
 * same class of baked-in orientation as JPEG's EXIF tag and a video container's
 * display matrix, and it is applied at the same seam: beneath the user's own
 * rotation, so {@code F8}/{@code F6} still compose on top and the thumbnail
 * cache is never invalidated.</p>
 *
 * <p>The mapping was established by decoding real discs both ways and
 * pixel-comparing against ImageMagick, which honours the field: code {@code 1}
 * needs a quarter-turn counter-clockwise, code {@code 3} one clockwise. Code
 * {@code 2} is not used by any writer we have seen; like ImageMagick we leave
 * it alone rather than guess.</p>
 */
final class PcdOrientation {

    /** {@code PCD_IPI} marks the image pack; overview packs (PCD_OPA) differ. */
    private static final byte[] IMAGE_PACK_MAGIC = "PCD_IPI".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int MAGIC_OFFSET = 0x800;
    private static final int ROTATE_OFFSET = 0x0E02;
    private static final int HEADER_BYTES = ROTATE_OFFSET + 1;

    private PcdOrientation() {}

    /** Whether the name is a Photo CD image pack this class can speak for. */
    static boolean isPcd(String extension) {
        return "pcd".equals(extension);
    }

    /**
     * Clockwise quarter-turns needed to bring a decoded PCD frame upright, or
     * {@code 0} when the file is unrotated, is not an image pack, or is too
     * short/unreadable — an unrecognised layout degrades to today's behaviour
     * rather than being mangled by a guess.
     */
    static int quarterTurnsCw(Path file) {
        byte[] header = readHeader(file);
        if (header == null) {
            return 0;
        }
        for (int i = 0; i < IMAGE_PACK_MAGIC.length; i++) {
            if (header[MAGIC_OFFSET + i] != IMAGE_PACK_MAGIC[i]) {
                return 0;
            }
        }
        return switch (header[ROTATE_OFFSET] & 0x03) {
            case 1 -> 3;   // stored 90 CCW from upright -> turn 270 CW back
            case 3 -> 1;   // stored 90 CW  from upright -> turn  90 CW back
            default -> 0;  // 0 = upright; 2 is unused in practice
        };
    }

    /**
     * The pyramid levels to try, in order, when the full-resolution decode is
     * rejected. FFmpeg's {@code photocd} decoder fails on the 16Base
     * (3072x2048) layer of some otherwise-valid discs — ImageMagick reads the
     * very same layer, so this is a decoder limitation, not bad data. Every
     * lower layer decodes cleanly, so we step down rather than show nothing.
     */
    static final int[] FALLBACK_LOWRES = {1, 2, 3, 4};

    /**
     * Announces a step-down. The project rule is that recovery may be visible
     * but never silent: showing a half-size frame while implying it is the full
     * 3072x2048 would be exactly the hidden degrade we avoid.
     */
    static void announceStepDown(Path file, int lowres) {
        System.err.println("[PcdOrientation] " + file.getFileName()
                + ": FFmpeg rejected the full-resolution Photo CD layer; "
                + "showing it at 1/" + (1 << lowres) + " scale instead");
    }

    private static byte[] readHeader(Path file) {
        byte[] header = new byte[HEADER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = 0;
            while (read < HEADER_BYTES) {
                int n = in.read(header, read, HEADER_BYTES - read);
                if (n < 0) {
                    return null;  // truncated: shorter than the header
                }
                read += n;
            }
            return header;
        } catch (IOException e) {
            return null;
        }
    }
}
