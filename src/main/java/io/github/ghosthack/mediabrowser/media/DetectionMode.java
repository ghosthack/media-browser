package io.github.ghosthack.mediabrowser.media;

/**
 * How a directory listing decides whether (and as what kind) a file is media.
 *
 * <ul>
 *   <li>{@link #CONTENT_SNIFF} — asks the native facade to look
 *       at the file's bytes (libvips loader sniffing / FFmpeg demuxer probe,
 *       or Apple's ImageIO/AVFoundation). Accurate but pays a native probe per
 *       file.</li>
 *   <li>{@link #FILE_EXTENSION} — the default; classifies purely by filename
 *       extension (see {@link ExtensionClassifier}). Fast and never touches
 *       the file, but trusts the name and cannot see inside extension-less or
 *       mislabelled files.</li>
 * </ul>
 *
 * <p>Chosen from {@code AppSettings.detectionMode()} and switchable at runtime
 * from the Show menu.</p>
 */
public enum DetectionMode {
    CONTENT_SNIFF("content", "Detect by Content"),
    FILE_EXTENSION("extension", "Detect by File Extension");

    private final String settingsValue;
    private final String label;

    DetectionMode(String settingsValue, String label) {
        this.settingsValue = settingsValue;
        this.label = label;
    }

    /** The value persisted in {@code AppSettings} under {@code media.detection}. */
    public String settingsValue() {
        return settingsValue;
    }

    /** Human-readable name for menus. */
    public String label() {
        return label;
    }

    /** Parses the persisted setting value; anything but {@code "content"} goes by extension. */
    public static DetectionMode fromSettings(String value) {
        return value != null && value.equalsIgnoreCase("content")
                ? CONTENT_SNIFF : FILE_EXTENSION;
    }
}
