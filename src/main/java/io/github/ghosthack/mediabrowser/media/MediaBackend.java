package io.github.ghosthack.mediabrowser.media;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/**
 * Selectable media decode backend.
 *
 * <p>{@link #FFMPEG_FFM} decodes everything (stills included) through the
 * bundled-FFmpeg ffmpeg-ffm artifact alone. The user-install FFmpeg/libvips
 * wirings that once sat beside it are retired — see
 * {@code docs/ffm-retirement-handoff.md}.</p>
 *
 * <p>{@link #APPLE} uses Apple's ImageIO/AVFoundation exclusively (images,
 * video, and audio) with no FFmpeg/libvips dependency; {@link #WINDOWS_NATIVE}
 * is its Windows counterpart, using WIC (stills/metadata) and Media Foundation
 * (video/audio) exclusively with no FFmpeg/libvips fallback; {@link #PURE} uses the
 * vendored pure-Java decoders for still images and video (no native dependency)
 * and throws on anything they cannot decode (audio, exotic containers/codecs).
 * Chosen at startup from {@code AppSettings.mediaBackend()}.</p>
 *
 * <p>Facades are constructed reflectively from class names so this file
 * compiles unchanged in source trees that omit some backends (the public
 * source distribution drops the pure-Java and FFmpeg/libvips stacks). A
 * constant whose implementation classes are absent reports
 * {@link #isAvailable()} {@code false} and drops out of {@link #available()};
 * a persisted selection naming it makes {@link #fromSettings(String)} throw —
 * an explicit selection is honored or errors, never silently substituted.</p>
 */
public enum MediaBackend {
    // Bundled FFmpeg solo, pure: stills AND video through the ffmpeg-ffm
    // artifact and nothing else — nothing user-installed. Stills refine from
    // FFmpeg's one-frame-video shape heuristically (see FfmpegFfmMediaFacade).
    FFMPEG_FFM("ffmpeg-ffm", "Bundled FFmpeg (images + video)",
            noArg("ffm.FfmpegFfmMediaFacade")
                    .withResources(ffmpegNativesManifest())),
    // The same facade with the explicit TurboJPEG addition (the default):
    // baseline-JPEG thumbnails decode through libjpeg-turbo's scaled decode
    // (turbojpeg-ffm artifact) — ~1.6x faster than FFmpeg's full decode on
    // real camera JPEGs; progressive/CMYK/lossless still decode via FFmpeg
    // (capability routing). Fails at create() where the turbojpeg natives
    // are absent, rather than silently behaving like FFMPEG_FFM.
    FFMPEG_FFM_TURBOJPEG("ffmpeg-ffm-turbojpeg",
            "Bundled FFmpeg + TurboJPEG thumbnails",
            staticFactory("ffm.FfmpegFfmMediaFacade", "withTurboJpeg")
                    .withResources(ffmpegNativesManifest(), turbojpegNativesManifest())),
    // 100% Apple: no FFmpeg/libvips fallback. Pass a fallback facade to
    // AppleMediaFacade instead if coverage for e.g. WebM/VP9 is wanted.
    APPLE("apple", "Apple (ImageIO / AVFoundation)",
            nullFallback("apple.AppleMediaFacade").onPlatform(isMacOs())),
    // 100% Windows native: WIC (stills/metadata) + Media Foundation
    // (video/audio). No FFmpeg/libvips/pure fallback — every path either
    // works through a Windows API or throws MediaException.
    WINDOWS_NATIVE("windows-native", "Windows (WIC / Media Foundation)",
            noArg("windows.WindowsMediaFacade").onPlatform(isWindowsOs())),
    // TwelveMonkeys ImageIO: enhanced still-image decode (JPEG/CMYK,
    // TIFF, WebP, PSD, etc.) + animated GIF as video. No fallback here
    // (TWELVEMONKEYS solo); the 12M+ffm/jcodec backends pass a video
    // fallback. Anything non-still/non-GIF throws.
    TWELVEMONKEYS("twelvemonkeys", "TwelveMonkeys ImageIO (images + GIF)",
            nullFallback("twelvemonkeys.TwelveMonkeysImageIoMediaFacade")),
    // TwelveMonkeys stills/GIF + bundled FFmpeg (ffmpeg-ffm artifact) for
    // video/audio. Was the default 2026-07-21..22; FFMPEG_FFM's faster JPEG
    // decode won (docs/ffm-retirement-handoff.md), but this pairing keeps
    // the wider ImageIO still-format coverage (PSD/ICO/CMYK exotics).
    TWELVEMONKEYS_FFMPEG_FFM("twelvemonkeys-ffmpeg-ffm", "TwelveMonkeys ImageIO + bundled FFmpeg video",
            videoFallback("twelvemonkeys.TwelveMonkeysImageIoMediaFacade",
                    "ffm.FfmpegFfmMediaFacade")
                    .withResources(ffmpegNativesManifest())),
    // TwelveMonkeys stills/GIF + jcodec (pure-Java H.264/MPEG/ProRes)
    // for video. jcodec declines other codecs (classify → empty), which
    // the 12M wrapper surfaces as unsupported.
    TWELVEMONKEYS_JCODEC("twelvemonkeys-jcodec", "TwelveMonkeys ImageIO + jcodec video",
            videoFallback("twelvemonkeys.TwelveMonkeysImageIoMediaFacade",
                    "jcodec.JcodecMediaFacade")),
    // 100% pure Java: images and video decode with no native
    // dependency; audio and any codec/container the pure stack declines
    // throw (no fallback). Pass a fallback facade to PureMediaFacade
    // instead if FFmpeg coverage for those is wanted.
    PURE("pure", "Pure-Java (images + video)",
            nullFallback("pure.PureMediaFacade"));

