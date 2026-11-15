package io.github.ghosthack.mediabrowser.web;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.DetectionMode;
import io.github.ghosthack.mediabrowser.media.MediaBackend;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RotationStore;

import io.github.ghosthack.mediabrowser.media.move.ActionLogEntry;
import io.github.ghosthack.mediabrowser.media.move.ActionLogFile;
import io.github.ghosthack.mediabrowser.ui.ActionLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Web mode without the desktop UI, for working on the page and for end-to-end
 * checks from a shell: serves {@code [root]} (default: the {@code web.root}
 * setting) on {@code --port N} (default: {@code web.port}) with the settings'
 * media engine and passkey, prints the phone link, and runs until killed.
 *
 * <pre>
 * java -cp target/classes:$(cat cp.txt) \
 *     -Dmediabrowser.settings.dir=/tmp/mb-web \
 *     io.github.ghosthack.mediabrowser.web.WebModeMain /some/photos --port 7394
 * </pre>
 *
 * Pair with {@code MEDIABROWSER_WEB_DIR=src/main/resources/io/github/ghosthack/mediabrowser/web/page}
 * to edit the page live.
 */
public final class WebModeMain {

    private WebModeMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        AppSettings settings = AppSettings.load();
        Path root = settings.webRoot();
        int port = settings.webPort();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else {
                root = Path.of(args[i]).toAbsolutePath().normalize();
            }
        }
        var backend = MediaBackend.fromSettings(System.getProperty("media.backend", settings.mediaBackend()));
        var service = new MediaService(backend.label(), backend.create(),
                settings.thumbnailMemoryBudgetBytes());
        service.setDetectionMode(DetectionMode.fromSettings(settings.detectionMode()));
        service.setListingShowHidden(settings.listingShowHiddenFiles());
        service.setListingIgnoreJunk(settings.listingIgnoreJunkFiles());
        service.setListingHideEmptyFiles(settings.listingHideEmptyFiles());
        service.setListingHideEmptyFolders(settings.listingHideEmptyFolders());
        var aae = new AaeStore();
        service.setAaeStore(aae);
        boolean minted = !settings.hasWebPasskey();
        String passkey = settings.webPasskey();
        if (minted) settings.save();
        var server = new WebServer(new WebServer.Options(port, null, WebServer.webDirOverride()),
                root, passkey, settings.webJpegQuality(), service,
                new RotationStore(settings.adjustmentsVolatileMaxDirs()), aae, new JpegEncoder(),
                p -> System.out.println("rotated " + p));
        // No desktop to release or re-list; moves still go to the action log
        // file (when Settings turn it on), exactly as the app writes it.
        ActionLog.get().attachFile(settings, new ActionLogFile(AppSettings.actionLogFile()));
        server.setMoveHooks(new MoveHooks() {
            @Override public void persist(ActionLogEntry entry) { ActionLog.get().persist(entry); }
            @Override public CompletableFuture<Void> releaseBeforeMove(List<Path> moving) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public void afterMove(List<ActionLogEntry> entries, List<Path> moved,
                                            Set<Path> affectedDirs) {
                entries.forEach(e -> System.out.println(e.summary()));
            }
        });
        server.start();
        service.addCloseHook(server);
        var done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.close();
            done.countDown();
        }));
        System.out.println("engine:  " + backend.label());
        System.out.println("root:    " + server.root());
        System.out.println("passkey: " + passkey);
        for (var c : WebAddresses.discover()) {
            System.out.println("link:    " + WebAddresses.phoneLink(c.address(), server.port(), passkey)
                    + "  (" + c.interfaceName() + ")");
        }
        System.out.println("local:   " + WebAddresses.phoneLink("127.0.0.1", server.port(), passkey));
        done.await();
    }
}
