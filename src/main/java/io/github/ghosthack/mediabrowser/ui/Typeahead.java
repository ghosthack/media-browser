package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.DirEntry;

import javafx.scene.input.KeyEvent;

import java.util.List;

/**
 * Type-to-select over a {@link DirEntry} listing, shared by the main window's
 * list view and the mosaic grid (both navigate the same filtered+sorted
 * listing). Typed characters accumulate into a prefix that is cleared after a
 * short pause, so "ab" typed quickly matches names starting with "ab" while a
 * later keystroke starts fresh.
 *
 * <p>Usage from a {@code KEY_TYPED} handler: feed the event to {@link
 * #append(KeyEvent)}; a {@code null} return means the event isn't a typeahead
 * character (let it propagate). Otherwise resolve the target with {@link
 * #indexOf(List, int, String)} and consume the event. Call {@link #reset()} on a
 * directory change.</p>
 */
final class Typeahead {

    /** Idle gap after which the accumulated prefix is discarded. */
    private static final long RESET_NANOS = 1_000_000_000L;

    private final StringBuilder buffer = new StringBuilder();
    private long deadline;

    /**
     * Folds a {@code KEY_TYPED} event into the running prefix and returns it, or
     * {@code null} when the event isn't a printable typeahead character (a
     * shortcut/control chord, or an empty/control character) and should be left
     * to propagate.
     */
    String append(KeyEvent e) {
        if (e.isShortcutDown() || e.isControlDown()) return null;
        String character = e.getCharacter();
        if (character.isEmpty() || Character.isISOControl(character.charAt(0))) return null;
        long now = System.nanoTime();
        if (now > deadline) buffer.setLength(0);
        deadline = now + RESET_NANOS;
        buffer.append(character.toLowerCase());
        return buffer.toString();
    }

    /** Clears the running prefix (e.g. on a directory change). */
    void reset() {
        buffer.setLength(0);
    }

    /**
     * Index of the entry to select for {@code prefix}: the first match found by
     * scanning from {@code from} and wrapping around, so repeating a prefix while
     * already on a match keeps the selection there. The {@code ..} parent entry
     * is never matched. Returns {@code -1} when nothing matches.
     */
    static int indexOf(List<DirEntry> entries, int from, String prefix) {
        int size = entries.size();
        if (size == 0) return -1;
        int start = Math.max(from, 0);
        for (int offset = 0; offset < size; offset++) {
            int i = (start + offset) % size;
            DirEntry entry = entries.get(i);
            if (entry.type() != DirEntry.Type.PARENT
                    && entry.displayName().toLowerCase().startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
