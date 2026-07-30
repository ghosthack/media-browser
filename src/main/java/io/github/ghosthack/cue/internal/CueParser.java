package io.github.ghosthack.cue.internal;

import io.github.ghosthack.cue.CueArchiveException;
import io.github.ghosthack.cue.CueFileType;
import io.github.ghosthack.cue.CueIndex;
import io.github.ghosthack.cue.CueOpenOptions;
import io.github.ghosthack.cue.CueTrackMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict bounded parser for the CUE commands needed by the data-track adapter. */
public final class CueParser {
    private CueParser() {}

    public static ParsedCue parse(Path source, CueOpenOptions options) throws IOException {
        byte[] bytes = readBounded(source, options.maxCueBytes());
        String text = decode(bytes);
        if (text.indexOf('\0') >= 0) {
            throw new CueArchiveException("CUE sheet contains a NUL character");
        }

        String[] lines = text.split("\\R", -1);
        if (lines.length > options.maxLines()) {
            throw new CueArchiveException(
                    "CUE sheet has " + lines.length + " lines; budget is " + options.maxLines());
        }

        List<ParsedFile> files = new ArrayList<>();
        List<ParsedTrack> tracks = new ArrayList<>();
        MutableFile currentFile = null;
        MutableTrack currentTrack = null;
        Set<Integer> trackNumbers = new HashSet<>();
        int lastTrackNumber = 0;

        for (int lineNumber = 1; lineNumber <= lines.length; lineNumber++) {
            String line = lines[lineNumber - 1];
            if (line.length() > options.maxLineCharacters()) {
                throw problem(lineNumber, "line exceeds the character budget");
            }
            String stripped = line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith(";") || stripped.startsWith("#")) {
                continue;
            }

            List<String> words = tokenize(line, lineNumber);
            if (words.isEmpty()) continue;
            String command = words.getFirst().toUpperCase(Locale.ROOT);
            switch (command) {
                case "REM", "TITLE", "PERFORMER", "SONGWRITER", "CATALOG",
                        "ISRC", "FLAGS", "CDTEXTFILE" -> {
                    // Retained by neither the sector mapper nor ISO consumer.
                }
                case "FILE" -> {
                    requireWords(words, 3, lineNumber, "FILE name type");
                    if (words.get(1).length() > options.maxCompanionNameCharacters()) {
                        throw problem(lineNumber, "companion name exceeds the character budget");
                    }
                    if (files.size() >= options.maxFiles()) {
                        throw problem(lineNumber, "FILE count exceeds the budget");
                    }
                    currentFile =
                            new MutableFile(
                                    files.size(),
                                    words.get(1),
                                    CueFileType.parse(words.get(2)),
                                    new ArrayList<>());
                    files.add(currentFile.freezeView());
                    currentTrack = null;
                }
                case "TRACK" -> {
                    requireWords(words, 3, lineNumber, "TRACK number mode");
                    if (currentFile == null) {
                        throw problem(lineNumber, "TRACK appears before FILE");
                    }
                    if (tracks.size() >= options.maxTracks()) {
                        throw problem(lineNumber, "TRACK count exceeds the budget");
                    }
                    int number = parseDecimal(words.get(1), "track number", lineNumber);
                    if (number < 1 || number > 99) {
                        throw problem(lineNumber, "track number must be between 1 and 99");
                    }
                    if (!trackNumbers.add(number)) {
                        throw problem(lineNumber, "duplicate track number " + number);
                    }
                    if (number <= lastTrackNumber) {
                        throw problem(lineNumber, "track numbers must increase");
                    }
                    lastTrackNumber = number;
                    CueTrackMode mode = CueTrackMode.parse(words.get(2));
                    if (mode == CueTrackMode.UNKNOWN) {
                        throw problem(lineNumber, "unsupported track mode " + words.get(2));
                    }
                    currentTrack =
                            new MutableTrack(
                                    tracks.size(),
                                    number,
                                    mode,
                                    currentFile.index,
                                    new ArrayList<>());
                    currentFile.tracks.add(currentTrack);
                    tracks.add(currentTrack.freezeView());
                }
                case "INDEX" -> {
                    requireWords(words, 3, lineNumber, "INDEX number mm:ss:ff");
                    if (currentTrack == null) {
                        throw problem(lineNumber, "INDEX appears before TRACK");
                    }
                    if (currentTrack.indices.size() >= options.maxIndicesPerTrack()) {
                        throw problem(lineNumber, "INDEX count exceeds the per-track budget");
                    }
                    int number = parseDecimal(words.get(1), "index number", lineNumber);
                    if (number < 0 || number > 99) {
                        throw problem(lineNumber, "index number must be between 0 and 99");
                    }
                    long frames = parseFrames(words.get(2), lineNumber);
                    currentTrack.addIndex(new CueIndex(number, frames), lineNumber);
                }
                case "PREGAP" -> {
                    requireWords(words, 2, lineNumber, "PREGAP mm:ss:ff");
                    if (currentTrack == null) {
                        throw problem(lineNumber, "PREGAP appears before TRACK");
                    }
                    if (currentTrack.pregapFrames >= 0) {
                        throw problem(lineNumber, "duplicate PREGAP");
                    }
                    currentTrack.pregapFrames = parseFrames(words.get(1), lineNumber);
                }
                case "POSTGAP" -> {
                    requireWords(words, 2, lineNumber, "POSTGAP mm:ss:ff");
                    if (currentTrack == null) {
                        throw problem(lineNumber, "POSTGAP appears before TRACK");
                    }
                    if (currentTrack.postgapFrames >= 0) {
                        throw problem(lineNumber, "duplicate POSTGAP");
                    }
                    currentTrack.postgapFrames = parseFrames(words.get(1), lineNumber);
                }
                default -> {
                    // Unknown commands are intentionally ignored for compatibility.
                }
            }
        }

