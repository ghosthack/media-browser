package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.VideoPlayer;

import javafx.application.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The viewer's claim on the video it plays or is preparing to play: the
 * session token that voids stale playback callbacks, and the file that
 * session opened (the extension-fix target on stop).
 *
 * <p>A claim exists from {@link #begin} — before any player does, while
 * autoplay still prepares the file on the file-op thread — until
 * {@link #end}, which voids the token so a preparation completing later
 * closes what it prepared instead of starting it.</p>
 *
 * <p>Moves are why this is separate: on Windows an open file cannot be
 * moved, and a move selection may name the file itself, a folder above it,
 * or the archive container (or a folder above that) the file is played out
 * of — any of these must take the file back, including from a preparation
 * that has no player yet ({@link #releaseFor}).</p>
 *
 * <p>The claim owns the session's started player ({@link #attach}) and is
 * the only way to close it ({@link #closePlayer}): closing does not wait for
 * the playback thread to let go of the file, so the claim remembers each
 * closed player until it has, and a move awaits every one holding a file it
 * affects ({@link #releasedFor}). FX thread only.</p>
 */
final class PlaybackClaim {

    /** Guards the host-archive walk against a cyclic resolver. */
    private static final int MAX_ARCHIVE_DEPTH = 8;

    private int session;
    private Path path;
    /** The live session's started player, or {@code null}; see {@link #attach}. */
    private VideoPlayer player;
    /** The file {@link #player} opened — kept past {@link #end}, which clears {@link #path}. */
    private Path playerPath;
    /** Whether the live session's player delivered its {@code begin()}; see {@link #markProven}. */
    private boolean proven;
    /** Closed players whose thread may still hold their file; pruned lazily. */
    private final List<Closing> closing = new ArrayList<>();

    private record Closing(Path path, CompletableFuture<Void> released) {}

    /** Where after-release work runs: {@link Platform#runLater}; a queue in tests. */
    private final Executor fxThread;

    PlaybackClaim() {
        this(Platform::runLater);
    }

    PlaybackClaim(Executor fxThread) {
        this.fxThread = fxThread;
    }

    /** Opens a new session on {@code path}; returns its token. */
    int begin(Path path) {
        this.path = path;
        proven = false;
        return ++session;
    }

    /** Whether {@code token} is still the live session's. */
    boolean isLive(int token) {
        return token == session;
    }

    /** The file the live session opened (or is preparing), or {@code null}. */
    Path path() {
        return path;
    }

    /**
     * Ends the session: every outstanding token goes stale. Safe with none
     * live. Returns the path it held, or {@code null}.
     */
    Path end() {
        Path held = path;
        path = null;
        session++;
        return held;
    }

    /**
     * Ends the session when {@code moving} affects the file it holds — its
     * path, or with no path recorded but a player open, {@code current} —
     * and returns whether it did; the caller then closes the player, if any.
     */
    boolean releaseFor(Collection<Path> moving, Path current, boolean playerOpen,
                       Function<Path, Optional<Path>> hostArchive) {
        Path held = path != null ? path : playerOpen ? current : null;
        if (!affects(moving, held, hostArchive)) return false;
        end();
        return true;
    }

    /**
     * A move's whole take-back: when {@code moving} affects the live session
     * ({@link #releaseFor}), ends it, runs {@code stop} (the host's own stop,
     * which may close the player itself) and closes the player; then returns
     * the release of every closed player holding an affected file — the one
     * just closed included, which is why the release is gathered only after
     * the close ({@link #releasedFor}).
     */
    CompletableFuture<Void> takeBack(Collection<Path> moving, Path current,
                                     Function<Path, Optional<Path>> hostArchive,
                                     Runnable stop) {
        if (releaseFor(moving, current, player != null, hostArchive)) {
            stop.run();
            closePlayer(null);
        }
        return releasedFor(moving, hostArchive);
    }

    /** The live session's started player, or {@code null} (none, or still preparing). */
    VideoPlayer player() {
        return player;
    }

    /**
     * Hands the live session's prepared player to the claim, which from now
     * on owns closing it ({@link #closePlayer}); it holds the session's file.
     */
    void attach(VideoPlayer prepared) {
        player = prepared;
        playerPath = path;
    }

    /**
     * Closes the attached player, if any, without waiting for its thread
     * ({@link VideoPlayer#close}), and remembers it as holding its file until
     * it lets go ({@link #releasedFor}). A non-null {@code afterRelease} gets
     * that file on the FX thread once the player has let go of it — a rename
     * must not race the decoder's handle — unless by then the file is held
     * again ({@link #holds}): navigating back to it, or another closed player
     * still on it. The rename would then fail on Windows ("file in use"), or
     * on macOS land under the new holder and leave the claim on a stale path;
     * the stop that ends that hold offers the work again. Returns the file it
     * held, or {@code null} when no player was attached.
     */
    Path closePlayer(Consumer<Path> afterRelease) {
        if (player == null) return null;
        Path held = playerPath;
        CompletableFuture<Void> released = player.released();
        closing(held, released);
        player.close();
        if (afterRelease != null && held != null) {
            released.thenRun(() -> fxThread.execute(() -> {
                if (!holds(held)) afterRelease.accept(held);
            }));
        }
        player = null;
        playerPath = null;
        return held;
    }

    /**
     * Records that the session's player delivered its {@code begin()} — the
     * proof the file really plays, which {@link #stopPlayer} requires before
     * it offers the file the automatic extension fix. A new session
     * ({@link #begin}) starts unproven.
     */
    void markProven() {
        proven = true;
    }

    /**
     * The viewer's stop: closes the attached player as {@link #closePlayer}
     * does, offering its file to {@code extensionFix} once released — but
     * only when the session proved the file plays ({@link #markProven}); a
     * file that failed or never began keeps its name. {@code null} offers
     * nothing (a move, a re-open, an error). Clears the proof either way.
     */
    Path stopPlayer(Consumer<Path> extensionFix) {
        Consumer<Path> offer = proven ? extensionFix : null;
        proven = false;
        return closePlayer(offer);
    }

    /**
     * Whether {@code file} is held: the live (or preparing) session's file,
     * the attached player's, or a closed player's not yet released.
     */
    boolean holds(Path file) {
        if (file.equals(path) || player != null && file.equals(playerPath)) return true;
        return closing.stream().anyMatch(c -> !c.released().isDone() && file.equals(c.path()));
    }

    /**
     * Records that the player holding {@code path} was closed and lets go of
     * it when {@code released} completes.
     */
    private void closing(Path path, CompletableFuture<Void> released) {
        closing.removeIf(c -> c.released().isDone());
        if (path != null && !released.isDone()) closing.add(new Closing(path, released));
    }

    /**
     * Completes once every closed player holding a file {@code moving}
     * affects has let go of it — at once when none is still closing.
     */
    CompletableFuture<Void> releasedFor(Collection<Path> moving,
                                        Function<Path, Optional<Path>> hostArchive) {
        closing.removeIf(c -> c.released().isDone());
        return CompletableFuture.allOf(closing.stream()
                .filter(c -> affects(moving, c.path(), hostArchive))
                .map(Closing::released)
                .toArray(CompletableFuture[]::new));
    }

    /**
     * Whether moving {@code moving} affects {@code held}: some moving path
     * is {@code held} itself or an ancestor of it, compared as normalized
     * absolute paths, where an entry inside an archive is also reached through
     * its {@code hostArchive} container (and that container's own host,
     * should a resolver ever report one).
     */
    static boolean affects(Collection<Path> moving, Path held,
                           Function<Path, Optional<Path>> hostArchive) {
        if (held == null || moving.isEmpty()) return false;
        Path candidate = held;
        for (int depth = 0; candidate != null && depth <= MAX_ARCHIVE_DEPTH; depth++) {
            Path normalized = candidate.toAbsolutePath().normalize();
            for (Path m : moving) {
                if (m == null || m.getFileSystem() != normalized.getFileSystem()) continue;
                if (normalized.startsWith(m.toAbsolutePath().normalize())) return true;
            }
            candidate = hostArchive.apply(candidate).orElse(null);
        }
        return false;
    }
}
