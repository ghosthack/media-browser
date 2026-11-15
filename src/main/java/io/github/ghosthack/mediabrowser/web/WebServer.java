package io.github.ghosthack.mediabrowser.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ghosthack.mediabrowser.media.AaeSidecar;
import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.Adjustments;
import io.github.ghosthack.mediabrowser.media.DirEntry;
import io.github.ghosthack.mediabrowser.media.FolderVerdict;
import io.github.ghosthack.mediabrowser.media.MediaFailures;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.Thumbnails;
import io.github.ghosthack.mediabrowser.media.VisualResult;
import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;
import io.github.ghosthack.mediabrowser.media.color.ColorPolicy;
import io.github.ghosthack.mediabrowser.media.move.SidecarCarrier;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Web mode's server: the phone page and its JSON/image API over the desktop's
 * own {@link MediaService}, on the JDK's {@code com.sun.net.httpserver}.
 * docs/web-mode.md is the contract; in brief:
 *
 * <ul>
 *   <li>{@code GET /} and the page's files, from the jar or from the folder
 *       {@code MEDIABROWSER_WEB_DIR} / {@code -Dmediabrowser.web.dir} names
 *       (read on every request, so editing the page needs no rebuild);</li>
 *   <li>{@code POST /api/login} trades the passkey for an HttpOnly,
 *       SameSite=Strict cookie; every other {@code /api/} request without a
 *       valid cookie is answered 401;</li>
 *   <li>{@code GET /api/list?p=}, {@code GET /api/tile?p=&s=&v=},
 *       {@code GET /api/full?p=&e=&v=}, {@code POST /api/rotate};</li>
 *   <li>{@code POST /api/move} and {@code POST /api/undo} ({@link WebMoves}),
 *       through the desktop's move machinery and the {@link MoveHooks}.</li>
 * </ul>
 *
 * <p>Items are addressed by their {@link ArchivePaths} locator, fenced to the
 * web root by {@link WebPaths}, and only folders, browsable archives and media
 * the desktop's own listing shows are served. Original files never leave the
 * machine: tiles and full views are re-encoded by the {@link WebEncoder}.
 * Requests run on a small fixed pool with a bounded queue; decodes run on the
 * media service's own executors, never the FX thread.</p>
 */
public final class WebServer implements AutoCloseable {

