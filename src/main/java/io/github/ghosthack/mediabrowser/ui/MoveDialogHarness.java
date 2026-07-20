package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.move.FileOps;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogIntents;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogLogic;
import io.github.ghosthack.mediabrowser.media.move.MoveDialogModel;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase&nbsp;4 manual harness for {@link MoveDialog}. It renders the dialog from
 * a hand-built {@link MoveDialogModel} with the navigation intents wired
 * straight to {@link MoveDialogLogic} (so Tab cycling, history nav, the lazy
 * directory tree, typing and the focus-zone borders are all live) — but
 * <b>no moves are performed</b>: {@code submit()} just prints what it would do
 * and closes. This is the standalone proof that the view reconciles itself from
 * the model and routes keys correctly, ahead of the real controller in Phase&nbsp;5.
 *
 * <p>Run:
 * <pre>
 *   mvn -q javafx:run -Dapp.mainClass=io.github.ghosthack.mediabrowser.ui.MoveDialogHarness
 * </pre>
 * Optionally pass a starting directory and a few fake history entries as args:
 * <pre>
 *   ... -Djavafx.args="/some/dir /history/one /history/two"
 * </pre>
 */
public final class MoveDialogHarness extends Application {

    private static final MoveDialogLogic.LoadChildren LOAD_CHILDREN =
            dir -> FileOps.listSubdirectories(dir, false);

    private Path currentDir;
    private final List<String> history = new ArrayList<>();

    @Override
    public void start(Stage primary) {
        List<String> args = getParameters().getRaw();
        currentDir = args.isEmpty()
                ? Paths.get(System.getProperty("user.home"))
                : Paths.get(args.get(0)).toAbsolutePath();
        for (int i = 1; i < args.size(); i++) {
            history.add(Paths.get(args.get(i)).toAbsolutePath().toString());
        }
        if (history.isEmpty()) {
            // Seed a couple of plausible recents so the HISTORY zone is exercised.
            seedHistory();
        }

        var openButton = new Button("Open Move\u2026");
        openButton.setOnAction(e -> openDialog(primary));

        var hint = new Label("Phase 4 harness \u2014 navigation is live, no files are moved.\n"
                + "Tab/Shift+Tab cycle zones \u00b7 \u2191\u2193 history \u00b7 \u2191\u2193\u2190\u2192 tree \u00b7 Enter \u201csubmit\u201d \u00b7 Esc cancel");
        hint.setWrapText(true);

        var box = new VBox(12, hint, openButton);
        box.setPadding(new Insets(16));
        primary.setScene(new Scene(box, 460, 160));
        primary.setTitle("MoveDialog harness");
        primary.show();

        openDialog(primary);
    }

    private void openDialog(Stage owner) {
        MoveDialogModel model = new MoveDialogModel();
        List<String> sources = List.of(
                currentDir.resolve("example-photo.jpg").toString(),
                currentDir.resolve("another-clip.mp4").toString());
        MoveDialogLogic.open(model, sources, /* parentEntryExcluded */ true,
                history, currentDir.toString());
        MoveDialogLogic.initializeMiniTree(model, LOAD_CHILDREN);

        MoveDialog[] holder = new MoveDialog[1];
        MoveDialogIntents intents = new HarnessIntents(() -> holder[0], model);
        MoveDialog dialog = new MoveDialog(owner, model, intents);
        dialog.setHistory(history);
        holder[0] = dialog;
        dialog.show();
        System.out.println("[harness] dialog shown: " + sources.size()
                + " source(s), target=" + model.getTargetPath()
                + ", history=" + history.size()
                + ", treeRoots=" + model.getMiniTreeNodes().size());
    }

    private void seedHistory() {
        File[] roots = File.listRoots();
        if (roots == null) {
            return;
        }
        for (File root : roots) {
            for (var child : FileOps.listSubdirectories(root.toPath(), false)) {
                history.add(child.getPath());
                if (history.size() >= 3) {
                    return;
                }
            }
        }
    }

    /** Navigation wired to the pure logic; submit/cancel just close the dialog. */
    private final class HarnessIntents implements MoveDialogIntents {
        private final java.util.function.Supplier<MoveDialog> dialog;
        private final MoveDialogModel model;

        HarnessIntents(java.util.function.Supplier<MoveDialog> dialog, MoveDialogModel model) {
            this.dialog = dialog;
            this.model = model;
        }

        private void done() {
            dialog.get().setHistory(history);
            dialog.get().refresh();
        }

        @Override
        public void setTargetPath(String text) {
            model.setTargetPath(text);
            model.setInlineError(null);
            syncTreeToTypedPath(text);
            done();
        }

        @Override
        public void cycleFocusZone(boolean reverse) {
            MoveDialogLogic.cycleFocusZone(model, history, reverse);
            done();
        }

        @Override
        public void historyUp() {
            MoveDialogLogic.moveHistorySelection(model, history, -1);
            done();
        }

        @Override
        public void historyDown() {
            MoveDialogLogic.moveHistorySelection(model, history, 1);
            done();
        }

        @Override
        public void selectHistoryEntry(int index) {
            if (index >= 0 && index < history.size()) {
                model.setHistoryHighlightedIndex(index);
                MoveDialogLogic.setTargetPathFromTree(model, history.get(index));
            }
            done();
        }

        @Override
        public void treeUp() {
            MoveDialogLogic.moveMiniTreeHighlight(model, -1);
            done();
        }

        @Override
        public void treeDown() {
            MoveDialogLogic.moveMiniTreeHighlight(model, 1);
            done();
        }

        @Override
        public void treeLeft() {
            MoveDialogLogic.miniTreeHandleLeft(model);
            done();
        }

        @Override
        public void treeRight() {
            MoveDialogLogic.miniTreeHandleRight(model, LOAD_CHILDREN);
            done();
        }

        @Override
        public void expandTreeNode(String path) {
            MoveDialogLogic.expandMiniTreeNode(model, path, LOAD_CHILDREN);
            done();
        }

        @Override
        public void collapseTreeNode(String path) {
            MoveDialogLogic.collapseMiniTreeNode(model, path);
            done();
        }

        @Override
        public void selectTreeNode(String path) {
            MoveDialogLogic.selectMiniTreeNode(model, path);
            done();
        }

        @Override
        public void setQuickMoveShortcutsEnabled(boolean enabled) {
            model.setQuickMoveShortcutsEnabled(enabled);
            done();
        }

        @Override
        public void submit() {
            System.out.println("[harness] submit \u2014 would move "
                    + model.getSourceFilePaths().size() + " item(s) to "
                    + model.getTargetPath() + " (no-op in Phase 4)");
            dialog.get().close();
        }

        @Override
        public void cancel() {
            MoveDialogLogic.close(model);
            dialog.get().close();
        }

        /** Best-effort tree reveal when the typed path is an existing directory. */
        private void syncTreeToTypedPath(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            String trimmed = text;
            while (trimmed.length() > 1
                    && (trimmed.endsWith("/") || trimmed.endsWith(File.separator))) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            Path p = Paths.get(trimmed);
            if (Files.isDirectory(p)) {
                String abs = p.toAbsolutePath().toString();
                MoveDialogLogic.expandMiniTreeToPath(model, abs, LOAD_CHILDREN);
                model.setMiniTreeHighlightedPath(abs);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
