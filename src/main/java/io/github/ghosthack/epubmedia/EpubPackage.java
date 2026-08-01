package io.github.ghosthack.epubmedia;

import java.util.Optional;

/** Immutable metadata from the selected EPUB package document. */
public record EpubPackage(
        String packagePath,
        String version,
        Optional<String> title,
        Optional<String> creator,
        Optional<String> language,
        Optional<String> identifier,
        int manifestItemCount,
        int spineItemCount,
        int missingLocalResourceCount,
        int remoteResourceCount) {

    public EpubPackage {
        if (packagePath == null || packagePath.isBlank()) {
            throw new IllegalArgumentException("packagePath is blank");
        }
        version = version == null ? "" : version;
        title = title == null ? Optional.empty() : title;
        creator = creator == null ? Optional.empty() : creator;
        language = language == null ? Optional.empty() : language;
        identifier = identifier == null ? Optional.empty() : identifier;
        if (manifestItemCount < 0
                || spineItemCount < 0
                || missingLocalResourceCount < 0
                || remoteResourceCount < 0) {
            throw new IllegalArgumentException("package counts must not be negative");
        }
    }
}
