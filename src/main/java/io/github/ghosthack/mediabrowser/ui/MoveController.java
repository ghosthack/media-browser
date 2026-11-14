package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;
import io.github.ghosthack.mediabrowser.media.move.ActionLogEntry;
import io.github.ghosthack.mediabrowser.media.move.FileOps;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogIntents;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogLogic;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogModel;
import io.github.ghosthack.mediabrowser.media.move.MovePlanner;
import io.github.ghosthack.mediabrowser.media.move.SidecarCarrier;
import io.github.ghosthack.mediabrowser.media.move.TrashOps;
import io.github.ghosthack.mediabrowser.media.move.TreeNode;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Drives the {@link MoveDialog}: owns the {@link MoveDialogModel}, handles every
 * {@link MoveDialogIntents} the view emits, and ports the Swing predecessor's
 * {@code submitMoveDialog} as {@link #submit()}. Window-agnostic — a
 * {@link Host} supplies the current directory, the selection, the post-move
 * focus target and the refresh, so the <em>same</em> controller serves both the
 * main window (Phase&nbsp;5) and the mosaic (Phase&nbsp;6).
 *
 * <p>The pure navigation intents delegate straight to {@link MoveDialogLogic}
 * (exactly as the Phase&nbsp;4 harness did) and then {@link #sync()} the dialog.
 * The filesystem work in {@link #submit()} runs off the FX thread via
 * {@link MediaService#fileOp}; its result is marshalled back with
 * {@link Platform#runLater}. Move history lives in {@link AppSettings} and is
 * threaded through the logic as a working copy, persisted on a successful (or
 * partial) move.
 */
public final class MoveController implements MoveDialogIntents {

    /**
     * The window-specific seam the controller drives. Implemented by
     * {@code MainWindow} and {@code MosaicWindow}; everything else here is
     * shared.
     */
    public interface Host {
        /** The window that owns the modal dialog and its confirm/alert popups. */
        Stage owner();

        /** The directory relative paths resolve against and that is refreshed. */
        Path currentDirectory();

        /** The current selection resolved to move sources (excluding {@code ..}). */
        Selection currentSelection();

        /**
         * The path of the next item to focus once {@code movingPaths} leave the
         * listing, or {@code null} if nothing suitable remains. Computed against
         * the <em>pre-move</em> listing and cursor (see the predecessor's
         * {@code computeNextFocusAfterMove}).
         */
        Path nextFocusAfterMove(List<Path> movingPaths);

        /**
         * Release any native/media handles this window holds open on
         * {@code movingPaths} so the filesystem move can proceed — on Windows an
         * open file cannot be moved or renamed, and a playing video keeps the
         * source file open. Called on the FX thread immediately before the async
         * move (or quick-move) launches; returns when the handles are actually
         * closed, which the move awaits off the FX thread before touching the
         * filesystem (see {@link #releaseTimeoutMillis}). No default: every
         * host shares the one viewer that may hold a file, and a host that
         * inherited a no-op would let a move run against an open file, so
         * each states its release ({@code viewer.releaseBeforeMove}).
         */
        CompletableFuture<Void> releaseBeforeMove(List<Path> movingPaths);

        /**
         * Re-list the current directory and, once the async listing arrives,
         * select {@code focusPath} (or clear selection when {@code null}). The
         * ported "synchronous refresh before focus" sequencing:
         * implementations set their pending-selection hook and re-navigate so the
         * post-listing selection lands without a race.
         */
        void refreshAfterMove(Path focusPath);

        /** Show a transient status / notification message. */
        void showStatus(String message);

        /** Whether hidden directories appear in the mini tree (default no). */
        default boolean showHiddenDirectories() {
            return false;
        }
    }

    /**
     * The move sources resolved from a window's selection.
     *
     * @param sources         the file/folder paths to move (never the {@code ..}
     *                        parent entry)
     * @param parentExcluded  whether {@code ..} was part of the selection and was
     *                        dropped (drives the dialog's muted note)
     */
    public record Selection(List<Path> sources, boolean parentExcluded) {
        public Selection {
            sources = List.copyOf(sources);
        }
    }

    /**
     * Where the off-FX-thread filesystem work runs: {@link MediaService#fileOp}
     * in the app, a fake in tests (the seam that lets a test make the launch
     * itself throw).
     */
    interface FileOpRunner {
        <T> CompletableFuture<T> run(Supplier<T> task);
    }

    /**
     * The dialog surface the controller drives: {@link MoveDialog} in the app,
     * a fake in tests (so open/submit/cancel run without the FX toolkit).
     */
    interface DialogView {
        boolean isShowing();

        /** Owner for the dialog's confirm popups. */
        Stage stage();

        void setHistory(List<String> history);

        void refresh();

        void showAndWait();

        void close();
    }

    /** Builds the {@link DialogView} for one {@link #open()}. */
    interface DialogFactory {
        DialogView create(Stage owner, MoveDialogModel model, MoveDialogIntents intents);
    }

    private final FileOpRunner fileOps;
    /** Marshals finish handlers back to the FX thread ({@link Platform#runLater}). */
    private final Executor fxThread;
    private final DialogFactory dialogs;
    private final AppSettings settings;
    private final Host host;
    /** Has each moved file's {@code .picasa.ini} entry and {@code .AAE} follow it. */
    private final SidecarCarrier sidecars;
    private final MoveDialogLogic.LoadChildren loadChildren = this::loadChildren;
    /**
     * Asks whether to create a missing target directory: the modal
     * {@link #confirmCreate} in the app, replaced by tests that must reach the
     * create step without the FX toolkit.
     */
    Predicate<String> createConfirmation = this::confirmCreate;
    /**
     * The native Trash: whether the desktop offers it, the modal
     * {@link #confirmTrash}, and the bulk move itself — replaced by tests,
     * which must never reach the user's real Trash or the FX toolkit.
     */
    BooleanSupplier trashSupported = TrashOps::isSupported;
    Predicate<List<Path>> trashConfirmation = this::confirmTrash;
    Function<List<Path>, TrashOps.Result> trash = TrashOps::moveToTrash;

    /**
     * How long a move waits, on the fileOp thread, for {@link Host#releaseBeforeMove}
     * to complete before failing with "video still open" instead of moving a
     * file a decoder still holds. Shortened by tests.
     */
    long releaseTimeoutMillis = 5000;

    /** Live only while a dialog is open. */
    private MoveDialogModel model;
    private DialogView dialog;
    /** A mutable working copy of the persisted history for the open session. */
    private List<String> workingHistory = new ArrayList<>();

    /**
     * Reentrancy guard: a move (dialog submit or quick-move) is in flight.
     * Process-wide — static, like the predecessor's single {@code isSubmittingMoveDialog}
     * — because each window has its <em>own</em> controller and a move can
     * shift keyboard focus to another window mid-flight (the mosaic's post-move
     * refresh auto-opens the viewer), where a per-instance guard would not
     * block a queued auto-repeating F1–F4. Held from launch until the finish
     * handler has applied the post-move refresh/focus, again matching the predecessor:
     * released any earlier, a queued keystroke would launch the next move
     * against a half-refreshed listing. FX thread only.
     */
    private static boolean moveInFlight = false;

    /**
     * Whether a move is in flight right now (FX thread only) — lets the
     * viewer's automatic extension fix stand down instead of renaming a path
     * a move may be about to relocate.
     */
    static boolean isMoveInFlight() {
        return moveInFlight;
    }

    /**
     * Tests only: clear the process-wide guard, so one test that leaks it
     * cannot cascade into every later test (or suite) in the same JVM.
     */
    static void resetMoveInFlightForTest() {
        moveInFlight = false;
    }

    public MoveController(MediaService service, AppSettings settings, SidecarCarrier sidecars,
                          Host host) {
        this(service::fileOp, Platform::runLater, MoveDialog::new, settings, sidecars, host);
    }

    MoveController(FileOpRunner fileOps, Executor fxThread, DialogFactory dialogs,
                   AppSettings settings, SidecarCarrier sidecars, Host host) {
        this.fileOps = fileOps;
        this.fxThread = fxThread;
        this.dialogs = dialogs;
        this.settings = settings;
        this.sidecars = sidecars;
        this.host = host;
    }

    // ---- Open ---------------------------------------------------------------

    /**
     * Resolve the selection and, when non-empty, open the modal move dialog. An
     * empty source set shows a status note and does not open (mirrors the predecessor's
     * {@code openMoveDialog}).
     *
     * <p>Refused, visibly, while a move is in flight: Esc may close a dialog
     * mid-move, and a fresh dialog then could only swallow its Enter on the
     * guard while the earlier move finishes underneath it.</p>
     */
    public void open() {
        if (isShowing()) {
            return; // already open
        }
        if (moveInFlight) {
            host.showStatus("A move is still in progress; try again when it finishes.");
            return;
        }
        Selection selection = host.currentSelection();
        if (selection.sources().isEmpty()) {
            host.showStatus(selection.parentExcluded()
                    ? "Only the parent folder \"..\" is selected; nothing to move."
                    : "No files selected to move.");
            return;
        }

        if (refusesArchiveSources(selection)) return;

        workingHistory = new ArrayList<>(settings.moveHistory());
        Path dir = host.currentDirectory();
        List<String> sources = selection.sources().stream().map(Path::toString).toList();

        model = new MoveDialogModel();
        MoveDialogLogic.open(model, sources, selection.parentExcluded(),
                workingHistory, dir.toString());
        model.setQuickMoveShortcutsEnabled(settings.quickMoveShortcutsEnabled());
        MoveDialogLogic.initializeMiniTree(model, loadChildren);

        dialog = dialogs.create(host.owner(), model, this);
        dialog.setHistory(workingHistory);
        dialog.showAndWait();
    }

    /**
     * Whether the move must be refused because its sources live inside an
     * archive, reporting why when it does.
     *
     * <p>A mounted archive is immutable, so there is nothing to move
     * <em>from</em> — and the move machinery works in path strings, which for an
     * archive entry name only its position inside the container. Refusing here,
     * before the dialog opens, is the honest answer; the alternative would be a
     * dialog that can only fail on submit.</p>
     */
    private boolean refusesArchiveSources(Selection selection) {
        if (selection.sources().stream().noneMatch(ArchivePaths::inArchive)) return false;
        host.showStatus("Files inside an archive are read-only — extract them first.");
        return true;
    }

    /** Whether the dialog is currently showing. */
    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    // ---- Move to Trash / Recycle Bin ---------------------------------------

    /**
     * Confirm and move the current selection to the native Trash / Recycle Bin.
     * Selection, archive refusal, handle release, async execution and post-move
     * focus deliberately follow the same path as ordinary moves.
     */
    public void moveSelectionToTrash() {
        if (isShowing() || moveInFlight) return;

        Selection selection = host.currentSelection();
        if (selection.sources().isEmpty()) {
            host.showStatus(selection.parentExcluded()
                    ? "Only the parent folder \"..\" is selected; nothing to move to "
                            + trashDestinationName() + "."
                    : "No items selected to move to " + trashDestinationName() + ".");
            return;
        }
        if (refusesArchiveSources(selection)) return;
        if (!trashSupported.getAsBoolean()) {
            host.showStatus("Move to " + trashDestinationName()
                    + " is not supported by this desktop environment.");
            return;
        }

        List<Path> moving = selection.sources();
        if (!trashConfirmation.test(moving)) return;

        Path nextFocus = host.nextFocusAfterMove(moving);
        launchGuarded("Move to " + trashDestinationName() + " failed: ", () -> {
            CompletableFuture<Void> released = host.releaseBeforeMove(moving);
            fileOps.run(() -> {
                        awaitReleased(released);
                        try (var held = ArchivePaths.releaseForMutation(moving)) {
                            return trash.apply(moving);
                        }
                    })
                    .whenComplete((result, error) -> fxThread.execute(() ->
                            finishTrash(result, error, nextFocus)));
        });
    }

    /**
     * Take the in-flight guard and run {@code launch} (handle release plus the
     * async fileOp). A synchronous throw — a host failing to release, or the
     * fileOp executor rejecting the task once {@link MediaService} is closed —
     * never reaches a finish handler, so the guard is released here and the
     * failure reported; held, it would silently swallow every later move.
     *
     * @return whether the async move launched (its finish handler now owns
     *         the guard)
     */
    private boolean launchGuarded(String failurePrefix, Runnable launch) {
        moveInFlight = true;
        try {
            launch.run();
            return true;
        } catch (RuntimeException e) {
            moveInFlight = false;
            host.showStatus(failurePrefix + message(e));
            return false;
        }
    }

    /** Platform-native noun used by menus, confirmation copy and status text. */
    static String trashDestinationName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? "Recycle Bin" : "Trash";
    }

    /** Platform-native File-menu/context-menu label. */
    static String trashActionText() {
        return "Move to " + trashDestinationName();
    }

    private boolean confirmTrash(List<Path> moving) {
        String destination = trashDestinationName();
        String subject;
        if (moving.size() == 1) {
            Path name = moving.getFirst().getFileName();
            subject = "\u201c" + (name == null ? moving.getFirst() : name) + "\u201d";
        } else {
            subject = moving.size() + " selected items";
        }

        var move = new ButtonType("Move to " + destination, ButtonBar.ButtonData.OK_DONE);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, "", move, ButtonType.CANCEL);
        alert.setTitle("Move to " + destination);
        alert.setHeaderText("Move " + subject + " to " + destination + "?");
        alert.setContentText("You can restore " + (moving.size() == 1 ? "it" : "them")
                + " from " + destination + " using your system file manager.");
        alert.initOwner(host.owner());
        return alert.showAndWait().filter(button -> button == move).isPresent();
    }

    /** Apply a native trash result back on the FX thread. */
    private void finishTrash(TrashOps.Result result, Throwable error, Path nextFocus) {
        try {
            if (error != null) {
                host.showStatus("Move to " + trashDestinationName() + " failed: "
                        + message(error));
                return;
            }

            for (Path moved : result.moved()) {
                ActionLog.get().record(ActionLogEntry.trashed(moved));
            }

            if (!result.moved().isEmpty()) {
                Path focus = result.failures().isEmpty()
                        ? nextFocus : result.failures().getFirst().path();
                host.refreshAfterMove(focus);
            }

            List<String> failures = result.failures().stream()
                    .map(failure -> displayName(failure.path()) + ": " + failure.reason())
                    .toList();
            if (failures.isEmpty()) {
                int count = result.moved().size();
                host.showStatus("Moved " + count + " item" + (count == 1 ? "" : "s")
                        + " to " + trashDestinationName() + ".");
            } else if (!result.moved().isEmpty()) {
                host.showStatus(failureList("Some items could not be moved to "
                        + trashDestinationName() + ":", failures));
            } else {
                host.showStatus(failureList("Move to " + trashDestinationName()
                        + " failed:", failures));
            }
        } finally {
            moveInFlight = false;
        }
    }

    private static String displayName(Path path) {
        Path name = path.getFileName();
        return (name == null ? path : name).toString();
    }

    // ---- Quick move (F1–F4, port of AppStore.quickMoveToHistoryIndex) --------

    /**
     * Quick-move the current selection straight to {@code moveHistory[index]}
     * without opening the dialog — the F1–F4 shortcuts. A
     * no-op (returns {@code false}, so the keystroke can pass through) unless the
     * transient quick-move toggle is on and that history slot exists.
     *
     * <p>Unlike {@link #submit()} this <em>never</em> reorders the history, so
     * F1–F4 stay pinned to the same four destinations for rapid sorting.
     * Sources already living in the target directory are skipped; when every
     * source is already there the move is reported as "Already in …".
     *
     * @param index the zero-based history slot (0 = F1, 1 = F2, 2 = F3, 3 = F4)
     * @return whether the keystroke was handled (and should be consumed)
     */
    public boolean quickMove(int index) {
        if (!settings.quickMoveShortcutsEnabled() || isShowing() || moveInFlight) {
            return false;
        }
        List<String> history = settings.moveHistory();
        if (index < 0 || index >= history.size()) {
            return false; // nothing pinned to that slot — let the key propagate
        }

        Selection selection = host.currentSelection();
        if (selection.sources().isEmpty()) {
            host.showStatus(selection.parentExcluded()
                    ? "Only the parent folder \"..\" is selected; nothing to move."
                    : "No files selected to move.");
            return true;
        }

        if (refusesArchiveSources(selection)) return true;

        String target = history.get(index);
        Path targetDir = Path.of(target);
        if (!Files.isDirectory(targetDir)) {
            host.showStatus("Quick-move target no longer exists: " + target);
            return true;
        }

        // Skip sources already in the target directory (no self-move churn).
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        List<Path> moving = new ArrayList<>();
        for (Path source : selection.sources()) {
            Path parent = source.toAbsolutePath().normalize().getParent();
            if (parent == null || !parent.equals(normalizedTarget)) {
                moving.add(source);
            }
        }
        if (moving.isEmpty()) {
            host.showStatus("Already in " + target);
            return true;
        }

        // Compute next focus BEFORE moving (off the pre-move listing), then move
        // off the FX thread and fold the outcome back in — same shape as submit,
        // minus the dialog and the history reorder.
        Path nextFocus = host.nextFocusAfterMove(moving);
        List<String> sources = moving.stream().map(Path::toString).toList();
        launchGuarded("Quick-move failed: ", () -> {
            CompletableFuture<Void> released = host.releaseBeforeMove(moving);
            fileOps.run(() -> {
                        awaitReleased(released);
                        return execute(sources, target, false, null, false);
                    })
                    .whenComplete((execution, error) -> fxThread.execute(() ->
                            finishQuickMove(execution, error, target, nextFocus)));
        });
        return true;
    }

    /**
     * Back on the FX thread: apply the quick-move outcome (refresh, focus,
     * notify). The in-flight guard is released only once all of that has run.
     */
    private void finishQuickMove(Execution execution, Throwable error,
                                 String target, Path nextFocus) {
        try {
            if (error != null) {
                host.showStatus("Quick-move failed: " + message(error));
                return;
            }
            if (execution.createError() != null) {
                host.showStatus(execution.createError());
                return;
            }
            recordActions(execution);

            List<String> failures = execution.failures();
            List<Moved> succeeded = execution.moved();
            if (!succeeded.isEmpty()) {
                host.refreshAfterMove(nextFocus);
            }
            if (failures.isEmpty()) {
                host.showStatus("Moved " + succeeded.size() + " file"
                        + (succeeded.size() == 1 ? "" : "s") + " to " + target);
            } else if (!succeeded.isEmpty()) {
                host.showStatus(failureList("Some files failed to move:", failures));
            } else {
                host.showStatus(failureList("Move failed:", failures));
            }
        } finally {
            moveInFlight = false;
        }
    }

    // ---- Navigation intents (delegate to the pure logic, then reconcile) ----

    @Override
    public void setTargetPath(String text) {
        model.setTargetPath(text);
        model.setInlineError(null);
        syncTreeToTypedPath(text);
        sync();
    }

    @Override
    public void cycleFocusZone(boolean reverse) {
        MoveDialogLogic.cycleFocusZone(model, workingHistory, reverse);
        sync();
    }

    @Override
    public void historyUp() {
        MoveDialogLogic.moveHistorySelection(model, workingHistory, -1);
        sync();
    }

    @Override
    public void historyDown() {
        MoveDialogLogic.moveHistorySelection(model, workingHistory, 1);
        sync();
    }

    @Override
    public void selectHistoryEntry(int index) {
        if (index >= 0 && index < workingHistory.size()) {
            model.setHistoryHighlightedIndex(index);
            MoveDialogLogic.setTargetPathFromTree(model, workingHistory.get(index));
        }
        sync();
    }

    @Override
    public void treeUp() {
        MoveDialogLogic.moveMiniTreeHighlight(model, -1);
        sync();
    }

    @Override
    public void treeDown() {
        MoveDialogLogic.moveMiniTreeHighlight(model, 1);
        sync();
    }

    @Override
    public void treeLeft() {
        MoveDialogLogic.miniTreeHandleLeft(model);
        sync();
    }

    @Override
    public void treeRight() {
        MoveDialogLogic.miniTreeHandleRight(model, loadChildren);
        sync();
    }

    @Override
    public void expandTreeNode(String path) {
        MoveDialogLogic.expandMiniTreeNode(model, path, loadChildren);
        sync();
    }

    @Override
    public void collapseTreeNode(String path) {
        MoveDialogLogic.collapseMiniTreeNode(model, path);
        sync();
    }

    @Override
    public void selectTreeNode(String path) {
        MoveDialogLogic.selectMiniTreeNode(model, path);
        sync();
    }

    @Override
    public void setQuickMoveShortcutsEnabled(boolean enabled) {
        // Transient but shared across all windows: survives dialog open/close and
        // is visible to the main/mosaic/viewer controllers alike (AppSettings is
        // the shared singleton), yet is never persisted to app.properties.
        settings.setQuickMoveShortcutsEnabled(enabled);
        model.setQuickMoveShortcutsEnabled(enabled);
        ActionLog.get().touchMoveTargets();
        sync();
    }

    @Override
    public void cancel() {
        MoveDialogLogic.close(model);
        if (dialog != null) {
            dialog.close();
        }
    }

    // ---- Submit (port of AppStore.submitMoveDialog) -------------------------

    @Override
    public void submit() {
        if (moveInFlight) {
            return; // reentrancy guard: Move button + field Enter both fire
        }
        moveInFlight = true;
        boolean launched = false;
        try {
            launched = doSubmit();
        } finally {
            // Synchronous abort paths release the guard now; a launched async
            // move releases it in finishSubmit when it completes.
            if (!launched) {
                moveInFlight = false;
            }
        }
    }

    /** @return whether an async move was launched (so the guard stays held). */
    private boolean doSubmit() {
        List<String> sourceFiles = model.getSourceFilePaths();
        MovePlanner.Plan plan = MovePlanner.plan(model.getTargetPath(),
                host.currentDirectory().toString(), sourceFiles);
        if (!plan.valid()) {
            setError(plan.error());
            return false;
        }
        if (plan.needsCreate() && !createConfirmation.test(plan.resolvedTarget())) {
            return false; // declined → abort silently
        }

        // Compute next focus BEFORE moving (off the pre-move listing).
        List<Path> movingPaths = sourceFiles.stream().map(Path::of).toList();
        Path nextFocus = host.nextFocusAfterMove(movingPaths);

        final boolean rename = plan.singleFileRename();
        final boolean createDir = plan.needsCreate();
        final String resolvedTarget = plan.resolvedTarget();
        final String renameTarget = plan.renameTarget();
        final String historyPath = plan.historyPath();
        final List<String> sources = List.copyOf(sourceFiles);

        model.setMoving(true);
        model.setInlineError(null);
        sync();

        // The completion is bound to this dialog session, not to the fields:
        // Esc may close the dialog mid-move, and the outcome must not land in
        // (or close) whatever the fields hold by then.
        final MoveDialogModel startedModel = model;
        final DialogView startedDialog = dialog;
        try {
            CompletableFuture<Void> released = host.releaseBeforeMove(movingPaths);
            fileOps.run(() -> {
                        awaitReleased(released);
                        return execute(sources, resolvedTarget, rename, renameTarget, createDir);
                    })
                    .whenComplete((execution, error) -> fxThread.execute(() ->
                            finishSubmit(startedModel, startedDialog, execution, error,
                                    rename, resolvedTarget, historyPath, nextFocus)));
        } catch (RuntimeException e) {
            // Never launched: no finish handler will clear the busy state, so
            // do it here (submit releases the guard) and show why.
            model.setMoving(false);
            setError("Move failed: " + message(e));
            return false;
        }
        return true;
    }

    /**
     * Off the FX thread, before any filesystem work: waits for the host's
     * handle release, failing the whole move loudly — nothing moved — when a
     * decoder has not let go in time, rather than moving (or half-moving) a
     * file that is still open.
     */
    private void awaitReleased(CompletableFuture<Void> released) {
        try {
            released.get(releaseTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Video still open: playback did not release the file"
                    + " within " + releaseTimeoutMillis + " ms; nothing was moved");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted waiting for playback to release the file; nothing was moved");
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not release the file: "
                    + message(e) + "; nothing was moved");
        }
    }

    /** Off-FX-thread move execution. */
    private Execution execute(List<String> sources, String resolvedTarget, boolean rename,
                              String renameTarget, boolean createDir) {
        // The archive-layer counterpart of Host#releaseBeforeMove: a source may
        // be a container the user has browsed into (or a directory holding
        // one), whose mounted reader keeps the file itself open. Held until the
        // move is done, so an in-flight decode cannot re-mount it meanwhile.
        try (var held = ArchivePaths.releaseForMutation(
                sources.stream().map(Path::of).toList())) {
            return executeReleased(sources, resolvedTarget, rename, renameTarget, createDir);
        }
    }

    private Execution executeReleased(List<String> sources, String resolvedTarget,
                                      boolean rename, String renameTarget, boolean createDir) {
        List<Moved> moved = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        if (createDir) {
            try {
                FileOps.createDirectoriesRecursive(Path.of(resolvedTarget));
            } catch (IOException e) {
                return new Execution(moved, failures,
                        "Failed to create directory: " + message(e));
            }
        }

        if (rename) {
            String source = sources.get(0);
            try {
                Path finalPath = FileOps.renameMove(Path.of(source), Path.of(renameTarget),
                        sidecars);
                moved.add(new Moved(Path.of(source), finalPath));
            } catch (IOException e) {
                failures.add(new File(source).getName() + ": " + message(e));
            }
        } else {
            Path targetDir = Path.of(resolvedTarget);
            for (String source : sources) {
                try {
                    Path finalPath = FileOps.moveWithAutoRename(Path.of(source), targetDir,
                            sidecars);
                    moved.add(new Moved(Path.of(source), finalPath));
                } catch (IOException e) {
                    failures.add(new File(source).getName() + ": " + message(e));
                }
            }
        }
        return new Execution(moved, failures, null);
    }

    /**
     * Back on the FX thread: apply the outcome (history, refresh, focus,
     * notify). The in-flight guard is released only once all of that has run.
     *
     * <p>Errors go inline while the dialog that started the move is still
     * showing; once it was cancelled mid-move they go through
     * {@link Host#showStatus}, so a failure is never reported only to a closed
     * dialog.</p>
     */
    private void finishSubmit(MoveDialogModel startedModel, DialogView startedDialog,
                              Execution execution, Throwable error, boolean rename,
                              String resolvedTarget, String historyPath,
                              Path nextFocus) {
        try {
            startedModel.setMoving(false);
            boolean inline = startedDialog == dialog && startedDialog.isShowing();

            if (error != null) {
                report(inline, "Move failed: " + message(error));
                return;
            }
            if (execution.createError() != null) {
                report(inline, execution.createError());
                return;
            }
            recordActions(execution);

            List<String> failures = execution.failures();
            List<Moved> succeeded = execution.moved();

            if (failures.isEmpty()) {
                // Full success: record history, close, refresh, advance focus, notify.
                recordHistory(historyPath);
                MoveDialogLogic.close(startedModel);
                startedDialog.close();
                host.refreshAfterMove(nextFocus);
                host.showStatus(rename
                        ? "Moved and renamed to " + resolvedTarget
                        : "Moved " + succeeded.size() + " file" + (succeeded.size() == 1 ? "" : "s")
                                + " to " + resolvedTarget);
            } else if (!succeeded.isEmpty()) {
                // Partial: record history, keep open with an error, refresh + focus.
                // Refresh first: it rewrites the host's status line, so a
                // non-inline report made before it would be overwritten unseen.
                recordHistory(historyPath);
                host.refreshAfterMove(nextFocus);
                report(inline, failureList("Some files failed to move:", failures));
            } else {
                // Total failure: error only, no history, no refresh.
                report(inline, failureList("Move failed:", failures));
            }
        } finally {
            moveInFlight = false;
        }
    }

    /** An outcome message: inline in the open dialog, else as a host status. */
    private void report(boolean inline, String message) {
        if (inline) {
            setError(message);
        } else {
            host.showStatus(message);
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private void recordHistory(String historyPath) {
        MoveDialogLogic.addToMoveHistory(workingHistory, historyPath, settings.moveHistoryLimit());
        settings.setMoveHistory(workingHistory);
        ActionLog.get().touchMoveTargets();
        try {
            settings.save();
        } catch (IOException e) {
            host.showStatus("Cannot save move history: " + message(e));
        }
        if (dialog != null) {
            dialog.setHistory(workingHistory);
        }
    }

    private boolean confirmCreate(String resolvedTarget) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Directory does not exist:\n" + resolvedTarget + "\n\nDo you want to create it?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Create Directory");
        alert.setHeaderText(null);
        alert.initOwner(dialog != null ? dialog.stage() : host.owner());
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void setError(String message) {
        model.setInlineError(message);
        sync();
    }

    /** Push the model (and the working history) back into the dialog. */
    private void sync() {
        if (dialog == null) {
            return;
        }
        dialog.setHistory(workingHistory);
        dialog.refresh();
    }

    private List<TreeNode> loadChildren(Path dir) {
        return FileOps.listSubdirectories(dir, host.showHiddenDirectories());
    }

    /** Reveal a typed path in the mini tree when it is an existing directory. */
    private void syncTreeToTypedPath(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String trimmed = text;
        while (trimmed.length() > 1
                && (trimmed.endsWith("/") || trimmed.endsWith(File.separator))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        Path path = Path.of(trimmed);
        if (Files.isDirectory(path)) {
            String absolute = path.toAbsolutePath().toString();
            MoveDialogLogic.expandMiniTreeToPath(model, absolute, loadChildren);
            model.setMiniTreeHighlightedPath(absolute);
        }
    }

    private static String failureList(String heading, List<String> failures) {
        StringBuilder sb = new StringBuilder(heading).append("\n");
        for (String failure : failures) {
            sb.append("\u2022 ").append(failure).append("\n");
        }
        return sb.toString().trim();
    }

    private static String message(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /**
     * Log each completed relocation into the shared session {@link ActionLog}
     * (classified move-vs-rename by whether the file left its directory). On
     * the FX thread, from both finish handlers; a total failure logs nothing.
     */
    private static void recordActions(Execution execution) {
        for (Moved moved : execution.moved()) {
            ActionLog.get().record(ActionLogEntry.moveOrRename(moved.source(), moved.finalPath()));
        }
    }

    /** One completed relocation: the source and its resolved on-disk result. */
    private record Moved(Path source, Path finalPath) {}

    /** Outcome of the off-thread {@link #execute}. */
    private record Execution(List<Moved> moved, List<String> failures, String createError) {}
}
