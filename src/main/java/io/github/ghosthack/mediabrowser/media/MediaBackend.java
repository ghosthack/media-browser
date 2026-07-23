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
 * {@link #isAvailable()} {@code false}, drops out of {@link #available()}, and
 * any persisted selection naming it falls back to the default in
 * {@link #fromSettings(String)}.</p>
 */
public enum MediaBackend {
    // Bundled FFmpeg solo, pure: stills AND video through the ffmpeg-ffm
    // artifact and nothing else — nothing user-installed. Stills refine from
    // FFmpeg's one-frame-video shape heuristically (see FfmpegFfmMediaFacade).
    FFMPEG_FFM("ffmpeg-ffm", "Bundled FFmpeg (images + video)",
            noArg("ffm.FfmpegFfmMediaFacade")),
    // The same facade with the explicit TurboJPEG addition (the default):
    // baseline-JPEG thumbnails decode through libjpeg-turbo's scaled decode
    // (turbojpeg-ffm artifact) — ~1.6x faster than FFmpeg's full decode on
    // real camera JPEGs; progressive/CMYK/lossless still decode via FFmpeg
    // (capability routing). Fails at create() where the turbojpeg natives
    // are absent, rather than silently behaving like FFMPEG_FFM.
    FFMPEG_FFM_TURBOJPEG("ffmpeg-ffm-turbojpeg",
            "Bundled FFmpeg + TurboJPEG thumbnails",
            staticFactory("ffm.FfmpegFfmMediaFacade", "withTurboJpeg")),
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
    // bytedeco. Was the default 2026-07-21..22; FFMPEG_FFM's faster JPEG
    // decode won (docs/ffm-retirement-handoff.md), but this pairing keeps
    // the wider ImageIO still-format coverage (PSD/ICO/CMYK exotics).
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

    /** Instantiates a facade; deferred so absent classes only fail on use. */
    @FunctionalInterface
    private interface Factory {
        MediaFacade create() throws ReflectiveOperationException;
    }

    /** The implementation classes a backend needs, plus how to build its facade. */
    private record Spec(List<String> requiredClasses, Factory factory) {}

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
     * The default backend: {@link #FFMPEG_FFM_TURBOJPEG} — stills and video
     * through the bundled-FFmpeg (ffmpeg-ffm artifact) path plus the explicit
     * TurboJPEG thumbnail fast path, the fastest measured JPEG option
     * (benchmarks in docs/ffm-retirement-handoff.md; formats FFmpeg doesn't
     * claim, e.g. PSD/ICO, need an explicit TwelveMonkeys-paired backend).
     * In trees that omit {@code media/ffm} it degrades to
     * {@link #TWELVEMONKEYS_JAVACV}, bundled-FFmpeg video over bytedeco.
     */
    public static MediaBackend defaultBackend() {
        return FFMPEG_FFM_TURBOJPEG.isAvailable() ? FFMPEG_FFM_TURBOJPEG : TWELVEMONKEYS_JAVACV;
    }

    /**
     * Parses the persisted setting value; matches by {@code settingsValue}.
     * Unknown values — and backends whose classes are absent from this build —
     * resolve to {@link #defaultBackend()}.
     */
    public static MediaBackend fromSettings(String value) {
        if (value != null) {
            for (MediaBackend backend : values()) {
                if (backend.settingsValue.equalsIgnoreCase(value) && backend.isAvailable()) {
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
