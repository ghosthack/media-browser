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
 * only ever appended (each with its own open-append-force-close, so every line
 * is on disk the moment the move completes), never rewritten or trimmed, and
 * the file survives restarts so a runaway or crashed session leaves its trace.
 *
 * <p>{@link #tail} reads only a bounded window off the end of the file, so
 * bootstrap stays cheap even after a runaway session has grown the file large.
 *
 * <p>An append failure is reported to stderr once and then goes quiet — disk
 * logging must never break or spam the move it is observing.
 */
public final class ActionLogFile {

    /** How far back {@link #tail} looks: plenty for its few-entry callers. */
    private static final int TAIL_WINDOW_BYTES = 64 * 1024;

    private final Path file;
    /** Guarded by this instance's monitor, like the append itself. */
    private boolean appendFailureReported;

    /**
     * At {@code file}: the app passes the one beside {@code app.properties}
     * and the album CSVs. There is no default, so nothing can bypass the
     * application-directory override and write under the real home.
     */
    public ActionLogFile(Path file) {
        this.file = file;
    }

    /** The file appended to, for showing in settings UI / messages. */
    public Path file() {
        return file;
    }

    /** Whether the file is non-empty and its last byte is not a line feed. */
    private boolean endsMidLine() throws IOException {
        if (!Files.isRegularFile(file)) return false;
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0) return false;
            var last = java.nio.ByteBuffer.allocate(1);
            return channel.read(last, size - 1) == 1 && last.get(0) != '\n';
        }
    }

    /**
     * Appends {@code entry} as one JSONL line, creating the file and parents,
     * and returns only once the line has been forced to the storage device —
     * a crash or power loss after this returns cannot lose it. Blocking file
     * IO: call it off the FX thread. Safe from any thread; appends through one
     * instance are serialized, so concurrent callers never interleave lines.
     */
    public synchronized void append(ActionLogEntry entry) {
        byte[] line = (entry.toJsonLine() + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (endsMidLine()) {
                // A crash mid-append left a torn last line: close it so this
                // entry starts its own line instead of fusing with the fragment
                // (the reader then skips the fragment as unparseable).
                byte[] separator = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
                byte[] joined = new byte[separator.length + line.length];
                System.arraycopy(separator, 0, joined, 0, separator.length);
                System.arraycopy(line, 0, joined, separator.length, line.length);
                line = joined;
            }
            try (var channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                ByteBuffer pending = ByteBuffer.wrap(line);
                while (pending.hasRemaining()) {
                    channel.write(pending);
                }
                // Closing only hands the line to the OS cache; force it (and
                // the grown file size) to the device before reporting it done.
                channel.force(true);
            }
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
