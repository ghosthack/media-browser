package io.github.ghosthack.mediabrowser.media;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/**
 * The Windows file-associations vocabulary shared by the dialog
 * ({@code ui.FileAssociationsDialog}) and the registry layer
 * ({@code win.FileAssociationRegistry}): the ProgId scheme, the type
 * descriptions Explorer shows, per-extension format names, and the curated
 * subset.
 *
 * <p>The curated lists are deliberately narrower than
 * {@link ExtensionClassifier}'s full recognized-extension sets: common
 * formats only. They are what the first-launch prompt pre-checks as a
 * proposal; the dialog itself always lists the full recognized set, nothing
 * pre-checked beyond what the registry already reports. Nothing is ever
 * registered by the installer — jpackage would write its own opaque ProgIds,
 * which this vocabulary could not recognize — so every association this app
 * owns goes through {@link #progId}.</p>
 */
public final class FileAssociationCatalog {

    private FileAssociationCatalog() {}

    // List, not Set: Set.of()/Set.copyOf() iteration order is unspecified and
    // in practice randomized per JVM run (a deliberate JDK safeguard), which
    // would shuffle the dialog's checkbox order on every launch. A List
    // preserves the declared order deterministically; uniqueness within each
    // curated list is guaranteed by construction, not by the type.
    private static final List<String> IMAGE = List.of(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tiff");
    private static final List<String> VIDEO = List.of(
            "mp4", "mkv", "mov", "webm", "avi", "wmv", "m4v");
    private static final List<String> AUDIO = List.of(
            "mp3", "flac", "wav", "ogg", "m4a", "aac", "wma");

    /** The curated extensions offered for {@code kind}, in a stable display order. */
    public static List<String> curated(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> IMAGE;
            case VIDEO -> VIDEO;
            case AUDIO -> AUDIO;
        };
    }

    /** Every curated extension across all three kinds, image/video/audio in order. */
    public static Map<String, MediaKind> curatedAll() {
        Map<String, MediaKind> all = new LinkedHashMap<>();
        for (String ext : IMAGE) all.put(ext, MediaKind.IMAGE);
        for (String ext : VIDEO) all.put(ext, MediaKind.VIDEO);
        for (String ext : AUDIO) all.put(ext, MediaKind.AUDIO);
        return all;
    }

    /** The ProgId this app registers for {@code extension} — namespaced per extension. */
    public static String progId(String extension) {
        return "MediaBrowser.AssocFile." + extension.toLowerCase(Locale.ROOT);
    }

    /** The friendly type description shown in Explorer's Open-with picker. */
    public static String description(String extension, MediaKind kind) {
        return "Media Browser " + kind.badge() + " file (." + extension.toLowerCase(Locale.ROOT) + ")";
    }

    /**
     * A short, human-readable name for the file format {@code extension}
     * represents (e.g. {@code "png" -> "Portable Network Graphics"}), for
     * the dialog's format column. Covers every extension
     * {@link ExtensionClassifier}'s three lists recognize; an extension
     * added there without an entry here falls back to a generic
     * {@code "<EXT> File"} label rather than a blank cell.
     */
    public static String formatDescription(String extension) {
        String name = FORMAT_NAMES.get(extension.toLowerCase(Locale.ROOT));
        return name != null ? name : extension.toUpperCase(Locale.ROOT) + " File";
    }

