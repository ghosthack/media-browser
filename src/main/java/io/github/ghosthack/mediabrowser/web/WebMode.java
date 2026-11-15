package io.github.ghosthack.mediabrowser.web;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RotationStore;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Web mode as the app runs it: reads the {@code web.*} settings, starts the
 * {@link WebServer} off the FX thread when {@code web.enabled} is on, and
 * stops it with the app (also registered as a {@link MediaService} close
 * hook, so the server never outlives the service it reads from).
 *
 * <p>On/off and the port are read once, at start; the root folder, JPEG
 * quality and passkey are pushed into the running server live. A failure to
 * start (port in use, missing root) is kept as {@link #failure()} for the
 * Phone Access window and printed, never swallowed.</p>
 */
public final class WebMode implements AutoCloseable {

    public enum State { OFF, STARTING, RUNNING, FAILED }

    private final AppSettings settings;
    private final boolean enabledAtStart;
    private final int portAtStart;
    private volatile State state;
    private volatile String failure;
    private WebServer server;
    private Consumer<String> onFailure = m -> { };

    private WebMode(AppSettings settings) {
        this.settings = settings;
        this.enabledAtStart = settings.webEnabled();
        this.portAtStart = settings.webPort();
        this.state = enabledAtStart ? State.STARTING : State.OFF;
    }

    /**
     * Starts web mode per {@code settings} in the background (a no-op when it
     * is off); {@code onAdjusted} hears of each rotation made from a phone,
     * {@code onFailure} why it could not start (both on a background thread).
     */
    public static WebMode start(AppSettings settings, MediaService service, RotationStore rotations,
                                AaeStore aae, Consumer<Path> onAdjusted, Consumer<String> onFailure) {
        return start(settings, service, rotations, aae, onAdjusted, onFailure, MoveHooks.NONE);
    }

    /**
     * {@link #start(AppSettings, MediaService, RotationStore, AaeStore, Consumer, Consumer)}
     * with what a phone move asks of the desktop ({@code moveHooks}).
     */
    public static WebMode start(AppSettings settings, MediaService service, RotationStore rotations,
                                AaeStore aae, Consumer<Path> onAdjusted, Consumer<String> onFailure,
                                MoveHooks moveHooks) {
        var mode = new WebMode(settings);
        if (!mode.enabledAtStart) return mode;
        mode.onFailure = onFailure == null ? m -> { } : onFailure;
        service.addCloseHook(mode);
        CompletableFuture.runAsync(() -> mode.startNow(service, rotations, aae, onAdjusted, moveHooks),
                runnable -> Thread.ofPlatform().daemon().name("web-mode-start").start(runnable));
        return mode;
    }

    private void startNow(MediaService service, RotationStore rotations, AaeStore aae,
                          Consumer<Path> onAdjusted, MoveHooks moveHooks) {
        Path root = settings.webRoot();
        try {
            String passkey = passkey();
            var s = new WebServer(new WebServer.Options(portAtStart, null, WebServer.webDirOverride()),
                    root, passkey, settings.webJpegQuality(), service, rotations, aae,
                    new JpegEncoder(), onAdjusted);
            s.setMoveHooks(moveHooks);
            s.start();
            synchronized (this) {
                if (state != State.STARTING) {          // closed meanwhile
                    s.close();
                    return;
                }
                server = s;
                state = State.RUNNING;
            }
            System.err.println("[WebMode] serving " + root + " on port " + s.port());
        } catch (BindException e) {
            fail("Port " + portAtStart + " is already in use (" + e.getMessage()
                    + "). Pick another port in Settings ▸ Web and restart.");
        } catch (IOException | RuntimeException e) {
            fail("Cannot serve " + root + ": " + e.getMessage());
        }
    }

    private void fail(String message) {
        synchronized (this) {
            if (state != State.STARTING) return;
            failure = message;
            state = State.FAILED;
        }
        System.err.println("[WebMode] not started: " + message);
        onFailure.accept(message);
    }

    public State state() {
        return state;
    }

    /** Why web mode did not start, or null. */
    public String failure() {
        return failure;
    }

    /** The port it listens on: the running server's, else the one it was started with. */
    public synchronized int port() {
        return server != null ? server.port() : portAtStart;
    }

    /** Whether Settings now asks for something only a restart applies (on/off or port). */
    public boolean restartPending() {
        return settings.webEnabled() != enabledAtStart
                || (enabledAtStart && settings.webPort() != portAtStart);
    }

    /** The current passkey; a first-ever one is minted and saved (off this thread). */
    public String passkey() {
        boolean minted = !settings.hasWebPasskey();
        String key = settings.webPasskey();
        if (minted) saveSoon();
        return key;
    }

    /**
     * Replaces the passkey: every phone holding a cookie must enter the new
     * one. Saved in the background; applied to the running server at once.
     */
    public String rotatePasskey() {
        String key = settings.rotateWebPasskey();
        WebServer s;
        synchronized (this) {
            s = server;
        }
        if (s != null) s.setPasskey(key);
        saveSoon();
        return key;
    }

    /**
     * Pushes the live settings (root folder, JPEG quality) into the running
     * server. Returns a message when the new root cannot be served (the old
     * one stays), else null.
     */
    public String applyLiveSettings() {
        WebServer s;
        synchronized (this) {
            s = server;
        }
        if (s == null) return null;
        s.setQuality(settings.webJpegQuality());
        Path root = settings.webRoot();
        try {
            if (!root.equals(s.root())) s.setRoot(root);
            return null;
        } catch (IOException | RuntimeException e) {
            return "Web root not changed: " + root + " cannot be served (" + e.getMessage() + ")";
        }
    }

    private void saveSoon() {
        CompletableFuture.runAsync(() -> {
            try {
                settings.save();
            } catch (IOException e) {
                System.err.println("[WebMode] could not save the passkey: " + e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        WebServer s;
        synchronized (this) {
            s = server;
            server = null;
            if (state == State.STARTING || state == State.RUNNING) state = State.OFF;
        }
        if (s != null) s.close();
    }
}
