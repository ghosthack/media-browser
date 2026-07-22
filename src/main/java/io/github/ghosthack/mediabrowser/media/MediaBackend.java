package io.github.ghosthack.mediabrowser.media;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/**
 * Selectable media decode backend.
 *
 * <p>{@link #FFMPEG_MACPORTS}, {@link #FFMPEG_BREW} and {@link #FFMPEG_WINDOWS}
 * all run the same FFmpeg + libvips decode logic through
 * {@code FfmpegVipsMediaFacade}; they differ only in which native libraries it
 * binds to (a {@code io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings} /
 * {@code VipsBindings} pair) — MacPorts FFmpeg 4.x / libvips 8.x under
 * {@code /opt/local}, Homebrew FFmpeg 8.x under {@code /opt/homebrew}, or
 * Windows FFmpeg 8.x / libvips loaded by DLL name off {@code PATH}.</p>
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
 * {@link #isAvailable()} {@code false}, drops out of {@link #available()}, and
 * any persisted selection naming it falls back to the default in
 * {@link #fromSettings(String)}.</p>
 */
public enum MediaBackend {
    FFMPEG_MACPORTS("ffmpeg-macports", "MacPorts FFmpeg 4.x / libvips 8.x",
            ffmpegVips("ffm.bind.macports.MacPortsFfmpegBindings",
                    "ffm.bind.macports.MacPortsVipsBindings")),
    // Homebrew FFmpeg 8.x / libvips, bound to /opt/homebrew via the
    // ffi.brew.{ffmpeg,vips} stubs (regenerate with jextract/gen-bindings.sh brew).
    FFMPEG_BREW("ffmpeg-brew", "Homebrew FFmpeg 8.x / libvips",
            ffmpegVips("ffm.bind.brew.BrewFfmpegBindings",
                    "ffm.bind.brew.BrewVipsBindings")),
    // Windows FFmpeg 8.x / libvips, bound by DLL name off PATH via the
    // ffi.win.{ffmpeg,vips} stubs (derived from the brew 8.x set; see
    // docs/windows-ffmpeg-backend.md).
    FFMPEG_WINDOWS("ffmpeg-windows", "Windows FFmpeg 8.x / libvips",
            ffmpegVips("ffm.bind.win.WindowsFfmpegBindings",
                    "ffm.bind.win.WindowsVipsBindings")),
    // FFmpeg 8.x from the io.github.ghosthack:ffmpeg-ffm Maven artifact —
    // natives ship in classifier jars and self-extract, no user-installed
    // FFmpeg (docs/ffmpeg-bundled-backend.md). Stills still go through the
    // platform's vips binding, picked by OS since the artifact covers FFmpeg only.
    FFMPEG_BUNDLED("ffmpeg-bundled", "Bundled FFmpeg 8.x (Maven natives) / libvips",
            ffmpegVips("ffm.bind.bundled.BundledFfmpegBindings", bundledVipsClass())),
    // Bundled FFmpeg solo: stills AND video through the ffmpeg-ffm artifact —
    // no libvips, nothing user-installed. Stills refine from FFmpeg's
    // one-frame-video shape heuristically (see FfmpegFfmMediaFacade).
    FFMPEG_FFM("ffmpeg-ffm", "Bundled FFmpeg (images + video)",
            noArg("ffm.FfmpegFfmMediaFacade")),
    // 100% Apple: no FFmpeg/libvips fallback. Pass a fallback facade to
    // AppleMediaFacade instead if coverage for e.g. WebM/VP9 is wanted.
    APPLE("apple", "Apple (ImageIO / AVFoundation)",
            nullFallback("apple.AppleMediaFacade")),
    // 100% Windows native: WIC (stills/metadata) + Media Foundation
    // (video/audio). No FFmpeg/libvips/pure fallback — every path either
    // works through a Windows API or throws MediaException.
    WINDOWS_NATIVE("windows-native", "Windows (WIC / Media Foundation)",
            noArg("windows.WindowsMediaFacade")),
    // TwelveMonkeys ImageIO: enhanced still-image decode (JPEG/CMYK,
    // TIFF, WebP, PSD, etc.) + animated GIF as video. No fallback here
    // (TWELVEMONKEYS solo); the 12M+JavaCV/jcodec backends pass a video
    // fallback. Anything non-still/non-GIF throws.
    TWELVEMONKEYS("twelvemonkeys", "TwelveMonkeys ImageIO (images + GIF)",
            nullFallback("twelvemonkeys.TwelveMonkeysImageIoMediaFacade")),
    // TwelveMonkeys stills/GIF + JavaCV (bundled FFmpeg) for everything
    // else (video/audio).
    TWELVEMONKEYS_JAVACV("twelvemonkeys-javacv", "TwelveMonkeys ImageIO + JavaCV video",
            videoFallback("twelvemonkeys.TwelveMonkeysImageIoMediaFacade",
                    "javacv.JavaCvMediaFacade")),
    // TwelveMonkeys stills/GIF + bundled FFmpeg (ffmpeg-ffm artifact) for
    // video/audio — the JAVACV pairing with the FFM backend instead of
    // bytedeco, and the natural default once ffmpeg-ffm is proven.
    TWELVEMONKEYS_FFMPEG_FFM("twelvemonkeys-ffmpeg-ffm", "TwelveMonkeys ImageIO + bundled FFmpeg video",
            videoFallback("twelvemonkeys.TwelveMonkeysImageIoMediaFacade",
                    "ffm.FfmpegFfmMediaFacade")),
    // TwelveMonkeys stills/GIF + jcodec (pure-Java H.264/MPEG/ProRes)
    // for video. jcodec declines other codecs (classify → empty), which
    // the 12M wrapper surfaces as unsupported.
    TWELVEMONKEYS_JCODEC("twelvemonkeys-jcodec", "TwelveMonkeys ImageIO + jcodec video",
            videoFallback("twelvemonkeys.TwelveMonkeysImageIoMediaFacade",
                    "jcodec.JcodecMediaFacade")),
    // JavaCV solo: bundled FFmpeg for both stills and video.
    JAVACV("javacv", "JavaCV (bundled FFmpeg: images + video)",
            noArg("javacv.JavaCvMediaFacade")),
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
    private static final String FFMPEG_VIPS_FACADE = PKG + "ffm.FfmpegVipsMediaFacade";

