package io.github.ghosthack.mediabrowser.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Identifies media from a curated table of header magic numbers — the rescue
 * half of {@link DetectionMode#CONTENT_SNIFF}, promoting files whose name says
 * nothing (extension-less, or an unknown suffix like {@code .dat}).
 *
 * <p>Deliberately curated, not exhaustive: only popular formats whose identity
 * sits in a fixed-offset signature inside the first {@value #HEADER_LENGTH}
 * bytes qualify, so a sniff is one tiny read and never a parse. Formats whose
 * detection needs scanning or whose magic is too weak to trust (a bare JXL
 * codestream's 2-byte {@code FF 0A}, a raw MP3 frame sync, MPEG-PS/TS packets)
 * are left out on purpose — an unidentified file simply stays unclassified,
 * exactly what extension detection would have said.</p>
 *
 * <p>Kind mapping favors the common case and lets the decode refine it: EBML
 * is called VIDEO (an audio-only {@code .mka} stream is rare), {@code OggS}
 * AUDIO (Ogg video is rare), and ISO-BMFF image-sequence brands IMAGE — the
 * viewer's probe settles playability by content anyway
 * ({@code FfmpegFfmMediaFacade.refineKind}).</p>
 */
public final class ContentSniffer {

    /** Bytes read per sniff; every signature below fits inside this prefix. */
    static final int HEADER_LENGTH = 16;

    private ContentSniffer() {}

    /**
     * The media kind implied by the file's header magic, or empty when the
     * header matches no curated signature — including on any read error or a
     * file shorter than the signature: a promotion pass must never turn an
     * unreadable file into a failure, "not identified" is a valid answer.
     */
    public static Optional<MediaKind> sniff(Path file) {
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(HEADER_LENGTH);
        } catch (IOException e) {
            return Optional.empty();
        }
        return sniffHeader(header);
    }

    /** The table proper, on an already-read header. Package-private for tests. */
    static Optional<MediaKind> sniffHeader(byte[] h) {
        if (starts(h, 0xFF, 0xD8, 0xFF)) return image();                       // JPEG
        if (starts(h, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return image();
        if (starts(h, 'G', 'I', 'F', '8', '7', 'a')
                || starts(h, 'G', 'I', 'F', '8', '9', 'a')) return image();
        if (starts(h, 'I', 'I', 0x2A, 0x00)
                || starts(h, 'M', 'M', 0x00, 0x2A)) return image();            // TIFF
        // JXL ISO-BMFF container. (The bare codestream's FF 0A is 2 bytes of
        // magic — too weak for a promotion, deliberately not matched.)
        if (starts(h, 0x00, 0x00, 0x00, 0x0C, 'J', 'X', 'L', ' ',
                0x0D, 0x0A, 0x87, 0x0A)) return image();
        // BMP's "BM" alone is 2 bytes; require the reserved zero bytes at 6..9
        // so arbitrary text starting with "BM" doesn't promote.
        if (starts(h, 'B', 'M') && h.length >= 10
                && h[6] == 0 && h[7] == 0 && h[8] == 0 && h[9] == 0) return image();
        if (starts(h, 0x1A, 0x45, 0xDF, 0xA3)) return Optional.of(MediaKind.VIDEO); // EBML: MKV/WebM
        if (starts(h, 'O', 'g', 'g', 'S')) return audio();
        if (starts(h, 'f', 'L', 'a', 'C')) return audio();
        if (starts(h, 'I', 'D', '3')) return audio();                          // MP3 with ID3v2
        if (starts(h, 'R', 'I', 'F', 'F') && h.length >= 12) return riffKind(h);
        if (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y'
                && h[7] == 'p') return ftypKind(h);
        return Optional.empty();
    }

    /** RIFF form type at bytes 8..11 picks the kind: WebP, AVI or WAV. */
    private static Optional<MediaKind> riffKind(byte[] h) {
        String form = ascii(h, 8);
        return switch (form) {
            case "WEBP" -> image();
            case "AVI " -> Optional.of(MediaKind.VIDEO);
            case "WAVE" -> audio();
            default -> Optional.empty();
        };
    }

    /**
     * ISO-BMFF major brand at bytes 8..11. HEIF/AVIF image brands (sequences
     * included — see the class note) map to IMAGE, the MP4/QuickTime family to
     * VIDEO, and the iTunes audio brands to AUDIO. Unknown brands stay
     * unclassified rather than guessing at the whole BMFF universe.
     */
    private static Optional<MediaKind> ftypKind(byte[] h) {
        String brand = ascii(h, 8);
        return switch (brand) {
            case "avif", "avis", "heic", "heix", "heim", "heis",
                 "hevc", "hevx", "mif1", "msf1" -> image();
            case "M4A ", "M4B " -> audio();
            case "qt  ", "M4V ", "M4VP" -> Optional.of(MediaKind.VIDEO);
            default -> brand.startsWith("mp4") || brand.startsWith("iso")
                    || brand.startsWith("3gp")
                    ? Optional.of(MediaKind.VIDEO) : Optional.empty();
        };
    }

    /**
     * Bytes read per {@link #canonicalExtension} probe: enough to also see the
     * EBML DocType and the first Ogg page's codec id, which sit just past the
     * 4-byte magics ({@value #HEADER_LENGTH} bytes cannot tell {@code .mkv}
     * from {@code .webm}, or {@code .ogg} from {@code .opus}/{@code .ogv}).
     */
    static final int EXTENSION_PROBE_LENGTH = 64;

    /**
     * The canonical filename extension for the file's header magic (no leading
     * dot, e.g. {@code "jpg"}), or empty when the header matches no curated
     * signature <em>or</em> matches one whose extension is ambiguous and the
     * disambiguating bytes are absent — the extension-fix rename must only
     * ever apply a name it is certain of, so "don't know" is always a valid
     * answer and never an error. Every value returned is an extension
     * {@link ExtensionClassifier} recognizes, so the renamed file classifies
     * in extension mode.
     */
    public static Optional<String> canonicalExtension(Path file) {
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(EXTENSION_PROBE_LENGTH);
        } catch (IOException e) {
            return Optional.empty();
        }
        return canonicalExtensionFromHeader(header);
    }

    /** The extension table on an already-read header. Package-private for tests. */
    static Optional<String> canonicalExtensionFromHeader(byte[] h) {
        if (starts(h, 0xFF, 0xD8, 0xFF)) return Optional.of("jpg");
        if (starts(h, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of("png");
        if (starts(h, 'G', 'I', 'F', '8', '7', 'a')
                || starts(h, 'G', 'I', 'F', '8', '9', 'a')) return Optional.of("gif");
        if (starts(h, 'I', 'I', 0x2A, 0x00)
                || starts(h, 'M', 'M', 0x00, 0x2A)) return Optional.of("tif");
        if (starts(h, 0x00, 0x00, 0x00, 0x0C, 'J', 'X', 'L', ' ',
                0x0D, 0x0A, 0x87, 0x0A)) return Optional.of("jxl");
        if (starts(h, 'B', 'M') && h.length >= 10
                && h[6] == 0 && h[7] == 0 && h[8] == 0 && h[9] == 0) return Optional.of("bmp");
        if (starts(h, 0x1A, 0x45, 0xDF, 0xA3)) {
            // The EBML DocType ("webm" or "matroska") sits a few bytes into the
            // header element; either word inside the probe window settles it.
            if (contains(h, "webm")) return Optional.of("webm");
            if (contains(h, "matroska")) return Optional.of("mkv");
            return Optional.empty();
        }
        if (starts(h, 'O', 'g', 'g', 'S')) {
            // The first page's lone packet opens with the codec id.
            if (contains(h, "OpusHead")) return Optional.of("opus");
            if (contains(h, "vorbis")) return Optional.of("ogg");
            if (contains(h, "theora")) return Optional.of("ogv");
            return Optional.empty();
        }
        if (starts(h, 'f', 'L', 'a', 'C')) return Optional.of("flac");
        if (starts(h, 'I', 'D', '3')) return Optional.of("mp3");
        if (starts(h, 'R', 'I', 'F', 'F') && h.length >= 12) {
            return switch (ascii(h, 8)) {
                case "WEBP" -> Optional.of("webp");
                case "AVI " -> Optional.of("avi");
                case "WAVE" -> Optional.of("wav");
                default -> Optional.empty();
            };
        }
        if (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y'
                && h[7] == 'p') {
            String brand = ascii(h, 8);
            return switch (brand) {
                case "avif", "avis" -> Optional.of("avif");
                case "heic", "heix", "heim", "heis",
                     "hevc", "hevx", "mif1", "msf1" -> Optional.of("heic");
                case "M4A ", "M4B " -> Optional.of("m4a");
                case "M4V ", "M4VP" -> Optional.of("m4v");
                case "qt  " -> Optional.of("mov");
                default -> {
                    if (brand.startsWith("mp4") || brand.startsWith("iso")) {
                        yield Optional.of("mp4");
                    }
                    yield brand.startsWith("3gp") ? Optional.of("3gp") : Optional.empty();
                }
            };
        }
        return Optional.empty();
    }

    /** Whether the ASCII/latin-1 needle occurs anywhere in the header window. */
    private static boolean contains(byte[] h, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (h[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Whether {@code h} starts with the given byte values (ints for literals). */
    private static boolean starts(byte[] h, int... magic) {
        if (h.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (h[i] != (byte) magic[i]) return false;
        }
        return true;
    }

    /** The four bytes at {@code from} as ASCII (caller checked the length). */
    private static String ascii(byte[] h, int from) {
        return new String(h, from, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static Optional<MediaKind> image() {
        return Optional.of(MediaKind.IMAGE);
    }

    private static Optional<MediaKind> audio() {
        return Optional.of(MediaKind.AUDIO);
    }
}
