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
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.regex.Pattern;
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
 * loopback-only {@link ServerSocket} and records its port, plus a random
 * per-run token, in the same file; a second launch that loses the lock race
 * reads both, connects, sends the token and its file path, and exits without
 * ever opening a window once the holder acknowledges the path.</p>
 *
 * <p>The lock covers one byte at {@link #LOCK_OFFSET}, well past the few
 * bytes of port/token text, never the text itself: Windows byte-range locks
 * are mandatory, so a whole-file lock makes the second launch's read of the
 * port fail outright ("another process has locked a portion of the file"),
 * and every forwarded double-click would silently open a second window.</p>
 *
 * <p>The token keeps the loopback port from being a way for any local
 * process to make this instance open an arbitrary path: only a process that
 * can read the lock file can forward. On Windows the user profile's ACL
 * keeps other users out; on POSIX file systems the file is made owner-only.
 * Without the holder's {@code ok} acknowledgement — a wrong token, a path
 * that will not parse, a handler that throws, a holder shutting down — the
 * second launch opens its own window instead of exiting, so a forwarded file
 * is never silently dropped.</p>
 *
 * <p>The file outlives its holder (nothing clears it when a process dies),
 * so a second launch that loses the lock to a holder still starting up can
 * read the previous run's endpoint: it keeps re-reading and retrying until
 * {@link #FORWARD_WAIT} runs out, which is what lets several files opened
 * together from Explorer all land in the one instance.</p>
 */
public final class SingleInstanceGuard implements AutoCloseable {

    /** Where the one-byte lock sits; Windows locks a byte past EOF without extending the file. */
    static final long LOCK_OFFSET = 1L << 20;

    /** How long a second launch keeps trying to reach a holder that may still be starting up. */
    static final Duration FORWARD_WAIT = Duration.ofSeconds(2);
    private static final Duration PORT_POLL_INTERVAL = Duration.ofMillis(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    /** How long a second launch waits for the holder's acknowledgement before opening its own window. */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(2);
    /** How long the holder waits for a connected client's request before dropping it. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    static final String ACK = "ok";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    /** Null in the degenerate no-op case (lock-file I/O itself failed). */
    private final FileChannel channel;
    /** Null when this process isn't the lock holder. */
    private final FileLock lock;
    /** Null when this process isn't the lock holder. */
    private final ServerSocket server;
    /** Null when this process isn't the lock holder. */
    private final String token;

    /** Guarded by {@code this}, with {@link #handler}, so a path is either handed over or replayed, never both or neither. */
    private final ArrayDeque<Path> buffered = new ArrayDeque<>();
    private Consumer<Path> handler;
    private volatile boolean closed;

    private SingleInstanceGuard(FileChannel channel, FileLock lock, ServerSocket server, String token) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
        this.token = token;
        if (server != null) {
            Thread thread = new Thread(this::acceptLoop, "file-assoc-instance-listener");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Called once from {@code main()}, before {@code Application.launch}.
     *
     * @param appDir the application directory that holds {@code instance.lock}
     *        ({@code AppSettings.appDir()}); passed in so this package stays
     *        free of app classes
     * @return empty when {@code rawArg} was handed to an already-running
     *         instance and it acknowledged it — the caller must exit without
     *         ever launching; present when this process should proceed to
     *         launch normally, either as the genuine first instance (holding
     *         the lock) or, if forwarding failed, as an ordinary unguarded
     *         second window — a strictly better failure mode than refusing
     *         to start
     */
    public static Optional<SingleInstanceGuard> acquireOrForward(Path appDir, String rawArg) {
        // Resolved against *this* process's working directory before it ever
        // crosses the socket — the receiving instance has its own (probably
        // different) working directory, so a still-relative path would
        // resolve against the wrong one there.
        String absArg = Path.of(rawArg).toAbsolutePath().normalize().toString();
        FileChannel channel = null;
        try {
            Path lockFile = appDir.resolve("instance.lock");
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = tryLock(channel);
            if (lock != null) {
                ownerOnly(lockFile);
                String token = HexFormat.of().formatHex(randomBytes());
                ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                try {
                    writeEndpoint(channel, server.getLocalPort(), token);
                } catch (IOException e) {
                    server.close();
                    throw e;
                }
                return Optional.of(new SingleInstanceGuard(channel, lock, server, token));
            }
            if (forwardToRunningInstance(channel, absArg)) {
                channel.close();
                return Optional.empty();
            }
            // Could not hand the file over (a frozen/debugger-paused holder,
            // a narrow acquire-then-write race, a holder that refused it) —
            // proceed as an ordinary second window rather than refusing to
            // start or dropping the file.
            System.err.println("[SingleInstanceGuard] could not forward " + absArg
                    + " to the running instance; opening a new window");
            return Optional.of(new SingleInstanceGuard(channel, null, null, null));
        } catch (IOException e) {
            // Filesystem trouble (e.g. a read-only home) must never block
            // opening the file the user just double-clicked.
            System.err.println("[SingleInstanceGuard] instance lock unavailable (" + e
                    + "); opening a new window");
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) { }
            }
            return Optional.of(new SingleInstanceGuard(null, null, null, null));
        }
    }

    /** Owner read/write only where the file system has POSIX permissions; Windows relies on the profile ACL. */
    private static void ownerOnly(Path lockFile) throws IOException {
        if (lockFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** The lock, or null when another holder has it — another JVM, or another channel in this one. */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock(LOCK_OFFSET, 1, false);
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static void writeEndpoint(FileChannel channel, int port, String token) throws IOException {
        channel.truncate(0);
        channel.write(ByteBuffer.wrap(("port=" + port + "\ntoken=" + token + "\n")
                .getBytes(StandardCharsets.UTF_8)), 0);
        channel.force(true);
    }

    /** The holder's advertised port and token, read together. */
    record Endpoint(int port, String token) {}

    /**
     * Null until the holder has written both its port and a whole token: the
     * text must end in a newline, so a read torn by a concurrent write never
     * yields a truncated token.
     */
    static Endpoint readEndpoint(FileChannel channel) {
        try {
            long size = channel.size();
            if (size <= 0 || size > 4096) return null;
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            channel.read(buf, 0);
            String text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
            if (!text.endsWith("\n")) return null;
            Integer port = null;
            String token = null;
            for (String line : text.split("\n")) {
                if (line.startsWith("port=")) {
                    port = Integer.parseInt(line.substring("port=".length()).trim());
                } else if (line.startsWith("token=")) {
                    token = line.substring("token=".length()).trim();
                }
            }
            return port == null || token == null || !TOKEN.matcher(token).matches() ? null : new Endpoint(port, token);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * Re-reads the endpoint and retries until the holder acknowledges or
     * {@link #FORWARD_WAIT} runs out: the file may still hold a dead run's
     * endpoint (or none yet) while a just-started holder binds and writes.
     */
    private static boolean forwardToRunningInstance(FileChannel channel, String absArg) {
        long deadline = System.nanoTime() + FORWARD_WAIT.toNanos();
        while (true) {
            Endpoint endpoint = readEndpoint(channel);
            if (endpoint != null && send(endpoint, absArg)) return true;
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(PORT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Sends {@code token} and {@code path}; true only once the holder acknowledges it took the path. */
    static boolean send(Endpoint endpoint, String path) {
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) return false; // the protocol is line-based
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()),
                    (int) CONNECT_TIMEOUT.toMillis());
            socket.setSoTimeout((int) ACK_TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write((endpoint.token() + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return ACK.equals(reader.readLine());
        } catch (IOException e) {
            return false;
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (server.isClosed()) return; // close()
                System.err.println("[SingleInstanceGuard] accept failed: " + e);
                try {
                    Thread.sleep(50); // a persistent failure must not spin
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            // One thread per request, so a client that connects and never
            // writes holds up only itself, not the next double-click.
            Thread.ofVirtual().name("file-assoc-instance-request").start(() -> serve(socket));
        }
    }

    /** Longest request line accepted: above any path Windows can express (32,767 chars). */
    static final int MAX_REQUEST_LINE = 40_000;

    /**
     * One line of at most {@link #MAX_REQUEST_LINE} characters, without its
     * terminator; {@code null} at end of stream. A longer line, or one still
     * arriving after {@code deadlineNanos} (the socket timeout only bounds a
     * single read, not a slow drip), is an {@link IOException}.
     */
    static String readBoundedLine(java.io.Reader in, long deadlineNanos) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') line.setLength(end - 1);
                return line.toString();
            }
            if (line.length() >= MAX_REQUEST_LINE) {
                throw new IOException("request line longer than " + MAX_REQUEST_LINE + " characters");
            }
            if (System.nanoTime() - deadlineNanos > 0) {
                throw new IOException("request not complete within its deadline");
            }
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout((int) REQUEST_TIMEOUT.toMillis());
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            // Bounded reads: the token has not been checked yet, so any local
            // process could otherwise feed an endless line into this heap.
            long deadline = System.nanoTime() + 2 * REQUEST_TIMEOUT.toNanos();
            String presented = readBoundedLine(reader, deadline);
            String line = readBoundedLine(reader, deadline);
            if (presented == null || !MessageDigest.isEqual(
                    presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                System.err.println("[SingleInstanceGuard] rejected a forward without this instance's token");
                return;
            }
            if (line == null || line.isEmpty()) return;
            // A holder shutting down would queue the path behind its own
            // exit; leave it to the sender's own window instead.
            if (closed || !deliver(Path.of(line))) return;
            socket.getOutputStream().write((ACK + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (IOException | RuntimeException e) {
            // No acknowledgement goes back, so the sender opens the file in
            // its own window instead; this listener keeps serving.
            System.err.println("[SingleInstanceGuard] forwarded path not taken: " + e);
        }
    }

    /** False once the guard is closed, so the path is not acknowledged. */
    private synchronized boolean deliver(Path path) {
        if (closed) return false;
        if (handler != null) {
            handler.accept(path);
        } else {
            buffered.add(path);
        }
        return true;
    }

    /**
     * Registers the callback for paths forwarded by a second launch,
     * replaying any received before this was called (a narrow startup race
     * between the listener thread and the caller wiring this up).
     */
    public synchronized void onFileReceived(Consumer<Path> handler) {
        this.handler = handler;
        Path p;
        while ((p = buffered.poll()) != null) {
            handler.accept(p);
        }
    }

    /** Whether this process holds the instance lock (and so receives forwarded paths). */
    boolean isHolder() {
        return lock != null;
    }

    /**
     * Stops taking forwarded paths (a request in flight is not acknowledged,
     * so its sender opens its own window), clears the advertised endpoint,
     * and releases the lock. Call it first when shutting down.
     */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        if (lock != null) {
            try {
                channel.truncate(0);
            } catch (IOException ignored) { }
        }
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
