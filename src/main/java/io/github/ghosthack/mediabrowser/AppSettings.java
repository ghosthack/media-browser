package io.github.ghosthack.mediabrowser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persisted application settings, stored as a properties file at
 * {@code ~/.media-browser/app.properties}. Loaded once at startup;
 * {@link #save} writes the current values back. Settings that affect window
 * construction (such as {@link #undecoratedWindows()}) take effect on the
 * next start.
 */
public final class AppSettings {

    private static final Path FILE = Path.of(System.getProperty("user.home"),
            ".media-browser", "app.properties");
    private static final String UNDECORATED_KEY = "window.undecorated";
    private static final String UNDECORATED_RESIZABLE_KEY = "window.undecorated.resizable";
    private static final String MAXIMIZE_OVERSCAN_KEY = "window.maximize.overscan";
    private static final String VIEWER_DRAG_VIEWPORT_KEY = "viewer.dragViewport";
    private static final String MOSAIC_DRAG_BACKGROUND_KEY = "mosaic.dragBackground";
    private static final String IN_WINDOW_MENU_KEY = "menu.inWindow";
    private static final String KEYS_MODIFIER1_KEY = "keys.modifier1";
    private static final String KEYS_MODIFIER2_KEY = "keys.modifier2";
    private static final String WINDOW_MODE_KEY = "window.mode";
    private static final String STARTUP_LAYOUT_KEY = "startup.layout";
    /** Legacy startup keys, still read to migrate pre-{@code startup.layout} files. */
    private static final String STARTUP_MOSAIC_KEY = "startup.mosaic";
    private static final String STARTUP_TWO_WINDOW_KEY = "startup.twoWindow";
    private static final String BACKEND_KEY = "media.backend";
    private static final String DECODE_DEVICE_KEY = "decode.device";
    private static final String DETECTION_KEY = "media.detection";
    private static final String THEME_KEY = "ui.theme";
    private static final String THUMB_MAX_EDGE_KEY = "mosaic.thumbnail.maxEdge";
    private static final String THUMB_BUDGET_MB_KEY = "mosaic.thumbnail.memoryBudgetMb";
    private static final String MOSAIC_THUMBNAILS_VISIBLE_KEY = "mosaic.thumbnail.visible";
    private static final String MOSAIC_FILL_TILES_KEY = "mosaic.thumbnail.fill";
    private static final String MOSAIC_SEAMLESS_KEY = "mosaic.seamless";
    private static final String MOSAIC_AUTO_OPEN_KEY = "mosaic.autoOpen";
    private static final String MOSAIC_SELECTION_ANIMATION_KEY = "mosaic.selectionAnimation";
    /** Legacy boolean; read only to migrate older files to the enum above. */
    private static final String MOSAIC_PULSE_SELECTION_KEY = "mosaic.pulseSelection";
    private static final String MOSAIC_PULSE_PERIOD_KEY = "mosaic.pulsePeriodMs";
    private static final String MOSAIC_TILE_SIZE_KEY = "mosaic.tileSize";
    private static final String MOSAIC_MARGIN_KEY = "mosaic.margin";
    private static final String MOSAIC_BORDER_WIDTH_KEY = "mosaic.borderWidth";
    private static final String MOSAIC_BORDER_COLOR_KEY = "mosaic.borderColor";
    private static final String MOSAIC_FOLDER_PREVIEW_GRID_KEY = "mosaic.folderPreview.grid";
    private static final String MOSAIC_FOLDER_GLYPH_KEY = "mosaic.folderGlyph";
    /** Legacy boolean; read only to migrate older files to the enum above. */
    private static final String MOSAIC_FOLDER_IMAGE_KEY = "mosaic.folderImage";
    private static final String MOSAIC_DIR_LABELS_KEY = "mosaic.labels.dirs";
    private static final String MOSAIC_FILE_LABELS_KEY = "mosaic.labels.files";
    private static final String MOSAIC_MEDIA_LABELS_KEY = "mosaic.labels.media";
    private static final String MOVE_HISTORY_LIMIT_KEY = "move.history.limit";
    /** Recent move targets are stored as indexed keys {@code move.history.<n>}. */
    private static final String MOVE_HISTORY_ENTRY_PREFIX = "move.history.";
    /** Recently-used album files are stored as indexed keys {@code album.recent.<n>}. */
    private static final String ALBUM_RECENT_ENTRY_PREFIX = "album.recent.";
    /** How many recent album file names are retained (the menu surfaces the first few). */
    private static final int ALBUM_RECENT_LIMIT = 16;

    /**
     * Per-window chrome-visibility startup defaults. Each window seeds its menu
     * bar / toolbar / status bar (and the browser's navigation tree) from these
     * on construction; the Settings dialog also pushes changes live.
     */
    private static final String BROWSER_MENU_BAR_KEY = "browser.menuBar.visible";
    private static final String BROWSER_TOOLBAR_KEY = "browser.toolbar.visible";
    private static final String BROWSER_STATUS_BAR_KEY = "browser.statusBar.visible";
    private static final String BROWSER_NAV_TREE_KEY = "browser.navTree.visible";
    private static final String BROWSER_ACTION_LOG_KEY = "browser.actionLog.visible";
    private static final String VIEWER_MENU_BAR_KEY = "viewer.menuBar.visible";
    private static final String VIEWER_TOOLBAR_KEY = "viewer.toolbar.visible";
    private static final String VIEWER_STATUS_BAR_KEY = "viewer.statusBar.visible";
    /** Viewer slideshow (timed auto-advance) config; the toggle itself is runtime-only. */
    private static final String VIEWER_SLIDESHOW_INTERVAL_KEY = "viewer.slideshow.intervalSeconds";
    private static final String VIEWER_SLIDESHOW_REVERSE_KEY = "viewer.slideshow.reverse";
    /** Viewer flipbook (buffered image-sequence playback) config; the toggle itself is runtime-only. */
    private static final String VIEWER_FLIPBOOK_FPS_KEY = "viewer.flipbook.fps";
    private static final String VIEWER_LOADING_INDICATOR_KEY = "viewer.loadingIndicator";
    private static final String VIEWER_LOADING_INDICATOR_DELAY_KEY = "viewer.loadingIndicator.delayMs";
    private static final String MOSAIC_MENU_BAR_KEY = "mosaic.menuBar.visible";
    private static final String MOSAIC_TOOLBAR_KEY = "mosaic.toolbar.visible";
    private static final String MOSAIC_STATUS_BAR_KEY = "mosaic.statusBar.visible";
    private static final String MOSAIC_LOCATION_BAR_KEY = "mosaic.locationBar.visible";
    private static final String MOSAIC_ACTION_LOG_KEY = "mosaic.actionLog.visible";
    /** App-wide: mirror the action log into the append-only JSONL file. */
    private static final String ACTION_LOG_FILE_KEY = "actionLog.file";

    /** Viewer slideshow defaults: 3-second forward auto-advance, interval clamped 1..10s. */
    private static final int DEFAULT_VIEWER_SLIDESHOW_INTERVAL = 3;
    private static final int MIN_VIEWER_SLIDESHOW_INTERVAL = 1;
    private static final int MAX_VIEWER_SLIDESHOW_INTERVAL = 10;

    /** Viewer flipbook default: 24 fps looping playback, frame rate clamped 1..60. */
    private static final int DEFAULT_VIEWER_FLIPBOOK_FPS = 24;
    private static final int MIN_VIEWER_FLIPBOOK_FPS = 1;
    private static final int MAX_VIEWER_FLIPBOOK_FPS = 60;

    /** Defaults for the mosaic preview renditions. */
    private static final int DEFAULT_THUMB_MAX_EDGE = 300;
    private static final int DEFAULT_THUMB_BUDGET_MB = 256;

    /** Defaults for the mosaic grid layout. */
    private static final int DEFAULT_MOSAIC_TILE_SIZE = 100;
    private static final int DEFAULT_MOSAIC_MARGIN = 10;
    private static final int DEFAULT_MOSAIC_BORDER_WIDTH = 1;
    private static final String DEFAULT_MOSAIC_BORDER_COLOR = "#3c3c3c";
    /** Folder-tile preview collage edge (N for an N×N grid; 0 = none). */
    private static final int DEFAULT_MOSAIC_FOLDER_PREVIEW_GRID = 2;
    /** Default selection-animation style (none). */
    private static final MosaicSelectionAnimation DEFAULT_MOSAIC_SELECTION_ANIMATION =
            MosaicSelectionAnimation.NONE;
    /** Default folder-glyph style (the muted gray vector shape). */
    private static final MosaicFolderGlyph DEFAULT_MOSAIC_FOLDER_GLYPH =
            MosaicFolderGlyph.GLYPH;
    private static final LoadingIndicator DEFAULT_VIEWER_LOADING_INDICATOR =
            LoadingIndicator.GAME_CONSOLE;
    /**
     * Delay, in milliseconds, before the viewer shows its loading indicator: a
     * grace period that gates the indicator so a fast/cached decode landing
     * within it never flashes one up. {@code 0} shows it at once. Defaults to
     * {@code 200}, clamped to {@code [0, 5000]}.
     */
    private static final int DEFAULT_VIEWER_LOADING_INDICATOR_DELAY_MS = 200;
    private static final int MIN_VIEWER_LOADING_INDICATOR_DELAY_MS = 0;
    private static final int MAX_VIEWER_LOADING_INDICATOR_DELAY_MS = 5000;
    /** Full selection-animation cycle duration, in milliseconds, and its bounds. */
    private static final int DEFAULT_MOSAIC_PULSE_PERIOD_MS = 1600;
    private static final int MIN_MOSAIC_PULSE_PERIOD_MS = 200;
    private static final int MAX_MOSAIC_PULSE_PERIOD_MS = 10000;

    /** Defaults and bounds for the move-history list. */
    private static final int DEFAULT_MOVE_HISTORY_LIMIT = 10;
    private static final int MIN_MOVE_HISTORY_LIMIT = 1;
    private static final int MAX_MOVE_HISTORY_LIMIT = 100;

    private boolean undecoratedWindows;
    private boolean undecoratedResizable;
    private boolean maximizeOverscan;
    private boolean viewerDragViewport;
    private boolean mosaicDragBackground;
    private boolean inWindowMenu;
    /**
     * Logical modifier mapping tokens for the menu-accelerator scheme. Parsed by
     * {@code ui.KeyScheme}; {@code "auto"} (the default) means the platform
     * default — {@code modifier1} = Command on macOS / Control elsewhere, and
     * {@code modifier2} = Option on macOS / Alt elsewhere. Other tokens
     * ({@code control}, {@code command}, {@code alt}) force a concrete physical
     * modifier regardless of platform. Takes effect on the next start.
     */
    private String keysModifier1;
    private String keysModifier2;
    private WindowMode windowMode;
    private StartupLayout startupLayout;
    private String mediaBackend;
    private String decodeDevice;
    private String detectionMode;
    private Theme theme;
    private int thumbnailMaxEdge;
    private int thumbnailMemoryBudgetMb;
    private boolean mosaicThumbnailsVisible;
    private boolean mosaicFillTiles;
    private boolean mosaicSeamless;
    private boolean mosaicAutoOpen;
    private MosaicSelectionAnimation mosaicSelectionAnimation;
    private int mosaicPulsePeriodMs;
    private int mosaicTileSize;
    private int mosaicMargin;
    private int mosaicBorderWidth;
    private String mosaicBorderColor;
    private int mosaicFolderPreviewGrid;
    private MosaicFolderGlyph mosaicFolderGlyph;
    private boolean mosaicDirLabelsVisible;
    private boolean mosaicFileLabelsVisible;
    private boolean mosaicMediaLabelsVisible;
    private List<String> moveHistory = new ArrayList<>();
    private int moveHistoryLimit = DEFAULT_MOVE_HISTORY_LIMIT;
    private List<String> albumRecents = new ArrayList<>();
    private boolean browserMenuBarVisible;
    private boolean browserToolbarVisible;
    private boolean browserStatusBarVisible;
    private boolean browserNavTreeVisible;
    private boolean browserActionLogVisible;
    private boolean viewerMenuBarVisible;
    private boolean viewerToolbarVisible;
    private boolean viewerStatusBarVisible;
    private int viewerSlideshowIntervalSeconds = DEFAULT_VIEWER_SLIDESHOW_INTERVAL;
    private LoadingIndicator viewerLoadingIndicator = DEFAULT_VIEWER_LOADING_INDICATOR;
    private int viewerLoadingIndicatorDelayMs = DEFAULT_VIEWER_LOADING_INDICATOR_DELAY_MS;
    private boolean viewerSlideshowReverse;
    private int viewerFlipbookFps = DEFAULT_VIEWER_FLIPBOOK_FPS;
    private boolean mosaicMenuBarVisible;
    private boolean mosaicToolbarVisible;
    private boolean mosaicStatusBarVisible;
    private boolean mosaicLocationBarVisible;
    private boolean mosaicActionLogVisible;
    private boolean actionLogFileEnabled;
    /**
     * The quick-move ({@code F1}–{@code F4}) toggle. Transient: shared
     * process-wide via this singleton so the checkbox state is consistent across
     * the main, mosaic and viewer move dialogs, but deliberately <em>not</em>
     * loaded from / saved to {@code app.properties} — it resets each run.
     */
    private boolean quickMoveShortcutsEnabled;

    private AppSettings() {}

    /** Reads the default settings file; missing file or bad values mean defaults. */
    public static AppSettings load() {
        return load(FILE);
    }

    /** Reads {@code file}; missing file or unreadable values mean defaults. */
    static AppSettings load(Path file) {
        var props = new Properties();
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Cannot read " + file + ": " + e.getMessage());
            }
        }
        var settings = new AppSettings();
        settings.undecoratedWindows =
                Boolean.parseBoolean(props.getProperty(UNDECORATED_KEY, "false"));
        settings.undecoratedResizable =
                Boolean.parseBoolean(props.getProperty(UNDECORATED_RESIZABLE_KEY, "true"));
        settings.maximizeOverscan =
                Boolean.parseBoolean(props.getProperty(MAXIMIZE_OVERSCAN_KEY, "true"));
        settings.viewerDragViewport =
                Boolean.parseBoolean(props.getProperty(VIEWER_DRAG_VIEWPORT_KEY, "false"));
        settings.mosaicDragBackground =
                Boolean.parseBoolean(props.getProperty(MOSAIC_DRAG_BACKGROUND_KEY, "true"));
        settings.inWindowMenu =
                Boolean.parseBoolean(props.getProperty(IN_WINDOW_MENU_KEY, "false"));
        settings.keysModifier1 = props.getProperty(KEYS_MODIFIER1_KEY, "auto");
        settings.keysModifier2 = props.getProperty(KEYS_MODIFIER2_KEY, "auto");
        settings.windowMode = WindowMode.fromSettings(
                props.getProperty(WINDOW_MODE_KEY), WindowMode.SINGLE);
        settings.startupLayout = StartupLayout.fromSettings(
                props.getProperty(STARTUP_LAYOUT_KEY), legacyStartupLayout(props));
        settings.mediaBackend = props.getProperty(BACKEND_KEY, "ffmpeg-ffm-turbojpeg");
        settings.decodeDevice = props.getProperty(DECODE_DEVICE_KEY, "auto");
        settings.detectionMode = props.getProperty(DETECTION_KEY, "extension");
        settings.theme = Theme.fromSettings(props.getProperty(THEME_KEY), Theme.PLAIN_DARK_GRAY);
        settings.thumbnailMaxEdge = parseBoundedInt(
                props.getProperty(THUMB_MAX_EDGE_KEY), DEFAULT_THUMB_MAX_EDGE, 32, 2048);
        settings.thumbnailMemoryBudgetMb = parseBoundedInt(
                props.getProperty(THUMB_BUDGET_MB_KEY), DEFAULT_THUMB_BUDGET_MB, 16, 8192);
        settings.mosaicThumbnailsVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_THUMBNAILS_VISIBLE_KEY, "true"));
        settings.mosaicFillTiles =
                Boolean.parseBoolean(props.getProperty(MOSAIC_FILL_TILES_KEY, "false"));
        settings.mosaicSeamless =
                Boolean.parseBoolean(props.getProperty(MOSAIC_SEAMLESS_KEY, "true"));
        settings.mosaicAutoOpen =
                Boolean.parseBoolean(props.getProperty(MOSAIC_AUTO_OPEN_KEY, "true"));
        // Prefer the new enum key; fall back to migrating the legacy boolean
        // (true → PULSE) so existing users keep their game-style highlight.
        MosaicSelectionAnimation legacyAnim = Boolean.parseBoolean(
                props.getProperty(MOSAIC_PULSE_SELECTION_KEY, "false"))
                ? MosaicSelectionAnimation.PULSE
                : MosaicSelectionAnimation.NONE;
        settings.mosaicSelectionAnimation = MosaicSelectionAnimation.fromSettings(
                props.getProperty(MOSAIC_SELECTION_ANIMATION_KEY), legacyAnim);
        settings.mosaicPulsePeriodMs = parseBoundedInt(
                props.getProperty(MOSAIC_PULSE_PERIOD_KEY), DEFAULT_MOSAIC_PULSE_PERIOD_MS,
                MIN_MOSAIC_PULSE_PERIOD_MS, MAX_MOSAIC_PULSE_PERIOD_MS);
        settings.mosaicTileSize = parseBoundedInt(
                props.getProperty(MOSAIC_TILE_SIZE_KEY), DEFAULT_MOSAIC_TILE_SIZE, 64, 512);
        settings.mosaicMargin = parseBoundedInt(
                props.getProperty(MOSAIC_MARGIN_KEY), DEFAULT_MOSAIC_MARGIN, 0, 128);
        settings.mosaicBorderWidth = parseBoundedInt(
                props.getProperty(MOSAIC_BORDER_WIDTH_KEY), DEFAULT_MOSAIC_BORDER_WIDTH, 0, 32);
        settings.mosaicBorderColor = props.getProperty(
                MOSAIC_BORDER_COLOR_KEY, DEFAULT_MOSAIC_BORDER_COLOR);
        settings.mosaicFolderPreviewGrid = parseBoundedInt(
                props.getProperty(MOSAIC_FOLDER_PREVIEW_GRID_KEY),
                DEFAULT_MOSAIC_FOLDER_PREVIEW_GRID, 0, 4);
        // Prefer the new enum key; fall back to migrating the legacy boolean
        // (true → IMAGE) so existing users keep their photographic folders.
        MosaicFolderGlyph legacyGlyph = Boolean.parseBoolean(
                props.getProperty(MOSAIC_FOLDER_IMAGE_KEY, "false"))
                ? MosaicFolderGlyph.IMAGE
                : DEFAULT_MOSAIC_FOLDER_GLYPH;
        settings.mosaicFolderGlyph = MosaicFolderGlyph.fromSettings(
                props.getProperty(MOSAIC_FOLDER_GLYPH_KEY), legacyGlyph);
        // Tile name labels: shown for folders and non-media files by default,
        // hidden for media tiles (their imagery already identifies them).
        settings.mosaicDirLabelsVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_DIR_LABELS_KEY, "true"));
        settings.mosaicFileLabelsVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_FILE_LABELS_KEY, "true"));
        settings.mosaicMediaLabelsVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_MEDIA_LABELS_KEY, "true"));
        settings.moveHistoryLimit = parseBoundedInt(
                props.getProperty(MOVE_HISTORY_LIMIT_KEY), DEFAULT_MOVE_HISTORY_LIMIT,
                MIN_MOVE_HISTORY_LIMIT, MAX_MOVE_HISTORY_LIMIT);
        settings.moveHistory = loadMoveHistory(props, settings.moveHistoryLimit);
        settings.albumRecents = loadAlbumRecents(props);
        // Per-window chrome visibility. Defaults: the browser, viewer and mosaic
        // menu bars and the browser nav tree shown, all three toolbars hidden,
        // and the browser and mosaic status bars shown.
        settings.browserMenuBarVisible =
                Boolean.parseBoolean(props.getProperty(BROWSER_MENU_BAR_KEY, "true"));
        settings.browserToolbarVisible =
                Boolean.parseBoolean(props.getProperty(BROWSER_TOOLBAR_KEY, "false"));
        settings.browserStatusBarVisible =
                Boolean.parseBoolean(props.getProperty(BROWSER_STATUS_BAR_KEY, "true"));
        settings.browserNavTreeVisible =
                Boolean.parseBoolean(props.getProperty(BROWSER_NAV_TREE_KEY, "true"));
        settings.browserActionLogVisible =
                Boolean.parseBoolean(props.getProperty(BROWSER_ACTION_LOG_KEY, "false"));
        settings.viewerMenuBarVisible =
                Boolean.parseBoolean(props.getProperty(VIEWER_MENU_BAR_KEY, "true"));
        settings.viewerToolbarVisible =
                Boolean.parseBoolean(props.getProperty(VIEWER_TOOLBAR_KEY, "false"));
        settings.viewerStatusBarVisible =
                Boolean.parseBoolean(props.getProperty(VIEWER_STATUS_BAR_KEY, "false"));
        settings.viewerSlideshowIntervalSeconds = parseBoundedInt(
                props.getProperty(VIEWER_SLIDESHOW_INTERVAL_KEY), DEFAULT_VIEWER_SLIDESHOW_INTERVAL,
                MIN_VIEWER_SLIDESHOW_INTERVAL, MAX_VIEWER_SLIDESHOW_INTERVAL);
        settings.viewerSlideshowReverse =
                Boolean.parseBoolean(props.getProperty(VIEWER_SLIDESHOW_REVERSE_KEY, "false"));
        settings.viewerFlipbookFps = parseBoundedInt(
                props.getProperty(VIEWER_FLIPBOOK_FPS_KEY), DEFAULT_VIEWER_FLIPBOOK_FPS,
                MIN_VIEWER_FLIPBOOK_FPS, MAX_VIEWER_FLIPBOOK_FPS);
        settings.viewerLoadingIndicator = LoadingIndicator.fromSettings(
                props.getProperty(VIEWER_LOADING_INDICATOR_KEY), DEFAULT_VIEWER_LOADING_INDICATOR);
        settings.viewerLoadingIndicatorDelayMs = parseBoundedInt(
                props.getProperty(VIEWER_LOADING_INDICATOR_DELAY_KEY),
                DEFAULT_VIEWER_LOADING_INDICATOR_DELAY_MS,
                MIN_VIEWER_LOADING_INDICATOR_DELAY_MS, MAX_VIEWER_LOADING_INDICATOR_DELAY_MS);
        settings.mosaicMenuBarVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_MENU_BAR_KEY, "true"));
        settings.mosaicToolbarVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_TOOLBAR_KEY, "false"));
        settings.mosaicStatusBarVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_STATUS_BAR_KEY, "true"));
        settings.mosaicLocationBarVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_LOCATION_BAR_KEY, "false"));
        settings.mosaicActionLogVisible =
                Boolean.parseBoolean(props.getProperty(MOSAIC_ACTION_LOG_KEY, "false"));
        settings.actionLogFileEnabled =
                Boolean.parseBoolean(props.getProperty(ACTION_LOG_FILE_KEY, "false"));
        return settings;
    }

    /** Reads contiguous {@code move.history.<n>} entries, trimmed to {@code limit}. */
    private static List<String> loadMoveHistory(Properties props, int limit) {
        List<String> history = new ArrayList<>();
        for (int i = 0; i < MAX_MOVE_HISTORY_LIMIT; i++) {
            String entry = props.getProperty(MOVE_HISTORY_ENTRY_PREFIX + i);
            if (entry == null) {
                break;
            }
            entry = entry.trim();
            // Skip entries that aren't parseable as a path. A bad value (e.g. a
            // legacy file written with unescaped backslashes, so Properties.load
            // turns "\t" into a TAB) would otherwise crash the move dialog when it
            // calls Paths.get; dropping it here also self-heals the file on save.
            if (!entry.isEmpty() && isParseablePath(entry)) {
                history.add(entry);
            }
        }
        while (history.size() > limit) {
            history.remove(history.size() - 1);
        }
        return history;
    }

    /** Reads contiguous {@code album.recent.<n>} entries (album file names). */
    private static List<String> loadAlbumRecents(Properties props) {
        List<String> recents = new ArrayList<>();
        for (int i = 0; i < ALBUM_RECENT_LIMIT; i++) {
            String entry = props.getProperty(ALBUM_RECENT_ENTRY_PREFIX + i);
            if (entry == null) {
                break;
            }
            entry = entry.trim();
            if (!entry.isEmpty()) {
                recents.add(entry);
            }
        }
        return recents;
    }

    /** Whether {@code value} is a syntactically valid path on this platform. */
    private static boolean isParseablePath(String value) {
        try {
            Path.of(value);
            return true;
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * Derives the startup layout from the legacy {@code startup.mosaic} /
     * {@code startup.twoWindow} booleans, used only when the newer
     * {@code startup.layout} key is absent. A fresh install (no keys) maps to
     * {@link StartupLayout#MOSAIC_VIEWER} — mosaic left, viewer right.
     */
    private static StartupLayout legacyStartupLayout(Properties props) {
        boolean mosaic = Boolean.parseBoolean(props.getProperty(STARTUP_MOSAIC_KEY, "true"));
        boolean twoWindow = Boolean.parseBoolean(props.getProperty(STARTUP_TWO_WINDOW_KEY, "true"));
        if (!mosaic) return StartupLayout.BROWSER;
        return twoWindow ? StartupLayout.MOSAIC_VIEWER : StartupLayout.MOSAIC;
    }

    /** Parses an int property, clamping to [min, max]; falls back on bad input. */
    private static int parseBoundedInt(String value, int fallback, int min, int max) {
        int n = fallback;
        if (value != null) {
            try {
                n = Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // keep fallback
            }
        }
        return Math.max(min, Math.min(max, n));
    }

    /** Writes the current values back to the default settings file. */
    public void save() throws IOException {
        save(FILE);
    }

    /** Writes the current values to {@code file}, creating parent dirs. */
    void save(Path file) throws IOException {
        var props = new Properties();
        props.setProperty(UNDECORATED_KEY, Boolean.toString(undecoratedWindows));
        props.setProperty(UNDECORATED_RESIZABLE_KEY,
                Boolean.toString(undecoratedResizable));
        props.setProperty(MAXIMIZE_OVERSCAN_KEY, Boolean.toString(maximizeOverscan));
        props.setProperty(VIEWER_DRAG_VIEWPORT_KEY, Boolean.toString(viewerDragViewport));
        props.setProperty(MOSAIC_DRAG_BACKGROUND_KEY,
                Boolean.toString(mosaicDragBackground));
        props.setProperty(IN_WINDOW_MENU_KEY, Boolean.toString(inWindowMenu));
        props.setProperty(KEYS_MODIFIER1_KEY, keysModifier1);
        props.setProperty(KEYS_MODIFIER2_KEY, keysModifier2);
        props.setProperty(WINDOW_MODE_KEY, windowMode.name());
        props.setProperty(STARTUP_LAYOUT_KEY, startupLayout.name());
        props.setProperty(BACKEND_KEY, mediaBackend);
        props.setProperty(DECODE_DEVICE_KEY, decodeDevice);
        props.setProperty(DETECTION_KEY, detectionMode);
        props.setProperty(THEME_KEY, theme.name());
        props.setProperty(THUMB_MAX_EDGE_KEY, Integer.toString(thumbnailMaxEdge));
        props.setProperty(THUMB_BUDGET_MB_KEY, Integer.toString(thumbnailMemoryBudgetMb));
        props.setProperty(MOSAIC_THUMBNAILS_VISIBLE_KEY,
                Boolean.toString(mosaicThumbnailsVisible));
        props.setProperty(MOSAIC_FILL_TILES_KEY, Boolean.toString(mosaicFillTiles));
        props.setProperty(MOSAIC_SEAMLESS_KEY, Boolean.toString(mosaicSeamless));
        props.setProperty(MOSAIC_AUTO_OPEN_KEY, Boolean.toString(mosaicAutoOpen));
        props.setProperty(MOSAIC_SELECTION_ANIMATION_KEY, mosaicSelectionAnimation.name());
        props.setProperty(MOSAIC_PULSE_PERIOD_KEY, Integer.toString(mosaicPulsePeriodMs));
        props.setProperty(MOSAIC_TILE_SIZE_KEY, Integer.toString(mosaicTileSize));
        props.setProperty(MOSAIC_MARGIN_KEY, Integer.toString(mosaicMargin));
        props.setProperty(MOSAIC_BORDER_WIDTH_KEY, Integer.toString(mosaicBorderWidth));
        props.setProperty(MOSAIC_BORDER_COLOR_KEY, mosaicBorderColor);
        props.setProperty(MOSAIC_FOLDER_PREVIEW_GRID_KEY,
                Integer.toString(mosaicFolderPreviewGrid));
        props.setProperty(MOSAIC_FOLDER_GLYPH_KEY, mosaicFolderGlyph.name());
        props.setProperty(MOSAIC_DIR_LABELS_KEY, Boolean.toString(mosaicDirLabelsVisible));
        props.setProperty(MOSAIC_FILE_LABELS_KEY, Boolean.toString(mosaicFileLabelsVisible));
        props.setProperty(MOSAIC_MEDIA_LABELS_KEY, Boolean.toString(mosaicMediaLabelsVisible));
        props.setProperty(MOVE_HISTORY_LIMIT_KEY, Integer.toString(moveHistoryLimit));
        for (int i = 0; i < moveHistory.size(); i++) {
            props.setProperty(MOVE_HISTORY_ENTRY_PREFIX + i, moveHistory.get(i));
        }
        for (int i = 0; i < albumRecents.size(); i++) {
            props.setProperty(ALBUM_RECENT_ENTRY_PREFIX + i, albumRecents.get(i));
        }
        props.setProperty(BROWSER_MENU_BAR_KEY, Boolean.toString(browserMenuBarVisible));
        props.setProperty(BROWSER_TOOLBAR_KEY, Boolean.toString(browserToolbarVisible));
        props.setProperty(BROWSER_STATUS_BAR_KEY, Boolean.toString(browserStatusBarVisible));
        props.setProperty(BROWSER_NAV_TREE_KEY, Boolean.toString(browserNavTreeVisible));
        props.setProperty(BROWSER_ACTION_LOG_KEY, Boolean.toString(browserActionLogVisible));
        props.setProperty(VIEWER_MENU_BAR_KEY, Boolean.toString(viewerMenuBarVisible));
        props.setProperty(VIEWER_TOOLBAR_KEY, Boolean.toString(viewerToolbarVisible));
        props.setProperty(VIEWER_STATUS_BAR_KEY, Boolean.toString(viewerStatusBarVisible));
        props.setProperty(VIEWER_SLIDESHOW_INTERVAL_KEY, Integer.toString(viewerSlideshowIntervalSeconds));
        props.setProperty(VIEWER_SLIDESHOW_REVERSE_KEY, Boolean.toString(viewerSlideshowReverse));
        props.setProperty(VIEWER_FLIPBOOK_FPS_KEY, Integer.toString(viewerFlipbookFps));
        props.setProperty(VIEWER_LOADING_INDICATOR_KEY, viewerLoadingIndicator.name());
        props.setProperty(VIEWER_LOADING_INDICATOR_DELAY_KEY,
                Integer.toString(viewerLoadingIndicatorDelayMs));
        props.setProperty(MOSAIC_MENU_BAR_KEY, Boolean.toString(mosaicMenuBarVisible));
        props.setProperty(MOSAIC_TOOLBAR_KEY, Boolean.toString(mosaicToolbarVisible));
        props.setProperty(MOSAIC_STATUS_BAR_KEY, Boolean.toString(mosaicStatusBarVisible));
        props.setProperty(MOSAIC_LOCATION_BAR_KEY, Boolean.toString(mosaicLocationBarVisible));
        props.setProperty(MOSAIC_ACTION_LOG_KEY, Boolean.toString(mosaicActionLogVisible));
        props.setProperty(ACTION_LOG_FILE_KEY, Boolean.toString(actionLogFileEnabled));
        Files.createDirectories(file.getParent());
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "Media Browser settings");
        }
    }

    /** Render windows without OS chrome (title bar, borders). */
    public boolean undecoratedWindows() {
        return undecoratedWindows;
    }

    public void setUndecoratedWindows(boolean undecorated) {
        this.undecoratedWindows = undecorated;
    }

    /** Allow edge-drag resizing of chromeless windows; no effect when decorated. */
    public boolean undecoratedResizable() {
        return undecoratedResizable;
    }

    public void setUndecoratedResizable(boolean resizable) {
        this.undecoratedResizable = resizable;
    }

    /**
     * Whether an {@code F} maximize overscans past the screen edges to hide a
     * chromeless window's hairline border (macOS only; no effect on decorated
     * windows, which keep their normal chrome). Defaults to {@code true}.
     */
    public boolean maximizeOverscan() {
        return maximizeOverscan;
    }

    public void setMaximizeOverscan(boolean overscan) {
        this.maximizeOverscan = overscan;
    }

    /**
     * Whether dragging the viewer's viewport (the image/media area) moves the
     * window, the way the toolbar and menu bar do for a chromeless window.
     * Useful for repositioning a borderless viewer that has no title bar to
     * grab. Applies live (the viewer's drag handler reads this each drag).
     * Defaults to {@code false}.
     */
    public boolean viewerDragViewport() {
        return viewerDragViewport;
    }

    public void setViewerDragViewport(boolean drag) {
        this.viewerDragViewport = drag;
    }

    /**
     * Whether dragging the mosaic grid's empty black background (a press that
     * hits no tile) moves the window. Tile presses keep selecting as before.
     * Applies live (the mosaic's drag handler reads this each drag).
     * Defaults to {@code true}.
     */
    public boolean mosaicDragBackground() {
        return mosaicDragBackground;
    }

    public void setMosaicDragBackground(boolean drag) {
        this.mosaicDragBackground = drag;
    }

    /**
     * Render the application menu in-window (a strip at the top of each window)
     * instead of hoisting it to the macOS system menu bar. Defaults to
     * {@code false}; ignored off macOS, where the menu is always in-window.
     * Takes effect on the next start.
     */
    public boolean inWindowMenu() {
        return inWindowMenu;
    }

    public void setInWindowMenu(boolean inWindow) {
        this.inWindowMenu = inWindow;
    }

    /**
     * The logical-modifier-1 mapping token (default {@code "auto"}). {@code "auto"}
     * resolves to Command on macOS / Control elsewhere; {@code "control"},
     * {@code "command"} and {@code "alt"} force a concrete physical modifier.
     * Parsed by {@code ui.KeyScheme}; takes effect on the next start.
     */
    public String keysModifier1() {
        return keysModifier1;
    }

    public void setKeysModifier1(String token) {
        this.keysModifier1 = token == null || token.isBlank() ? "auto" : token;
    }

    /**
     * The logical-modifier-2 mapping token (default {@code "auto"}). {@code "auto"}
     * resolves to Option on macOS / Alt elsewhere; {@code "control"},
     * {@code "command"} and {@code "alt"} force a concrete physical modifier.
     * Parsed by {@code ui.KeyScheme}; takes effect on the next start.
     */
    public String keysModifier2() {
        return keysModifier2;
    }

    public void setKeysModifier2(String token) {
        this.keysModifier2 = token == null || token.isBlank() ? "auto" : token;
    }

    /**
     * Which windows the application opens, and how it tiles them, on startup
     * (e.g. just the browser, just the mosaic, the mosaic and viewer tiled into
     * halves, or the browser, mosaic and viewer tiled into thirds). Defaults to
     * {@link StartupLayout#MOSAIC_VIEWER}. Takes effect on the next start.
     */
    public StartupLayout startupLayout() {
        return startupLayout;
    }

    /**
     * How the three views are hosted: one shared window ({@link WindowMode#SINGLE},
     * the default) or the classic separate windows ({@link WindowMode#MULTI}).
     * Takes effect on the next start.
     */
    public WindowMode windowMode() {
        return windowMode;
    }

    public void setWindowMode(WindowMode windowMode) {
        this.windowMode = windowMode == null ? WindowMode.SINGLE : windowMode;
    }

    public void setStartupLayout(StartupLayout startupLayout) {
        this.startupLayout = startupLayout == null ? StartupLayout.MOSAIC_VIEWER : startupLayout;
    }

    /** Native decode backend, e.g. {@code "ffmpeg-ffm-turbojpeg"} (default), {@code "ffmpeg-ffm"}, {@code "apple"}. */
    public String mediaBackend() {
        return mediaBackend;
    }

    public void setMediaBackend(String backend) {
        this.mediaBackend = backend;
    }

    /**
     * Playback decode policy for the bundled-FFmpeg backends:
     * {@code "auto"} (default; hardware when the codec and device support it),
     * {@code "software"}, or {@code "hardware"} (required — fails loudly).
     * Applied by {@code media.ffm.HwDecode}.
     */
    public String decodeDevice() {
        return decodeDevice;
    }

    public void setDecodeDevice(String decodeDevice) {
        this.decodeDevice = decodeDevice == null || decodeDevice.isBlank()
                ? "auto" : decodeDevice;
    }

    /** Media detection method: {@code "content"} (default) or {@code "extension"}. */
    public String detectionMode() {
        return detectionMode;
    }

    public void setDetectionMode(String mode) {
        this.detectionMode = mode;
    }

    /**
     * The application look applied across every window (default
     * {@link Theme#PLAIN_DARK_GRAY}). Applied live by {@code ui.ThemeManager} when
     * changed in the Settings dialog.
     */
    public Theme theme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme == null ? Theme.DEFAULT : theme;
    }

    /** Longest-edge pixel cap for mosaic preview renditions (default 300). */
    public int thumbnailMaxEdge() {
        return thumbnailMaxEdge;
    }

    public void setThumbnailMaxEdge(int maxEdge) {
        this.thumbnailMaxEdge = maxEdge;
    }

    /** In-memory budget for cached mosaic renditions, in MiB (default 256). */
    public int thumbnailMemoryBudgetMb() {
        return thumbnailMemoryBudgetMb;
    }

    public void setThumbnailMemoryBudgetMb(int budgetMb) {
        this.thumbnailMemoryBudgetMb = budgetMb;
    }

    /** The rendition memory budget as a byte count, for the cache. */
    public long thumbnailMemoryBudgetBytes() {
        return (long) thumbnailMemoryBudgetMb * 1024 * 1024;
    }

    /**
     * Whether mosaic media thumbnails and folder preview collages are drawn and
     * requested. Defaults to {@code true}.
     */
    public boolean mosaicThumbnailsVisible() {
        return mosaicThumbnailsVisible;
    }

    public void setMosaicThumbnailsVisible(boolean visible) {
        this.mosaicThumbnailsVisible = visible;
    }

    /**
     * Whether mosaic media tiles are crop-to-fill squares (seamless, no
     * letterbox) rather than aspect-preserved fits on black. Defaults to
     * {@code false} (aspect-preserved fit).
     */
    public boolean mosaicFillTiles() {
        return mosaicFillTiles;
    }

    public void setMosaicFillTiles(boolean fill) {
        this.mosaicFillTiles = fill;
    }

    /**
     * Whether the mosaic renders seamlessly — zero tile margin and border,
     * leaving the configured {@link #mosaicMargin()} / {@link #mosaicBorderWidth()}
     * values untouched for when it is switched back off. Defaults to
     * {@code true}.
     */
    public boolean mosaicSeamless() {
        return mosaicSeamless;
    }

    public void setMosaicSeamless(boolean seamless) {
        this.mosaicSeamless = seamless;
    }

    /**
     * Whether selecting a media tile in the mosaic auto-opens it in the viewer
     * (without stealing keyboard focus, so the grid stays navigable). Defaults
     * to {@code true}.
     */
    public boolean mosaicAutoOpen() {
        return mosaicAutoOpen;
    }

    public void setMosaicAutoOpen(boolean autoOpen) {
        this.mosaicAutoOpen = autoOpen;
    }

    /**
     * How the mosaic animates its selected (lead/cursor) tile: {@code NONE},
     * the game-style brightness {@code PULSE}, or the sober {@code MARCHING_ANTS}
     * border crawl. Defaults to {@link MosaicSelectionAnimation#NONE}.
     */
    public MosaicSelectionAnimation mosaicSelectionAnimation() {
        return mosaicSelectionAnimation;
    }

    public void setMosaicSelectionAnimation(MosaicSelectionAnimation animation) {
        this.mosaicSelectionAnimation = animation == null
                ? DEFAULT_MOSAIC_SELECTION_ANIMATION : animation;
    }

    /**
     * Duration of one full selection-animation cycle (the pulse's white-then-black
     * fade, or one full step of the marching-ants crawl) in milliseconds; smaller
     * is faster. Defaults to {@code 1600}, clamped to {@code [200, 10000]}.
     */
    public int mosaicPulsePeriodMs() {
        return mosaicPulsePeriodMs;
    }

    public void setMosaicPulsePeriodMs(int periodMs) {
        this.mosaicPulsePeriodMs = Math.max(MIN_MOSAIC_PULSE_PERIOD_MS,
                Math.min(MAX_MOSAIC_PULSE_PERIOD_MS, periodMs));
    }

    /** Mosaic cell content size in pixels (default 100). */
    public int mosaicTileSize() {
        return mosaicTileSize;
    }

    public void setMosaicTileSize(int tileSize) {
        this.mosaicTileSize = tileSize;
    }

    /** Gap between mosaic tiles in pixels (default 10). */
    public int mosaicMargin() {
        return mosaicMargin;
    }

    public void setMosaicMargin(int margin) {
        this.mosaicMargin = margin;
    }

    /** Border thickness drawn around each mosaic tile in pixels (default 1). */
    public int mosaicBorderWidth() {
        return mosaicBorderWidth;
    }

    public void setMosaicBorderWidth(int width) {
        this.mosaicBorderWidth = width;
    }

    /** Border colour as a web hex string (default {@code #3c3c3c}). */
    public String mosaicBorderColor() {
        return mosaicBorderColor;
    }

    public void setMosaicBorderColor(String webColor) {
        this.mosaicBorderColor = webColor;
    }

    /**
     * Edge length {@code N} of the {@code N×N} preview collage drawn inside a
     * mosaic folder tile (so {@code N²} child images); {@code 0} draws a plain
     * folder glyph instead. Defaults to {@code 2} (a 2×2 collage).
     */
    public int mosaicFolderPreviewGrid() {
        return mosaicFolderPreviewGrid;
    }

    public void setMosaicFolderPreviewGrid(int grid) {
        this.mosaicFolderPreviewGrid = Math.max(0, Math.min(4, grid));
    }

    /**
     * How a mosaic folder tile with no preview collage is drawn: the muted gray
     * vector glyph, its black-on-gray inverse, or a photographic folder image.
     * Defaults to {@link MosaicFolderGlyph#GLYPH}.
     */
    public MosaicFolderGlyph mosaicFolderGlyph() {
        return mosaicFolderGlyph;
    }

    public void setMosaicFolderGlyph(MosaicFolderGlyph glyph) {
        this.mosaicFolderGlyph = glyph == null ? DEFAULT_MOSAIC_FOLDER_GLYPH : glyph;
    }

    /**
     * Whether mosaic folder (and {@code ..}) tiles show their name caption
     * (default {@code true}).
     */
    public boolean mosaicDirLabelsVisible() {
        return mosaicDirLabelsVisible;
    }

    public void setMosaicDirLabelsVisible(boolean visible) {
        this.mosaicDirLabelsVisible = visible;
    }

    /**
     * Whether mosaic non-media file tiles show their name caption
     * (default {@code true}).
     */
    public boolean mosaicFileLabelsVisible() {
        return mosaicFileLabelsVisible;
    }

    public void setMosaicFileLabelsVisible(boolean visible) {
        this.mosaicFileLabelsVisible = visible;
    }

    /**
     * Whether mosaic media tiles show their name caption (default {@code true}).
     */
    public boolean mosaicMediaLabelsVisible() {
        return mosaicMediaLabelsVisible;
    }

    public void setMosaicMediaLabelsVisible(boolean visible) {
        this.mosaicMediaLabelsVisible = visible;
    }

    /**
     * Recent move-target directories, most-recent-first, stored without trailing
     * separators. Returns a defensive copy.
     */
    public List<String> moveHistory() {
        return new ArrayList<>(moveHistory);
    }

    public void setMoveHistory(List<String> history) {
        this.moveHistory = history == null ? new ArrayList<>() : new ArrayList<>(history);
    }

    /** Maximum retained move-history entries (default 10, clamped to [1, 100]). */
    public int moveHistoryLimit() {
        return moveHistoryLimit;
    }

    public void setMoveHistoryLimit(int limit) {
        this.moveHistoryLimit = Math.max(MIN_MOVE_HISTORY_LIMIT,
                Math.min(MAX_MOVE_HISTORY_LIMIT, limit));
    }

    /**
     * Recently-used album file names (e.g. {@code album-000.csv}), most-recent
     * first. Returns a defensive copy; the "Add to Album" menu surfaces the
     * first few entries that still exist on disk.
     */
    public List<String> albumRecents() {
        return new ArrayList<>(albumRecents);
    }

    public void setAlbumRecents(List<String> recents) {
        this.albumRecents = recents == null ? new ArrayList<>() : new ArrayList<>(recents);
    }

    /**
     * Records {@code fileName} as the most-recently-used album, moving it to the
     * front of the recents list (deduplicated) and trimming to the retained
     * limit. Call {@link #save()} to persist.
     */
    public void recordAlbumUse(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        albumRecents.remove(fileName);
        albumRecents.add(0, fileName);
        while (albumRecents.size() > ALBUM_RECENT_LIMIT) {
            albumRecents.remove(albumRecents.size() - 1);
        }
    }

    /**
     * Whether quick-move shortcuts are enabled. In-memory only (see the field
     * doc); shared across every window's {@code MoveController} so the dialog
     * checkbox reads the same state regardless of which window opened it.
     */
    public boolean quickMoveShortcutsEnabled() {
        return quickMoveShortcutsEnabled;
    }

    public void setQuickMoveShortcutsEnabled(boolean enabled) {
        this.quickMoveShortcutsEnabled = enabled;
    }

    // --- per-window chrome visibility ---------------------------------------
    // Startup defaults applied as each window is built (Settings ▸ Browser /
    // Viewer / Mosaic); the dialog also pushes changes live to open windows.

    /** Whether the browser (main) window shows its menu bar (default {@code true}). */
    public boolean browserMenuBarVisible() {
        return browserMenuBarVisible;
    }

    public void setBrowserMenuBarVisible(boolean visible) {
        this.browserMenuBarVisible = visible;
    }

    /** Whether the browser (main) window shows its toolbar (default {@code false}). */
    public boolean browserToolbarVisible() {
        return browserToolbarVisible;
    }

    public void setBrowserToolbarVisible(boolean visible) {
        this.browserToolbarVisible = visible;
    }

    /** Whether the browser (main) window shows its status bar (default {@code true}). */
    public boolean browserStatusBarVisible() {
        return browserStatusBarVisible;
    }

    public void setBrowserStatusBarVisible(boolean visible) {
        this.browserStatusBarVisible = visible;
    }

    /** Whether the browser (main) window shows its action log panel (default {@code false}). */
    public boolean browserActionLogVisible() {
        return browserActionLogVisible;
    }

    public void setBrowserActionLogVisible(boolean visible) {
        this.browserActionLogVisible = visible;
    }

    /** Whether the browser (main) window shows its navigation tree (default {@code true}). */
    public boolean browserNavTreeVisible() {
        return browserNavTreeVisible;
    }

    public void setBrowserNavTreeVisible(boolean visible) {
        this.browserNavTreeVisible = visible;
    }

    /** Whether the viewer window shows its menu bar (default {@code true}). */
    public boolean viewerMenuBarVisible() {
        return viewerMenuBarVisible;
    }

    public void setViewerMenuBarVisible(boolean visible) {
        this.viewerMenuBarVisible = visible;
    }

    /** Whether the viewer window shows its toolbar (default {@code false}). */
    public boolean viewerToolbarVisible() {
        return viewerToolbarVisible;
    }

    public void setViewerToolbarVisible(boolean visible) {
        this.viewerToolbarVisible = visible;
    }

    /** Whether the viewer window shows its status bar (default {@code false}). */
    public boolean viewerStatusBarVisible() {
        return viewerStatusBarVisible;
    }

    public void setViewerStatusBarVisible(boolean visible) {
        this.viewerStatusBarVisible = visible;
    }

    /**
     * Which loading indicator the viewer shows while the next media decodes
     * (default {@link LoadingIndicator#GAME_CONSOLE}). Seeded into the viewer at
     * startup, persisted on every change, and editable from the Settings dialog.
     */
    public LoadingIndicator viewerLoadingIndicator() {
        return viewerLoadingIndicator;
    }

    public void setViewerLoadingIndicator(LoadingIndicator indicator) {
        this.viewerLoadingIndicator = indicator == null
                ? DEFAULT_VIEWER_LOADING_INDICATOR : indicator;
    }

    /**
     * Grace period, in milliseconds, before the viewer shows its loading
     * indicator while the next media decodes (default {@code 200}, clamped to
     * {@code [0, 5000]}). A decode that lands within this window never shows an
     * indicator, sparing a flash on fast/cached loads; {@code 0} shows it at
     * once. Has no effect when the indicator is {@link LoadingIndicator#NONE}.
     */
    public int viewerLoadingIndicatorDelayMs() {
        return viewerLoadingIndicatorDelayMs;
    }

    public void setViewerLoadingIndicatorDelayMs(int delayMs) {
        this.viewerLoadingIndicatorDelayMs = Math.max(MIN_VIEWER_LOADING_INDICATOR_DELAY_MS,
                Math.min(MAX_VIEWER_LOADING_INDICATOR_DELAY_MS, delayMs));
    }

    /**
     * The viewer slideshow auto-advance interval in seconds, clamped to
     * {@code [1, 10]} (default {@code 3}). The slideshow toggle is runtime-only;
     * only this interval and its direction persist.
     */
    public int viewerSlideshowIntervalSeconds() {
        return viewerSlideshowIntervalSeconds;
    }

    public void setViewerSlideshowIntervalSeconds(int seconds) {
        this.viewerSlideshowIntervalSeconds = Math.max(MIN_VIEWER_SLIDESHOW_INTERVAL,
                Math.min(MAX_VIEWER_SLIDESHOW_INTERVAL, seconds));
    }

    /** Whether the viewer slideshow advances backwards (default {@code false}, forward). */
    public boolean viewerSlideshowReverse() {
        return viewerSlideshowReverse;
    }

    public void setViewerSlideshowReverse(boolean reverse) {
        this.viewerSlideshowReverse = reverse;
    }

    /**
     * The viewer flipbook frame rate in frames per second, clamped to
     * {@code [1, 60]} (default {@code 24}). The flipbook toggle is runtime-only;
     * only the frame rate persists.
     */
    public int viewerFlipbookFps() {
        return viewerFlipbookFps;
    }

    public void setViewerFlipbookFps(int fps) {
        this.viewerFlipbookFps = Math.max(MIN_VIEWER_FLIPBOOK_FPS,
                Math.min(MAX_VIEWER_FLIPBOOK_FPS, fps));
    }

    /** Whether the mosaic window shows its menu bar (default {@code true}). */
    public boolean mosaicMenuBarVisible() {
        return mosaicMenuBarVisible;
    }

    public void setMosaicMenuBarVisible(boolean visible) {
        this.mosaicMenuBarVisible = visible;
    }

    /** Whether the mosaic window shows its toolbar (default {@code false}). */
    public boolean mosaicToolbarVisible() {
        return mosaicToolbarVisible;
    }

    public void setMosaicToolbarVisible(boolean visible) {
        this.mosaicToolbarVisible = visible;
    }

    /** Whether the mosaic window shows its status bar (default {@code true}). */
    public boolean mosaicStatusBarVisible() {
        return mosaicStatusBarVisible;
    }

    public void setMosaicStatusBarVisible(boolean visible) {
        this.mosaicStatusBarVisible = visible;
    }

    /** Whether the mosaic window shows its location bar (default {@code false}). */
    public boolean mosaicLocationBarVisible() {
        return mosaicLocationBarVisible;
    }

    /** Whether the mosaic window shows its action log panel (default {@code false}). */
    public boolean mosaicActionLogVisible() {
        return mosaicActionLogVisible;
    }

    public void setMosaicActionLogVisible(boolean visible) {
        this.mosaicActionLogVisible = visible;
    }

    public void setMosaicLocationBarVisible(boolean visible) {
        this.mosaicLocationBarVisible = visible;
    }

    /**
     * Whether every recorded action-log entry is also appended to the on-disk
     * JSONL log ({@code ~/.media-browser/action-log.jsonl}) — an append-only
     * diagnostic trail that survives restarts. Default {@code false}; read on
     * every record, so the Settings toggle applies live (only the startup
     * seeding of the panel from the file's tail needs the setting on at
     * launch).
     */
    public boolean actionLogFileEnabled() {
        return actionLogFileEnabled;
    }

    public void setActionLogFileEnabled(boolean enabled) {
        this.actionLogFileEnabled = enabled;
    }
}
