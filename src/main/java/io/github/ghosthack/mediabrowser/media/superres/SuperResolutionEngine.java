package io.github.ghosthack.mediabrowser.media.superres;

import io.github.ghosthack.mediabrowser.media.BufferedImageRaster;
import io.github.ghosthack.mediabrowser.media.RasterFrame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Local, file-bridged Real-ESRGAN inference through Upscayl's maintained ncnn
 * Vulkan backend. The executable and selected model are downloaded on first
 * use, pinned to immutable releases/commits, and SHA-256 verified before they
 * can execute. Input and output are temporary lossless PNGs; media pixels never
 * leave the machine.
 *
 * <p>The process boundary is deliberate. It keeps the optional AGPL-3.0
 * backend separate from the application and avoids loading another native
 * runtime into the JavaFX/LWJGL process. Vulkan (MoltenVK over Metal on macOS)
 * supplies GPU inference across the supported desktop platforms.</p>
 */
public final class SuperResolutionEngine {

    /** 4M input pixels at 4× become 64M output pixels / 256 MiB of BGRA. */
    static final long MAX_OUTPUT_PIXELS = 64_000_000L;
    static final int MAX_OUTPUT_DIMENSION = 32_767;

    private static final String BACKEND_VERSION = "20251207-174704";
    private static final String BACKEND_RELEASE =
            "https://github.com/upscayl/upscayl-ncnn/releases/download/"
                    + BACKEND_VERSION + "/upscayl-bin-" + BACKEND_VERSION + "-";
    private static final String MODEL_COMMIT = "6cfaf45b2aae2847cba4f2313b57ca20a0ddd79c";
    private static final String MODEL_ROOT =
            "https://raw.githubusercontent.com/upscayl/upscayl/"
                    + MODEL_COMMIT + "/resources/models/";
    private static final int MAX_LOG_BYTES = 64 * 1024;

    private final Path root;
    private final HttpClient http;
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private final AtomicLong cancellationVersion = new AtomicLong();

    public SuperResolutionEngine() {
        this(Path.of(System.getProperty("user.home"), ".media-browser", "super-resolution"));
    }

