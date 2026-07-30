package io.github.ghosthack.cue.internal;

import io.github.ghosthack.cue.CueArchiveException;
import io.github.ghosthack.cue.CueOpenOptions;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Resolves untrusted CUE companion names under an explicit containment policy. */
public final class CompanionResolver {
    private CompanionResolver() {}

    public static Path resolve(Path cueDirectory, String declared, CueOpenOptions options)
            throws IOException {
        Path base = cueDirectory.toAbsolutePath().normalize();
        Path baseReal = base.toRealPath();
        List<Path> candidates = new ArrayList<>();
        try {
            Path raw = Path.of(declared);
            candidates.add(raw.isAbsolute() ? raw : base.resolve(raw));
            if (declared.indexOf('\\') >= 0) {
                String portable = declared.replace('\\', '/');
                Path alternative = Path.of(portable);
                Path candidate = alternative.isAbsolute() ? alternative : base.resolve(alternative);
                if (!candidates.contains(candidate)) candidates.add(candidate);
            }
        } catch (InvalidPathException e) {
            throw new CueArchiveException("invalid companion path: " + declared, e);
        }

        boolean windowsAbsolute =
                declared.length() >= 3
                        && Character.isLetter(declared.charAt(0))
                        && declared.charAt(1) == ':'
                        && (declared.charAt(2) == '\\' || declared.charAt(2) == '/');
        if (windowsAbsolute && !options.allowExternalCompanions()) {
            throw new CueArchiveException("external companion path is not allowed: " + declared);
        }

        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            requireLexicalContainment(base, normalized, declared, options);
            if (Files.exists(normalized)) {
                return validateRealPath(baseReal, normalized, declared, options);
            }
        }

        if (options.caseInsensitiveFallback()) {
            for (Path candidate : candidates) {
                Path normalized = candidate.toAbsolutePath().normalize();
                requireLexicalContainment(base, normalized, declared, options);
                Path matched = resolveUniqueCaseInsensitive(base, normalized);
                if (matched != null) {
                    return validateRealPath(baseReal, matched, declared, options);
                }
            }
        }
        throw new CueArchiveException("missing companion file: " + declared);
    }

    private static void requireLexicalContainment(
            Path base, Path candidate, String declared, CueOpenOptions options)
            throws CueArchiveException {
        if (!options.allowExternalCompanions() && !candidate.startsWith(base)) {
            throw new CueArchiveException("companion escapes the CUE directory: " + declared);
        }
    }

    private static Path validateRealPath(
            Path baseReal, Path candidate, String declared, CueOpenOptions options)
            throws IOException {
        Path real = candidate.toRealPath();
        if (!options.allowExternalCompanions() && !real.startsWith(baseReal)) {
            throw new CueArchiveException(
                    "companion resolves outside the CUE directory: " + declared);
        }
        if (!Files.isRegularFile(real)) {
            throw new CueArchiveException("companion is not a regular file: " + declared);
        }
        return real;
    }

    private static Path resolveUniqueCaseInsensitive(Path base, Path target)
            throws IOException {
        if (!target.startsWith(base)) return null;
        Path current = base;
        Path relative = base.relativize(target);
        for (Path component : relative) {
            Path exact = current.resolve(component);
            if (Files.exists(exact)) {
                current = exact;
                continue;
            }
            List<Path> matches = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(current)) {
                for (Path child : children) {
                    if (child.getFileName().toString()
                            .equalsIgnoreCase(component.toString())) {
                        matches.add(child);
                        if (matches.size() > 1) return null;
                    }
                }
            }
            if (matches.size() != 1) return null;
            current = matches.getFirst();
        }
        return current;
    }
}
