package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.epubmedia.EpubEntry;
import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.stream.Collectors;

/** Preserves EPUB manifest provenance across the native decoder boundary. */
final class EpubEntryDetails {
    private EpubEntryDetails() {}

    static Optional<EpubEntry> entry(Path path) {
        if (!(path.getFileSystem().provider() instanceof StreamFileSystemProvider)) {
            return Optional.empty();
        }
        try {
            Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
            return key instanceof EpubEntry epub ? Optional.of(epub) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<MediaKind> viewableKind(Path path) {
        return entry(path).map(EpubEntryDetails::kind);
    }

    static MediaProbe preserveProbe(Path source, MediaProbe decoded) {
        Optional<EpubEntry> found = entry(source);
        if (found.isEmpty()) return remapPath(source, decoded);
        EpubEntry epub = found.get();
        return new MediaProbe(
                source,
                kind(epub),
                "EPUB/" + epub.mediaType(),
                epub.uncompressedSize(),
                decoded.durationMicros(),
                decoded.bitRate(),
                decoded.width(),
                decoded.height(),
                decoded.videoCodec(),
                decoded.frameRate(),
                decoded.audioCodec(),
                decoded.sampleRate(),
                decoded.channels(),
                decoded.pixelDescription());
    }

    static Metadata preserveMetadata(Path source, Metadata decoded) {
        Optional<EpubEntry> found = entry(source);
        if (found.isEmpty()) return remapPath(source, decoded);
        EpubEntry epub = found.get();
        var builder = new Metadata.Builder(source);
        String group = "EPUB manifest source";
        builder.add(group, "Entry index", Integer.toString(epub.index()));
        builder.add(group, "Package path", epub.packagePath());
        builder.add(group, "Manifest ID", epub.manifestId());
        builder.add(group, "Media type", epub.mediaType());
        builder.add(group, "Media kind", epub.kind().name());
        builder.add(group, "Origins", epub.origins().stream()
                .map(Enum::name).sorted().collect(Collectors.joining(", ")));
        builder.add(group, "Cover", Boolean.toString(epub.cover()));
        if (epub.uncompressedSize() >= 0) {
            builder.add(group, "Logical bytes", Long.toString(epub.uncompressedSize()));
        }
        if (epub.compressedSize() >= 0) {
            builder.add(group, "Stored bytes", Long.toString(epub.compressedSize()));
        }
        for (Metadata.Group decodedGroup : decoded.groups()) {
            for (Metadata.Entry field : decodedGroup.entries()) {
                builder.add("Decoded " + decodedGroup.name(), field);
            }
        }
        return builder.build();
    }

    private static MediaKind kind(EpubEntry entry) {
        return switch (entry.kind()) {
            case IMAGE -> MediaKind.IMAGE;
            case AUDIO -> MediaKind.AUDIO;
            case VIDEO -> MediaKind.VIDEO;
        };
    }

    private static MediaProbe remapPath(Path source, MediaProbe decoded) {
        if (source.equals(decoded.path())) return decoded;
        return new MediaProbe(
                source,
                decoded.kind(),
                decoded.container(),
                decoded.fileSize(),
                decoded.durationMicros(),
                decoded.bitRate(),
                decoded.width(),
                decoded.height(),
                decoded.videoCodec(),
                decoded.frameRate(),
                decoded.audioCodec(),
                decoded.sampleRate(),
                decoded.channels(),
                decoded.pixelDescription());
    }

    private static Metadata remapPath(Path source, Metadata decoded) {
        return source.equals(decoded.path())
                ? decoded : new Metadata(source, decoded.provider(), decoded.status(),
                        decoded.message(), decoded.groups());
    }
}