    static final String COOKIE = "mb_web";
    static final String WEB_DIR_ENV = "MEDIABROWSER_WEB_DIR";
    static final String WEB_DIR_PROPERTY = "mediabrowser.web.dir";
    static final String PAGE_RESOURCES = "/io/github/ghosthack/mediabrowser/web/page/";
    /** Tile edges served; a request snaps up to the next one, and anything larger to the last. */
    static final int[] TILE_SIZES = {96, 128, 192, 256, 384, 512, 768};
    static final int MIN_FULL_EDGE = 256;
    static final int MAX_FULL_EDGE = 2560;
    static final int FULL_EDGE_STEP = 128;
    static final int THREADS = 8;
    static final int QUEUE = 128;
    static final int MAX_BODY = 8 * 1024;
    /** A move's form names every item in the bag. */
    static final int MAX_MOVE_BODY = 2 * 1024 * 1024;
    static final long CACHE_BYTES = 96L * 1024 * 1024;
    static final Duration DECODE_TIMEOUT = Duration.ofSeconds(60);
    /** Full-size decodes at once: each holds a whole decoded frame. */
    static final int FULL_DECODES = 2;
    /** One year, in seconds: a tile URL carries its version, so it never changes. */
    private static final String IMMUTABLE = "private, max-age=31536000, immutable";
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "webmanifest", "application/manifest+json");
    static final String CSP = "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; "
            + "script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; "
            + "frame-ancestors 'none'; form-action 'self'";

    /**
     * @param port   TCP port; 0 picks a free one (tests)
     * @param bind   the address to listen on, or null for every interface
     * @param webDir where the page's files are read from, or null for the jar
     */
    public record Options(int port, InetAddress bind, Path webDir) {}

    private final Options options;
    private final MediaService service;
    private final RotationStore rotations;
    private final AaeStore aae;
    private final WebEncoder encoder;
    private final Consumer<Path> onAdjusted;
    private final LoginThrottle throttle;
    private final EncodedCache cache = new EncodedCache(CACHE_BYTES);
    private final Semaphore fullDecodes = new Semaphore(FULL_DECODES);
    /** Media kinds of entries a listing showed, by locator: what the image endpoints may serve. */
    private final ConcurrentHashMap<String, MediaKind> mediaKinds = new ConcurrentHashMap<>();
    private final WebMoves moves;

    private volatile WebPaths paths;
    private volatile int quality;
    private volatile String passkey;
    private volatile String cookieToken;

    private HttpServer server;
    private ThreadPoolExecutor executor;

    /**
     * @param root       the folder the page may browse (must exist)
     * @param passkey    the passkey a phone trades for its cookie
     * @param quality    JPEG quality 1..100
     * @param aae        the shared AAE store, or null for none
     * @param onAdjusted told (on a server thread) after a rotation was persisted
     * @throws IOException when {@code root} is not an existing folder
     */
    public WebServer(Options options, Path root, String passkey, int quality,
                     MediaService service, RotationStore rotations, AaeStore aae,
                     WebEncoder encoder, Consumer<Path> onAdjusted) throws IOException {
        this(options, root, passkey, quality, service, rotations, aae, encoder, onAdjusted,
                new LoginThrottle());
    }

    WebServer(Options options, Path root, String passkey, int quality,
              MediaService service, RotationStore rotations, AaeStore aae,
              WebEncoder encoder, Consumer<Path> onAdjusted, LoginThrottle throttle) throws IOException {
        this.options = Objects.requireNonNull(options, "options");
        this.service = Objects.requireNonNull(service, "service");
        this.rotations = Objects.requireNonNull(rotations, "rotations");
        this.aae = aae;
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.onAdjusted = onAdjusted == null ? p -> { } : onAdjusted;
        this.throttle = throttle;
        this.paths = new WebPaths(root);
        // The desktop's own carrier (.picasa.ini entry, .AAE with its refusal
        // rule) over the shared stores; a server without an AAE store still
        // carries the .AAE files on disk.
        this.moves = new WebMoves(service, SidecarCarrier.of(rotations, aae == null ? new AaeStore() : aae),
                this::requireMedia);
        setQuality(quality);
        setPasskey(passkey);
    }

    /** The folder {@code MEDIABROWSER_WEB_DIR} or {@code -Dmediabrowser.web.dir} names, if any. */
    public static Path webDirOverride() {
        String dir = System.getProperty(WEB_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) dir = System.getenv(WEB_DIR_ENV);
        return dir == null || dir.isBlank() ? null : Path.of(dir.trim());
    }

    /**
     * What a phone move asks of the desktop (release before, log and re-list
     * after); {@link MoveHooks#NONE} until set.
     */
    public void setMoveHooks(MoveHooks hooks) {
        moves.setHooks(hooks);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        if (server != null) throw new IllegalStateException("already started");
        InetSocketAddress address = options.bind() == null
                ? new InetSocketAddress(options.port())
                : new InetSocketAddress(options.bind(), options.port());
        HttpServer s = HttpServer.create(address, 0);
        var threads = new AtomicInteger();
        executor = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE), r -> {
                    Thread t = new Thread(r, "web-mode-" + threads.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(true);
        s.setExecutor(executor);
        s.createContext("/", this::handle);
        s.start();
        server = s;
    }

    /** The port listened on, or -1 when not started. */
    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public synchronized boolean running() {
        return server != null;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        cache.clear();
        mediaKinds.clear();
        moves.clear();
    }

    // ------------------------------------------------------------------ live settings

    /** Serves {@code root} from now on. @throws IOException when it is not an existing folder */
    public void setRoot(Path root) throws IOException {
        paths = new WebPaths(root);
        mediaKinds.clear();
    }

    public Path root() {
        return paths.root();
    }

    public void setQuality(int quality) {
        this.quality = Math.max(1, Math.min(100, quality));
    }

    /** Replaces the passkey; every cookie issued for the old one stops working at once. */
    public void setPasskey(String passkey) {
        if (Passkeys.normalize(passkey).isEmpty()) throw new IllegalArgumentException("empty passkey");
        this.passkey = passkey;
        this.cookieToken = Passkeys.cookieToken(passkey);
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange ex) {
        try (ex) {
            securityHeaders(ex);
            route(ex);
        } catch (IOException e) {
            // the client went away mid-response: nothing to tell it
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if (!path.startsWith("/api/")) {
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    throw new WebException(405, "method", "method not allowed");
                }
                serveFile(ex, path);
                return;
            }
            if (method.equals("POST") && !sameOrigin(ex)) {
                throw new WebException(403, "cross-site", "request from another site refused");
            }
            if (path.equals("/api/login")) {
                requireMethod(method, "POST");
                login(ex);
                return;
            }
            if (path.equals("/api/logout")) {
                requireMethod(method, "POST");
                ex.getResponseHeaders().add("Set-Cookie", COOKIE
                        + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                sendJson(ex, 200, ok());
                return;
            }
            if (!authorized(ex)) {
                throw new WebException(401, "auth",
                        "Enter the passkey shown in Media Browser's Phone Access window");
            }
            switch (path) {
                case "/api/status" -> {
                    requireMethod(method, "GET");
                    status(ex);
                }
                case "/api/list" -> {
                    requireMethod(method, "GET");
                    list(ex);
                }
                case "/api/folder" -> {
                    requireMethod(method, "GET");
                    folder(ex);
                }
                case "/api/tile" -> {
                    requireMethod(method, "GET", "HEAD");
                    tile(ex);
                }
                case "/api/full" -> {
                    requireMethod(method, "GET", "HEAD");
                    full(ex);
                }
                case "/api/rotate" -> {
                    requireMethod(method, "POST");
                    rotate(ex);
                }
                case "/api/move" -> {
                    requireMethod(method, "POST");
                    Map<String, List<String>> form = formMulti(ex);
                    sendJson(ex, 200, moves.move(paths, first(form, "t"),
                            form.getOrDefault("p", List.of())));
                }
                case "/api/undo" -> {
                    requireMethod(method, "POST");
                    sendJson(ex, 200, moves.undo(paths, first(formMulti(ex), "id")));
                }
                default -> throw WebException.notFound("no such endpoint");
            }
        } catch (WebException e) {
            sendError(ex, e.status, e.code, e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("[WebServer] " + method + " " + path + " failed: " + e);
            sendError(ex, 500, "internal", message(e));
        }
    }

    private static void requireMethod(String method, String... allowed) throws WebException {
        for (String a : allowed) {
            if (a.equals(method)) return;
        }
        throw new WebException(405, "method", "method not allowed");
    }

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", CSP);
        h.set("Cross-Origin-Resource-Policy", "same-origin");
    }

    // ------------------------------------------------------------------ auth

    private void login(HttpExchange ex) throws IOException, WebException {
        String address = ex.getRemoteAddress().getAddress().getHostAddress();
        Duration wait = throttle.retryAfter(address);
        if (!wait.isZero()) {
            long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
            ex.getResponseHeaders().set("Retry-After", Long.toString(seconds));
            throw new WebException(429, "throttled",
                    "Too many wrong passkeys. Try again in " + seconds + " s.");
        }
        String given = form(ex).getOrDefault("passkey", "");
        String current = passkey;
        if (!Passkeys.matches(current, given)) {
            throttle.failed(address);
            throw new WebException(401, "wrong-passkey", "That passkey is not the current one");
        }
        throttle.succeeded(address);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + Passkeys.cookieToken(current)
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000");
        sendJson(ex, 200, ok());
    }

    private boolean authorized(HttpExchange ex) {
        byte[] expected = cookieToken.getBytes(StandardCharsets.US_ASCII);
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=") && MessageDigest.isEqual(expected,
                        p.substring(COOKIE.length() + 1).getBytes(StandardCharsets.US_ASCII))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A POST without an Origin (not from a browser page) or from a page on this server. */
    static boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String host = ex.getRequestHeaders().getFirst("Host");
        try {
            String authority = URI.create(origin).getRawAuthority();
            return host != null && authority != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ API

    private void status(HttpExchange ex) throws IOException {
        Path root = paths.root();
        var out = ok();
        out.put("app", "Media Browser");
        out.put("root", WebPaths.locator(root));
        out.put("rootName", displayName(root, root));
        out.put("quality", quality);
        sendJson(ex, 200, out);
    }

    private void list(HttpExchange ex) throws IOException, WebException {
        WebPaths fence = paths;
        Path dir = fence.resolve(query(ex, "p"));
        if (!ArchivePaths.isDirectory(dir)) {
            if (Files.isRegularFile(dir) && ArchivePaths.isArchiveFile(dir)) {
                dir = await(service.enterArchive(dir));
            } else {
                throw new WebException(404, "not-folder", "not a folder");
            }
        }
        List<DirEntry> entries = await(service.listEntries(dir));
        var folders = new ArrayList<Map<String, Object>>();
        var media = new ArrayList<Map<String, Object>>();
        boolean inArchive = ArchivePaths.inArchive(dir);
        for (DirEntry e : entries) {
            if (e.type() == DirEntry.Type.PARENT || e.type() == DirEntry.Type.OTHER) continue;
            if (!inArchive && escapes(fence, e.path())) continue;
            String locator = WebPaths.locator(e.path());
            if (e.type() == DirEntry.Type.MEDIA) {
                rememberKind(locator, e.mediaKind());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("n", e.displayName());
                m.put("p", locator);
                m.put("k", e.mediaKind().name().toLowerCase(java.util.Locale.ROOT));
                m.put("v", version(e.path(), adjustments(e.path())));
                service.corruption(e.path()).ifPresent(c -> m.put("c", c));
                media.add(m);
            } else {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("n", e.displayName());
                f.put("p", locator);
                f.put("t", e.type() == DirEntry.Type.ARCHIVE ? "archive" : "dir");
                folders.add(f);
            }
        }
        var out = ok();
        out.put("path", WebPaths.locator(dir));
        out.put("name", displayName(dir, fence.root()));
        out.put("readOnly", inArchive);
        out.put("crumbs", crumbs(dir, fence.root()));
        out.put("folders", folders);
        out.put("media", media);
        sendJson(ex, 200, out);
    }

    /** Whether a listed plain path is a symlink leading outside the root. */
    private static boolean escapes(WebPaths fence, Path path) {
        if (!Files.isSymbolicLink(path)) return false;
        try {
            return !fence.contains(path.toRealPath());
        } catch (IOException e) {
            return true;    // a dangling link: nothing to serve either
        }
    }

    private List<Map<String, Object>> crumbs(Path dir, Path root) {
        var out = new ArrayList<Map<String, Object>>();
        Path at = dir;
        for (int depth = 0; at != null && depth < 256; depth++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("n", displayName(at, root));
            c.put("p", WebPaths.locator(at));
            out.addFirst(c);
            if (at.equals(root)) break;
            at = ArchivePaths.parentOf(at);
        }
        return out;
    }

    private static String displayName(Path dir, Path root) {
        if (ArchivePaths.isArchiveRoot(dir)) return ArchivePaths.displayName(dir);
        Path name = dir.getFileName();
        return name == null ? dir.toString() : name.toString();
    }

    /**
     * A folder tile's collage: the first {@code n}×{@code n} visual media
     * directly inside it, chosen exactly as the desktop mosaic's folder
     * preview ({@link io.github.ghosthack.mediabrowser.media.MediaService#folderPreview}).
     * Each child comes with its version, so the page fetches it through
     * {@code /api/tile} like any media tile. Archives get no collage (that
     * would mount every archive in the folder just to draw it).
     */
    private void folder(HttpExchange ex) throws IOException, WebException {
        WebPaths fence = paths;
        Path dir = fence.resolve(query(ex, "p"));
        if (!ArchivePaths.isDirectory(dir)) throw new WebException(404, "not-folder", "not a folder");
        int n = Math.max(1, Math.min(4, intQuery(ex, "n", 2)));
        int want = n * n;
        var preview = await(service.folderPreview(dir, want));
        boolean inArchive = ArchivePaths.inArchive(dir);
        var items = new ArrayList<Map<String, Object>>();
        addPreviews(items, preview.previews(), fence, inArchive, want);
        // Too few pictures of its own: fill from its subfolders, breadth-first
        // (nearest first, in listing order) down to PREVIEW_DEPTH levels and at
        // most PREVIEW_FOLDERS folders, so a folder of dated folders of
        // folders still shows photos without walking a whole tree. An
        // unreadable folder (e.g. the macOS-protected Photos Library) keeps
        // its plain icon; opening it shows the real error.
        if (items.size() < want && preview.verdict() != FolderVerdict.UNREADABLE) {
            var queue = new java.util.ArrayDeque<Path>();
            var depths = new java.util.HashMap<Path, Integer>();
            queue.add(dir);
            depths.put(dir, 0);
            int scanned = 0;
            while (!queue.isEmpty() && items.size() < want && scanned < PREVIEW_FOLDERS) {
                Path parent = queue.poll();
                int depth = depths.get(parent);
                if (depth >= PREVIEW_DEPTH) continue;
                List<DirEntry> children;
                try {
                    children = await(service.listEntries(parent));
                } catch (WebException e) {
                    if (parent.equals(dir)) throw e;
                    continue;          // an unreadable subfolder: skip it, keep looking
                }
                for (DirEntry e : children) {
                    if (items.size() >= want || scanned >= PREVIEW_FOLDERS) break;
                    if (e.type() != DirEntry.Type.DIRECTORY) continue;
                    if (!inArchive && escapes(fence, e.path())) continue;
                    scanned++;
                    var sub = await(service.folderPreview(e.path(), want - items.size()));
                    addPreviews(items, sub.previews(), fence, inArchive, want);
                    if (sub.verdict() != FolderVerdict.UNREADABLE) {
                        queue.add(e.path());
                        depths.put(e.path(), depth + 1);
                    }
                }
            }
        }
        var out = ok();
        out.put("previews", items);
        out.put("verdict", preview.verdict().name().toLowerCase(java.util.Locale.ROOT));
        sendJson(ex, 200, out);
    }

    /** How many folder levels below a folder tile its collage may come from. */
    private static final int PREVIEW_DEPTH = 3;
    /** Subfolders scanned at most to fill one folder tile's collage. */
    private static final int PREVIEW_FOLDERS = 24;

    private void addPreviews(List<Map<String, Object>> items, List<Path> previews,
                             WebPaths fence, boolean inArchive, int want) throws WebException {
        for (Path child : previews) {
            if (items.size() >= want) return;
            if (!inArchive && escapes(fence, child)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("p", WebPaths.locator(child));
            m.put("v", version(child, adjustments(child)));
            items.add(m);
        }
    }

    private void tile(HttpExchange ex) throws IOException, WebException {
        Path file = paths.resolve(query(ex, "p"));
        requireMedia(file);
        int size = snapTile(intQuery(ex, "s", TILE_SIZES[3]));
        Adjustments adj = adjustments(file);
        String version = version(file, adj);
        int q = quality;
        String key = "t|" + size + "|" + WebPaths.locator(file) + "|" + version + "|" + q
                + "|" + encoder.id() + "|" + colorKey();
        String etag = etag(key);
        String cacheControl = version.equals(query(ex, "v")) ? IMMUTABLE : "private, no-cache";
        if (notModified(ex, etag, cacheControl)) return;
        EncodedCache.Entry entry = cache.get(key);
        if (entry == null) {
            Thumbnail thumb = await(service.thumbnail(file, size, ThumbnailMode.FILL));
            RasterFrame frame = thumb.frame().orElseThrow(() -> noVisual(thumb.kind()));
            frame = RasterFrames.apply(frame, adj);
            entry = new EncodedCache.Entry(encoder.encode(frame, q), etag, thumb.corruption());
            cache.put(key, entry);
        }
        sendImage(ex, entry, cacheControl);
    }

    private void full(HttpExchange ex) throws IOException, WebException {
        Path file = paths.resolve(query(ex, "p"));
        requireMedia(file);
        int edge = snapFull(intQuery(ex, "e", 1280));
        Adjustments adj = adjustments(file);
        AaeSidecar edit = aae == null ? null : aae.forImage(file).orElse(null);
        String version = version(file, adj);
        int q = quality;
        String key = "f|" + edge + "|" + WebPaths.locator(file) + "|" + version + "|" + q
                + "|" + encoder.id() + "|" + colorKey();
        String etag = etag(key);
        String cacheControl = version.equals(query(ex, "v")) ? IMMUTABLE : "private, no-cache";
        if (notModified(ex, etag, cacheControl)) return;
        EncodedCache.Entry entry = cache.get(key);
        if (entry == null) {
            boolean acquired;
            try {
                acquired = fullDecodes.tryAcquire(DECODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WebException(503, "busy", "server is stopping");
            }
            if (!acquired) throw new WebException(503, "busy", "too many full-size decodes at once");
            try {
                VisualResult visual = await(service.loadVisual(file));
                RasterFrame frame = visual.frame().orElseThrow(() -> noVisual(visual.probe().kind()));
                // Same order as the desktop viewer: AAE crop/straighten on the
                // decoded master, then the size, then the user adjustments.
                frame = RasterFrames.applyAae(frame, edit);
                frame = Thumbnails.fit(frame, edge);
                frame = RasterFrames.apply(frame, adj);
                entry = new EncodedCache.Entry(encoder.encode(frame, q), etag, visual.corruption());
                cache.put(key, entry);
            } finally {
                fullDecodes.release();
            }
        }
        sendImage(ex, entry, cacheControl);
    }

    private void rotate(HttpExchange ex) throws IOException, WebException {
        Map<String, String> form = form(ex);
        Path file = paths.resolve(form.get("p"));
        requireMedia(file);
        if (ArchivePaths.inArchive(file)) {
            throw new WebException(409, "read-only",
                    "Inside an archive: rotation is not saved there (read-only)");
        }
        int delta = switch (form.getOrDefault("turn", "")) {
            case "cw" -> 1;
            case "ccw" -> -1;
            default -> throw WebException.badRequest("turn must be cw or ccw");
        };
        int quarterTurns = rotations.rotate(file, delta);
        onAdjusted.accept(file);
        var out = ok();
        out.put("rot", quarterTurns);
        out.put("v", version(file, adjustments(file)));
        if (rotations.isSessionOnly(file)) out.put("notice", RotationStore.SESSION_ONLY_NOTICE);
        sendJson(ex, 200, out);
    }

    // ------------------------------------------------------------------ media helpers

    /** Refuses anything the desktop's listing of its folder does not show as media. */
    private void requireMedia(Path file) throws WebException {
        String locator = WebPaths.locator(file);
        if (mediaKinds.containsKey(locator)) return;
        Path parent = file.getParent();
        if (parent == null || !ArchivePaths.isDirectory(parent)) {
            throw new WebException(404, "not-media", "not a media item");
        }
        for (DirEntry e : await(service.listEntries(parent))) {
            if (e.type() == DirEntry.Type.MEDIA) rememberKind(WebPaths.locator(e.path()), e.mediaKind());
        }
        if (!mediaKinds.containsKey(locator)) throw new WebException(404, "not-media", "not a media item");
    }

    private void rememberKind(String locator, MediaKind kind) {
        if (mediaKinds.size() > 200_000) mediaKinds.clear();
        mediaKinds.put(locator, kind);
    }

    private Adjustments adjustments(Path file) {
        return rotations.adjustments(file);
    }

    /**
     * The item's version: changes whenever its served pixels would — the file
     * (size, mtime), its adjustments, its AAE edit. Tile and full URLs carry it.
     */
    String version(Path file, Adjustments adj) throws WebException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException | RuntimeException e) {
            throw WebException.notFound("cannot read: " + message(e));
        }
        String aaeSig = aae == null ? "" : MediaService.aaeSignature(aae.forImage(file).orElse(null));
        String raw = attrs.size() + "|" + attrs.lastModifiedTime().toMillis() + "|"
                + adj.quarterTurnsCw() + adj.mirrorH() + adj.mirrorV() + adj.grayscale() + adj.invert()
                + "|" + aaeSig;
        return sha256(raw).substring(0, 16);
    }

    private static String colorKey() {
        ColorPolicy.Snapshot color = ColorPolicy.snapshot();
        return color.mode() + "/" + color.gamut();
    }

    private static WebException noVisual(MediaKind kind) {
        String what = kind == null ? "item" : kind.name().toLowerCase(java.util.Locale.ROOT);
        return new WebException(422, "no-visual", "This " + what + " has no picture");
    }

    static int snapTile(int requested) {
        for (int size : TILE_SIZES) {
            if (requested <= size) return size;
        }
        return TILE_SIZES[TILE_SIZES.length - 1];
    }

    static int snapFull(int requested) {
        int clamped = Math.max(MIN_FULL_EDGE, Math.min(MAX_FULL_EDGE, requested));
        int stepped = (clamped + FULL_EDGE_STEP - 1) / FULL_EDGE_STEP * FULL_EDGE_STEP;
        return Math.min(MAX_FULL_EDGE, stepped);
    }

    /** {@code future}'s value, with a decode failure turned into the page's error shape. */
    private static <T> T await(CompletableFuture<T> future) throws WebException {
        try {
            return future.get(DECODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new WebException(503, "busy", "server is stopping");
        } catch (TimeoutException e) {
            throw new WebException(504, "timeout", "took longer than "
                    + DECODE_TIMEOUT.toSeconds() + " s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (MediaFailures.isCorrupt(cause)) {
                throw new WebException(422, "corrupt", MediaFailures.message(cause));
            }
            throw new WebException(422, "failed", MediaFailures.plainMessage(cause));
        }
    }

    // ------------------------------------------------------------------ files

    private void serveFile(HttpExchange ex, String path) throws IOException, WebException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!FILE_NAME.matcher(name).matches()) throw WebException.notFound("not found");
        byte[] bytes = readPageFile(name);
        if (bytes == null) throw WebException.notFound("not found");
        String ext = name.substring(name.lastIndexOf('.') + 1);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), bytes);
    }

    private byte[] readPageFile(String name) throws IOException {
        if (options.webDir() != null) {
            Path file = options.webDir().resolve(name);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        try (InputStream in = WebServer.class.getResourceAsStream(PAGE_RESOURCES + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ HTTP helpers

    private static boolean notModified(HttpExchange ex, String etag, String cacheControl)
            throws IOException {
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm == null) return false;
        for (String tag : inm.split(",")) {
            String t = tag.trim();
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals(etag) || t.equals("*")) {
                ex.getResponseHeaders().set("ETag", etag);
                ex.getResponseHeaders().set("Cache-Control", cacheControl);
                ex.sendResponseHeaders(304, -1);
                return true;
            }
        }
        return false;
    }

    private void sendImage(HttpExchange ex, EncodedCache.Entry entry, String cacheControl)
            throws IOException {
        var h = ex.getResponseHeaders();
        h.set("ETag", entry.etag());
        h.set("Cache-Control", cacheControl);
        if (entry.corruption() != null) h.set("X-Media-Corrupt", headerSafe(entry.corruption()));
        send(ex, 200, encoder.mediaType(), entry.bytes());
    }

    /** {@code text} as one printable-ASCII header line. */
    static String headerSafe(String text) {
        var out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c >= 0x20 && c < 0x7f ? c : '?');
            if (out.length() >= 300) break;
        }
        return out.toString();
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    private static void sendJson(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, "application/json; charset=utf-8", Json.bytes(body));
    }

    private static void sendError(HttpExchange ex, int status, String code, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("error", message);
        sendJson(ex, status, body);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) ex.getResponseBody().write(body);
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException, WebException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new WebException(413, "too-large", "request too large");
        return parsePairs(new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, List<String>> formMulti(HttpExchange ex) throws IOException, WebException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_MOVE_BODY + 1);
        if (body.length > MAX_MOVE_BODY) throw new WebException(413, "too-large", "request too large");
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            } catch (IllegalArgumentException e) {
                throw WebException.badRequest("malformed form field");
            }
        }
        return out;
    }

    private static String first(Map<String, List<String>> form, String key) {
        List<String> values = form.get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static String query(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        return raw == null ? null : parsePairs(raw).get(key);
    }

    private static int intQuery(HttpExchange ex, String key, int fallback) throws WebException {
        String value = query(ex, key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw WebException.badRequest(key + " must be a number");
        }
    }

    static Map<String, String> parsePairs(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.putIfAbsent(k, v);
            } catch (IllegalArgumentException e) {
                // a malformed escape: the pair is ignored
            }
        }
        return out;
    }

    private static String etag(String key) {
        return "\"" + sha256(key).substring(0, 32) + "\"";
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