        if (files.isEmpty()) throw new CueArchiveException("CUE sheet declares no FILE");
        if (tracks.isEmpty()) throw new CueArchiveException("CUE sheet declares no TRACK");

        List<ParsedFile> frozenFiles = new ArrayList<>(files.size());
        for (ParsedFile view : files) {
            MutableFile file = view.owner;
            if (file.tracks.isEmpty()) {
                throw new CueArchiveException(
                        "FILE declares no TRACK: " + file.declaredName);
            }
            frozenFiles.add(file.freeze());
        }
        List<ParsedTrack> frozenTracks = new ArrayList<>(tracks.size());
        for (ParsedTrack view : tracks) {
            frozenTracks.add(view.owner.freeze());
        }
        return new ParsedCue(List.copyOf(frozenFiles), List.copyOf(frozenTracks));
    }

    private static byte[] readBounded(Path source, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new CueArchiveException(
                        "CUE sheet exceeds the " + limit + "-byte budget");
            }
            return bytes;
        }
    }

    private static String decode(byte[] bytes) throws CueArchiveException {
        int offset =
                bytes.length >= 3
                                && bytes[0] == (byte) 0xef
                                && bytes[1] == (byte) 0xbb
                                && bytes[2] == (byte) 0xbf
                        ? 3
                        : 0;
        try {
            CharBuffer decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return decoded.toString();
        } catch (CharacterCodingException ignored) {
            return Charset.forName("windows-1252").decode(
                            ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        }
    }

    private static List<String> tokenize(String line, int lineNumber)
            throws CueArchiveException {
        List<String> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < line.length()) {
            while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
            if (cursor >= line.length()) break;
            if (line.charAt(cursor) == '"') {
                int start = ++cursor;
                int end = line.indexOf('"', start);
                if (end < 0) throw problem(lineNumber, "unterminated quoted string");
                result.add(line.substring(start, end));
                cursor = end + 1;
                if (cursor < line.length() && !Character.isWhitespace(line.charAt(cursor))) {
                    throw problem(lineNumber, "unexpected text after quoted string");
                }
            } else {
                int start = cursor;
                while (cursor < line.length() && !Character.isWhitespace(line.charAt(cursor))) {
                    cursor++;
                }
                result.add(line.substring(start, cursor));
            }
        }
        return result;
    }

    private static long parseFrames(String value, int lineNumber) throws CueArchiveException {
        String[] fields = value.split(":", -1);
        if (fields.length != 3) {
            throw problem(lineNumber, "timestamp must use mm:ss:ff");
        }
        long minutes = parseLong(fields[0], "minutes", lineNumber);
        long seconds = parseLong(fields[1], "seconds", lineNumber);
        long frames = parseLong(fields[2], "frames", lineNumber);
        if (seconds > 59 || frames > 74) {
            throw problem(lineNumber, "timestamp seconds or frames are out of range");
        }
        try {
            return Math.addExact(
                    Math.addExact(Math.multiplyExact(minutes, 60L * 75L), seconds * 75L),
                    frames);
        } catch (ArithmeticException e) {
            throw new CueArchiveException(
                    "line " + lineNumber + ": timestamp overflows", e);
        }
    }

    private static int parseDecimal(String value, String label, int lineNumber)
            throws CueArchiveException {
        long parsed = parseLong(value, label, lineNumber);
        if (parsed > Integer.MAX_VALUE) throw problem(lineNumber, label + " is too large");
        return (int) parsed;
    }

    private static long parseLong(String value, String label, int lineNumber)
            throws CueArchiveException {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw problem(lineNumber, label + " must be a non-negative decimal integer");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new CueArchiveException("line " + lineNumber + ": " + label + " is too large", e);
        }
    }

    private static void requireWords(
            List<String> words, int count, int lineNumber, String syntax)
            throws CueArchiveException {
        if (words.size() != count) throw problem(lineNumber, "expected " + syntax);
    }

    private static CueArchiveException problem(int lineNumber, String message) {
        return new CueArchiveException("line " + lineNumber + ": " + message);
    }

    public record ParsedCue(List<ParsedFile> files, List<ParsedTrack> tracks) {}

    public static final class ParsedFile {
        private final MutableFile owner;
        public final int index;
        public final String declaredName;
        public final CueFileType type;
        public final List<ParsedTrack> tracks;

        private ParsedFile(MutableFile owner, List<ParsedTrack> tracks) {
            this.owner = owner;
            this.index = owner.index;
            this.declaredName = owner.declaredName;
            this.type = owner.type;
            this.tracks = tracks;
        }
    }

    public static final class ParsedTrack {
        private final MutableTrack owner;
        public final int index;
        public final int number;
        public final CueTrackMode mode;
        public final int fileIndex;
        public final List<CueIndex> indices;
        public final long pregapFrames;
        public final long postgapFrames;

        private ParsedTrack(MutableTrack owner, List<CueIndex> indices) {
            this.owner = owner;
            this.index = owner.index;
            this.number = owner.number;
            this.mode = owner.mode;
            this.fileIndex = owner.fileIndex;
            this.indices = indices;
            this.pregapFrames = Math.max(0, owner.pregapFrames);
            this.postgapFrames = Math.max(0, owner.postgapFrames);
        }

        public CueIndex index(int number) {
            return indices.stream()
                    .filter(index -> index.number() == number)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static final class MutableFile {
        private final int index;
        private final String declaredName;
        private final CueFileType type;
        private final List<MutableTrack> tracks;

        private MutableFile(
                int index,
                String declaredName,
                CueFileType type,
                List<MutableTrack> tracks) {
            this.index = index;
            this.declaredName = declaredName;
            this.type = type;
            this.tracks = tracks;
        }

        private ParsedFile freezeView() {
            return new ParsedFile(this, List.of());
        }

        private ParsedFile freeze() throws CueArchiveException {
            List<ParsedTrack> frozen = new ArrayList<>(tracks.size());
            for (MutableTrack track : tracks) frozen.add(track.freeze());
            return new ParsedFile(this, List.copyOf(frozen));
        }
    }

    private static final class MutableTrack {
        private final int index;
        private final int number;
        private final CueTrackMode mode;
        private final int fileIndex;
        private final List<CueIndex> indices;
        private long pregapFrames = -1;
        private long postgapFrames = -1;

        private MutableTrack(
                int index,
                int number,
                CueTrackMode mode,
                int fileIndex,
                List<CueIndex> indices) {
            this.index = index;
            this.number = number;
            this.mode = mode;
            this.fileIndex = fileIndex;
            this.indices = indices;
        }

        private void addIndex(CueIndex index, int lineNumber) throws CueArchiveException {
            if (indices.stream().anyMatch(existing -> existing.number() == index.number())) {
                throw problem(lineNumber, "duplicate INDEX " + index.number());
            }
            if (!indices.isEmpty()) {
                CueIndex previous = indices.getLast();
                if (index.number() <= previous.number()) {
                    throw problem(lineNumber, "INDEX numbers must increase");
                }
                if (index.frames() < previous.frames()) {
                    throw problem(lineNumber, "INDEX timestamps must not decrease");
                }
            }
            indices.add(index);
        }

        private ParsedTrack freezeView() {
            return new ParsedTrack(this, List.of());
        }

        private ParsedTrack freeze() throws CueArchiveException {
            if (indices.stream().noneMatch(index -> index.number() == 1)) {
                throw new CueArchiveException("track " + number + " has no INDEX 01");
            }
            return new ParsedTrack(this, List.copyOf(indices));
        }
    }
}
