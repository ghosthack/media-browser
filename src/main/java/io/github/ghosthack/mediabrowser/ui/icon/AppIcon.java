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
    MORE("..."),
    REFRESH("⟳"),
    CROP("▣"),
    SEAMLESS("▩"),
    DRAG_EXPORT("⇱"),
    LABELS("🏷"),
    FILTER("▽"),
    SORT("⇵"),
    SETTINGS("⚙"),
    INFO("ℹ"),
    METADATA("▤"),
    DIAGNOSTICS("📊"),
    ACTION_LOG("📜"),
    NAV_TREE("▥"),
    ADDRESS_BAR("▭"),
    STATUS_BAR("⬓"),
    FOCUS_MODE("◉"),
    GRID("▦"),
    SCREENSAVER("🌙");

    private final String originalGlyph;

    AppIcon(String originalGlyph) {
        this.originalGlyph = originalGlyph;
    }

    /** Exact text used by the pre-icon-pack UI. */
    public String originalGlyph() {
        return originalGlyph;
    }
}