    private static final Map<String, String> FORMAT_NAMES = Map.ofEntries(
            // Images
            entry("jpg", "JPEG Image"), entry("jpeg", "JPEG Image"),
            entry("jpe", "JPEG Image"), entry("jfif", "JPEG Image"),
            entry("png", "Portable Network Graphics"),
            entry("apng", "Animated Portable Network Graphics"),
            entry("gif", "Graphics Interchange Format"),
            entry("bmp", "Windows Bitmap"),
            entry("tif", "Tagged Image File Format"), entry("tiff", "Tagged Image File Format"),
            entry("webp", "WebP Image"),
            entry("heic", "High Efficiency Image Format"), entry("heif", "High Efficiency Image Format"),
            entry("avif", "AV1 Image File Format"),
            entry("jp2", "JPEG 2000 Image"), entry("j2k", "JPEG 2000 Image"), entry("jpf", "JPEG 2000 Image"),
            entry("jls", "JPEG-LS Image"),
            entry("jxl", "JPEG XL Image"),
            entry("ico", "Windows Icon"),
            entry("tga", "Truevision TGA"),
            entry("ppm", "Portable Pixmap"),
            entry("pgm", "Portable Graymap"),
            entry("pbm", "Portable Bitmap"),
            entry("pnm", "Portable Any Map"),
            entry("pam", "Portable Arbitrary Map"),
            entry("pfm", "Portable Float Map"),
            entry("exr", "OpenEXR Image"),
            entry("hdr", "Radiance HDR Image"),
            entry("dds", "DirectDraw Surface"),
            entry("psd", "Photoshop Document"),
            entry("svg", "Scalable Vector Graphics"),
            entry("raw", "Camera Raw Image"),
            entry("cr2", "Canon Raw Image"), entry("cr3", "Canon Raw Image"), entry("crw", "Canon Raw Image"),
            entry("nef", "Nikon Raw Image"), entry("nrw", "Nikon Raw Image"),
            entry("arw", "Sony Raw Image"), entry("srf", "Sony Raw Image"), entry("sr2", "Sony Raw Image"),
            entry("raf", "Fujifilm Raw Image"),
            entry("orf", "Olympus Raw Image"),
            entry("rw2", "Panasonic Raw Image"),
            entry("pef", "Pentax Raw Image"),
            entry("srw", "Samsung Raw Image"),
            entry("erf", "Epson Raw Image"),
            entry("kdc", "Kodak Raw Image"), entry("dcr", "Kodak Raw Image"),
            entry("mos", "Leaf Raw Image"),
            entry("mrw", "Minolta Raw Image"),
            entry("rwl", "Leica Raw Image"),
            entry("iiq", "Phase One Raw Image"),
            entry("3fr", "Hasselblad Raw Image"),
            entry("fff", "Imacon Raw Image"),
            entry("mef", "Mamiya Raw Image"),
            entry("x3f", "Sigma Raw Image"),
            entry("dng", "Digital Negative"),
            entry("pcx", "PC Paintbrush Image"),
            entry("xpm", "X PixMap"),
            entry("xbm", "X BitMap"),
            entry("xwd", "X Window Dump"),
            entry("sgi", "SGI Image"),
            entry("ras", "Sun Raster Image"),
            entry("qoi", "Quite OK Image"),
            entry("dpx", "Digital Picture Exchange"),
            entry("pcd", "Kodak Photo CD"),
            // Video
            entry("mp4", "MPEG-4 Video"), entry("m4v", "MPEG-4 Video"), entry("m4s", "MPEG-4 Video"),
            entry("mkv", "Matroska Video"),
            entry("webm", "WebM Video"),
            entry("avi", "Audio Video Interleave"),
            entry("mov", "QuickTime Movie"), entry("qt", "QuickTime Movie"),
            entry("wmv", "Windows Media Video"), entry("asf", "Windows Media Video"),
            entry("flv", "Flash Video"), entry("f4v", "Flash Video"),
            entry("divx", "DivX Video"),
            entry("mpg", "MPEG Video"), entry("mpeg", "MPEG Video"),
            entry("m2v", "MPEG Video"), entry("mpv", "MPEG Video"),
            entry("ts", "MPEG Transport Stream"), entry("m2ts", "MPEG Transport Stream"),
            entry("mts", "MPEG Transport Stream"),
            entry("ogv", "Ogg Video"), entry("ogm", "Ogg Video"),
            entry("3gp", "3GPP Video"), entry("3g2", "3GPP Video"),
            entry("vob", "DVD Video Object"),
            entry("mxf", "Material Exchange Format"),
            entry("nut", "NUT Video"),
            entry("dv", "Digital Video"),
            entry("rm", "RealMedia Video"),
            // Audio
            entry("mp3", "MPEG Audio"), entry("mp2", "MPEG Audio"),
            entry("m4a", "MPEG-4 Audio"), entry("m4b", "MPEG-4 Audio"),
            entry("m4r", "MPEG-4 Audio"), entry("3ga", "MPEG-4 Audio"),
            entry("aac", "Advanced Audio Coding"),
            entry("flac", "Free Lossless Audio Codec"),
            entry("ogg", "Ogg Vorbis Audio"), entry("oga", "Ogg Vorbis Audio"),
            entry("opus", "Opus Audio"),
            entry("wav", "Waveform Audio"),
            entry("wma", "Windows Media Audio"),
            entry("aiff", "Audio Interchange File Format"), entry("aif", "Audio Interchange File Format"),
            entry("ape", "Monkey's Audio"),
            entry("mka", "Matroska Audio"),
            entry("ac3", "Dolby Digital Audio"),
            entry("dts", "DTS Audio"),
            entry("amr", "Adaptive Multi-Rate Audio"),
            entry("caf", "Core Audio Format"),
            entry("au", "Sun Audio"),
            entry("ra", "RealAudio"),
            entry("mpc", "Musepack Audio"),
            entry("wv", "WavPack Audio"),
            entry("tta", "True Audio"),
            entry("spx", "Speex Audio"),
            entry("dsf", "DSD Stream File"));
}
