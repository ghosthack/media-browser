package io.github.ghosthack.mediabrowser.web;

import io.github.ghosthack.mediabrowser.media.move.ActionLogEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * What a move made from the phone page needs from the desktop around it — the
 * same three things a desktop move host does ({@code MoveController.Host}).
 * The app wires them to the shell ({@code App}); {@link #NONE} is for the
 * headless {@link WebModeMain} and tests.
 */
public interface MoveHooks {

    /**
     * Durably logs one item the moment it lands, on the file-op thread
     * ({@code ActionLog.persist} in the app).
     */
    void persist(ActionLogEntry entry);

    /**
     * Before anything moves: release every handle the desktop holds on
     * {@code moving} (a playing video; Windows cannot move an open file).
     * Called on a server thread; the app runs the shell's release on the FX
     * thread, and the move waits (on the file-op thread, up to a few seconds)
     * for the returned future before touching the filesystem.
     */
    CompletableFuture<Void> releaseBeforeMove(List<Path> moving);

    /**
     * After a batch that moved anything: {@code entries} (already persisted)
     * shown in the desktop's action log, and every desktop view listing one of
     * {@code affectedDirs} re-listed. Called on a server thread.
     *
     * @param moved the sources that left their folders
     */
    void afterMove(List<ActionLogEntry> entries, List<Path> moved, Set<Path> affectedDirs);

    /** Logs nothing, releases nothing, refreshes nothing. */
    MoveHooks NONE = new MoveHooks() {
        @Override
        public void persist(ActionLogEntry entry) {
        }

        @Override
        public CompletableFuture<Void> releaseBeforeMove(List<Path> moving) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void afterMove(List<ActionLogEntry> entries, List<Path> moved, Set<Path> affectedDirs) {
        }
    };
}
