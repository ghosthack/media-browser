package io.github.ghosthack.mediabrowser.media.move;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The optional on-disk action log: an append-only JSONL file at
 * {@code ~/.media-browser/action-log.jsonl}, one {@link ActionLogEntry} per
 * line (see {@link ActionLogEntry#toJsonLine()}). Written by
 * {@link io.github.ghosthack.mediabrowser.ui.ActionLog} when the
 * {@code actionLog.file} setting is on — a diagnostic black box: entries are
 * only ever appended (each with its own open-append-close, so every line is
 * on disk the moment the move completes), never rewritten or trimmed, and the
 * file survives restarts so a runaway or crashed session leaves its trace.
 *
 * <p>{@link #tail} reads only a bounded window off the end of the file, so
 * bootstrap stays cheap even after a runaway session has grown the file large.
 *
 * <p>An append failure is reported to stderr once and then goes quiet — disk
 * logging must never break or spam the move it is observing.
 */
public final class ActionLogFile {

    /** Lives beside {@code app.properties} and the album CSVs. */
    private static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"),
            ".media-browser", "action-log.jsonl");

    /** How far back {@link #tail} looks: plenty for its few-entry callers. */
    private static final int TAIL_WINDOW_BYTES = 64 * 1024;

    private final Path file;
    private boolean appendFailureReported;

    public ActionLogFile() {
        this(DEFAULT_FILE);
    }

    /** At an explicit path — for tests and harnesses. */
    public ActionLogFile(Path file) {
        this.file = file;
    }

    /** The file appended to, for showing in settings UI / messages. */
    public Path file() {
        return file;
    }

    /** Appends {@code entry} as one JSONL line, creating the file and parents. */
    public void append(ActionLogEntry entry) {
        byte[] line = (entry.toJsonLine() + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!appendFailureReported) {
                appendFailureReported = true;
                System.err.println("Cannot append to action log " + file + ": " + e);
            }
        }
    }

    /**
     * The last {@code maxEntries} well-formed entries of the file, oldest
     * first — the bootstrap seed for the in-memory log. Reads at most the
     * final {@value #TAIL_WINDOW_BYTES} bytes; malformed lines (a torn write,
     * hand edits) are skipped. A missing or unreadable file is an empty list.
     */
    public List<ActionLogEntry> tail(int maxEntries) {
        if (maxEntries <= 0 || !Files.isRegularFile(file)) {
            return List.of();
        }
        String window;
        boolean truncatedFront;
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long from = Math.max(0, size - TAIL_WINDOW_BYTES);
            truncatedFront = from > 0;
            var buffer = ByteBuffer.allocate((int) (size - from));
            channel.position(from);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep filling; a short read mid-loop is fine
            }
            window = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot read action log " + file + ": " + e);
            return List.of();
        }

        String[] lines = window.split("\r?\n");
        List<ActionLogEntry> entries = new ArrayList<>();
        // A window that starts mid-file starts mid-line; skip that fragment.
        for (int i = truncatedFront ? 1 : 0; i < lines.length; i++) {
            ActionLogEntry entry = ActionLogEntry.fromJsonLine(lines[i]);
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (entries.size() > maxEntries) {
            entries = entries.subList(entries.size() - maxEntries, entries.size());
        }
        return List.copyOf(entries);
    }
}