    SuperResolutionEngine(Path root) {
        this.root = root;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** Whether this OS/architecture has a pinned native backend build. */
    public boolean isSupported() {
        return PlatformArtifact.detect() != null;
    }

    /** Explanation suitable for a status bar when this machine is unsupported. */
    public String unsupportedReason() {
        if (isSupported()) return "";
        return "Super resolution is unavailable on "
                + System.getProperty("os.name") + " / " + System.getProperty("os.arch")
                + " (supported: macOS Intel/Apple Silicon, Windows x64, Linux x64)";
    }

    /** Fast existence check used only to decide whether the first-use consent dialog is needed. */
    public boolean isReady(SuperResolutionModel model) {
        PlatformArtifact artifact = PlatformArtifact.detect();
        if (artifact == null) return false;
        Path install = installDir();
        Path models = install.resolve("models");
        return Files.isRegularFile(install.resolve(artifact.executableName))
                && Files.isRegularFile(install.resolve("LICENSE"))
                && Files.isRegularFile(models.resolve(model.fileBase() + ".bin"))
                && Files.isRegularFile(models.resolve(model.fileBase() + ".param"));
    }

    /** Stops the native process, if any. Used when navigation supersedes an enhancement. */
    public void cancelCurrent() {
        cancellationVersion.incrementAndGet();
        Process process = activeProcess.getAndSet(null);
        if (process != null && process.isAlive()) process.destroyForcibly();
    }

    /**
     * Installs the verified backend/model if needed and enhances {@code input}
     * to 4×. This is blocking and must run on a worker thread.
     */
    public RasterFrame enhance(RasterFrame input, SuperResolutionModel model,
                               Consumer<String> progress) {
        if (input == null) throw new SuperResolutionException("No still image is loaded");
        if (model == null) throw new SuperResolutionException("No super-resolution model selected");
        validateOutputSize(input.width(), input.height());
        PlatformArtifact artifact = PlatformArtifact.detect();
        if (artifact == null) throw new SuperResolutionException(unsupportedReason());
        long cancellation = cancellationVersion.get();

        Consumer<String> notify = progress == null ? ignored -> { } : progress;
        try {
            checkCancellation(cancellation);
            Path executable = prepareBackend(artifact, notify, cancellation);
            Path modelDir = prepareModel(model, notify, cancellation);
            checkCancellation(cancellation);
            notify.accept("Enhancing locally with Real-ESRGAN (4×)…");
            return run(input, model, executable, modelDir, cancellation);
        } catch (SuperResolutionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SuperResolutionException("Super-resolution enhancement was cancelled", e);
        } catch (IOException e) {
            throw new SuperResolutionException("Super resolution failed: " + safeMessage(e), e);
        }
    }

    static void validateOutputSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new SuperResolutionException("Invalid input dimensions: " + width + " × " + height);
        }
        long outputWidth = (long) width * SuperResolutionModel.SCALE;
        long outputHeight = (long) height * SuperResolutionModel.SCALE;
        long outputPixels = Math.multiplyExact(outputWidth, outputHeight);
        if (outputWidth > MAX_OUTPUT_DIMENSION || outputHeight > MAX_OUTPUT_DIMENSION
                || outputPixels > MAX_OUTPUT_PIXELS) {
            throw new SuperResolutionException("A 4× result would be " + outputWidth + " × "
                    + outputHeight + " (" + outputPixels / 1_000_000 + " MP); the safe limit is "
                    + MAX_OUTPUT_PIXELS / 1_000_000 + " MP. Super resolution is intended for "
                    + "lower-resolution sources.");
        }
    }

    private Path prepareBackend(PlatformArtifact artifact, Consumer<String> progress,
                                long cancellation)
            throws IOException, InterruptedException {
        Path install = installDir();
        Path executable = install.resolve(artifact.executableName);
        if (verified(executable, artifact.executableBytes, artifact.executableSha256)
                && Files.isRegularFile(install.resolve("LICENSE"))) {
            makeExecutable(executable);
            return executable;
        }

        Files.createDirectories(root);
        progress.accept("Downloading verified local AI runtime…");
        Path archive = downloadVerified(
                URI.create(BACKEND_RELEASE + artifact.assetSuffix + ".zip"),
                artifact.archiveBytes, artifact.archiveSha256, cancellation);
        Path staging = Files.createTempDirectory(root, "backend-install-");
        boolean installed = false;
        try {
            extractBackend(archive, staging, artifact);
            Path stagedExecutable = staging.resolve(artifact.executableName);
            if (!verified(stagedExecutable, artifact.executableBytes, artifact.executableSha256)) {
                throw new SuperResolutionException("The extracted AI runtime failed verification");
            }
            makeExecutable(stagedExecutable);
            replaceDirectory(staging, install);
            installed = true;
        } finally {
            Files.deleteIfExists(archive);
            if (!installed) deleteTree(staging);
        }
        return install.resolve(artifact.executableName);
    }

    private Path prepareModel(SuperResolutionModel model, Consumer<String> progress,
                              long cancellation)
            throws IOException, InterruptedException {
        Path models = installDir().resolve("models");
        Files.createDirectories(models);
        Path bin = models.resolve(model.fileBase() + ".bin");
        Path param = models.resolve(model.fileBase() + ".param");
        if (!verified(bin, model.binBytes(), model.binSha256())) {
            progress.accept("Downloading verified " + model.label() + " model…");
            installFile(URI.create(MODEL_ROOT + model.fileBase() + ".bin"),
                    bin, model.binBytes(), model.binSha256(), cancellation);
        }
        if (!verified(param, model.paramBytes(), model.paramSha256())) {
            installFile(URI.create(MODEL_ROOT + model.fileBase() + ".param"),
                    param, model.paramBytes(), model.paramSha256(), cancellation);
        }
        return models;
    }

    private void installFile(URI uri, Path target, long bytes, String sha256,
                             long cancellation)
            throws IOException, InterruptedException {
        Path downloaded = downloadVerified(uri, bytes, sha256, cancellation);
        try {
            moveReplace(downloaded, target);
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    private Path downloadVerified(URI uri, long expectedBytes, String expectedSha256,
                                  long cancellation)
            throws IOException, InterruptedException {
        Files.createDirectories(root);
        Path temp = Files.createTempFile(root, "download-", ".tmp");
        boolean keep = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "Media-Browser-Super-Resolution/1")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("download returned HTTP " + response.statusCode());
            }
            try (InputStream in = response.body(); var out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buffer)) >= 0;) {
                    checkCancellation(cancellation);
                    total += n;
                    if (total > expectedBytes) throw new IOException("download exceeded expected size");
                    out.write(buffer, 0, n);
                }
                if (total != expectedBytes) {
                    throw new IOException("download size was " + total + " bytes; expected " + expectedBytes);
                }
            }
            if (!sha256(temp).equals(expectedSha256)) {
                throw new IOException("download failed SHA-256 verification");
            }
            keep = true;
            return temp;
        } finally {
            if (!keep) Files.deleteIfExists(temp);
        }
    }

    private RasterFrame run(RasterFrame input, SuperResolutionModel model,
                            Path executable, Path modelDir, long cancellation)
            throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("media-browser-superres-");
        Path source = work.resolve("input.png");
        Path output = work.resolve("output.png");
        Process process = null;
        try {
            writePng(input, source);
            var command = new java.util.ArrayList<String>();
            command.add(executable.toString());
            command.addAll(java.util.List.of(
                    "-i", source.toString(),
                    "-o", output.toString(),
                    "-m", modelDir.toString(),
                    "-n", model.fileBase(),
                    "-z", "4", "-s", "4",
                    "-t", "0", "-j", "1:2:2",
                    "-f", "png", "-v"));
            process = new ProcessBuilder(command)
                    .directory(executable.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            Process old = activeProcess.getAndSet(process);
            if (old != null && old.isAlive()) old.destroyForcibly();
            checkCancellation(cancellation);
            String log;
            try (InputStream stream = process.getInputStream()) {
                log = readCapped(stream);
            }
            int exit = process.waitFor();
            if (exit != 0 || !Files.isRegularFile(output)) {
                String detail = lastUsefulLine(log);
                throw new SuperResolutionException("Real-ESRGAN failed"
                        + (detail.isBlank() ? " (exit " + exit + ")" : ": " + detail));
            }
            BufferedImage enhanced = ImageIO.read(output.toFile());
            if (enhanced == null) throw new IOException("AI runtime produced an unreadable PNG");
            int expectedWidth = Math.multiplyExact(input.width(), SuperResolutionModel.SCALE);
            int expectedHeight = Math.multiplyExact(input.height(), SuperResolutionModel.SCALE);
            if (enhanced.getWidth() != expectedWidth || enhanced.getHeight() != expectedHeight) {
                throw new IOException("AI runtime produced " + enhanced.getWidth() + " × "
                        + enhanced.getHeight() + "; expected " + expectedWidth + " × "
                        + expectedHeight);
            }
            return BufferedImageRaster.toRaster(enhanced);
        } finally {
            if (process != null) {
                activeProcess.compareAndSet(process, null);
                if (process.isAlive()) process.destroyForcibly();
            }
            deleteTree(work);
        }
    }

    private static void writePng(RasterFrame frame, Path target) throws IOException {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB);
        int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        byte[] bgra = frame.bgra();
        for (int p = 0, i = 0; p < argb.length; p++, i += 4) {
            argb[p] = (bgra[i + 3] & 0xFF) << 24
                    | (bgra[i + 2] & 0xFF) << 16
                    | (bgra[i + 1] & 0xFF) << 8
                    | (bgra[i] & 0xFF);
        }
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("No PNG writer is available");
        }
    }

    private static String readCapped(InputStream input) throws IOException {
        var captured = new ByteArrayOutputStream(MAX_LOG_BYTES);
        byte[] chunk = new byte[4096];
        int stored = 0;
        for (int n; (n = input.read(chunk)) >= 0;) {
            int keep = Math.min(n, MAX_LOG_BYTES - stored);
            if (keep > 0) {
                captured.write(chunk, 0, keep);
                stored += keep;
            }
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String lastUsefulLine(String log) {
        String[] lines = log == null ? new String[0] : log.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (!line.isBlank() && !line.endsWith("%") && !line.matches("[0-9.]+%?")) return line;
        }
        return "";
    }

    private void extractBackend(Path archive, Path staging, PlatformArtifact artifact)
            throws IOException {
        Set<String> allowed = Set.of("LICENSE", "README.md", artifact.executableName);
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.isDirectory()) continue;
                String normalized = entry.getName().replace('\\', '/');
                String name = normalized.substring(normalized.lastIndexOf('/') + 1);
                if (!allowed.contains(name)) {
                    throw new IOException("unexpected file in AI runtime archive: " + normalized);
                }
                Path output = staging.resolve(name);
                Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String required : allowed) {
            if (!Files.isRegularFile(staging.resolve(required))) {
                throw new IOException("AI runtime archive is missing " + required);
            }
        }
    }

    private Path installDir() {
        return root.resolve("upscayl-ncnn-" + BACKEND_VERSION);
    }

    private static void replaceDirectory(Path staging, Path target) throws IOException {
        Path stale = null;
        if (Files.exists(target)) {
            stale = target.resolveSibling(target.getFileName() + ".stale-" + UUID.randomUUID());
            move(target, stale);
        }
        try {
            move(staging, target);
        } catch (IOException failure) {
            if (stale != null && !Files.exists(target)) move(stale, target);
            throw failure;
        }
        if (stale != null) deleteTree(stale);
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static boolean verified(Path file, long bytes, String expectedSha256) {
        try {
            return Files.isRegularFile(file) && Files.size(file) == bytes
                    && sha256(file).equals(expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = input.read(buffer)) >= 0;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void makeExecutable(Path executable) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && !executable.toFile().setExecutable(true, true)) {
            throw new IOException("cannot mark AI runtime executable");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private void checkCancellation(long expectedVersion) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
                || cancellationVersion.get() != expectedVersion) {
            throw new InterruptedException("super resolution cancelled");
        }
    }

    private record PlatformArtifact(
            String assetSuffix,
            String executableName,
            long archiveBytes,
            String archiveSha256,
            long executableBytes,
            String executableSha256) {

        static PlatformArtifact detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                if (!(arch.equals("aarch64") || arch.equals("arm64")
                        || arch.equals("x86_64") || arch.equals("amd64"))) return null;
                return new PlatformArtifact(
                        "macos", "upscayl-bin", 9_338_240L,
                        "277419791281a56eae0c739c70120b974d7267cf7c2de8e86dc09798d4b314db",
                        28_266_680L,
                        "b7f54f362fc10d5f334e587fb90917e95a5557ec1cfefbddce78657dd3fee055");
            }
            boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
            if (os.contains("win") && x64) {
                return new PlatformArtifact(
                        "windows", "upscayl-bin.exe", 2_421_760L,
                        "1f0f65c5d2ade866555e2ac467d35952c35a47080a4060fe56a1ab028f67258a",
                        7_763_968L,
                        "294d31be8f29d047c0d91a8dcd5e739616ece56bce3188ac688f9a52d70abe60");
            }
            if (os.contains("linux") && x64) {
                return new PlatformArtifact(
                        "linux", "upscayl-bin", 3_952_825L,
                        "a9fab3c770b62f2b7a35d8d6d61eb4e8b3aef79128b665c919d080b85a2292f2",
                        11_976_752L,
                        "8b0980197d5bdb6fc4fbc0cc50dae721f3df18d91d0d912ec0a3aeea6495367e");
            }
            return null;
        }
    }
}