    /** Instantiates a facade; deferred so absent classes only fail on use. */
    @FunctionalInterface
    private interface Factory {
        MediaFacade create() throws ReflectiveOperationException;
    }

    /** The implementation classes a backend needs, plus how to build its facade. */
    private record Spec(List<String> requiredClasses, Factory factory) {}

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

    /**
     * The vips binding {@link #FFMPEG_BUNDLED} pairs with, by OS: Homebrew
     * paths on macOS, DLL-name-off-PATH elsewhere (the least path-specific
     * choice for Windows and anything else).
     */
    private static String bundledVipsClass() {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        return mac ? "ffm.bind.brew.BrewVipsBindings" : "ffm.bind.win.WindowsVipsBindings";
    }

    /** {@code FfmpegVipsMediaFacade} over a no-arg-constructed bindings pair. */
    private static Spec ffmpegVips(String ffmpegBindingsClass, String vipsBindingsClass) {
        String ffmpegBindings = PKG + ffmpegBindingsClass;
        String vipsBindings = PKG + vipsBindingsClass;
        return new Spec(List.of(FFMPEG_VIPS_FACADE, ffmpegBindings, vipsBindings),
                () -> (MediaFacade) newInstance(FFMPEG_VIPS_FACADE,
                        newInstance(ffmpegBindings), newInstance(vipsBindings)));
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
        for (String className : spec.requiredClasses()) {
            try {
                Class.forName(className, false, MediaBackend.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    /** The backends present in this build, in declaration order — menu source. */
    public static List<MediaBackend> available() {
        return Arrays.stream(values()).filter(MediaBackend::isAvailable).toList();
    }

    /**
     * The default backend: {@link #TWELVEMONKEYS_FFMPEG_FFM} — TwelveMonkeys
     * stills plus the bundled-FFmpeg (ffmpeg-ffm artifact) video path, which
     * behaves identically on macOS/Windows with nothing installed. In trees
     * that omit {@code media/ffm} (the public source distribution, today) it
     * degrades to {@link #TWELVEMONKEYS_JAVACV}, the same pairing over
     * bytedeco's FFmpeg.
     */
    public static MediaBackend defaultBackend() {
        return TWELVEMONKEYS_FFMPEG_FFM.isAvailable()
                ? TWELVEMONKEYS_FFMPEG_FFM : TWELVEMONKEYS_JAVACV;
    }

    /**
     * Parses the persisted setting value; matches by {@code settingsValue}.
     * Unknown values — and backends whose classes are absent from this build —
     * resolve to {@link #defaultBackend()}.
     */
    public static MediaBackend fromSettings(String value) {
        if (value != null) {
            // Legacy value (pre-split, single MacPorts FFmpeg backend).
            String wanted = value.equalsIgnoreCase("ffmpeg") ? "ffmpeg-macports" : value;
            for (MediaBackend backend : values()) {
                if (backend.settingsValue.equalsIgnoreCase(wanted) && backend.isAvailable()) {
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