    // Class names in the enum constants above are relative to this package;
    // the factory helpers prepend it. (The constants cannot reference these
    // fields directly — an enum's constants initialize before its other static
    // fields, and javac rejects the forward reference.)
    private static final String PKG = "io.github.ghosthack.mediabrowser.media.";

    /** Instantiates a facade; deferred so absent classes only fail on use. */
    @FunctionalInterface
    private interface Factory {
        MediaFacade create() throws ReflectiveOperationException;
    }

    /**
     * The implementation classes and classpath resources a backend needs, plus
     * how to build its facade. {@code requiredResources} carries the bundled
     * natives manifests of the ffmpeg-ffm/turbojpeg-ffm artifacts — present
     * only in the classifier jar of a covered platform, so their absence means
     * "this platform has no natives", checkable without loading anything.
     */
    private record Spec(List<String> requiredClasses, List<String> requiredResources,
                        boolean platformSupported, Factory factory) {
        Spec(List<String> requiredClasses, Factory factory) {
            this(requiredClasses, List.of(), true, factory);
        }
        Spec withResources(String... resources) {
            return new Spec(requiredClasses, List.of(resources), platformSupported, factory);
        }
        Spec onPlatform(boolean supported) {
            return new Spec(requiredClasses, requiredResources, supported, factory);
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    private static boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** This platform's natives classifier, mirroring the artifacts' loaders. */
    private static String nativeClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String osPart = os.contains("mac") ? "macos" : os.contains("win") ? "windows" : "linux";
        String archPart = (arch.equals("aarch64") || arch.equals("arm64")) ? "arm64" : "x64";
        return osPart + "-" + archPart;
    }

    /** The ffmpeg-ffm natives manifest for this platform (method: the enum
     * constants may call methods but not forward-reference static fields). */
    private static String ffmpegNativesManifest() {
        return "natives/" + nativeClassifier() + "/manifest.txt";
    }

    /** The turbojpeg-ffm natives manifest for this platform. */
    private static String turbojpegNativesManifest() {
        return "turbojpeg-natives/" + nativeClassifier() + "/manifest.txt";
    }

    /** Facade built by a public static no-arg factory method on {@code facadeClass}. */
    private static Spec staticFactory(String facadeClass, String method) {
        String facade = PKG + facadeClass;
        return new Spec(List.of(facade),
                () -> (MediaFacade) Class.forName(facade).getMethod(method).invoke(null));
    }

    /** Facade with a public no-arg constructor ({@code facadeClass} relative to {@code PKG}). */
    private static Spec noArg(String facadeClass) {
        String facade = PKG + facadeClass;
        return new Spec(List.of(facade),
                () -> (MediaFacade) newInstance(facade));
    }

    /** Facade whose constructor takes a fallback {@code MediaFacade}, passed {@code null}. */
    private static Spec nullFallback(String facadeClass) {
        String facade = PKG + facadeClass;
        return new Spec(List.of(facade),
                () -> (MediaFacade) newInstance(facade, new Object[] {null}));
    }

    /** Facade whose constructor takes a no-arg-constructed fallback facade. */
    private static Spec videoFallback(String facadeClass, String fallbackClass) {
        String facade = PKG + facadeClass;
        String fallback = PKG + fallbackClass;
        return new Spec(List.of(facade, fallback),
                () -> (MediaFacade) newInstance(facade, newInstance(fallback)));
    }

    private static Object newInstance(String className, Object... args)
            throws ReflectiveOperationException {
        Class<?> cls = Class.forName(className);
        for (Constructor<?> ctor : cls.getConstructors()) {
            if (ctor.getParameterCount() == args.length) {
                return ctor.newInstance(args);
            }
        }
        throw new NoSuchMethodException(
                className + " has no public " + args.length + "-arg constructor");
    }

    private final String settingsValue;
    private final String label;
    private final Spec spec;

    MediaBackend(String settingsValue, String label, Spec spec) {
        this.settingsValue = settingsValue;
        this.label = label;
        this.spec = spec;
    }

    /** The value persisted in {@code AppSettings} under {@code media.backend}. */
    public String settingsValue() {
        return settingsValue;
    }

    /** Human-readable name for menus and dialogs. */
    public String label() {
        return label;
    }

    /**
     * Whether this backend's implementation classes are present in this build.
     * Looks classes up without initializing them, so no native libraries load.
     */
    public boolean isAvailable() {
        return unavailabilityReason() == null;
    }

    /** Why this backend cannot run here — for error messages; null when available. */
    private String unavailabilityReason() {
        if (!spec.platformSupported()) {
            return "it is gated off this OS";
        }
        for (String className : spec.requiredClasses()) {
            try {
                Class.forName(className, false, MediaBackend.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                return "class " + className + " is not part of this build";
            }
        }
        for (String resource : spec.requiredResources()) {
            if (MediaBackend.class.getClassLoader().getResource(resource) == null) {
                return "classpath resource " + resource
                        + " is missing (no bundled natives for this platform)";
            }
        }
        return null;
    }

    /** The backends present in this build, in declaration order — menu source. */
    public static List<MediaBackend> available() {
        return Arrays.stream(values()).filter(MediaBackend::isAvailable).toList();
    }

    /**
     * The default backend: {@link #FFMPEG_FFM_TURBOJPEG} — stills and video
     * through the bundled-FFmpeg (ffmpeg-ffm artifact) path plus the explicit
     * TurboJPEG thumbnail fast path, the fastest measured JPEG option
     * (benchmarks in docs/ffm-retirement-handoff.md; formats FFmpeg doesn't
     * claim, e.g. PSD/ICO, need an explicit TwelveMonkeys-paired backend).
     * Unconditional — deliberately no availability probe: in a tree or on a
     * platform where this backend cannot run, {@link #create()} fails loudly
     * at startup ({@code App}'s startup check then visibly replaces the
     * setting) rather than silently substituting another decode stack, so the
     * backend in use is never a guess. (An availability-degrading default —
     * latterly to the JavaCV pair retired 2026-07-22 — filled this role until
     * determinism won.)
     */
    public static MediaBackend defaultBackend() {
        return FFMPEG_FFM_TURBOJPEG;
    }

    /**
     * Parses the persisted setting value; matches by {@code settingsValue}.
     * Unknown (and retired) values resolve to {@link #defaultBackend()} — the
     * stale-{@code app.properties} migration path. A value that names a
     * <em>recognized</em> backend which cannot run here throws instead of
     * silently substituting another: an explicit selection is honored or
     * errors, never guessed ({@code App}'s startup check turns the throw into
     * the visible replace-with-jcodec path; {@code SmokeTest} surfaces it raw).
     *
     * @throws IllegalStateException when {@code value} names a recognized
     *         backend that is unavailable in this build / on this platform
     */
    public static MediaBackend fromSettings(String value) {
        if (value != null) {
            for (MediaBackend backend : values()) {
                if (backend.settingsValue.equalsIgnoreCase(value)) {
                    String reason = backend.unavailabilityReason();
                    if (reason != null) {
                        throw new IllegalStateException("media.backend '" + value
                                + "' names " + backend.name() + " (" + backend.label
                                + "), which cannot run here: " + reason);
                    }
                    return backend;
                }
            }
        }
        return defaultBackend();
    }

    /** Builds the facade for this backend (loads native libraries). */
    public MediaFacade create() {
        try {
            return spec.factory().create();
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to create backend " + label, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Backend '" + label + "' is not part of this build", e);
        }
    }
}
