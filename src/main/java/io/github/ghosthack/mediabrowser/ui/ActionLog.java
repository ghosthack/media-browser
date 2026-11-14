package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.media.move.ActionLogEntry;
import io.github.ghosthack.mediabrowser.media.move.ActionLogFile;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * The session action log: every file-organization action performed this run
 * (dialog moves, renames, quick-moves and moves to trash), in order, capped at {@link #LIMIT}
 * oldest-first. In-memory and emptied on quit — except that with the
 * {@code actionLog.file} setting on (Settings ▸ General, default off, applied
 * live) every recorded entry is also appended to the on-disk JSONL log
 * ({@link ActionLogFile}), and at startup the file's last
 * {@link #BOOTSTRAP_TAIL} entries are seeded back in (see
 * {@link #attachFile}).
 *
 * <p>A process-wide singleton (like {@link ThemeManager}): the main, mosaic
 * and viewer windows' {@link MoveController}s all record into the same log,
 * and every hosted {@link ActionLogPanel} (browser + mosaic) observes it. The
 * in-memory list is FX-thread only — the panels bind to {@link #entries()}
 * directly — but the on-disk half is not: a controller calls {@link #persist}
 * on its file-operation thread the moment each relocation completes (so a
 * crash mid-batch still leaves every finished move in the file, and no file IO
 * runs on the FX thread), then hands the same entry to {@link #recordPersisted}
 * from its {@code Platform.runLater} completion handler to show it.
 *
 * <p>{@link #moveTargetsRevision()} is a change signal for state the panel
 * shows but this log does not own: the persisted move history (the F1–F4
 * quick-move targets) and the transient quick-move toggle, both living in
 * {@code AppSettings}, which is not observable. {@link MoveController} bumps
 * it whenever either changes so the panel re-reads them.
 */
public final class ActionLog {

    /** Session cap, matching the Swing predecessor's action-log default. */
    public static final int LIMIT = 2000;

    /** How many persisted entries seed the panel back in at startup. */
    public static final int BOOTSTRAP_TAIL = 10;

    private static final ActionLog INSTANCE = new ActionLog();

    public static ActionLog get() {
        return INSTANCE;
    }

    private final ObservableList<ActionLogEntry> entries = FXCollections.observableArrayList();
    private final ObservableList<ActionLogEntry> readOnlyEntries =
            FXCollections.unmodifiableObservableList(entries);
    private final SimpleIntegerProperty moveTargetsRevision = new SimpleIntegerProperty();

    /**
     * Both set once at startup by {@link #attachFile}; null in harnesses/tests.
     * Volatile: {@link #persist} reads them off the FX thread.
     */
    private volatile AppSettings settings;
    private volatile ActionLogFile file;

    private ActionLog() {}

    /**
     * Hooks up the optional on-disk log: {@code file} receives every entry
     * recorded while {@code settings.actionLogFileEnabled()} (checked per
     * record, so the Settings toggle applies live), and — when the setting is
     * already on at this call — the file's last {@link #BOOTSTRAP_TAIL}
     * entries are seeded into the in-memory log now, without re-appending
     * them. Called once at application start, before any window records.
     */
    public void attachFile(AppSettings settings, ActionLogFile file) {
        this.settings = settings;
        this.file = file;
        if (settings.actionLogFileEnabled()) {
            entries.addAll(file.tail(BOOTSTRAP_TAIL));
        }
    }

    /** The log, oldest first. Unmodifiable; observe it for live updates. */
    public ObservableList<ActionLogEntry> entries() {
        return readOnlyEntries;
    }

    /**
     * Append {@code entry} to the on-disk log when that is attached and
     * enabled, returning once the line is durable ({@link ActionLogFile#append}).
     * The off-FX-thread half of recording: blocking file IO, safe from any
     * thread, to be called by the thread that performed the relocation as soon
     * as it completes — not batched, and not deferred to the FX thread. Follow
     * it with {@link #recordPersisted} on the FX thread.
     */
    public void persist(ActionLogEntry entry) {
        ActionLogFile target = file;
        AppSettings current = settings;
        if (entry != null && target != null && current != null
                && current.actionLogFileEnabled()) {
            target.append(entry);
        }
    }

    /**
     * Show an entry already handed to {@link #persist}: appended to the
     * in-memory log only (trimming the oldest past {@link #LIMIT}), never to
     * the file a second time. FX thread only.
     */
    public void recordPersisted(ActionLogEntry entry) {
        if (entry == null) {
            return;
        }
        entries.add(entry);
        while (entries.size() > LIMIT) {
            entries.remove(0);
        }
    }

    /**
     * {@link #persist} then {@link #recordPersisted} in one call, for a caller
     * already on the FX thread with nowhere better to do the file append.
     * Prefer the split pair: this blocks the FX thread on a forced write.
     */
    public void record(ActionLogEntry entry) {
        persist(entry);
        recordPersisted(entry);
    }

    /** Tests only: forget the file a test attached, so later suites write nowhere. */
    void detachFileForTest() {
        settings = null;
        file = null;
    }

    /** Tests only: drop every in-memory entry a test recorded into the singleton. */
    void clearForTest() {
        entries.clear();
    }

    /** Bumped whenever the quick-move targets or their enabled state change. */
    public ReadOnlyIntegerProperty moveTargetsRevision() {
        return moveTargetsRevision;
    }

    /** Signal that the move history or the quick-move toggle changed. FX thread only. */
    public void touchMoveTargets() {
        moveTargetsRevision.set(moveTargetsRevision.get() + 1);
    }
}
