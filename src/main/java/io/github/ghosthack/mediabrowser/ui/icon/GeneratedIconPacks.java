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
            entry(AppIcon.MORE, PaintMode.FILL, "M3 10 H7 V14 H3 Z M10 10 H14 V14 H10 Z M17 10 H21 V14 H17 Z"),
            entry(AppIcon.REFRESH, PaintMode.FILL, "M6 3 H15 V7 H6 Z M3 6 H7 V18 H3 Z M6 17 H18 V21 H6 Z M17 10 H21 V18 H17 Z M15 1 L21 5 L15 9 Z"),
            entry(AppIcon.CROP, PaintMode.FILL, "M6 2 H9 V15 H22 V18 H6 Z M2 6 H18 V22 H15 V9 H2 Z"),
            entry(AppIcon.SEAMLESS, PaintMode.FILL, "M2 2 H11 V11 H2 Z M13 2 H22 V11 H13 Z M13 13 H22 V22 H13 Z M2 13 H11 V22 H2 Z"),
            entry(AppIcon.DRAG_EXPORT, PaintMode.FILL, "M3 3 H13 V6 H3 Z M3 6 H6 V21 H3 Z M6 18 H15 V21 H6 Z M13 2 L22 7 L13 12 V9 H8 V5 H13 Z"),
            entry(AppIcon.LABELS, PaintMode.FILL, "M4 4 H20 V8 H17 V6 H14 V20 H17 V22 H7 V20 H10 V6 H7 V8 H4 Z"),
            entry(AppIcon.FILTER, PaintMode.FILL, "M2 3 H22 V7 L15 13 V21 L9 18 V13 L2 7 Z"),
            entry(AppIcon.SORT, PaintMode.FILL, "M11 4 H22 V7 H11 Z M11 10 H19 V13 H11 Z M11 16 H16 V19 H11 Z M5 3 H9 V16 H13 L7 22 L1 16 H5 Z"),
            entry(AppIcon.SETTINGS, PaintMode.FILL, "M3 4 H21 V7 H3 Z M7 2 H10 V9 H7 Z M3 11 H21 V14 H3 Z M14 9 H17 V16 H14 Z M3 18 H21 V21 H3 Z M9 16 H12 V23 H9 Z"),
            entry(AppIcon.INFO, PaintMode.FILL, "M10 2 H14 V6 H10 Z M9 8 H14 V18 H17 V22 H7 V18 H10 V12 H9 Z"),
            entry(AppIcon.METADATA, PaintMode.FILL, "M2 3 H11 V21 H2 Z M13 3 H22 V6 H13 Z M13 8 H22 V11 H13 Z M13 13 H22 V16 H13 Z M13 18 H22 V21 H13 Z"),
            entry(AppIcon.DIAGNOSTICS, PaintMode.FILL, "M3 14 H7 V21 H3 Z M10 8 H14 V21 H10 Z M17 3 H21 V21 H17 Z"),
            entry(AppIcon.ACTION_LOG, PaintMode.FILL, "M2 4 H5 V7 H2 Z M8 4 H22 V7 H8 Z M2 11 H5 V14 H2 Z M8 11 H22 V14 H8 Z M2 18 H5 V21 H2 Z M8 18 H22 V21 H8 Z"),
            entry(AppIcon.NAV_TREE, PaintMode.FILL, "M2 3 H9 V21 H2 Z M11 3 H22 V6 H11 Z M11 8 H22 V11 H11 Z M11 13 H22 V16 H11 Z"),
            entry(AppIcon.ADDRESS_BAR, PaintMode.FILL, "M2 5 H22 V8 H2 Z M2 16 H22 V19 H2 Z M2 8 H5 V16 H2 Z M19 8 H22 V16 H19 Z M7 10 H17 V14 H7 Z"),
            entry(AppIcon.STATUS_BAR, PaintMode.FILL, "M2 3 H22 V6 H2 Z M2 18 H22 V21 H2 Z M2 6 H5 V18 H2 Z M19 6 H22 V18 H19 Z M2 14 H22 V17 H2 Z"),
            entry(AppIcon.FOCUS_MODE, PaintMode.FILL, "M2 2 H22 V5 H2 Z M2 19 H22 V22 H2 Z M2 2 H5 V22 H2 Z M19 2 H22 V22 H19 Z M9 9 H15 V15 H9 Z"),
            entry(AppIcon.GRID, PaintMode.FILL, "M3 3 H10 V10 H3 Z M14 3 H21 V10 H14 Z M14 14 H21 V21 H14 Z M3 14 H10 V21 H3 Z"),
            entry(AppIcon.SCREENSAVER, PaintMode.FILL, "M4 8 H15 V11 L10 18 H15 V21 H4 V18 L9 11 H4 Z M15 2 H21 V4 L18 8 H21 V10 H15 V8 L18 4 H15 Z")
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
            entry(AppIcon.MORE, PaintMode.STROKE, "M6 12 H6.01 M12 12 H12.01 M18 12 H18.01"),
            entry(AppIcon.REFRESH, PaintMode.STROKE, "M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"),
            entry(AppIcon.CROP, PaintMode.STROKE, "M6.13 1L6 16a2 2 0 0 0 2 2h15M1 6.13L16 6a2 2 0 0 1 2 2v15"),
            entry(AppIcon.SEAMLESS, PaintMode.STROKE, "M3 3h8v8H3zM13 3h8v8h-8zM13 13h8v8h-8zM3 13h8v8H3z"),
            entry(AppIcon.DRAG_EXPORT, PaintMode.STROKE, "M12 4H4v16h16v-8M13 8l7-6m0 0v6m0-6h-6"),
            entry(AppIcon.LABELS, PaintMode.STROKE, "M4 7V4h16v3M9 20h6M12 4v16"),
            entry(AppIcon.FILTER, PaintMode.STROKE, "M22 3H2l8 9.46V19l4 2v-8.54z"),
            entry(AppIcon.SORT, PaintMode.STROKE, "M11 5h10M11 9h7M11 13h4M3 17l3 3 3-3M6 20V4"),
            entry(AppIcon.SETTINGS, PaintMode.STROKE, "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"),
            entry(AppIcon.INFO, PaintMode.STROKE, "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01"),
            entry(AppIcon.METADATA, PaintMode.STROKE, "M21 3h-8v18h8a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1zM11 3H3a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h8z"),
            entry(AppIcon.DIAGNOSTICS, PaintMode.STROKE, "M22 12h-4l-3 9L9 3l-3 9H2"),
            entry(AppIcon.ACTION_LOG, PaintMode.STROKE, "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"),
            entry(AppIcon.NAV_TREE, PaintMode.STROKE, "M21 3H3a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zM9 3v18"),
            entry(AppIcon.ADDRESS_BAR, PaintMode.STROKE, "M2 6h20v12H2zM6 12h12"),
            entry(AppIcon.STATUS_BAR, PaintMode.STROKE, "M2 4h20v16H2zM2 16h20"),
            entry(AppIcon.FOCUS_MODE, PaintMode.STROKE, "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"),
            entry(AppIcon.GRID, PaintMode.STROKE, "M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z"),
            entry(AppIcon.SCREENSAVER, PaintMode.STROKE, "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z")
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
