package io.github.ghosthack.mediabrowser.web;

import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;
import io.github.ghosthack.mediabrowser.media.move.ActionLogEntry;
import io.github.ghosthack.mediabrowser.media.move.FileOps;
import io.github.ghosthack.mediabrowser.media.move.MoveBatch;
import io.github.ghosthack.mediabrowser.media.move.SidecarCarrier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The phone page's moves ({@code POST /api/move}) and their undo
 * ({@code POST /api/undo}), over the desktop's own move machinery:
 * {@link MoveBatch} with the app's {@link SidecarCarrier} on
 * {@link MediaService#fileOp}, each landed item persisted through
 * {@link MoveHooks#persist} (the desktop's action log), handles released
 * before and the desktop re-listed after through the other hooks.
 *
 * <p>The fence: target and every source resolve through {@link WebPaths}
 * (inside the root, symlinks followed); nothing inside an archive and no
 * archive file moves or is moved into (409 {@code read-only} /
 * {@code archive}); a folder is never moved into itself or below itself
 * (409 {@code into-itself}); a source that is itself a symbolic link, or the
 * root, is refused (409 {@code not-movable}); a plain file must be one the
 * desktop's listing shows as media. Any refusal refuses the whole request
 * before anything moves; a source that is simply gone is that item's failure.
 * Everything else is per item: moved, renamed (a collision got the
 * {@code " (N)"} name), skipped (already there) or failed, with the reason.</p>
 *
 * <p>Undo moves exactly the items a move moved back to their folders, under
 * their original names when free (else the usual {@code " (N)"} rename),
 * through the same rules, batch, log and hooks. The record of recent moves is
 * in memory and bounded ({@link #RECORDS}); an undo uses its record up.</p>
 */
final class WebMoves {

    /** Recent moves kept for undo. */
    static final int RECORDS = 32;
    /** Items one request may name. */
    static final int MAX_ITEMS = 5000;
    /** How long a move waits for the desktop to release its handles (the desktop's own figure). */
    static final long RELEASE_TIMEOUT_MILLIS = 5000;
    /** How long a request waits for its batch; a cross-device folder move can take a while. */
    static final Duration MOVE_TIMEOUT = Duration.ofMinutes(10);

    /** Refuses a plain file the desktop's listing does not show as media. */
    @FunctionalInterface
    interface MediaGate {
        void requireMedia(Path file) throws WebException;
    }

    /**
     * One moved item: where it was, where it is now, and its identity there
     * ({@link #identity}), so an undo can tell it from a different file that
     * took its name since.
     */
    private record Undoable(Path original, Path now, Object key) {}

    /** Ids of moves that outlived their request's wait and are still running (guarded by records). */
    private final Set<String> running = new HashSet<>();
    /** How long a request waits for its batch; tests shorten it. */
    Duration moveTimeout = MOVE_TIMEOUT;

    private final MediaService service;
    private final SidecarCarrier sidecars;
    private final MediaGate media;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();
    private final Map<String, List<Undoable>> records = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Undoable>> eldest) {
            return size() > RECORDS;
        }
    };
    private volatile MoveHooks hooks = MoveHooks.NONE;

    WebMoves(MediaService service, SidecarCarrier sidecars, MediaGate media) {
        this.service = Objects.requireNonNull(service, "service");
        this.sidecars = Objects.requireNonNull(sidecars, "sidecars");
        this.media = Objects.requireNonNull(media, "media");
    }

    void setHooks(MoveHooks hooks) {
        this.hooks = hooks == null ? MoveHooks.NONE : hooks;
    }

    void clear() {
        synchronized (records) {
            records.clear();
        }
    }

    // ------------------------------------------------------------------ move

    Map<String, Object> move(WebPaths fence, String targetText, List<String> itemTexts)
            throws WebException {
        if (itemTexts.isEmpty()) throw WebException.badRequest("nothing to move");
        if (itemTexts.size() > MAX_ITEMS) {
            throw new WebException(413, "too-large", "at most " + MAX_ITEMS + " items per move");
        }
        synchronized (lock) {
            Path dir = target(fence, targetText);
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
            List<Path> moving = new ArrayList<>();
            for (String text : new LinkedHashSet<>(itemTexts)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("p", text);
                rows.add(row);
                Path src;
                try {
                    src = source(fence, text, dir);
                } catch (WebException e) {
                    if (e.status != 404) throw e;      // a refusal: nothing moves
                    row.put("n", lastSegment(text));
                    failed(row, e.getMessage());
                    continue;
                }
                row.put("n", name(src));
                if (dir.equals(src.getParent())) {
                    skipped(row, "already in " + name(dir));
                } else if (pending.containsKey(src.toString())) {
                    skipped(row, "listed twice");
                } else {
                    pending.put(src.toString(), row);
                    moving.add(src);
                }
            }
            // An item inside a folder that is moving goes with it.
            for (Path src : List.copyOf(moving)) {
                for (Path other : moving) {
                    if (!other.equals(src) && src.startsWith(other)) {
                        skipped(pending.remove(src.toString()), "moves with its folder " + name(other));
                        moving.remove(src);
                        break;
                    }
                }
            }

            List<Undoable> undo = new ArrayList<>();
            String id = newId();
            if (!moving.isEmpty()) {
                Set<Path> affected = new LinkedHashSet<>();
                affected.add(dir);
                moving.forEach(src -> affected.add(src.getParent()));
                // A batch that outlives the wait still reports when it ends:
                // the desktop refreshes and the move becomes undoable under id.
                MoveBatch.Execution done = run(moving,
                        src -> FileOps.moveWithAutoRename(src, dir, sidecars), id,
                        late -> keep(id, report(late, pending, null, affected)));
                undo = report(done, pending, null, affected);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            if (!undo.isEmpty()) {
                keep(id, undo);
                out.put("id", id);
            }
            out.put("target", WebPaths.locator(dir));
            out.put("targetName", name(dir));
            counts(out, rows);
            out.put("items", rows);
            return out;
        }
    }

    /** The folder to move into: an existing plain folder inside the root. */
    private static Path target(WebPaths fence, String text) throws WebException {
        if (text == null || text.isBlank()) throw WebException.badRequest("no target folder");
        Path dir = fence.resolve(text);
        if (ArchivePaths.inArchive(dir) || ArchivePaths.isArchiveFile(dir)) {
            throw new WebException(409, "read-only", "Cannot move into an archive (read-only)");
        }
        if (!Files.isDirectory(dir)) throw new WebException(404, "not-folder", "The target is not a folder");
        return dir;
    }

    /** A source the move rules allow; a 404 means it is gone, anything else refuses the request. */
    private Path source(WebPaths fence, String text, Path dir) throws WebException {
        if (text == null || text.isBlank()) throw WebException.badRequest("an empty item");
        Path src = fence.resolve(text);
        if (ArchivePaths.inArchive(src)) {
            throw new WebException(409, "read-only",
                    lastSegment(text) + " is inside an archive (read-only)");
        }
        if (src.equals(fence.root())) {
            throw new WebException(409, "not-movable", "The root folder itself cannot be moved");
        }
        if (isLink(text)) {
            throw new WebException(409, "not-movable", lastSegment(text)
                    + " is a symbolic link; it is not moved from the phone");
        }
        if (Files.isDirectory(src)) {
            if (dir.startsWith(src)) {
                throw new WebException(409, "into-itself",
                        "Cannot move " + name(src) + " into itself or a folder inside it");
            }
        } else if (ArchivePaths.isArchiveFile(src)) {
            throw new WebException(409, "archive", name(src) + " is an archive; archives are not moved"
                    + " from the phone");
        } else {
            media.requireMedia(src);
        }
        return src;
    }

    /** Whether the item the page named is itself a symbolic link (not merely below one). */
    private static boolean isLink(String text) {
        try {
            return Files.isSymbolicLink(Path.of(text));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ undo

    Map<String, Object> undo(WebPaths fence, String id) throws WebException {
        if (id == null || id.isBlank()) throw WebException.badRequest("no move id");
        synchronized (lock) {
            List<Undoable> record;
            synchronized (records) {
                if (running.contains(id)) {
                    throw new WebException(409, "moving",
                            "That move is still running; Undo once it has finished");
                }
                record = records.remove(id);
            }
            if (record == null) {
                throw WebException.notFound("No such move to undo (only the last "
                        + RECORDS + " moves can be undone, each once)");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
            Map<Path, Undoable> back = new HashMap<>();
            Map<Path, Path> folders = new HashMap<>();
            List<Path> moving = new ArrayList<>();
            for (Undoable u : record) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("p", WebPaths.locator(u.now()));
                row.put("n", name(u.now()));
                rows.add(row);
                String refusal = undoRefusal(fence, u);
                if (refusal != null) {
                    failed(row, refusal);
                    continue;
                }
                Path folder = u.original().getParent();
                if (folder.equals(u.now().getParent())) {
                    skipped(row, "already in " + name(folder));
                    continue;
                }
                pending.put(u.now().toString(), row);
                back.put(u.now(), u);
                folders.put(u.now(), folder);
                moving.add(u.now());
            }
            if (!moving.isEmpty()) {
                Set<Path> affected = new LinkedHashSet<>();
                moving.forEach(now -> {
                    affected.add(now.getParent());
                    affected.add(folders.get(now));
                });
                MoveBatch.Execution done = run(moving, src -> FileOps.moveWithAutoRename(
                        src, folders.get(src), back.get(src).original().getFileName().toString(),
                        sidecars), null, late -> report(late, pending, back, affected));
                report(done, pending, back, affected);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            counts(out, rows);
            out.put("items", rows);
            return out;
        }
    }

    /** Why an item cannot go back (the move rules, re-checked now), or null. */
    private static String undoRefusal(WebPaths fence, Undoable u) {
        Path now;
        try {
            now = fence.resolve(WebPaths.locator(u.now()));
        } catch (WebException e) {
            return e.status == 404 ? "It is no longer where it was moved to" : e.getMessage();
        }
        if (!now.equals(u.now()) || Files.isSymbolicLink(u.now())
                || (u.key() != null && !u.key().equals(identity(u.now())))) {
            return "It has been replaced since the move";
        }
        Path folder;
        try {
            folder = fence.resolve(WebPaths.locator(u.original().getParent()));
        } catch (WebException e) {
            return e.status == 404 ? "Its original folder is gone" : e.getMessage();
        }
        if (!Files.isDirectory(folder) || ArchivePaths.inArchive(folder)) {
            return "Its original folder is gone";
        }
        if (Files.isDirectory(now) && folder.startsWith(now)) {
            return "Cannot move a folder into itself";
        }
        return null;
    }

    // ------------------------------------------------------------------ the batch

    /** Records a finished move's undoable items under {@code id} (nothing when none moved). */
    private void keep(String id, List<Undoable> undo) {
        synchronized (records) {
            running.remove(id);
            if (!undo.isEmpty()) records.put(id, List.copyOf(undo));
        }
    }

    /**
     * Released handles, then {@code relocation} over {@code moving} on the
     * file-op thread. A batch still running when the wait ends is handed to
     * {@code late} once it finishes, and the 504 carries {@code lateId} (a
     * move's future undo id, marked running meanwhile; null for an undo).
     */
    private MoveBatch.Execution run(List<Path> moving, MoveBatch.Relocation relocation,
                                    String lateId, Consumer<MoveBatch.Execution> late)
            throws WebException {
        MoveHooks h = hooks;
        CompletableFuture<Void> released;
        try {
            released = h.releaseBeforeMove(List.copyOf(moving));
        } catch (RuntimeException e) {
            throw new WebException(500, "internal", "Could not release the files: "
                    + e.getMessage() + "; nothing was moved");
        }
        List<String> sources = moving.stream().map(Path::toString).toList();
        CompletableFuture<MoveBatch.Execution> batch;
        try {
            batch = service.fileOp(() -> {
                awaitReleased(released);
                return MoveBatch.run(sources, h::persist, relocation);
            });
        } catch (RuntimeException e) {
            throw new WebException(503, "busy", "The desktop is closing; nothing was moved");
        }
        try {
            return batch.get(moveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebException(503, "busy", "server is stopping; the move may still finish");
        } catch (TimeoutException e) {
            if (lateId != null) {
                synchronized (records) {
                    running.add(lateId);
                }
            }
            batch.whenComplete((done, failure) -> {
                if (failure != null) {
                    System.err.println("[WebMoves] a move that outlived its request failed: " + failure);
                    if (lateId != null) keep(lateId, List.of());
                } else {
                    late.accept(done);
                }
            });
            throw new WebException(504, "timeout", "The move is taking longer than "
                    + describe(moveTimeout) + "; it carries on, and can be undone once it has finished")
                    .withId(lateId);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof NotReleased) throw new WebException(409, "in-use", cause.getMessage());
            System.err.println("[WebMoves] move failed: " + cause);
            throw new WebException(500, "internal", "Move failed: " + cause.getMessage());
        }
    }

    /** A move refused because the desktop did not let go of the files in time. */
    private static final class NotReleased extends RuntimeException {
        NotReleased(String message) {
            super(message, null, false, false);
        }
    }

    private static void awaitReleased(CompletableFuture<Void> released) {
        try {
            released.get(RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new NotReleased("A video is still open on the desktop: it did not let go within "
                    + RELEASE_TIMEOUT_MILLIS + " ms; nothing was moved");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotReleased("Interrupted waiting for the desktop to release the files;"
                    + " nothing was moved");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new NotReleased("The desktop could not release the files: " + cause.getMessage()
                    + "; nothing was moved");
        }
    }

    /**
     * Fills each pending row from the batch, tells the desktop, and returns
     * what moved cleanly (undoable). {@code back} is the undo's record (the
     * original names), null for a move.
     */
    private List<Undoable> report(MoveBatch.Execution done, Map<String, Map<String, Object>> pending,
                                  Map<Path, Undoable> back, Set<Path> affected) {
        Set<String> failedSources = new LinkedHashSet<>();
        for (MoveBatch.Failure f : done.failures()) {
            failedSources.add(f.source());
            Map<String, Object> row = pending.get(f.source());
            if (row != null) failed(row, f.reason());
        }
        List<Undoable> undoable = new ArrayList<>();
        List<ActionLogEntry> entries = new ArrayList<>();
        List<Path> moved = new ArrayList<>();
        for (MoveBatch.Moved m : done.moved()) {
            entries.add(m.entry());
            moved.add(m.source());
            Map<String, Object> row = pending.get(m.source().toString());
            if (row == null) continue;
            row.put("to", WebPaths.locator(m.finalPath()));
            row.put("name", name(m.finalPath()));
            if (failedSources.contains(m.source().toString())) continue;   // stranded: said in its reason
            String wanted = back == null ? name(m.source()) : name(back.get(m.source()).original());
            row.put("status", wanted.equals(name(m.finalPath())) ? "moved" : "renamed");
            undoable.add(new Undoable(m.source(), m.finalPath(), identity(m.finalPath())));
        }
        if (!entries.isEmpty()) {
            try {
                hooks.afterMove(List.copyOf(entries), List.copyOf(moved), Set.copyOf(affected));
            } catch (RuntimeException e) {
                System.err.println("[WebMoves] desktop refresh after a phone move failed: " + e);
            }
        }
        return undoable;
    }

    private static String describe(Duration d) {
        long minutes = d.toMinutes();
        return minutes >= 1 ? minutes + (minutes == 1 ? " minute" : " minutes") : d.toMillis() + " ms";
    }

    /**
     * What tells {@code path}'s file from another that later takes its name
     * (not following a link): the file key (device + inode) where the
     * filesystem has one; on Windows, where Java gives none, the creation
     * time with — for a file — the modification time and size (Windows'
     * name tunnelling can hand a replacement the old creation time, never
     * the old modification time of a fresh write); null when unreadable.
     */
    private static Object identity(Path path) {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.fileKey() != null) return a.fileKey();
            return a.isDirectory() ? List.of(a.creationTime())
                    : List.of(a.creationTime(), a.lastModifiedTime(), a.size());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ rows

    private static void failed(Map<String, Object> row, String reason) {
        row.put("status", "failed");
        row.put("error", reason);
    }

    private static void skipped(Map<String, Object> row, String reason) {
        row.put("status", "skipped");
        row.put("reason", reason);
    }

    private static void counts(Map<String, Object> out, List<Map<String, Object>> rows) {
        for (String status : List.of("moved", "renamed", "skipped", "failed")) {
            out.put(status, rows.stream().filter(r -> status.equals(r.get("status"))).count());
        }
    }

    private String newId() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String name(Path path) {
        Path n = path.getFileName();
        return n == null ? path.toString() : n.toString();
    }

    private static String lastSegment(String text) {
        String t = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
        return t.substring(t.lastIndexOf('/') + 1);
    }
}
