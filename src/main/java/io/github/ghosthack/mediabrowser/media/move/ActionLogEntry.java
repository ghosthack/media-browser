package io.github.ghosthack.mediabrowser.media.move;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;

/**
 * One user-performed file-organization action, recorded in the session
 * action log ({@link io.github.ghosthack.mediabrowser.ui.ActionLog}). The in-memory
 * log empties on quit, but each entry carries enough to serialize or revert
 * later: the action type, timestamp, source path, and the <em>resolved</em>
 * final target path (post auto-rename, which may differ from the requested
 * destination) — the JSONL form ({@link #toJsonLine()}/{@link #fromJsonLine})
 * is what the optional append-only {@link ActionLogFile} writes.
 *
 * <p>Ported from {@code iris94.core.models.ActionLogEntry}, trimmed to the
 * action types this app performs (dialog moves, single-file renames and
 * quick-moves; iris's delete and virtual-catalog types have no counterpart
 * here yet).
 */
public record ActionLogEntry(
        long timestampMillis,
        Type type,
        String sourcePath,
        String targetPath) {

    public enum Type {
        MOVE,
        RENAME
    }

    /**
     * Entry for a real-FS relocation, classified as RENAME when the file
     * stayed in its directory (single-file move+rename to the same parent)
     * and MOVE otherwise. {@code finalPath} must be the resolved on-disk
     * result, including any auto-rename collision suffix.
     */
    public static ActionLogEntry moveOrRename(Path sourcePath, Path finalPath) {
        Path sourceParent = sourcePath.toAbsolutePath().getParent();
        Path targetParent = finalPath.toAbsolutePath().getParent();
        boolean sameParent = sourceParent != null && sourceParent.equals(targetParent);
        return new ActionLogEntry(System.currentTimeMillis(),
                sameParent ? Type.RENAME : Type.MOVE,
                sourcePath.toString(), finalPath.toString());
    }

    /** One-line human-readable description, used by the action-log panel. */
    public String summary() {
        return switch (type) {
            case MOVE -> "Moved " + sourcePath + " → " + targetPath;
            case RENAME -> "Renamed " + sourcePath + " → " + leafName(targetPath);
        };
    }

    /** Last path segment, tolerating both '/' and the platform separator. */
    private static String leafName(String path) {
        if (path == null) return "";
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
        return cut >= 0 && cut < path.length() - 1 ? path.substring(cut + 1) : path;
    }

    // ---- JSONL (the append-only file's line format) --------------------------

    /**
     * This entry as one JSON object on one line, e.g.
     * <pre>{"ts":1752300000123,"time":"2026-07-12T08:00:00.123Z","type":"MOVE","source":"/a/x.jpg","target":"/b/x.jpg"}</pre>
     * {@code time} is {@code ts} restated as UTC ISO-8601, purely for human
     * readers of the file; {@link #fromJsonLine} ignores it.
     */
    public String toJsonLine() {
        return "{\"ts\":" + timestampMillis
                + ",\"time\":\"" + Instant.ofEpochMilli(timestampMillis) + "\""
                + ",\"type\":\"" + type.name() + "\""
                + ",\"source\":" + jsonString(sourcePath)
                + ",\"target\":" + jsonString(targetPath) + "}";
    }

    /**
     * Parses one {@link #toJsonLine()} line back into an entry; returns
     * {@code null} for anything malformed (a torn last line after a crash, a
     * hand-edited file) rather than throwing — the tail reader just skips it.
     */
    public static ActionLogEntry fromJsonLine(String line) {
        if (line == null) return null;
        try {
            String ts = jsonValue(line, "ts");
            String type = jsonValue(line, "type");
            String source = jsonValue(line, "source");
            String target = jsonValue(line, "target");
            if (ts == null || type == null || source == null || target == null) return null;
            return new ActionLogEntry(Long.parseLong(ts), Type.valueOf(type), source, target);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** {@code value} as a JSON string literal (quotes, backslashes, controls escaped). */
    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The value of {@code key} in the flat JSON object {@code line}: a string
     * value unescaped, a number as its digits. {@code null} when absent.
     */
    private static String jsonValue(String line, String key) {
        String marker = "\"" + key + "\":";
        int at = line.indexOf(marker);
        if (at < 0) return null;
        int i = at + marker.length();
        if (line.charAt(i) != '"') { // number (ts): read the digit run
            int end = i;
            while (end < line.length() && (Character.isDigit(line.charAt(end))
                    || line.charAt(end) == '-')) {
                end++;
            }
            return line.substring(i, end);
        }
        StringBuilder sb = new StringBuilder();
        for (i++; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = line.charAt(++i);
            switch (esc) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(line, i + 1, i + 5, 16));
                    i += 4;
                }
                default -> sb.append(esc); // \" \\ \/ …
            }
        }
        return null; // unterminated string
    }
}
