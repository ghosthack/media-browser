package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.cue.CueArchive;
import io.github.ghosthack.epubmedia.EpubArchive;
import io.github.ghosthack.pdfmedia.PdfArchive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The container formats the browser can descend into.
 *
 * <p>Identification is extension <em>and</em> magic, both required. Extension
 * alone would make every {@code .iso} a mount attempt including the many that
 * are UDF-only or plain junk; magic alone would mean sniffing the head of every
 * file in every directory scan, which is exactly the per-file read cost the
 * fast listing path exists to avoid. Requiring both keeps the check to a
 * handful of files per folder and filters obvious mismatches before a mount
 * asks the full format reader to validate the container.</p>
 */
public enum ArchiveFormat {

    /** ZIP and its comic-book alias, read through the JDK's zipfs provider. */
    ZIP(Set.of("zip", "cbz")),

    /** EPUB package media selected through its OPF manifest. */
    EPUB(Set.of("epub")),

    /** ISO 9660 disc image, read through the vendored reader. */
    ISO(Set.of("iso")),

    /** RAR 1.5–7 archives and the CBR comic-book alias. */
    RAR(Set.of("rar", "cbr")),

    /** 7z archives and the CB7 comic-book alias. */
    SEVEN_Z(Set.of("7z", "cb7")),

    /** A CUE sheet whose sole mountable data track contains ISO 9660. */
    CUE(Set.of("cue")),

    /** Physical PDF media plus recognized virtual MRC layer graphs. */
    PDF(Set.of("pdf"));

    /** Bytes needed to check the furthest-out signature (ISO's, at 0x8001). */
    private static final int ISO_MAGIC_OFFSET = 32769;

    private final Set<String> extensions;

    ArchiveFormat(Set<String> extensions) {
        this.extensions = extensions;
    }

    /**
     * The format of {@code file}, or empty when it is not a browsable archive.
     * A read failure answers empty: an unreadable file is simply not an
     * archive, never an error thrown into a directory scan.
     */
    public static Optional<ArchiveFormat> of(Path file) {
        ArchiveFormat byName = byExtension(file);
        if (byName == null) return Optional.empty();
        try {
            return byName.matchesMagic(file) ? Optional.of(byName) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Whether the name alone suggests an archive — no I/O. */
    public static boolean looksLikeArchive(Path file) {
        return byExtension(file) != null;
    }

    private static ArchiveFormat byExtension(Path file) {
        Path name = file.getFileName();
        if (name == null) return null;
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        if (dot <= 0) return null;
        String extension = s.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (ArchiveFormat format : values()) {
            if (format.extensions.contains(extension)) return format;
        }
        return null;
    }

    private boolean matchesMagic(Path file) throws IOException {
        return switch (this) {
            case ZIP -> {
                byte[] header = head(file, 4);
                // "PK" followed by a local-file, end-of-central-directory or
                // spanned-archive marker; an empty zip is still browsable.
                yield header.length == 4 && header[0] == 'P' && header[1] == 'K'
                        && (header[2] == 3 && header[3] == 4
                        || header[2] == 5 && header[3] == 6
                        || header[2] == 7 && header[3] == 8);
            }
            case EPUB -> EpubArchive.matches(file);
            case ISO -> {
                // The first volume descriptor sits at sector 16; its standard
                // identifier is the only cheap, reliable ISO 9660 tell.
                byte[] magic = at(file, ISO_MAGIC_OFFSET, 5);
                yield magic.length == 5 && magic[0] == 'C' && magic[1] == 'D'
                        && magic[2] == '0' && magic[3] == '0' && magic[4] == '1';
            }
            case RAR -> {
                byte[] header = head(file, 8);
                boolean common = header.length >= 7
                        && header[0] == 'R' && header[1] == 'a' && header[2] == 'r'
                        && header[3] == '!' && header[4] == 0x1A && header[5] == 0x07;
                yield common && (header[6] == 0x00
                        || header.length == 8 && header[6] == 0x01 && header[7] == 0x00);
            }
            case SEVEN_Z -> {
                byte[] header = head(file, 6);
                yield header.length == 6
                        && header[0] == 0x37 && header[1] == 0x7A
                        && header[2] == (byte) 0xBC && header[3] == (byte) 0xAF
                        && header[4] == 0x27 && header[5] == 0x1C;
            }
            case CUE -> {
                // A CUE is only enterable when the exact operation mount()
                // performs can succeed. BIN itself has no reliable magic and
                // is intentionally never promoted to a container.
                try (CueArchive cue = CueArchive.open(file)) {
                    yield cue.iso9660Tracks().size() == 1;
                }
            }
            case PDF -> PdfArchive.matches(head(file, 1029));
        };
    }

    private static byte[] head(Path file, int length) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(length);
        }
    }

    private static byte[] at(Path file, long offset, int length) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            if (channel.size() < offset + length) return new byte[0];
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { /* fill */ }
            return buffer.hasRemaining() ? new byte[0] : buffer.array();
        }
    }
}
