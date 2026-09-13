package io.github.ghosthack.mediabrowser.win;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Ensures a file-association launch ("Open with Media Browser", a second
 * double-click on an associated file) hands its path to the already-running
 * instance instead of opening a second process. Scoped narrowly to that
 * path: a bare no-argument launch never touches this class at all, so it
 * still opens an ordinary second window today, same as always — making
 * every launch single-instance would be a separate, bigger UX decision.
 *
 * <p>Mutual exclusion is a single {@link FileLock} on
 * {@code ~/.media-browser/instance.lock} (the directory {@code AppSettings}
 * already uses) — no PID files, no manual staleness detection: the OS
 * releases the lock the instant the holding process exits or dies, on every
 * platform, so there is nothing to clean up. The lock holder also binds a
 * loopback-only {@link ServerSocket} and records its port in the same file;
 * a second launch that loses the lock race reads that port, connects, and
 * writes its file path as one line, then exits without ever opening a
 * window.</p>
 */
public final class SingleInstanceGuard implements AutoCloseable {

    private static final Path LOCK_FILE =
            Path.of(System.getProperty("user.home"), ".media-browser", "instance.lock");
    private static final Duration PORT_WAIT = Duration.ofMillis(500);
    private static final Duration PORT_POLL_INTERVAL = Duration.ofMillis(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);

    /** Null in the degenerate no-op case (lock-file I/O itself failed). */
    private final FileChannel channel;
    /** Null when this process isn't the lock holder. */
    private final FileLock lock;
    /** Null when this process isn't the lock holder. */
    private final ServerSocket server;

    private final ConcurrentLinkedQueue<Path> buffered = new ConcurrentLinkedQueue<>();
    private volatile Consumer<Path> handler;

    private SingleInstanceGuard(FileChannel channel, FileLock lock, ServerSocket server) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
        if (server != null) {
            Thread thread = new Thread(this::acceptLoop, "file-assoc-instance-listener");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Called once from {@code main()}, before {@code Application.launch}.
     *
     * @return empty when {@code rawArg} was handed to an already-running
     *         instance — the caller must exit without ever launching;
     *         present when this process should proceed to launch normally,
     *         either as the genuine first instance (holding the lock) or, if
     *         forwarding failed, as an ordinary unguarded second window —
     *         a strictly better failure mode than refusing to start
     */
    public static Optional<SingleInstanceGuard> acquireOrForward(String rawArg) {
        // Resolved against *this* process's working directory before it ever
        // crosses the socket — the receiving instance has its own (probably
        // different) working directory, so a still-relative path would
        // resolve against the wrong one there.
        String absArg = Path.of(rawArg).toAbsolutePath().normalize().toString();
        try {
            Files.createDirectories(LOCK_FILE.getParent());
            FileChannel channel = FileChannel.open(LOCK_FILE,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock != null) {
                ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
                writePort(channel, server.getLocalPort());
                return Optional.of(new SingleInstanceGuard(channel, lock, server));
            }
            if (forwardToRunningInstance(channel, absArg)) {
                channel.close();
                return Optional.empty();
            }
            // Could not reach the lock holder (a frozen/debugger-paused
            // process, or a narrow acquire-then-write-port race) — proceed
            // as an ordinary second window rather than refusing to start.
            return Optional.of(new SingleInstanceGuard(channel, null, null));
        } catch (IOException e) {
            // Filesystem trouble (e.g. a read-only home) must never block
            // opening the file the user just double-clicked.
            return Optional.of(new SingleInstanceGuard(null, null, null));
        }
    }

    private static void writePort(FileChannel channel, int port) throws IOException {
        channel.truncate(0);
        channel.position(0);
        channel.write(ByteBuffer.wrap(("port=" + port + "\n").getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
    }

    private static Integer readPort(FileChannel channel) {
        try {
            long size = channel.size();
            if (size <= 0) return null;
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            channel.read(buf, 0);
            for (String line : new String(buf.array(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("port=")) {
                    return Integer.parseInt(line.substring("port=".length()).trim());
                }
            }
            return null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Polls briefly for the lock holder's port (covers the narrow acquire-then-write race), then forwards. */
    private static boolean forwardToRunningInstance(FileChannel channel, String rawArg) {
        long deadline = System.nanoTime() + PORT_WAIT.toNanos();
        Integer port = null;
        while (System.nanoTime() < deadline) {
            port = readPort(channel);
            if (port != null) break;
            try {
                Thread.sleep(PORT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (port == null) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    (int) CONNECT_TIMEOUT.toMillis());
            try (OutputStream out = socket.getOutputStream()) {
                out.write((rawArg + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void acceptLoop() {
        while (true) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return; // server closed by close(), or a genuine failure — either way, stop
            }
            try (socket; var reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    deliver(Path.of(line.trim()));
                }
            } catch (IOException ignored) {
                // A dropped connection just loses that one forwarded path.
            }
        }
    }

    private void deliver(Path path) {
        Consumer<Path> h = handler;
        if (h != null) {
            h.accept(path);
        } else {
            buffered.add(path);
        }
    }

    /**
     * Registers the callback for paths forwarded by a second launch,
     * replaying any received before this was called (a narrow startup race
     * between the listener thread and the caller wiring this up).
     */
    public void onFileReceived(Consumer<Path> handler) {
        this.handler = handler;
        Path p;
        while ((p = buffered.poll()) != null) {
            handler.accept(p);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) { }
        }
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) { }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) { }
        }
    }
}
