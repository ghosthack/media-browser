package io.github.ghosthack.mediabrowser.media.twelvemonkeys;

import java.io.IOException;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Reads the EXIF orientation (1..8) from an {@link ImageReader}'s
 * {@link IIOMetadata} so a decoded still can be made upright with
 * {@link io.github.ghosthack.mediabrowser.media.RasterFrames#applyExifOrientation}.
 *
 * <p>Two sources are consulted, in order:</p>
 * <ol>
 *   <li><b>TIFF</b> (TwelveMonkeys {@code imageio-tiff}, and WebP/PSD which reuse
 *       a TIFF-style native metadata tree): tag&nbsp;274 (Orientation) is read
 *       straight off the native {@code TIFFField} tree.</li>
 *   <li><b>JPEG</b> (and any format whose native tree carries the raw APP1/EXIF
 *       segment): the {@code Exif\0\0} TIFF blob is extracted from an
 *       {@code unknown} marker node and parsed for tag&nbsp;274.</li>
 * </ol>
 *
 * <p>Returns {@code 1} (already upright) whenever orientation is absent,
 * unreadable, or out of the 1..8 range — so callers can apply the result
 * unconditionally. Self-contained: imports only {@code javax.imageio.*},
 * {@code org.w3c.dom.*} and the JDK.</p>
 */
public final class ImageIoOrientation {

    /** EXIF/TIFF Orientation tag. */
    private static final int TAG_ORIENTATION = 274;

    private ImageIoOrientation() {}

    /**
     * EXIF orientation (1..8) for image {@code imageIndex} of {@code reader},
     * or {@code 1} when none is present/readable.
     */
    public static int read(ImageReader reader, int imageIndex) {
        if (reader == null) {
            return 1;
        }
        try {
            return read(reader.getImageMetadata(imageIndex));
        } catch (IOException | RuntimeException ignore) {
            return 1;
        }
    }

    /** EXIF orientation (1..8) from already-fetched metadata, or {@code 1}. */
    public static int read(IIOMetadata metadata) {
        if (metadata == null) {
            return 1;
        }
        String[] formats = metadata.getMetadataFormatNames();
        if (formats == null) {
            return 1;
        }
        for (String format : formats) {
            Node tree;
            try {
                tree = metadata.getAsTree(format);
            } catch (RuntimeException ignore) {
                continue;
            }
            if (tree == null) {
                continue;
            }
            // 1. TIFF-style native tree: a TIFFField with number="274".
            int fromTiff = orientationFromTiffTree(tree);
            if (isValid(fromTiff)) {
                return fromTiff;
            }
            // 2. JPEG-style native tree: an unknown APP1 marker carrying an
            //    "Exif\0\0" TIFF blob.
            byte[] exif = exifBlobFromTree(tree);
            if (exif != null) {
                int fromExif = orientationFromExifBlob(exif);
                if (isValid(fromExif)) {
                    return fromExif;
                }
            }
        }
        return 1;
    }

    private static boolean isValid(int orientation) {
        return orientation >= 1 && orientation <= 8;
    }

    // --- TIFF native tree (number="274") -----------------------------------

    private static int orientationFromTiffTree(Node node) {
        if (node == null) {
            return 0;
        }
        NamedNodeMap attrs = node.getAttributes();
        if (attrs != null) {
            Node number = attrs.getNamedItem("number");
            Node name = attrs.getNamedItem("name");
            boolean isOrientationField =
                    (number != null && parseInt(number.getNodeValue(), -1) == TAG_ORIENTATION)
                            || (name != null && "Orientation".equalsIgnoreCase(name.getNodeValue()));
            if (isOrientationField) {
                int value = firstFieldValue(node);
                if (isValid(value)) {
                    return value;
                }
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            int found = orientationFromTiffTree(child);
            if (isValid(found)) {
                return found;
            }
        }
        return 0;
    }

    /**
     * The first numeric value carried under a {@code TIFFField} node — the
     * {@code value} attribute on a {@code TIFFShort}/{@code TIFFByte}/etc. child.
     */
    private static int firstFieldValue(Node field) {
        for (Node group = field.getFirstChild(); group != null; group = group.getNextSibling()) {
            for (Node entry = group.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                NamedNodeMap a = entry.getAttributes();
                if (a != null) {
                    Node v = a.getNamedItem("value");
                    if (v != null) {
                        int parsed = parseInt(v.getNodeValue(), -1);
                        if (parsed >= 0) {
                            return parsed;
                        }
                    }
                }
            }
        }
        return -1;
    }

    // --- JPEG native tree: raw EXIF blob ------------------------------------

    private static byte[] exifBlobFromTree(Node node) {
        if (node instanceof IIOMetadataNode metaNode) {
            Object userObject = metaNode.getUserObject();
            if (userObject instanceof byte[] bytes && looksLikeExif(bytes)) {
                return bytes;
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            byte[] found = exifBlobFromTree(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean looksLikeExif(byte[] b) {
        return b != null
                && b.length >= 6
                && b[0] == 'E' && b[1] == 'x' && b[2] == 'i' && b[3] == 'f'
                && b[4] == 0 && b[5] == 0;
    }

    /**
     * Parses tag&nbsp;274 from an {@code Exif\0\0}-prefixed TIFF blob (the body
     * of a JPEG APP1 segment). Returns {@code 0} when not found/malformed.
     */
    private static int orientationFromExifBlob(byte[] b) {
        // Skip the 6-byte "Exif\0\0" header; the TIFF header starts at offset 6.
        int base = 6;
        if (b.length < base + 8) {
            return 0;
        }
        boolean bigEndian;
        int b0 = b[base] & 0xFF;
        int b1 = b[base + 1] & 0xFF;
        if (b0 == 'M' && b1 == 'M') {
            bigEndian = true;
        } else if (b0 == 'I' && b1 == 'I') {
            bigEndian = false;
        } else {
            return 0;
        }
        // 0x002A magic at base+2.
        if (u16(b, base + 2, bigEndian) != 0x002A) {
            return 0;
        }
        long ifd0 = u32(b, base + 4, bigEndian);
        int ifd = base + (int) ifd0;
        if (ifd < base || ifd + 2 > b.length) {
            return 0;
        }
        int count = u16(b, ifd, bigEndian);
        int entry = ifd + 2;
        for (int i = 0; i < count; i++, entry += 12) {
            if (entry + 12 > b.length) {
                return 0;
            }
            int tag = u16(b, entry, bigEndian);
            if (tag == TAG_ORIENTATION) {
                // type SHORT(3) → value packed in the value/offset field (4 bytes),
                // left-aligned, so the SHORT lives at entry+8.
                int value = u16(b, entry + 8, bigEndian);
                return isValid(value) ? value : 0;
            }
        }
        return 0;
    }

    // --- little helpers -----------------------------------------------------

    private static int u16(byte[] b, int off, boolean bigEndian) {
        int x = b[off] & 0xFF;
        int y = b[off + 1] & 0xFF;
        return bigEndian ? (x << 8) | y : (y << 8) | x;
    }

    private static long u32(byte[] b, int off, boolean bigEndian) {
        long x0 = b[off] & 0xFFL;
        long x1 = b[off + 1] & 0xFFL;
        long x2 = b[off + 2] & 0xFFL;
        long x3 = b[off + 3] & 0xFFL;
        return bigEndian
                ? (x0 << 24) | (x1 << 16) | (x2 << 8) | x3
                : (x3 << 24) | (x2 << 16) | (x1 << 8) | x0;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
