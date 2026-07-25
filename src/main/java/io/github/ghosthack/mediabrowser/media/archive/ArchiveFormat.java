package io.github.ghosthack.mediabrowser.media.archive;

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
 * handful of files per folder and never promotes something the reader will
 * then fail to open.</p>
 */
public enum ArchiveFormat {

    /** ZIP and its comic-book alias, read through the JDK's zipfs provider. */
    ZIP(Set.of("zip", "cbz")),

    /** ISO 9660 disc image, read through the vendored reader. */
    ISO(Set.of("iso"));

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
            case ISO -> {
                // The first volume descriptor sits at sector 16; its standard
                // identifier is the only cheap, reliable ISO 9660 tell.
                byte[] magic = at(file, ISO_MAGIC_OFFSET, 5);
                yield magic.length == 5 && magic[0] == 'C' && magic[1] == 'D'
                        && magic[2] == '0' && magic[3] == '0' && magic[4] == '1';
            }
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
