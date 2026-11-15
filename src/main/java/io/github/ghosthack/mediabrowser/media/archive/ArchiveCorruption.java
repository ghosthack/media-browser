package io.github.ghosthack.mediabrowser.media.archive;

import io.github.ghosthack.iso9660.IsoArchiveException;
import io.github.ghosthack.seven.SevenArchiveException;
import io.github.ghosthack.unrar.RarArchiveException;
import io.github.ghosthack.zip.ZipArchiveException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a container's open failure is the reader reporting damaged
 * structure — and only then labels it {@link CorruptArchiveException}.
 *
 * <p>The media-inspection readers throw one exception type per format for
 * "malformed, unsupported, or over budget" alike, so the reader's own
 * wording (or, for RAR and 7z, the type of the parser exception it wraps)
 * is the only precise signal there is. Each list below is the subset of that
 * reader's messages that describe damage; anything else — an unsupported
 * feature, a budget, a password, a plain I/O error, a file the reader did
 * not recognise at all — stays the reader's original exception. Pinned by
 * {@code ArchiveCorruptionTest} against real damaged containers, so a reader
 * upgrade that rewords a message fails a test instead of silently
 * un-labelling corruption.</p>
 *
 * <p>Not covered: PDF (PDFBox reports parse failures as a bare
 * {@link IOException}, indistinguishable from a read error), EPUB and CUE
 * (their failures are sheet/companion/budget problems, not container
 * damage).</p>
 */
public final class ArchiveCorruption {

    private ArchiveCorruption() {}

    /** ZIP reader messages that describe damaged structure. */
    private static final List<String> ZIP_DAMAGE = List.of(
            "truncated ", "invalid ", "inconsistent", "lies outside source",
            "lies before source start", "missing local header",
            "local and central ZIP headers disagree", "CRC mismatch", "size mismatch",
            "unexpected bytes after ZIP central directory", "missing required Zip64 value",
            "declares zero compressed bytes", "unequal sizes");

    /** The truncated-ZIP message: damage only when the bytes start as a ZIP. */
    private static final String ZIP_NO_END = "ZIP end-of-central-directory record not found";

    /** commons-compress SevenZFile parser messages (lower-cased) that describe damage. */
    private static final List<String> SEVEN_DAMAGE = List.of(
            "out of bounds", "crc", "no valid start header", "badly terminated",
            "broken archive", "header format error", "premature end of stream",
            "invalid ", "negative unpacksize", "incomplete ", "too few folders",
            "doesn't contain enough", "error parsing file names", "is out of range",
            "bad 7z signature");

    /** junrar parser exceptions (wrapped by RarArchiveException) that mean damage. */
    private static final Set<String> RAR_DAMAGE = Set.of(
            "CorruptHeaderException", "CrcErrorException",
            "BadRarArchiveException", "MainHeaderNullException");

    /** ISO 9660 reader messages that describe damaged structure. */
    private static final List<String> ISO_DAMAGE = List.of(
            "invalid ", "malformed ", "truncated ISO", "unexpected EOF in ISO source",
            "ISO directory padding does not advance",
            "ISO directory record crosses a logical block",
            "orphaned ISO multi-extent continuation",
            "ISO multi-extent records do not describe the same file",
            "ISO multi-extent chain is interrupted",
            "ISO multi-extent chain lacks a final record",
            "ISO volume declares zero logical blocks",
            "ISO root record is not a directory",
            "ISO root directory does not begin with its self record",
            "short Rock Ridge NM entry", "Rock Ridge continuation cycle detected",
            "Rock Ridge SUSP skip exceeds directory record",
            "ISO extents do not fill entry", "ISO read extent lies outside the source",
            "ISO extent lies beyond the source", "ISO directory record lies outside its extents");

    /**
     * {@code failure} unchanged, or a {@link CorruptArchiveException} wrapping
     * it when the {@code format} reader reported damaged structure.
     */
    public static IOException classify(ArchiveFormat format, Path archive, IOException failure) {
        if (failure instanceof CorruptArchiveException) return failure;
        return damage(format, archive, failure)
                .<IOException>map(reason -> new CorruptArchiveException(reason, failure))
                .orElse(failure);
    }

    /** The reader's damage description, when {@code failure} is damage. */
    static Optional<String> damage(ArchiveFormat format, Path archive, IOException failure) {
        if (failure instanceof FileSystemException) return Optional.empty();
        return switch (format) {
            case ZIP -> zip(archive, failure);
            case SEVEN_Z -> seven(failure);
            case RAR -> rar(failure);
            case ISO -> iso(failure);
            case CUE, PDF, EPUB -> Optional.empty();
        };
    }

    private static Optional<String> zip(Path archive, IOException failure) {
        if (!(failure instanceof ZipArchiveException)) return Optional.empty();
        String message = String.valueOf(failure.getMessage());
        if (message.startsWith(ZIP_NO_END)) {
            // "No end record" is also what any non-ZIP bytes produce; it is
            // damage only for a file that begins as a ZIP (local header).
            return startsWithLocalHeader(archive)
                    ? Optional.of(message + " (truncated ZIP)") : Optional.empty();
        }
        return containsAny(message, ZIP_DAMAGE) ? Optional.of(message) : Optional.empty();
    }

    private static Optional<String> seven(IOException failure) {
        if (!(failure instanceof SevenArchiveException)) return Optional.empty();
        Throwable cause = failure.getCause();
        // Past the reader's own signature check, so the bytes are a 7z.
        if (cause instanceof EOFException) {
            return Optional.of("7z: unexpected end of data");
        }
        if (cause != null && cause.getClass().getSimpleName().equals("CorruptedInputException")) {
            return Optional.of("7z: " + cause.getMessage());
        }
        if (!(cause instanceof IOException) || cause instanceof FileSystemException) {
            return Optional.empty();
        }
        String message = String.valueOf(cause.getMessage());
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("unsupported")) return Optional.empty();
        return containsAny(lower, SEVEN_DAMAGE) ? Optional.of("7z: " + message) : Optional.empty();
    }

    private static Optional<String> rar(IOException failure) {
        if (!(failure instanceof RarArchiveException)) return Optional.empty();
        for (Throwable t = failure.getCause(); t != null; t = t.getCause()) {
            String kind = t.getClass().getSimpleName();
            if (RAR_DAMAGE.contains(kind)) {
                return Optional.of("RAR: " + (t.getMessage() == null
                        ? describeRar(kind) : t.getMessage()));
            }
        }
        return Optional.empty();
    }

    private static String describeRar(String kind) {
        return switch (kind) {
            case "MainHeaderNullException" -> "main archive header missing";
            case "CrcErrorException" -> "CRC error";
            default -> "damaged archive (" + kind + ")";
        };
    }

    private static Optional<String> iso(IOException failure) {
        if (!(failure instanceof IsoArchiveException)) return Optional.empty();
        String message = String.valueOf(failure.getMessage());
        if (startsWithAny(message, ISO_DAMAGE)
                || (message.startsWith("ISO volume declares ") && message.contains(" but source has "))
                || (message.startsWith("both-endian ISO ") && message.endsWith(" values disagree"))) {
            return Optional.of(message);
        }
        return Optional.empty();
    }

    private static boolean startsWithLocalHeader(Path archive) {
        try (InputStream in = Files.newInputStream(archive)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == 'P' && head[1] == 'K'
                    && head[2] == 3 && head[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsAny(String message, List<String> needles) {
        for (String needle : needles) {
            if (message.contains(needle)) return true;
        }
        return false;
    }

    private static boolean startsWithAny(String message, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (message.startsWith(prefix)) return true;
        }
        return false;
    }
}
