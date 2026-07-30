package io.github.ghosthack.mediabrowser.ui.icon;

import io.github.ghosthack.mediabrowser.IconPack;

import java.util.Map;

/**
 * Generated vector geometry for non-original icon packs.
 *
 * <p>Regenerate with {@code java src/icon-packs/GenerateIconPacks.java};
 * do not hand-edit geometry here. Every path uses a normalized 24×24
 * coordinate system.</p>
 */
public final class GeneratedIconPacks {

    /** How JavaFX should paint an SVG path. */
    public enum PaintMode { STROKE, FILL }

    /** One complete icon, including any disconnected path segments. */
    public record Glyph(PaintMode paintMode, String svgPath) {
        public Glyph {
            if (paintMode == null) throw new IllegalArgumentException("paintMode");
            if (svgPath == null || svgPath.isBlank()) {
                throw new IllegalArgumentException("svgPath");
            }
        }
    }

    private static final Map<AppIcon, Glyph> ARCADE = Map.ofEntries(
            entry(AppIcon.UP, PaintMode.FILL, "M10 21 H14 V10 H20 L12 2 L4 10 H10 Z"),
            entry(AppIcon.FOLDER, PaintMode.FILL, "M2 7 H10 L12 9 H22 V20 H2 Z M2 4 H10 L12 7 H2 Z"),
            entry(AppIcon.ARCHIVE, PaintMode.FILL, "M3 4 H21 V8 H3 Z M4 9 H20 V20 H4 Z M9 12 H15 V15 H9 Z"),
            entry(AppIcon.IMAGE, PaintMode.FILL, "M3 4 H21 V7 H3 Z M3 17 H21 V20 H3 Z M3 7 H6 V17 H3 Z M18 7 H21 V17 H18 Z M6 15 L10 11 L13 14 L16 10 L18 12 V17 H6 Z M7 8 H10 V11 H7 Z"),
            entry(AppIcon.VIDEO, PaintMode.FILL, "M3 5 H16 V8 H6 V16 H16 V19 H3 Z M17 9 L22 6 V18 L17 15 Z"),
            entry(AppIcon.AUDIO, PaintMode.FILL, "M8 6 H20 V17 H17 V9 H11 V19 H8 Z M3 17 H11 V21 H3 Z M14 15 H22 V19 H14 Z"),
            entry(AppIcon.PREVIOUS, PaintMode.FILL, "M4 4 H8 V20 H4 Z M9 12 L20 4 V20 Z"),
            entry(AppIcon.NEXT, PaintMode.FILL, "M16 4 H20 V20 H16 Z M4 4 L15 12 L4 20 Z"),
            entry(AppIcon.PLAY, PaintMode.FILL, "M5 3 L21 12 L5 21 Z"),
            entry(AppIcon.PAUSE, PaintMode.FILL, "M6 4 H11 V20 H6 Z M14 4 H19 V20 H14 Z"),
            entry(AppIcon.PIN, PaintMode.FILL, "M8 3 H16 V6 H15 V11 L19 15 H14 V22 H10 V15 H5 L9 11 V6 H8 Z"),
            entry(AppIcon.MORE, PaintMode.FILL, "M3 10 H7 V14 H3 Z M10 10 H14 V14 H10 Z M17 10 H21 V14 H17 Z")
    );

    private static final Map<AppIcon, Glyph> LUCID = Map.ofEntries(
            entry(AppIcon.UP, PaintMode.STROKE, "M12 19 V5 M5 12 L12 5 L19 12"),
            entry(AppIcon.FOLDER, PaintMode.STROKE, "M3 8 V18 Q3 20 5 20 H19 Q21 20 21 18 V9 Q21 7 19 7 H12 L10 5 H5 Q3 5 3 7 Z"),
            entry(AppIcon.ARCHIVE, PaintMode.STROKE, "M4 7 H20 V20 H4 Z M3 4 H21 V7 H3 Z M9 11 H15"),
            entry(AppIcon.IMAGE, PaintMode.STROKE, "M4 5 H20 V19 H4 Z M7 16 L11 12 L14 15 L16 13 L20 17 M8 9 H8.01"),
            entry(AppIcon.VIDEO, PaintMode.STROKE, "M3 6 H16 V18 H3 Z M16 10 L21 7 V17 L16 14 Z"),
            entry(AppIcon.AUDIO, PaintMode.STROKE, "M9 18 V7 L19 5 V16 M9 10 L19 8 M9 18 C9 20 7 21 5 21 C3 21 2 20 2 19 C2 17 4 16 6 16 C8 16 9 17 9 18 M19 16 C19 18 17 19 15 19 C13 19 12 18 12 17 C12 15 14 14 16 14 C18 14 19 15 19 16"),
            entry(AppIcon.PREVIOUS, PaintMode.STROKE, "M18 5 L9 12 L18 19 M6 5 V19"),
            entry(AppIcon.NEXT, PaintMode.STROKE, "M6 5 L15 12 L6 19 M18 5 V19"),
            entry(AppIcon.PLAY, PaintMode.STROKE, "M7 4 L20 12 L7 20 Z"),
            entry(AppIcon.PAUSE, PaintMode.STROKE, "M8 5 V19 M16 5 V19"),
            entry(AppIcon.PIN, PaintMode.STROKE, "M9 4 H15 M10 4 L9 10 L6 13 H18 L15 10 L14 4 M12 13 V21"),
            entry(AppIcon.MORE, PaintMode.STROKE, "M6 12 H6.01 M12 12 H12.01 M18 12 H18.01")
    );

    private GeneratedIconPacks() {}

    /** Returns vector geometry, or {@code null} for the text-backed Original pack. */
    public static Glyph glyph(IconPack pack, AppIcon icon) {
        if (pack == null || icon == null || pack == IconPack.ORIGINAL) return null;
        return switch (pack) {
            case ORIGINAL -> null;
            case ARCADE -> ARCADE.get(icon);
            case LUCID -> LUCID.get(icon);
        };
    }

    private static Map.Entry<AppIcon, Glyph> entry(
            AppIcon icon, PaintMode mode, String svgPath) {
        return Map.entry(icon, new Glyph(mode, svgPath));
    }
}
