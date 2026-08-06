package io.github.ghosthack.metadatastripper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Public API for stripping one file into its automatically derived output path. */
public final class MetadataStripper {
    private static final Map<MediaFormat, ContainerStripper> STRIPPERS = strippers();

    public Path strip(Path input) throws IOException {
        return stripDetailed(input, false).output();
    }

    public Path strip(Path input, boolean replaceExisting) throws IOException {
        return stripDetailed(input, replaceExisting).output();
    }

    /** Detects a supported container from its signature without trusting its extension. */
    public MediaFormat detect(Path input) throws IOException {
        Path source = regularFile(input);
        return FormatDetector.detect(source);
    }

    /**
     * Strips one file and reports the detected format and resulting byte counts.
     * The encoded media payload is never decoded or recompressed.
     */
    public StripResult stripDetailed(Path input) throws IOException {
        return stripDetailed(input, false);
    }

    /** Detailed counterpart to {@link #strip(Path, boolean)}. */
    public StripResult stripDetailed(Path input, boolean replaceExisting) throws IOException {
        Path source = regularFile(input);
        long sourceBytes = Files.size(source);

        Path output = outputPath(source);
        if (!replaceExisting && Files.exists(output)) {
            throw new IOException("Output already exists (use --force to replace it): " + output);
        }

        MediaFormat format = FormatDetector.detect(source);
        ContainerStripper stripper = STRIPPERS.get(format);
        Path parent = output.getParent();
        Path temporary = Files.createTempFile(parent, "." + output.getFileName() + "-", ".tmp");
        boolean moved = false;
        try {
            stripper.strip(source, temporary);
            moveIntoPlace(temporary, output, replaceExisting);
            moved = true;
            long outputBytes = Files.size(output);
            return new StripResult(source, output, format, sourceBytes, outputBytes);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Path regularFile(Path input) throws IOException {
        Objects.requireNonNull(input, "input");
        Path source = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Input is not a regular file: " + source);
        }
        return source;
    }

    public static Path outputPath(Path input) {
        Objects.requireNonNull(input, "input");
        Path fileNamePath = input.getFileName();
        if (fileNamePath == null) {
            throw new IllegalArgumentException("Input has no file name: " + input);
        }
        String name = fileNamePath.toString();
        int dot = name.lastIndexOf('.');
        String outputName = dot > 0
                ? name.substring(0, dot) + "_0m" + name.substring(dot)
                : name + "_0m";
        return input.resolveSibling(outputName);
    }

    private static void moveIntoPlace(Path temporary, Path output, boolean replace) throws IOException {
        StandardCopyOption[] atomicOptions = replace
                ? new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE};
        try {
            Files.move(temporary, output, atomicOptions);
        } catch (AtomicMoveNotSupportedException e) {
            if (replace) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, output);
            }
        }
    }

    private static Map<MediaFormat, ContainerStripper> strippers() {
        EnumMap<MediaFormat, ContainerStripper> map = new EnumMap<>(MediaFormat.class);
        map.put(MediaFormat.JPEG, ImageStrippers::stripJpeg);
        map.put(MediaFormat.PNG, ImageStrippers::stripPng);
        map.put(MediaFormat.GIF, ImageStrippers::stripGif);
        map.put(MediaFormat.WEBP, ImageStrippers::stripWebp);
        map.put(MediaFormat.JXL, ImageStrippers::stripJxl);
        map.put(MediaFormat.MP3, AudioStrippers::stripMp3);
        map.put(MediaFormat.FLAC, AudioStrippers::stripFlac);
        map.put(MediaFormat.WAV, AudioStrippers::stripWav);
        return Map.copyOf(map);
    }
}

