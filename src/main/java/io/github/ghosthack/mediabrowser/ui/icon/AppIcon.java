package io.github.ghosthack.mediabrowser.ui.icon;

/**
 * Semantic icon roles used by the UI. Controls request a role rather than
 * knowing which artwork the selected pack uses.
 */
public enum AppIcon {
    UP("⬆"),
    FOLDER("📁"),
    ARCHIVE("🗄"),
    IMAGE("🖼"),
    VIDEO("🎬"),
    AUDIO("🎵"),
    PREVIOUS("◀"),
    NEXT("▶"),
    PLAY("▶"),
    PAUSE("❚❚"),
    PIN("📌"),
    MORE("...");

    private final String originalGlyph;

    AppIcon(String originalGlyph) {
        this.originalGlyph = originalGlyph;
    }

    /** Exact text used by the pre-icon-pack UI. */
    public String originalGlyph() {
        return originalGlyph;
    }
}
