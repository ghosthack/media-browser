package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.LoadingIndicator;
import io.github.ghosthack.mediabrowser.MosaicFolderGlyph;
import io.github.ghosthack.mediabrowser.MosaicTelemetry;
import io.github.ghosthack.mediabrowser.MosaicSelectionAnimation;
import io.github.ghosthack.mediabrowser.album.AlbumStore;
import io.github.ghosthack.mediabrowser.media.AaeSidecar;
import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.Adjustments;
import io.github.ghosthack.mediabrowser.media.DirEntry;
import io.github.ghosthack.mediabrowser.media.MediaItem;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A windowed mosaic mirroring the main window's current directory listing:
 * {@code ..}, subfolders, viewable media and (optionally) other files, drawn in
 * a uniform grid on a black background with configurable per-tile borders and
 * inter-tile margins. Media tiles show a preview rendition (≤ {@code maxEdge},
 * generated lazily through {@link MediaService#thumbnail}); folder tiles show an
 * N×N collage of their first child visual media — images and videos
 * ({@link MediaService#folderPreview}).
 *
 * <p>The grid is rendered straight onto a {@link Canvas}, virtualized: only the
 * rows intersecting the viewport are drawn, and a tile's rendition is requested
 * only when it first becomes visible. Renditions arrive asynchronously and are
 * cached both by the service (the decode) and here as JavaFX {@link Image}s (the
 * paint), so scrolling back is cheap. Double-clicking or pressing Enter opens a
 * media tile in the shared viewer, or navigates into a folder / {@code ..} tile
 * (mirrored into the main window through the host's navigator). The Mosaic ▸
 * Close Mosaic menu action (or Window ▸ Browser) unwinds the {@link AppShell}
 * back-stack, usually to the browser view; Escape is deliberately not bound.</p>
 *
 * <p>Borders and margins are configured in Settings ▸ Mosaic (persisted to
 * {@link AppSettings}); the Seamless toggle ({@code Shortcut+B}, also in
 * Settings ▸ Mosaic, persisted as its own setting)
 * renders with zero margin/border without touching those settings. The toolbar
 * slider sets the on-screen tile size without regenerating any rendition (the
 * cached ≤ {@code maxEdge} image is simply scaled on draw).</p>
 *
 * <p>The window has no menu strip of its own: the application's single menu bar
 * (built by {@link MainWindow} and installed here through {@link #installMenuBar})
 * is shared with the main window and carries a Mosaic menu mirroring these
 * actions (Open, Seamless, tile sizing). The toolbar is shown by
 * default; Mosaic ▸ Show Toolbar ({@code Shortcut+T}) toggles it. An optional
 * location bar below it (Mosaic ▸ Show Location Bar, hidden by default; Go ▸
 * Go to Location reveals and focuses it) mirrors the current directory into an
 * editable field whose Enter/Go navigates the shared location, like the main
 * window's address bar.</p>
 *
 * <p>The Mosaic menu also carries an Auto-open toggle: while it is on, moving
 * the selection onto a media tile shows it in the shared viewer without taking
 * focus, so arrow-browsing the grid drives a live preview.</p>
 *
 * <p>While the grid is still decoding renditions (opening a large or slow
 * directory, or scrolling into freshly visible tiles), a loading indicator is
 * shown over the canvas — the same one the viewer uses, honouring the shared
 * {@link io.github.ghosthack.mediabrowser.LoadingIndicator} setting and its delay gate:
 * {@code Default} centres a "Loading…" pill, {@code Game Console} runs the
 * bottom-left spinning-CD overlay (see {@link GameConsoleLoadingOverlay}), and
 * {@code None} shows nothing. It spans the whole open: armed the moment a scan
 * is kicked off (so opening a slow, content-sniffed folder gives instant
 * feedback rather than a frozen-looking gap while the listing is built off the
 * FX thread), held through the thumbnail backlog, and dissolved once everything
 * is loaded — all behind the delay gate, so a fast/cached folder never flashes
 * one.</p>
 */
public final class MosaicWindow implements AppShell.ShellView, ViewerHost {

    /** Floor on retained FX images; the live cap grows to cover the viewport. */
    private static final int MIN_CACHED_IMAGES = 400;

    // Draw-loop colours, parsed once rather than re-parsed from a hex string on
    // every tile of every frame (Color.web allocates and parses each call).
    private static final Color GLYPH_FILL = Color.web("#555555");
    private static final Color CAPTION_BG = Color.rgb(0, 0, 0, 0.5);
    private static final Color CAPTION_FG = Color.web("#dddddd");
    private static final Color CAPTION_FG_FADE = Color.web("#dddddd", 0);
    private static final Color FOLDER_GLYPH_FILL = Color.web("#4a4a4a");

    /** Peak opacity of the brightness-pulse overlay (50%). */
    private static final double PULSE_MAX_ALPHA = 0.5;

    /**
     * The selection marker's nominal dash period (dash + gap, px). Each tile
     * stretches it to the nearest value that divides its edge exactly (see
     * {@link #drawSelectionBorder}), so a dot lands on every corner; the
     * marching-ants animation advances the dash offset by one stretched
     * period per cycle for a seamless loop.
     */
    private static final double SELECTION_DASH_PERIOD = 5;

    /** The single-window shell hosting this view. */
    private final AppShell shell;
    private final MediaService service;
    private final ViewerWindow viewer;
    private final AppSettings settings;
    /** Shared user-rotation store; consulted at draw time, never re-thumbnails. */
    private final RotationStore rotationStore;
    /** Shared Apple {@code .AAE} edit store; its crop is applied at draw time too. */
    private final AaeStore aaeStore;
    private final int maxEdge;
    /**
     * The shared logical accelerator scheme, captured when the menu bar is
     * installed; used to label the per-tile popup's shortcuts and to honour
     * modifier1+C (Copy Path).
     */
    private KeyScheme keys;
    /**
     * The shared menu bar, captured when it is installed; consulted by
     * {@link #onKey} so the grid's scene-level key filter steps aside while the
     * menu bar is being driven from the keyboard.
     */
    private MenuBar menuBar;
    /** Lazily-built per-tile actions popup (right-click / menu key). */
    private ContextMenu tileMenu;
    /** The "Add to Album" submenu of {@link #tileMenu}; refreshed on each show. */
    private AlbumMenu albumMenu;
    /** Shared album store (numbered {@code album-NNN.csv} files in the app dir). */
    private final AlbumStore albumStore = new AlbumStore();

    private final Canvas canvas = new Canvas();
    /**
     * Stacks the grid canvas under the loading overlays ({@link #loadingLabel} /
     * {@link #loadingOverlay}), which are mouse-transparent so clicks still fall
     * through to the canvas. A {@link StackPane} (rather than a plain Pane) so the
     * overlays anchor by alignment without manual layout math.
     */
    private final StackPane canvasHolder = new StackPane(canvas);
    private final ScrollBar vbar = new ScrollBar();
    /**
     * The loading indicator shown over the grid while thumbnails / folder
     * previews are still decoding, mirroring the viewer's (it honours the same
     * {@link AppSettings#viewerLoadingIndicator} choice and delay gate): the
     * {@code Default} style reveals {@link #loadingLabel} (a centred "Loading…"
     * pill), the {@code Game Console} style starts {@link #loadingOverlay} (the
     * bottom-left spinning-CD overlay), and {@code None} shows nothing.
     */
    private final GameConsoleLoadingOverlay loadingOverlay = new GameConsoleLoadingOverlay();
    /** The {@code Default} loading indicator: a centred "Loading…" pill over the grid. */
    private final Label loadingLabel = new Label("Loading\u2026");
    /**
     * Gates the loading indicator behind {@link AppSettings#viewerLoadingIndicatorDelayMs}:
     * armed when a decode backlog appears, it shows the indicator only if work is
     * still in flight when it fires, so a fast/cached folder never flashes one.
     */
    private final PauseTransition loadingIndicatorDelay = new PauseTransition();
    /**
     * True from the moment a directory scan is kicked off (off the FX thread)
     * until its listing lands and {@link #rebuild} runs. It drives the loading
     * indicator during the scan itself — the slow, content-sniffing part — so an
     * open into a large/slow folder gives instant feedback instead of a
     * frozen-looking gap until the first thumbnail request appears.
     */
    private boolean scanPending;
    /** Whether any thumbnail / folder-preview request is currently in flight. */
    private boolean loadingActive;
    /** True while a loading indicator is on screen, so scheduling/hiding stays idempotent. */
    private boolean loadingShown;
    /**
     * Location bar (its own strip under the toolbar, hidden by default):
     * mirrors the mosaic's current directory into an editable field whose
     * Enter/Go navigates the shared location, exactly like the main window's
     * address bar. Toggled via Mosaic ▸ Show Location Bar / Settings ▸ Mosaic.
     */
    private final TextField locationField = new TextField();
    /** The location strip itself; assembled in the constructor. */
    private HBox locationBar;
    /** Selection-info panel (right edge); reuses the shared InfoPanel, hidden by default. */
    private final InfoPanel infoPanel = new InfoPanel();
    /** On-demand full-metadata panel (right edge, below Info); reuses the viewer's MetadataPanel. */
    private final MetadataPanel metadataPanel = new MetadataPanel();
    /** Thumbnail-pipeline diagnostics snapshot (right edge, bottom); reuses the shared DiagnosticsPanel. */
    private final DiagnosticsPanel diagnosticsPanel;
    /** Right-edge vertical split: Info, Metadata, Diagnostics (membership follows the toggles). */
    private final SplitPane rightPanels = new SplitPane();
    /** Debounce for the opt-in Auto metadata load; (re)armed on each selection change. */
    private final PauseTransition metadataDebounce = new PauseTransition(Duration.millis(180));
    /** Status-bar labels (bottom edge): the lead/selection text on the left, the tally on the right. */
    private final Label statusLabel = new Label();
    private final Label statusCountLabel = new Label();
    /** Bottom status bar; assembled in the constructor and toggled by {@link #statusBarVisible}. */
    private HBox statusBar;
    /** Sequence guard voiding in-flight info-panel probes / metadata reads from a superseded selection. */
    private int probeSequence;

    /**
     * The shared, window-agnostic move dialog driver (Cmd+M / Mosaic ▸ Move…),
     * wired to a {@link MoveController.Host} that exposes this grid's selection,
     * current directory, post-move focus and refresh.
     */
    private final MoveController moveController;

    /** On-screen tile size control (nudged by the Mosaic menu). */
    private Slider sizeSlider;
    /** Toolbar visibility, hidden by default; the Mosaic ▸ Show Toolbar item binds here. */
    private final BooleanProperty toolbarVisible = new SimpleBooleanProperty(false);
    /** Status-bar visibility (the mosaic bar's Show ▸ Status Bar binds here); hidden by default. */
    private final BooleanProperty statusBarVisible = new SimpleBooleanProperty(false);
    /** Info-panel visibility (the mosaic bar's Show ▸ Info Panel binds here); hidden by default. */
    private final BooleanProperty infoPanelVisible = new SimpleBooleanProperty(false);
    /** Metadata-panel visibility (the mosaic bar's Show ▸ Metadata binds here); hidden by default. */
    private final BooleanProperty metadataPanelVisible = new SimpleBooleanProperty(false);
    /** Diagnostics-panel visibility (the mosaic bar's Show ▸ Diagnostics binds here); hidden by default. */
    private final BooleanProperty diagnosticsPanelVisible = new SimpleBooleanProperty(false);
    /** Menu-bar visibility (the mosaic bar's Show ▸ Menu Bar binds here); shown by default. */
    private final BooleanProperty menuBarVisible = new SimpleBooleanProperty(true);
    /** Location-bar visibility (Mosaic ▸ Show Location Bar binds here); hidden by default. */
    private final BooleanProperty locationBarVisible = new SimpleBooleanProperty(false);
    /** Action-log panel visibility (the mosaic bar's Show ▸ Action Log binds here); hidden by default. */
    private final BooleanProperty actionLogVisible = new SimpleBooleanProperty(false);
    /** Crop-to-fill (square) vs. fit-on-black tiles; the toolbar toggle / menu bind here. */
    private final BooleanProperty fillTiles = new SimpleBooleanProperty(false);
    /**
     * Seamless rendering (the toolbar toggle / Mosaic menu / Settings ▸ Mosaic
     * bind here): while on, tiles are drawn with zero margin and zero border;
     * off restores the configured layout. Persisted as its own setting — it
     * never writes the margin/border settings themselves.
     */
    private final BooleanProperty seamless = new SimpleBooleanProperty(false);
    /** Media thumbnail and folder-collage visibility; the Mosaic menu binds here. */
    private final BooleanProperty thumbnailsVisible = new SimpleBooleanProperty(true);
    /**
     * Per-type tile name-caption visibility (the Mosaic menu's Tile Labels
     * submenu binds here). Folders (and {@code ..}) and non-media files are
     * captioned by default; media tiles are not (their rendition already
     * identifies them). Each persists when toggled.
     */
    private final BooleanProperty dirLabelsVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty fileLabelsVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty mediaLabelsVisible = new SimpleBooleanProperty(false);
    /**
     * Folder-glyph style for a folder tile with no preview collage: the muted
     * vector folder shape, its black-on-gray inverse, or a photographic folder
     * image (a pre-resampled asset chosen for the tile size). The Mosaic menu
     * binds here; the plain glyph by default and persisted on change.
     */
    private final ObjectProperty<MosaicFolderGlyph> folderGlyph =
            new SimpleObjectProperty<>(MosaicFolderGlyph.GLYPH);
    /**
     * Auto-open mode: when on, moving the selection onto a media tile shows it
     * in the viewer without taking focus (the grid stays navigable). The toolbar
     * toggle / Mosaic menu bind here.
     */
    private final BooleanProperty autoOpen = new SimpleBooleanProperty(false);
    /**
     * Keep-focus mode (the mosaic's counterpart to the main window's toggle):
     * when on, activating a media tile shows it in the viewer but leaves this
     * mosaic in the foreground, so browsing the grid continues while the viewer
     * sits on another screen. Off by default. The toolbar toggle / Mosaic menu
     * bind here.
     */
    private final BooleanProperty keepFocus = new SimpleBooleanProperty(false);
    /**
     * Media-type filter toggles shown in the toolbar's Filter submenu. Each
     * defaults to on (showing everything); turning one off hides that type from
     * the grid. Directories and {@code ..} are never affected.
     */
    private final BooleanProperty filterImages = new SimpleBooleanProperty(true);
    private final BooleanProperty filterVideos = new SimpleBooleanProperty(true);
    private final BooleanProperty filterAudio = new SimpleBooleanProperty(true);
    private final BooleanProperty filterOther = new SimpleBooleanProperty(true);
    /**
     * True for one event tick after a click in the toolbar or a side panel, so
     * the scene focus-owner listener knows to hand keyboard focus straight back
     * to the grid canvas. See {@link #armFocusBounce()}; mirrors MainWindow.
     */
    private boolean bounceArmed;
    /**
     * Selection-animation style for the lead/cursor tile: {@code NONE}, the
     * game-style brightness {@code PULSE}, or the sober {@code MARCHING_ANTS}
     * border crawl. {@code NONE} by default; the Mosaic menu / Settings dialog
     * bind here and the choice persists.
     */
    private final ObjectProperty<MosaicSelectionAnimation> selectionAnimation =
            new SimpleObjectProperty<>(MosaicSelectionAnimation.NONE);
    /** Drives {@link #selectionAnimation}; repaints the lead tile each frame while on. */
    private AnimationTimer animationTimer;
    /** Optional telemetry pulse monitor; enabled only with {@code -Dmosaic.telemetry=true}. */
    private AnimationTimer telemetryTimer;
    private long telemetryLastPulseNanos;
    /** Full animation-cycle duration (pulse fade, or one ant step) in nanoseconds. */
    private long animationPeriodNanos;
    /** Animation start time (ns), captured on the first animation frame. */
    private long animationStartNanos;
    /** The current per-frame pulse overlay tint for the lead tile, or {@code null}. */
    private Color pulseOverlay;
    /**
     * The current marching-ants cycle phase in {@code [0, 1)} for the lead
     * tile; {@link #drawSelectionBorder} scales it by the tile's stretched
     * dash period to get the pixel offset.
     */
    private double marchPhase;
    /**
     * Re-entrancy guard for {@link #autoOpen}: set while mirroring the viewer's
     * current item back into the selection, so the resulting selection change
     * doesn't re-trigger an auto-open and loop with the viewer.
     */
    private boolean suppressAutoOpen;
    /** The rendition mode requested from the service; tracks {@link #fillTiles}. */
    private ThumbnailMode thumbnailMode = ThumbnailMode.FIT;
    /** Window-top container; the shared menu bar is inserted above the toolbar. */
    private VBox topBox;
    /** This view's root, hosted by the shell while the mosaic is active. */
    private final javafx.scene.layout.BorderPane root;
    private final ToolBar toolBar;
    /** The stage title while the mosaic is active; tracks the mirrored directory. */
    private final javafx.beans.property.SimpleStringProperty title =
            new javafx.beans.property.SimpleStringProperty("Mosaic");
    /**
     * True while the mosaic is the shell's active view; replaces the old
     * {@code stage.isShowing()} gates on the animation/telemetry timers and the
     * scan-started hook, so no per-frame work runs behind another view.
     */
    private boolean active;

    /** Layout (live-editable; mirrors and persists to settings). */
    private int tileSize;
    private int margin;
    private int borderWidth;
    private Color borderColor;
    /** Edge {@code N} of the N×N folder-tile preview collage (0 = folder glyph). */
    private int folderPreviewGrid;

    /**
     * The live, filtered+sorted directory listing this mosaic mirrors (the main
     * window's {@code visibleEntries}); a change-listener rebuilds the grid so
     * navigation, filtering and sorting in either window stay in lock-step.
     */
    private ObservableList<DirEntry> source;
    private Supplier<Path> dirSupplier;
    /** Navigates the (shared) location into a folder or {@code ..}; set by the host. */
    private Consumer<Path> navigator = path -> { };
    /** Steps the shared location back/forward through history; set by the host. */
    private Runnable backHandler = () -> { };
    private Runnable forwardHandler = () -> { };
    private final ListChangeListener<DirEntry> sourceListener = c -> rebuild();
    private boolean attached;
    /** The directory currently mirrored; a change resets scroll/selection. */
    private Path mosaicDir;
    /**
     * When navigating up via {@code ..}/Backspace, the folder we came from; the
     * next {@link #rebuild} selects (and reveals) it in the parent listing
     * instead of resetting the selection to the first tile.
     */
    private Path pendingSelectPath;
    /**
     * Set by a move refresh ({@link #refreshAfterMove}); the next {@link #rebuild}
     * auto-opens the tile the move landed on (when auto-open is on and it's a
     * media tile), so a completed move keeps the viewer in step exactly as
     * arrow-browsing does. Consumed by that rebuild.
     */
    private boolean autoOpenAfterRebuild;

    private List<DirEntry> items = List.of();
    /**
     * The lead/cursor tile index: where arrow navigation steps from, what Enter
     * activates, and what the viewer mirrors back. Always a member of
     * {@link #selection} when {@code >= 0}.
     */
    private int selected = -1;
    /**
     * The full set of selected tile indices (held in ascending listing order by
     * the {@link TreeSet}), so Move — and any future bulk op — can act on several
     * tiles. A plain click collapses it to a single entry; Shift extends a range
     * from {@link #anchor}; Cmd/Ctrl toggles one tile.
     */
    private final NavigableSet<Integer> selection = new TreeSet<>();
    /** The fixed end of a Shift-range; set by every single-tile selection. */
    private int anchor = -1;
    private int columns = 1;
    private int rows = 0;
    private double scrollY;

    /** Bumped on every directory change; stale async completions are ignored. */
    private int generation;

    /** Loaded tile images (access-ordered, capped); misses are re-requested. */
    private final LinkedHashMap<Path, Image> images =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, Image> e) {
                    return size() > maxCachedImages;
                }
            };
    /**
     * Display-scaled copies of {@link #images}, sized to the tile's on-screen
     * device pixels and reused across frames so a repaint just blits them rather
     * than rescaling the (up to 2048px) full rendition every time. Built lazily
     * on first draw and dropped whenever the on-screen tile size changes (slider,
     * fill mode, folder-grid, directory or monitor render scale).
     */
    private final LinkedHashMap<Path, Image> scaled =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, Image> e) {
                    return size() > maxCachedImages;
                }
            };
    /** The HiDPI render scale {@link #scaled} was built for; a change rebuilds it. */
    private double scaledScale = 1.0;
    /**
     * Live cap on retained FX images, applied to both {@link #images} and
     * {@link #scaled}. Recomputed by {@link #relayout()} to comfortably exceed
     * the number of tiles (and folder-collage sub-images) the viewport can show
     * at once, so a tile that is currently on screen is never evicted. A fixed
     * cap that is smaller than the visible tile count (e.g. 4K with small tiles)
     * makes a loaded tile's eviction re-request it, evict another visible tile to
     * make room, and loop forever — the mosaic flickers half its tiles out and
     * reloads them endlessly.
     */
    private int maxCachedImages = MIN_CACHED_IMAGES;
    /** Items already requested (in flight) — avoids duplicate fetches. */
    private final java.util.Set<Path> pending = new java.util.HashSet<>();
    /** Items known to have no visual (e.g. audio without cover art). */
    private final java.util.Set<Path> empties = new java.util.HashSet<>();
    /** Per-folder preview child paths (up to N²), filled lazily on first sight. */
    private final Map<Path, List<Path>> folderPreviews = new java.util.HashMap<>();
    /** Folders whose preview scan is in flight — avoids duplicate scans. */
    private final java.util.Set<Path> folderPending = new java.util.HashSet<>();

    private boolean redrawQueued;

    /**
     * Off-scene scratch node for measuring caption width (on the FX thread, at
     * draw time) so an overflowing name can be left-aligned and faded rather
     * than centre-clipped on both edges.
     */
    private final Text textMeasure = new Text();
    /**
     * Cached caption/path widths keyed by font then text, so the hot draw loop
     * does not run a {@link Text#getLayoutBounds()} text layout for every
     * captioned tile on every scroll frame. A given (font, text) pair always
     * measures the same width, so entries never go stale; the map is cleared on
     * a directory change to bound its growth.
     */
    private final Map<Font, Map<String, Double>> captionWidths = new java.util.HashMap<>();
    /** Cached fonts keyed by point size, reused across tiles and frames. */
    private final Map<Double, Font> fontCache = new java.util.HashMap<>();
    /**
     * Per-path draw-time user-adjustment cache (rotation + mirror + colour), so
     * the hot draw loop avoids the store's synchronized lock and
     * {@code File}/{@code String} allocations on every media/collage tile of every
     * frame. Filled lazily, cleared on a directory change, and the single entry
     * dropped when its file is adjusted (see {@link #invalidateAdjustment}).
     */
    private final Map<Path, Adjustments> adjustmentsCache = new java.util.HashMap<>();
    /**
     * Per-path cache of the colour-adjusted (grayscale/invert) display image and
     * the source {@link Image} it was derived from, so a tile carrying a colour
     * adjustment blits a pre-processed image instead of re-scanning pixels every
     * frame. The geometric part (rotation/mirror) is still a free draw-time canvas
     * transform; only colour needs a pixel pass. Recomputed when the source image
     * changes (a tile resize rebuilds {@link #scaled}); dropped per-path on an
     * adjustment and wholesale on a directory change. Bounded by the number of
     * colour-adjusted paths in the current directory.
     */
    private final Map<Path, Image> colorSource = new java.util.HashMap<>();
    private final Map<Path, Image> colorOutput = new java.util.HashMap<>();
    /** Sentinel marking a path with no applicable AAE crop in {@link #cropCache}. */
    private static final double[] NO_CROP = new double[0];
    /**
     * Per-path AAE crop-fraction cache (or {@link #NO_CROP}), so the hot draw
     * loop avoids the store lookup and {@code Optional} allocations on every
     * media tile of every frame. AAE edits don't change at runtime, so entries
     * never go stale; cleared on a directory change.
     */
    private final Map<Path, double[]> cropCache = new java.util.HashMap<>();

    /** Type-to-select over {@link #items} (shared with the main window's list). */
    private final Typeahead typeahead = new Typeahead();

    public MosaicWindow(AppShell shell, MediaService service, ViewerWindow viewer,
                        AppSettings settings, RotationStore rotationStore, AaeStore aaeStore) {
        this.shell = shell;
        this.service = service;
        this.diagnosticsPanel = new DiagnosticsPanel(service::thumbnailStats);
        this.viewer = viewer;
        this.settings = settings;
        this.rotationStore = rotationStore;
        this.aaeStore = aaeStore;
        this.maxEdge = settings.thumbnailMaxEdge();
        this.tileSize = settings.mosaicTileSize();
        this.seamless.set(settings.mosaicSeamless());
        this.margin = seamless.get() ? 0 : settings.mosaicMargin();
        this.borderWidth = seamless.get() ? 0 : settings.mosaicBorderWidth();
        this.borderColor = parseColor(settings.mosaicBorderColor(), Color.web("#3c3c3c"));
        this.seamless.addListener((o, was, v) -> {
            this.margin = v ? 0 : settings.mosaicMargin();
            this.borderWidth = v ? 0 : settings.mosaicBorderWidth();
            settings.setMosaicSeamless(v);
            saveSettingsQuietly();
            relayout();
        });
        this.folderPreviewGrid = settings.mosaicFolderPreviewGrid();
        boolean fill = settings.mosaicFillTiles();
        this.thumbnailMode = fill ? ThumbnailMode.FILL : ThumbnailMode.FIT;
        this.fillTiles.set(fill);
        this.fillTiles.addListener((o, was, v) -> setFillTiles(v));
        this.thumbnailsVisible.set(settings.mosaicThumbnailsVisible());
        this.thumbnailsVisible.addListener((o, was, v) -> {
            settings.setMosaicThumbnailsVisible(v);
            saveSettingsQuietly();
            requestDraw();
        });
        // Seed the live toggle from the persisted startup default; flipping it
        // afterwards is transient (it never writes back to the setting).
        this.autoOpen.set(settings.mosaicAutoOpen());
        this.autoOpen.addListener((o, was, v) -> { if (v) maybeAutoOpen(); });
        // Selection animation on the lead tile; none by default, persisted when changed.
        this.selectionAnimation.set(settings.mosaicSelectionAnimation());
        this.selectionAnimation.addListener((o, was, v) -> setSelectionAnimation(v));
        this.animationPeriodNanos = settings.mosaicPulsePeriodMs() * 1_000_000L;
        // Seed the chrome from the persisted startup defaults (Settings ▸ Mosaic);
        // each can still be toggled at runtime via the Show menu / toolbar.
        this.toolbarVisible.set(settings.mosaicToolbarVisible());
        this.statusBarVisible.set(settings.mosaicStatusBarVisible());
        this.menuBarVisible.set(settings.mosaicMenuBarVisible());
        this.locationBarVisible.set(settings.mosaicLocationBarVisible());
        this.actionLogVisible.set(settings.mosaicActionLogVisible());
        // Per-type tile labels; persisted on toggle, repainting the grid.
        this.dirLabelsVisible.set(settings.mosaicDirLabelsVisible());
        this.fileLabelsVisible.set(settings.mosaicFileLabelsVisible());
        this.mediaLabelsVisible.set(settings.mosaicMediaLabelsVisible());
        this.dirLabelsVisible.addListener((o, was, v) -> {
            settings.setMosaicDirLabelsVisible(v);
            saveSettingsQuietly();
            requestDraw();
        });
        this.fileLabelsVisible.addListener((o, was, v) -> {
            settings.setMosaicFileLabelsVisible(v);
            saveSettingsQuietly();
            requestDraw();
        });
        this.mediaLabelsVisible.addListener((o, was, v) -> {
            settings.setMosaicMediaLabelsVisible(v);
            saveSettingsQuietly();
            requestDraw();
        });
        // Folder-glyph style (vector / inverse / photo); persisted on change, repaints.
        this.folderGlyph.set(settings.mosaicFolderGlyph());
        this.folderGlyph.addListener((o, was, v) -> {
            settings.setMosaicFolderGlyph(v);
            saveSettingsQuietly();
            requestDraw();
        });

        // Filter toggles: changing any triggers a rebuild so the grid reflects
        // the new type visibility. Directories and .. are always shown.
        filterImages.addListener((o, was, v) -> rebuildWithPreservedSelection());
        filterVideos.addListener((o, was, v) -> rebuildWithPreservedSelection());
        filterAudio.addListener((o, was, v) -> rebuildWithPreservedSelection());
        filterOther.addListener((o, was, v) -> rebuildWithPreservedSelection());

        // --- toolbar (shown by default; toggled via Mosaic ▸ Show Toolbar) ----
        sizeSlider = new Slider(64, 512, tileSize);
        sizeSlider.setPrefWidth(180);
        sizeSlider.setTooltip(new Tooltip("Tile size"));
        sizeSlider.valueProperty().addListener((obs, was, v) -> {
            tileSize = v.intValue();
            settings.setMosaicTileSize(tileSize);
            scaled.clear();   // tiles are a new on-screen size; rescale lazily
            relayout();
        });
        var fillToggle = new ToggleButton("Fill");
        fillToggle.setTooltip(new Tooltip("Crop tiles to fill (seamless squares)"));
        fillToggle.selectedProperty().bindBidirectional(fillTiles);
        var seamlessToggle = new ToggleButton("Seamless");
        seamlessToggle.setTooltip(new Tooltip(
                "Draw tiles with no margin or border (margin/border settings kept)"));
        seamlessToggle.selectedProperty().bindBidirectional(seamless);
        var filterButton = new MenuButton("Filter");
        filterButton.setTooltip(new Tooltip("Filter media types"));
        var filterImagesItem = new CheckMenuItem("Images");
        filterImagesItem.selectedProperty().bindBidirectional(filterImages);
        var filterVideosItem = new CheckMenuItem("Videos");
        filterVideosItem.selectedProperty().bindBidirectional(filterVideos);
        var filterAudioItem = new CheckMenuItem("Audio");
        filterAudioItem.selectedProperty().bindBidirectional(filterAudio);
        var filterOtherItem = new CheckMenuItem("Others");
        filterOtherItem.selectedProperty().bindBidirectional(filterOther);
        filterButton.getItems().addAll(filterImagesItem, filterVideosItem,
                filterAudioItem, filterOtherItem);
        var tileLabel = new Label("Tile");
        tileLabel.setPadding(new Insets(0, 0, 0, 10)); // ~1.25-char left margin
        this.toolBar = new ToolBar(tileLabel, sizeSlider, new Separator(),
                fillToggle, seamlessToggle, new Separator(), filterButton);
        toolBar.visibleProperty().bind(toolbarVisible);
        toolBar.managedProperty().bind(toolbarVisible);

        // --- location bar (its own strip under the toolbar, hidden by default;
        // Mosaic ▸ Show Location Bar toggles it) — mirrors the main window's
        // address bar: Enter/Go navigates the shared location, so the change
        // flows back through the host and rebuilds the grid.
        locationField.setOnAction(e -> navigateFromLocation());
        HBox.setHgrow(locationField, Priority.ALWAYS);
        var goButton = new Button("Go");
        goButton.setOnAction(e -> navigateFromLocation());
        locationBar = new HBox(6, new Label("Location:"), locationField, goButton);
        locationBar.setPadding(new Insets(4, 8, 4, 8));
        locationBar.getStyleClass().add("mosaic-location-bar");
        locationBar.setStyle("-fx-alignment: center-left;");
        locationBar.visibleProperty().bind(locationBarVisible);
        locationBar.managedProperty().bind(locationBarVisible);

        // --- canvas + scrollbar ----------------------------------------------
        vbar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vbar.valueProperty().addListener((obs, was, v) -> {
            scrollY = v.doubleValue();
            requestDraw();
        });
        // The canvas is the grid's keyboard focus target: the scene-level key
        // filter (see onKey) drives navigation, but a focused toolbar control's
        // own InputMap (e.g. the Slider's Home/End -> min/max) still fires even
        // when that filter consumes the event, so keyboard focus must live here,
        // not on the toolbar. Made focusable, given focus on show, and bounced
        // back whenever a toolbar/panel click steals it (see the bounce wiring).
        canvas.setFocusTraversable(true);
        // Canvas fills the holder; the scrollbar sits to its right.
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> relayout());
        canvas.heightProperty().addListener((o, a, b) -> relayout());
        canvasHolder.setStyle("-fx-background-color: black;");
        // The fixed-size Game Console overlay would otherwise drive the holder's
        // minimum size up to 300×112; let it shrink to nothing so a narrow window
        // still shrinks the grid (the overlay just clips at the edge).
        canvasHolder.setMinSize(0, 0);

        // Loading indicators over the grid (mirroring the viewer): a centred
        // "Loading…" pill (Default) or the bottom-left spinning-CD overlay (Game
        // Console), shown while thumbnails / folder previews decode and gated
        // behind the shared delay so a fast folder never flashes one. Both are
        // mouse-transparent so clicks fall through to the canvas beneath.
        loadingLabel.setMouseTransparent(true);
        loadingLabel.setVisible(false);
        loadingLabel.setStyle("-fx-text-fill: #dddddd; -fx-background-color: rgba(0,0,0,0.55);"
                + " -fx-background-radius: 6; -fx-padding: 8 16 8 16; -fx-font-size: 14px;");
        StackPane.setAlignment(loadingLabel, Pos.CENTER);
        StackPane.setAlignment(loadingOverlay, Pos.BOTTOM_LEFT);
        StackPane.setMargin(loadingOverlay, new Insets(0, 0, 6, 6));
        canvasHolder.getChildren().addAll(loadingLabel, loadingOverlay);
        // Once the delay gate fires, the backlog is still loading, so reveal
        // whichever indicator the viewer's setting selects.
        loadingIndicatorDelay.setOnFinished(e -> showLoadingIndicator());

        canvas.setOnScroll(e -> {
            vbar.setValue(clamp(scrollY - e.getDeltaY(), 0, vbar.getMax()));
            e.consume();
        });
        canvas.setOnMousePressed(e -> {
            // A click on the grid claims keyboard focus (the canvas doesn't grab
            // it automatically the way a control would), so subsequent keys land
            // on the grid rather than a previously focused toolbar control.
            canvas.requestFocus();
            // Right-click is routed to the context-menu handler below; it must
            // not move the selection the way a left-click does.
            if (e.getButton() != MouseButton.PRIMARY) return;
            int hit = indexAt(e.getX(), e.getY());
            if (hit < 0) return;
            if (e.getClickCount() == 2) {
                selectSingle(hit);
                activateSelected();
            } else if (e.isShiftDown()) {
                extendSelectionTo(hit);          // range from the anchor
            } else if (e.isShortcutDown()) {
                toggleSelection(hit);            // Cmd/Ctrl: add or remove one tile
            } else {
                selectSingle(hit);
            }
        });
        // Right-click a tile → the per-tile actions popup at the cursor; the
        // platform menu key routes through onKey (see CONTEXT_MENU) instead, as
        // the canvas isn't a focus target.
        canvas.setOnContextMenuRequested(this::showTileMenuForMouse);

        // Dragging the grid's empty black background moves the window (Settings
        // ▸ Mosaic, applies live) — the mosaic's counterpart of the viewer's
        // drag-by-viewport. Only a press that hit no tile arms the drag, so a
        // press-and-move that starts on a tile can't tear the window loose.
        var pressOnBackground = new boolean[1];
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e ->
                pressOnBackground[0] = e.getButton() == MouseButton.PRIMARY
                        && indexAt(e.getX(), e.getY()) < 0);
        WindowChrome.addDragHandle(stage(), canvas,
                () -> pressOnBackground[0] && settings.mosaicDragBackground());

        var content = new HBox(canvasHolder, vbar);
        HBox.setHgrow(canvasHolder, Priority.ALWAYS);

        // Selection info + on-demand metadata panels stacked on the right edge
        // (a vertical split, Info over Metadata), and a status bar at the bottom:
        // all hidden by default, so the mosaic's full-bleed black grid looks
        // unchanged until a Show-menu toggle reveals them. The info panel reuses
        // the shared InfoPanel (probed on selection); the metadata panel reuses
        // the viewer's MetadataPanel (manual Load / opt-in Auto read).
        infoPanel.visibleProperty().bind(infoPanelVisible);
        infoPanel.managedProperty().bind(infoPanelVisible);
        rightPanels.setOrientation(javafx.geometry.Orientation.VERTICAL);
        // Info: probe (native work) only while shown; refresh the lead on reveal.
        infoPanelVisible.addListener((o, was, now) -> {
            updateRightPanels();
            if (now) updateSelectionPanels();
        });
        metadataPanelVisible.addListener((o, was, now) -> updateRightPanels());
        diagnosticsPanelVisible.addListener((o, was, now) -> updateRightPanels());
        // Metadata: manual by default — the read is driven by the panel's Load
        // button or its opt-in Auto toggle (debounced on selection); showing the
        // panel reads nothing until a read lands. Mirrors the viewer.
        metadataPanel.setOnLoadRequested(this::fireMetadataRead);
        metadataPanel.autoLoadProperty().addListener((o, was, on) -> {
            metadataDebounce.stop();
            if (on && metadataPanelVisible.get()) fireMetadataRead();
        });
        metadataDebounce.setOnFinished(e -> {
            if (metadataPanelVisible.get() && metadataPanel.isAutoLoad()) fireMetadataRead();
        });
        updateRightPanels();
        var statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        statusBar = new HBox(8, statusLabel, statusSpacer, statusCountLabel);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        statusBar.visibleProperty().bind(statusBarVisible);
        statusBar.managedProperty().bind(statusBarVisible);

        // The shared session action log + quick-move targets (same panel the
        // browser shows), above this window's status bar.
        var actionLogPanel = new ActionLogPanel(settings);
        actionLogPanel.visibleProperty().bind(actionLogVisible);
        actionLogPanel.managedProperty().bind(actionLogVisible);

        // The shared menu bar is inserted at index 0 by installMenuBar(...).
        topBox = new VBox(toolBar, locationBar);
        this.root = new javafx.scene.layout.BorderPane(content);
        root.setTop(topBox);
        root.setRight(rightPanels);
        root.setBottom(new VBox(actionLogPanel, statusBar));
        root.setStyle("-fx-background-color: black;");
        // Anchor for mosaic.css's looked-up colour defaults (the themeable
        // toolbar/slider tints) — .root only matches when this view is the
        // scene root, which single-window mode doesn't guarantee.
        root.getStyleClass().add("mosaic-root");

        // On the root (not the shared scene): Parent stylesheets sit above the
        // scene's theme overlay, preserving the old css-over-theme ordering, and
        // they leave with the root when another view takes the window.
        var css = MosaicWindow.class.getResource("mosaic.css");
        if (css != null) root.getStylesheets().add(css.toExternalForm());
        // A capturing-phase filter (not a bubbling handler) so grid navigation
        // keys are honoured before a focused toolbar control (the size Slider,
        // the Fill toggle) can swallow them — otherwise, once
        // the toolbar is shown, arrows/Enter would drive that control instead of
        // the grid. Mirrors ViewerWindow's key filter. On the view root, so it
        // only sees events while the mosaic is the shell's active view.
        //
        // Consuming here is not enough on its own: a focused control's own
        // InputMap (e.g. the Slider's Home/End -> min/max) fires even when this
        // filter has already consumed the event, so the toolbar must never keep
        // keyboard focus. The focus-bounce below keeps it on the grid canvas.
        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::onKey);
        root.addEventHandler(javafx.scene.input.KeyEvent.KEY_TYPED, e -> {
            // Don't type-ahead into the grid while the menu bar owns the keyboard
            // (open menu / focused bar) or a text input (the location field, the
            // Metadata filter) is being typed into — those keystrokes belong to it.
            if (menuBarActive() || textInputFocused()) return;
            String prefix = typeahead.append(e);
            if (prefix == null) return;
            int i = Typeahead.indexOf(items, selected, prefix);
            if (i >= 0) selectSingle(i);
            e.consume();
        });

        // Keyboard focus belongs on the grid canvas. Clicking the toolbar or a
        // side panel otherwise parks focus on the clicked control, where its own
        // key bindings hijack Home/End/arrows (the Tile slider jumping to min/max
        // on Home/End being the visible symptom). So: a click in those areas arms
        // a one-tick "bounce", and the instant the control takes focus we hand it
        // straight back to the canvas — synchronously, within the same dispatch,
        // so no focused frame is ever painted and no layout shifts. Text inputs
        // (the Metadata filter) and pop-up owners (Copy All) are left alone so
        // they remain usable. Mirrors MainWindow's focus bounce.
        //
        // A drag needs more: the Slider re-grabs focus on each MOUSE_DRAGGED,
        // after the one-tick arm has lapsed, so it ends up focused once the drag
        // settles. Re-settle focus to the grid when the mouse is released over a
        // bounce panel — by then the drag is done and the control no longer needs
        // keyboard focus. (Dragging itself is mouse-driven and unaffected.)
        for (Node panel : List.of(toolBar, locationBar, rightPanels)) {
            panel.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> armFocusBounce());
            panel.addEventFilter(MouseEvent.MOUSE_RELEASED,
                    e -> Platform.runLater(this::settleFocusToGrid));
        }
        // All three views share the shell's scene, so gate this view's bounce
        // on being the active one; the other views' listeners coexist on the
        // same focusOwnerProperty.
        stage().getScene().focusOwnerProperty().addListener((obs, was, now) -> {
            if (!shell.isActiveNow(AppShell.AppView.MOSAIC)) return;
            if (!bounceArmed || now == null || now == canvas) return;
            if (now instanceof TextInputControl || now instanceof MenuButton) return;
            canvas.requestFocus();
        });

        // The Move dialog (Cmd+M / Mosaic ▸ Move…): the same window-agnostic
        // controller the main window uses, driven by a host that exposes this
        // grid's selection, current dir, post-move focus and refresh.
        this.moveController = new MoveController(service, settings, new MoveController.Host() {
            @Override public Stage owner() { return stage(); }
            @Override public Path currentDirectory() {
                return dirSupplier != null ? dirSupplier.get() : null;
            }
            @Override public MoveController.Selection currentSelection() { return currentMoveSelection(); }
            @Override public Path nextFocusAfterMove(List<Path> moving) { return MosaicWindow.this.nextFocusAfterMove(moving); }
            @Override public void refreshAfterMove(Path focusPath) { MosaicWindow.this.refreshAfterMove(focusPath); }
            @Override public void showStatus(String message) { statusLabel.setText(message); }
        });
    }

    // --- ShellView ------------------------------------------------------------

    /** The stage hosting this view: the single window, or the mosaic's own. */
    private Stage stage() {
        return shell.stageFor(AppShell.AppView.MOSAIC);
    }

    @Override
    public javafx.scene.Parent root() {
        return root;
    }

    @Override
    public javafx.beans.property.ReadOnlyStringProperty titleProperty() {
        return title;
    }

    /**
     * Runs as the mosaic takes the window: re-attaches the (possibly navigated)
     * source listing and rebuilds the grid — the browser may have moved on while
     * the mosaic was inactive — restarts the animation/telemetry timers and
     * claims keyboard focus for the grid. A no-op re-attach when {@link #open}
     * already attached in this switch.
     */
    @Override
    public void activate() {
        active = true;
        if (!attached && source != null) {
            source.addListener(sourceListener);
            attached = true;
            rebuild();
        }
        updateAnimationTimer();
        updateTelemetryTimer();
        canvas.requestFocus();
    }

    /**
     * Runs as the mosaic gives the window up: stops mirroring the (possibly
     * navigating) source list so a background-folder change doesn't kick off
     * thumbnail work for nothing, parks the loading indicator and the
     * animation/telemetry timers. {@link #activate()} re-arms everything.
     */
    @Override
    public void deactivate() {
        active = false;
        detachSource();
        hideLoadingIndicator();
        scanPending = false;
        loadingActive = false;
        updateAnimationTimer();
        updateTelemetryTimer();
    }

    @Override
    public ToolBar toolBar() {
        return toolBar;
    }

    @Override
    public MenuBar menuBar() {
        return menuBar;
    }

    /**
     * Installs the application's shared menu bar (built by {@link MainWindow})
     * above the toolbar. On macOS it is hoisted to the system menu bar (top of
     * the screen) while this view is active, unless the in-window-menu setting
     * opts out; off macOS it always renders in-window.
     */
    public void installMenuBar(MenuBar bar, KeyScheme keys) {
        this.keys = keys;
        this.menuBar = bar;
        topBox.getChildren().add(0, bar);
        // Runtime hide/show (Show ▸ Menu Bar, mod1+\) + modifier-2 peek; the last
        // arg hoists to the macOS system menu bar while visible (unless the
        // in-window-menu setting opts out), so the toggle can hide it there too.
        // Gated on the mosaic being the active view so the three views' bars
        // don't race for the system menu bar on the single stage.
        WindowChrome.bindMenuAutoHide(bar, stage().getScene(), stage(),
                menuBarVisible, shell.isActive(AppShell.AppView.MOSAIC), keys,
                !settings.inWindowMenu());
    }

    /** Toolbar visibility (Mosaic ▸ Show Toolbar binds here); hidden by default. */
    public BooleanProperty toolbarVisibleProperty() {
        return toolbarVisible;
    }

    /** Status-bar visibility (the mosaic bar's Show ▸ Status Bar binds here); hidden by default. */
    public BooleanProperty statusBarVisibleProperty() {
        return statusBarVisible;
    }

    /** Info-panel visibility (the mosaic bar's Show ▸ Info Panel binds here); hidden by default. */
    public BooleanProperty infoPanelVisibleProperty() {
        return infoPanelVisible;
    }

    /** Metadata-panel visibility (the mosaic bar's Show ▸ Metadata binds here); hidden by default. */
    public BooleanProperty metadataPanelVisibleProperty() {
        return metadataPanelVisible;
    }

    public BooleanProperty diagnosticsPanelVisibleProperty() {
        return diagnosticsPanelVisible;
    }

    /** Menu-bar visibility (the mosaic bar's Show ▸ Menu Bar binds here); shown by default. */
    public BooleanProperty menuBarVisibleProperty() {
        return menuBarVisible;
    }

    /** Location-bar visibility (Mosaic ▸ Show Location Bar binds here); hidden by default. */
    public BooleanProperty locationBarVisibleProperty() {
        return locationBarVisible;
    }

    /** Action-log panel visibility (the mosaic bar's Show ▸ Action Log binds here); hidden by default. */
    public BooleanProperty actionLogVisibleProperty() {
        return actionLogVisible;
    }

    /**
     * Go ▸ Go to Location in the mosaic's menu bar: reveals the location bar
     * (if hidden) and hands it keyboard focus with the path selected for
     * overtyping. Mirrors the main window's focusAddressBar.
     */
    public void focusLocationBar() {
        locationBarVisible.set(true);
        locationField.requestFocus();
        locationField.selectAll();
    }

    /** Crop-to-fill toggle (Mosaic ▸ Fill Tiles / toolbar bind here); fit by default. */
    public BooleanProperty fillTilesProperty() {
        return fillTiles;
    }

    /**
     * Seamless toggle (Mosaic ▸ Seamless / toolbar bind here); on by default,
     * persisted on change. While on, tiles render with zero margin and border
     * without touching the margin/border settings; off restores the configured
     * layout.
     */
    public BooleanProperty seamlessProperty() {
        return seamless;
    }

    /**
     * Thumbnail visibility toggle (Mosaic menu binds here). When off, media
     * tiles and folder preview collages draw lightweight glyphs and do not start
     * new thumbnail requests.
     */
    public BooleanProperty thumbnailsVisibleProperty() {
        return thumbnailsVisible;
    }

    /**
     * Folder (and {@code ..}) tile name-caption visibility (Mosaic menu's Tile
     * Labels submenu binds here); shown by default. Persists when toggled.
     */
    public BooleanProperty dirLabelsVisibleProperty() {
        return dirLabelsVisible;
    }

    /**
     * Non-media file tile name-caption visibility (Mosaic menu's Tile Labels
     * submenu binds here); shown by default. Persists when toggled.
     */
    public BooleanProperty fileLabelsVisibleProperty() {
        return fileLabelsVisible;
    }

    /**
     * Media tile name-caption visibility (Mosaic menu's Tile Labels submenu
     * binds here); hidden by default. Persists when toggled.
     */
    public BooleanProperty mediaLabelsVisibleProperty() {
        return mediaLabelsVisible;
    }

    /**
     * Folder-glyph style (Mosaic menu binds here): the muted vector folder
     * shape, its black-on-gray inverse, or a photographic folder image. The
     * plain glyph by default and persisted when changed.
     */
    public ObjectProperty<MosaicFolderGlyph> folderGlyphProperty() {
        return folderGlyph;
    }

    /**
     * Auto-open toggle (Mosaic ▸ Auto-open / toolbar bind here). Its startup
     * value comes from the {@code mosaicAutoOpen} setting (the default, edited in
     * Settings); flipping it live is transient and does not change that default.
     * Turning it on immediately previews the current selection.
     */
    public BooleanProperty autoOpenProperty() {
        return autoOpen;
    }

    /**
     * Keep-focus toggle (Mosaic ▸ Keep Focus on Open / toolbar bind here). In
     * the separate-windows mode, activating a media tile shows it in the viewer
     * window without stealing focus, so grid browsing continues uninterrupted;
     * in the single window it is inert (one view fills the window, so opening
     * always switches).
     */
    public BooleanProperty keepFocusProperty() {
        return keepFocus;
    }

    /**
     * Selection-animation style (Mosaic ▸ Selection Animation / Settings bind
     * here). {@code NONE} by default; {@code PULSE} cycles the lead tile's
     * brightness, {@code MARCHING_ANTS} crawls its dotted border. Changing it
     * live persists the choice.
     */
    public ObjectProperty<MosaicSelectionAnimation> selectionAnimationProperty() {
        return selectionAnimation;
    }

    /**
     * Applies and persists the animation style, then starts/stops the timer.
     * Switching away repaints the lead tile once to clear any lingering tint or
     * dash offset.
     */
    private void setSelectionAnimation(MosaicSelectionAnimation style) {
        settings.setMosaicSelectionAnimation(style);
        try {
            settings.save();
        } catch (java.io.IOException ignored) {
            // a failed save is non-fatal; the live setting still applies
        }
        updateAnimationTimer();
    }

    /**
     * Sets the full animation-cycle duration (ms) live, so a Settings change
     * takes effect on the running animation without a restart. Persistence of
     * the value itself is handled by the Settings dialog.
     */
    public void setAnimationPeriodMs(int periodMs) {
        this.animationPeriodNanos = Math.max(1, periodMs) * 1_000_000L;
    }

    /**
     * Starts the selection-animation {@link AnimationTimer} while the window is
     * shown and a style is selected, else stops it and clears any overlay/offset.
     * Each frame advances the cycle and repaints just the lead/cursor tile, so
     * the work is one tile redraw per frame regardless of grid size.
     */
    private void updateAnimationTimer() {
        boolean run = selectionAnimation.get() != MosaicSelectionAnimation.NONE
                && active;
        if (run) {
            if (animationTimer == null) {
                animationStartNanos = 0;
                animationTimer = new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (animationStartNanos == 0) animationStartNanos = now;
                        double t = ((now - animationStartNanos) % animationPeriodNanos)
                                / (double) animationPeriodNanos;
                        applyAnimationPhase(t);
                        if (selected >= 0 && columns > 0 && !items.isEmpty()) {
                            repaintCell(canvas.getGraphicsContext2D(), selected);
                        }
                    }
                };
                animationTimer.start();
            }
        } else if (animationTimer != null) {
            animationTimer.stop();
            animationTimer = null;
            pulseOverlay = null;
            marchPhase = 0;
            if (selected >= 0 && columns > 0 && !items.isEmpty()) {
                repaintCell(canvas.getGraphicsContext2D(), selected);
            }
        }
    }

    /**
     * When telemetry is enabled, samples JavaFX pulse gaps while the mosaic is
     * visible. Long gaps are the most direct signal that the UI thread is blocked.
     */
    private void updateTelemetryTimer() {
        if (!MosaicTelemetry.enabled()) return;
        if (active) {
            if (telemetryTimer == null) {
                telemetryLastPulseNanos = 0;
                telemetryTimer = new AnimationTimer() {
                    @Override public void handle(long now) {
                        if (telemetryLastPulseNanos != 0) {
                            MosaicTelemetry.recordFxPulseInterval(now - telemetryLastPulseNanos);
                        }
                        telemetryLastPulseNanos = now;
                    }
                };
                telemetryTimer.start();
            }
        } else if (telemetryTimer != null) {
            telemetryTimer.stop();
            telemetryTimer = null;
            telemetryLastPulseNanos = 0;
        }
    }

    /**
     * Computes the per-frame animation state for cycle phase {@code t} in
     * {@code [0, 1)}: the pulse sets {@link #pulseOverlay}, the marching ants
     * set {@link #marchPhase} so the dots slide one full dash period per
     * cycle. Only the active style's state is set; the other is cleared so a
     * style switch can't leave a stale tint or offset on the lead tile.
     */
    private void applyAnimationPhase(double t) {
        if (selectionAnimation.get() == MosaicSelectionAnimation.PULSE) {
            pulseOverlay = pulseOverlayFor(t);
            marchPhase = 0;
        } else {   // MARCHING_ANTS
            marchPhase = t;
            pulseOverlay = null;
        }
    }

    /**
     * The pulse overlay tint for cycle phase {@code t} in {@code [0, 1)}: the
     * first half fades a white overlay {@code 0 → 50% → 0}, the second half fades
     * a black overlay {@code 0 → 50% → 0} (a triangle wave in each half).
     */
    private static Color pulseOverlayFor(double t) {
        if (t < 0.5) {
            double local = t / 0.5;                      // 0..1 across the white half
            double a = PULSE_MAX_ALPHA * (1 - Math.abs(2 * local - 1));
            return Color.color(1, 1, 1, a);
        }
        double local = (t - 0.5) / 0.5;                  // 0..1 across the black half
        double a = PULSE_MAX_ALPHA * (1 - Math.abs(2 * local - 1));
        return Color.color(0, 0, 0, a);
    }

    /**
     * In auto-open mode, shows the single selected media tile in the viewer
     * without taking focus, so arrow-browsing the grid keeps driving the viewer.
     * No-op for folders/{@code ..}/other tiles, for multi-tile selections, and
     * while mirroring the viewer's own item back (see {@link #suppressAutoOpen}).
     */
    private void maybeAutoOpen() {
        if (!autoOpen.get() || suppressAutoOpen) return;
        if (selection.size() != 1) return;
        if (selected < 0 || selected >= items.size()) return;
        DirEntry entry = items.get(selected);
        if (entry.type() != DirEntry.Type.MEDIA) return;
        viewer.open(entry.toMediaItem(), viewerRing(), false, this);
    }

    /**
     * Refreshes the status bar's left label and the info panel to track the
     * current selection, mirroring the main window's selection feedback. The
     * status label always updates; the info panel is probed (native work) only
     * while it is shown, so a hidden panel does nothing — matching the mosaic's
     * "don't work while hidden" stance elsewhere. Hooked from {@link
     * #commitSelection} and {@link #rebuild}, and on revealing the panel.
     */
    private void updateSelectionPanels() {
        int gen = ++probeSequence;        // one staleness mark per selection change
        int count = selection.size();
        DirEntry lead = (selected >= 0 && selected < items.size()) ? items.get(selected) : null;
        if (count > 1) {
            statusLabel.setText(multiSelectionSummary(count, lead));
        } else if (lead != null) {
            statusLabel.setText(lead.displayName());
        } else {
            statusLabel.setText(mosaicDir != null ? mosaicDir.toString() : "");
        }
        if (infoPanelVisible.get()) refreshInfoPanel(lead, count, gen);
        // Metadata panel (manual by default): reset to the new selection's
        // placeholder and re-arm the opt-in Auto debounce; the read itself is
        // driven by Load / Auto. Mirrors the viewer.
        metadataDebounce.stop();
        metadataPanel.resetToPlaceholder();
        if (metadataPanelVisible.get() && metadataPanel.isAutoLoad()
                && count == 1 && lead != null && lead.viewable()) {
            metadataDebounce.playFromStart();
        }
    }

    /**
     * One-line multi-selection summary shared by the status bar and info
     * panel: count, total bytes (from the listing's cached sizes — folders
     * and {@code ..} count as 0, no filesystem calls), and the lead tile's
     * name, e.g. {@code "5 items selected — 12.3 MiB — IMG_2041.jpg"}.
     */
    private String multiSelectionSummary(int count, DirEntry lead) {
        long totalBytes = 0;
        for (int index : selection) {
            if (index >= 0 && index < items.size()) totalBytes += items.get(index).size();
        }
        String text = count + " items selected — " + MediaProbe.humanBytes(totalBytes);
        if (lead != null) text += " — " + lead.displayName();
        return text;
    }

    /**
     * Rebuilds the right vertical split from whichever of the info / metadata
     * panels are toggled on, so a hidden panel leaves no empty divider and
     * both-hidden hands the grid its full width back. Mirrors the viewer's
     * right-panel handling.
     */
    private void updateRightPanels() {
        var panels = new ArrayList<Node>(3);
        if (infoPanelVisible.get()) panels.add(infoPanel);
        if (metadataPanelVisible.get()) panels.add(metadataPanel);
        if (diagnosticsPanelVisible.get()) panels.add(diagnosticsPanel);
        if (!rightPanels.getItems().equals(panels)) {
            rightPanels.getItems().setAll(panels);
            if (panels.size() == 2) rightPanels.setDividerPositions(0.4);
            else if (panels.size() == 3) rightPanels.setDividerPositions(0.34, 0.67);
        }
        boolean any = !panels.isEmpty();
        rightPanels.setVisible(any);
        rightPanels.setManaged(any);
    }

    /**
     * Populates the info panel for the lead tile: a single viewable item is
     * probed asynchronously (guarded on {@code gen} so a superseded selection's
     * result is ignored), mirroring {@code MainWindow.onSelection}; folders,
     * {@code ..}, other files, multi-selections and empty selections show a
     * message instead.
     */
    private void refreshInfoPanel(DirEntry lead, int count, int gen) {
        if (count > 1) {
            infoPanel.clearFileFacts();
            infoPanel.showMessage(multiSelectionSummary(count, lead));
            return;
        }
        if (lead == null) {
            infoPanel.clearFileFacts();
            infoPanel.showMessage("No selection");
            return;
        }
        if (!lead.viewable()) {
            updateFileFacts(lead, gen);
            infoPanel.showMessage(switch (lead.type()) {
                case PARENT -> "Parent folder";
                case DIRECTORY -> "Folder";
                default -> "Not viewable media";
            });
            return;
        }
        infoPanel.showMessage("Probing…");
        updateFileFacts(lead, gen);
        service.probe(lead.path()).whenComplete((probe, error) ->
                Platform.runLater(() -> {
                    if (gen != probeSequence) return;
                    if (error != null) {
                        infoPanel.showMessage(rootMessage(error));
                    } else {
                        infoPanel.show(probe);
                    }
                }));
    }

    /**
     * Fills the info panel's File section for the lead tile — name, size and
     * timestamps from an async filesystem stat, independent of the probe — or
     * clears it for the parent link and folders. Guarded on {@code gen} like
     * the probe itself; mirrors {@code MainWindow.updateFileFacts}.
     */
    private void updateFileFacts(DirEntry lead, int gen) {
        if (lead.type() == DirEntry.Type.PARENT || lead.type() == DirEntry.Type.DIRECTORY) {
            infoPanel.clearFileFacts();
            return;
        }
        service.fileFacts(lead.path()).whenComplete((facts, error) ->
                Platform.runLater(() -> {
                    if (gen != probeSequence) return;
                    if (error != null) {
                        infoPanel.clearFileFacts();
                    } else {
                        infoPanel.showFileFacts(
                                InfoPanel.fileFactRows(lead.displayName(), facts));
                    }
                }));
    }

    /**
     * Starts the on-demand metadata read for the lead tile (a manual Load click
     * or the opt-in Auto debounce), mirroring the viewer. Runs on the service's
     * dedicated {@code media-metadata} thread; a later selection bumps {@code
     * probeSequence}, so a superseded read is dropped.
     */
    private void fireMetadataRead() {
        DirEntry lead = (selected >= 0 && selected < items.size()) ? items.get(selected) : null;
        if (lead == null || !lead.viewable() || selection.size() != 1) {
            metadataPanel.showMessage(lead == null ? "No selection" : "Not viewable media");
            return;
        }
        int gen = probeSequence;
        metadataPanel.showMessage("Reading\u2026");
        service.metadata(lead.path()).whenComplete((md, error) ->
                Platform.runLater(() -> {
                    if (gen != probeSequence) return;
                    if (error != null) {
                        metadataPanel.showMessage(rootMessage(error));
                    } else {
                        metadataPanel.show(md);
                    }
                }));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /**
     * Switches between fit-on-black and crop-to-fill (square) renditions,
     * persists the choice, and drops the per-tile caches so tiles regenerate in
     * the new mode (FIT and FILL renditions cache separately in the service).
     */
    private void setFillTiles(boolean fill) {
        thumbnailMode = fill ? ThumbnailMode.FILL : ThumbnailMode.FIT;
        settings.setMosaicFillTiles(fill);
        try {
            settings.save();
        } catch (java.io.IOException ignored) {
            // a failed save is non-fatal; the live mode still applies
        }
        generation++;          // ignore in-flight completions rendered in the old mode
        images.clear();
        scaled.clear();
        pending.clear();
        empties.clear();
        folderPreviews.clear();
        folderPending.clear();
        requestDraw();
    }

    /**
     * Sets the handler that navigates the shared location into a folder (or up
     * via {@code ..}). The host (main window) re-lists and re-applies its
     * sort/filter, which flows back through {@link #source} and rebuilds the
     * grid — so the mosaic mirrors the main window rather than navigating alone.
     */
    public void setNavigator(Consumer<Path> navigator) {
        this.navigator = navigator != null ? navigator : path -> { };
    }

    /**
     * Sets the handlers that step the shared location back/forward through the
     * host's navigation history (wired to Cmd+Left / Cmd+Right). Like
     * {@link #setNavigator}, the host re-lists and the change flows back through
     * {@link #source} to rebuild this grid.
     */
    public void setHistoryHandlers(Runnable back, Runnable forward) {
        this.backHandler = back != null ? back : () -> { };
        this.forwardHandler = forward != null ? forward : () -> { };
    }

    /**
     * Sets the folder-tile preview collage edge {@code N} (N×N child images;
     * {@code 0} draws a plain folder glyph), dropping any cached scans so the
     * new {@code N²} limit takes effect, and repaints.
     */
    public void setFolderPreviewGrid(int grid) {
        this.folderPreviewGrid = Math.max(0, Math.min(4, grid));
        folderPreviews.clear();
        folderPending.clear();
        scaled.clear();   // collage sub-tiles change size with the grid edge
        relayout();       // recompute the cache cap: each tile now holds N² images
    }

    /**
     * Re-reads the persisted mosaic layout/appearance settings and applies them
     * live (Settings ▸ Mosaic ▸ Save). Tile size flows through the toolbar slider
     * so its handle stays in sync; the preview max-edge and cache budget are
     * fixed at construction and so are intentionally not re-applied here.
     */
    public void applyLayoutSettings() {
        // The Seamless toggle overrides margin/border while it is on.
        this.seamless.set(settings.mosaicSeamless());
        this.margin = seamless.get() ? 0 : settings.mosaicMargin();
        this.borderWidth = seamless.get() ? 0 : settings.mosaicBorderWidth();
        this.borderColor = parseColor(settings.mosaicBorderColor(), Color.web("#3c3c3c"));
        this.fillTiles.set(settings.mosaicFillTiles());
        this.thumbnailsVisible.set(settings.mosaicThumbnailsVisible());
        this.autoOpen.set(settings.mosaicAutoOpen());
        this.dirLabelsVisible.set(settings.mosaicDirLabelsVisible());
        this.fileLabelsVisible.set(settings.mosaicFileLabelsVisible());
        this.mediaLabelsVisible.set(settings.mosaicMediaLabelsVisible());
        this.folderGlyph.set(settings.mosaicFolderGlyph());
        this.selectionAnimation.set(settings.mosaicSelectionAnimation());
        setAnimationPeriodMs(settings.mosaicPulsePeriodMs());
        // The slider's listener stores tileSize, persists it, clears the scaled
        // cache and relayouts; a no-op when the value is unchanged.
        if (sizeSlider != null) sizeSlider.setValue(settings.mosaicTileSize());
        // Folder-preview grid clears the preview caches and relayouts/redraws,
        // which also picks up the margin/border/colour changes above.
        setFolderPreviewGrid(settings.mosaicFolderPreviewGrid());
    }

    /** Opens or navigates the selected tile (Mosaic ▸ Open Selected). */
    public void openSelectedItem() {
        activateSelected();
    }

    /**
     * Opens the move dialog for the selected tile (Cmd+M / Mosaic ▸ Move…).
     * No-op until the grid is bound to a directory.
     */
    public void openMove() {
        if (dirSupplier == null || dirSupplier.get() == null) return;
        moveController.open();
    }

    /**
     * Resolves the move sources from the selected tiles: every selected entry
     * other than the {@code ..} parent tile (in listing order), with
     * {@code parentExcluded} flagged when {@code ..} was part of the selection
     * (so the dialog never tries to move the parent link).
     */
    private MoveController.Selection currentMoveSelection() {
        boolean parentExcluded = false;
        List<Path> sources = new ArrayList<>();
        for (int i : selection) {            // TreeSet → ascending = listing order
            if (i < 0 || i >= items.size()) continue;
            DirEntry sel = items.get(i);
            if (sel.type() == DirEntry.Type.PARENT) parentExcluded = true;
            else sources.add(sel.path());
        }
        return new MoveController.Selection(sources, parentExcluded);
    }

    /**
     * The path to focus once {@code moving} leaves the grid: the first tile
     * after the selected block that is not moving, else the first before it,
     * else null. The cursor is the lowest selected tile, so the scan starts
     * just past the block. Read against the current (pre-move) {@link #items}.
     */
    private Path nextFocusAfterMove(List<Path> moving) {
        Set<Path> movingSet = new HashSet<>(moving);
        int cursor = selection.isEmpty() ? selected : selection.first();
        for (int i = cursor + 1; i < items.size(); i++) {
            Path p = items.get(i).path();
            if (!movingSet.contains(p)) return p;
        }
        for (int i = cursor - 1; i >= 0; i--) {
            Path p = items.get(i).path();
            if (!movingSet.contains(p)) return p;
        }
        return null;
    }

    /**
     * Refreshes the grid after a move and lands selection on {@code focusPath}.
     * Re-navigates the shared location to the current directory so the main
     * window re-lists; the new listing flows back through {@link #sourceListener}
     * and {@link #rebuild} honours {@link #pendingSelectPath} to re-select the
     * focus target (or clears selection when {@code null}).
     */
    private void refreshAfterMove(Path focusPath) {
        Path dir = dirSupplier != null ? dirSupplier.get() : null;
        if (dir == null) return;
        pendingSelectPath = focusPath;
        autoOpenAfterRebuild = true;   // keep the viewer in step once the re-list lands
        navigator.accept(dir);
    }

    /** Steps the on-screen tile size by {@code delta}px, clamped to the slider range. */
    public void nudgeTileSize(int delta) {
        sizeSlider.setValue(clamp(sizeSlider.getValue() + delta,
                sizeSlider.getMin(), sizeSlider.getMax()));
    }

    /** Returns focus to the main window and hides the mosaic (Mosaic ▸ Close Mosaic). */
    public void closeWindow() {
        backToMain();
    }

    /**
     * Shows the mosaic mirroring {@code source} (the main window's live,
     * filtered+sorted listing: {@code ..}, folders, media and other files), with
     * {@code dirSupplier} naming the current directory. The mosaic keeps a
     * change-listener on {@code source}, so navigating or re-filtering in either
     * window rebuilds the grid; tile renditions and folder collages fill in as
     * they become visible.
     */
    public void open(ObservableList<DirEntry> source, Supplier<Path> dirSupplier) {
        detachSource();
        this.source = source;
        this.dirSupplier = dirSupplier;
        source.addListener(sourceListener);
        attached = true;
        // Force a full reset even when re-opening on the same directory.
        mosaicDir = null;
        rebuild();
        shell.showView(AppShell.AppView.MOSAIC);
    }

    /**
     * Arms the focus bounce for one event tick: the next time a toolbar or panel
     * control takes focus (from a click), the scene focus-owner listener hands it
     * straight back to the grid canvas, before any frame is painted. The flag
     * clears on the next pulse, so it never disturbs a later, genuine focus
     * change (e.g. Tab navigation). Mirrors MainWindow.
     */
    private void armFocusBounce() {
        bounceArmed = true;
        Platform.runLater(() -> bounceArmed = false);
    }

    /**
     * Hands keyboard focus back to the grid canvas if it has drifted onto a
     * toolbar/panel control — used after a mouse interaction (e.g. dragging the
     * Tile slider) settles, where the one-tick {@link #armFocusBounce()} window
     * has already lapsed. Leaves text inputs (the Metadata filter) and pop-up
     * owners (Copy All) focused so they stay usable.
     */
    private void settleFocusToGrid() {
        Node owner = canvas.getScene() != null ? canvas.getScene().getFocusOwner() : null;
        if (owner == null || owner == canvas) return;
        if (owner instanceof TextInputControl || owner instanceof MenuButton) return;
        canvas.requestFocus();
    }

    /** Stops mirroring the source list (on hide); re-attached by {@link #open}. */
    private void detachSource() {
        if (attached && source != null) source.removeListener(sourceListener);
        attached = false;
    }

    /**
     * Re-snapshots {@link #source} into the grid. A change of directory resets
     * scroll/selection and drops the per-tile caches (a fresh folder); a change
     * within the same directory (filter/sort) preserves the selection by path.
     */
    private void rebuild() {
        // The listing has landed: the scan-phase indicator now yields to the
        // thumbnail backlog. Cleared here (not via refreshLoadingState) so the
        // indicator stays continuously shown as the first draw below requests
        // tiles, rather than flickering off and re-arming behind the delay gate.
        scanPending = false;
        Path dir = dirSupplier != null ? dirSupplier.get() : null;
        boolean dirChanged = !Objects.equals(dir, mosaicDir);
        Path priorLead = (selected >= 0 && selected < items.size())
                ? items.get(selected).path() : null;
        List<Path> priorPaths = new ArrayList<>();
        for (int i : selection) {
            if (i >= 0 && i < items.size()) priorPaths.add(items.get(i).path());
        }
        items = source == null ? List.of() : source.stream()
                .filter(e -> switch (e.type()) {
                    case PARENT, DIRECTORY -> true;
                    case MEDIA -> switch (e.mediaKind()) {
                        case IMAGE -> filterImages.get();
                        case VIDEO -> filterVideos.get();
                        case AUDIO -> filterAudio.get();
                    };
                    case OTHER -> filterOther.get();
                })
                .toList();
        mosaicDir = dir;
        boolean reveal = false;
        // A pending focus path — set after navigating up via ../Backspace, or by
        // refreshAfterMove once a move completes — wins over selection-by-path in
        // either branch below, then is consumed.
        int wanted = pendingSelectPath != null ? indexOfPath(pendingSelectPath) : -1;
        boolean hadPending = pendingSelectPath != null;
        pendingSelectPath = null;
        if (dirChanged) {
            typeahead.reset();
            generation++;
            images.clear();
            scaled.clear();
            pending.clear();
            empties.clear();
            folderPreviews.clear();
            folderPending.clear();
            // Draw-loop per-path/per-text caches are directory-scoped; drop them
            // so they don't carry stale adjustments/crops/captions across folders.
            adjustmentsCache.clear();
            colorSource.clear();
            colorOutput.clear();
            cropCache.clear();
            captionWidths.clear();
            if (wanted >= 0) {
                resetSelectionTo(wanted);
                reveal = true;          // scroll the source folder into view below
            } else {
                resetSelectionTo(items.isEmpty() ? -1 : 0);
            }
            scrollY = 0;
            vbar.setValue(0);
        } else if (wanted >= 0) {
            // Same directory, but a move asked to land on a specific entry.
            resetSelectionTo(wanted);
            reveal = true;
        } else if (hadPending) {
            // The pending entry is gone (e.g. it was the only file moved out);
            // fall back to the top rather than the stale prior selection.
            resetSelectionTo(items.isEmpty() ? -1 : 0);
        } else {
            // Same directory (filter/sort, or a partial move): preserve the whole
            // selection by path, dropping any entries that no longer exist.
            selection.clear();
            for (Path p : priorPaths) {
                int idx = indexOfPath(p);
                if (idx >= 0) selection.add(idx);
            }
            int lead = priorLead != null ? indexOfPath(priorLead) : -1;
            if (lead < 0) {
                lead = selection.isEmpty() ? (items.isEmpty() ? -1 : 0) : selection.first();
            }
            if (lead >= 0) selection.add(lead);
            selected = lead;
            anchor = lead;
        }
        title.set((dir != null ? dir.getFileName() != null
                ? dir.getFileName().toString() : dir.toString() : "Mosaic") + " — Mosaic");
        locationField.setText(dir != null ? dir.toString() : "");
        statusCountLabel.setText(countText());
        updateSelectionPanels();
        relayout();
        // After relayout (columns now known), bring the came-from folder on screen.
        if (reveal) scrollLeadIntoView(selected);
        // A completed move asked us to keep the viewer in step: open the tile the
        // move landed on (a no-op unless auto-open is on and the tile is media).
        if (autoOpenAfterRebuild) {
            autoOpenAfterRebuild = false;
            maybeAutoOpen();
        }
    }

    /**
     * Calls {@link #rebuild} with the current filter in place. When the
     * directory hasn't changed the existing selection is preserved by path
     * (entries that are filtered out are dropped); the scroll position is
     * kept. Used from the filter-toggle listeners so toggling a type on/off
     * does not reset the viewport.
     */
    private void rebuildWithPreservedSelection() {
        if (source == null) return;
        rebuild();
    }

    /** Index of the entry with the given path in the current grid, or -1. */
    private int indexOfPath(Path path) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path().equals(path)) return i;
        }
        return -1;
    }

    /** Status-line tally mirroring the main window's count. */
    private String countText() {
        long dirs = 0, media = 0, other = 0;
        for (DirEntry e : items) {
            switch (e.type()) {
                case DIRECTORY -> dirs++;
                case MEDIA -> media++;
                case OTHER -> other++;
                case PARENT -> { }
            }
        }
        return dirs + " folders · " + (media + other) + " files · " + media + " viewable";
    }

    // --- layout & drawing ----------------------------------------------------

    /** Recomputes the grid for the current size/tile/margin and redraws. */
    private void relayout() {
        double viewW = canvas.getWidth();
        int outer = tileSize + 2 * borderWidth;
        int strideX = outer + margin;
        columns = Math.max(1, (int) ((viewW - margin) / strideX));
        rows = items.isEmpty() ? 0 : (items.size() + columns - 1) / columns;

        double contentHeight = margin + rows * (double) (outer + margin);
        double viewH = canvas.getHeight();
        // Size the FX-image caches to the viewport so a currently-visible tile is
        // never evicted (see maxCachedImages): one screenful of tiles, times the
        // folder-collage sub-image count, plus two rows of scroll headroom.
        int strideY0 = outer + margin;
        int visibleRows = (int) Math.ceil(viewH / (double) strideY0) + 1;
        int perTile = Math.max(1, folderPreviewGrid * folderPreviewGrid);
        int visibleImages = columns * visibleRows * perTile;
        maxCachedImages = Math.max(MIN_CACHED_IMAGES, visibleImages + columns * perTile * 2);
        double maxScroll = Math.max(0, contentHeight - viewH);
        vbar.setMax(maxScroll);
        vbar.setVisibleAmount(maxScroll <= 0 ? 1 : viewH / contentHeight * maxScroll);
        vbar.setUnitIncrement(outer + margin);
        vbar.setBlockIncrement(viewH);
        vbar.setVisible(maxScroll > 0);
        vbar.setManaged(maxScroll > 0);
        scrollY = clamp(scrollY, 0, maxScroll);
        if (vbar.getValue() != scrollY) vbar.setValue(scrollY);
        requestDraw();
    }

    /** Coalesces redraw requests onto a single FX-tick repaint. */
    private void requestDraw() {
        if (redrawQueued) {
            MosaicTelemetry.recordDrawCoalesced();
            return;
        }
        redrawQueued = true;
        long queuedAt = MosaicTelemetry.now();
        Platform.runLater(() -> {
            MosaicTelemetry.recordDrawQueued(MosaicTelemetry.elapsedSince(queuedAt));
            redrawQueued = false;
            draw();
        });
    }

    private void draw() {
        long startedAt = MosaicTelemetry.now();
        int tilesDrawn = 0;
        try {
            GraphicsContext g = canvas.getGraphicsContext2D();
            // A monitor move can change the device-pixel scale; rebuild scaled tiles.
            double sc = renderScale();
            if (sc != scaledScale) {
                scaledScale = sc;
                scaled.clear();
            }
            double viewW = canvas.getWidth(), viewH = canvas.getHeight();
            g.setFill(Color.BLACK);
            g.fillRect(0, 0, viewW, viewH);
            if (items.isEmpty() || columns <= 0) return;

            int outer = tileSize + 2 * borderWidth;
            int strideX = outer + margin;
            int strideY = outer + margin;
            double leftPad = margin;

            int firstRow = Math.max(0, (int) ((scrollY - margin) / strideY));
            int lastRow = Math.min(rows - 1, (int) ((scrollY + viewH - margin) / strideY));

            for (int row = firstRow; row <= lastRow; row++) {
                double y = margin + row * (double) strideY - scrollY;
                for (int col = 0; col < columns; col++) {
                    int i = row * columns + col;
                    if (i >= items.size()) break;
                    double x = leftPad + col * (double) strideX;
                    drawTile(g, items.get(i), x, y, outer, selection.contains(i), i == selected);
                    tilesDrawn++;
                }
            }
        } finally {
            MosaicTelemetry.recordDraw(MosaicTelemetry.elapsedSince(startedAt), tilesDrawn);
            // The draw just requested any newly visible tiles, so re-evaluate the
            // backlog and arm/dissolve the loading indicator accordingly.
            refreshLoadingState();
        }
    }

    /**
     * Re-evaluates whether the mosaic is loading — a directory scan in flight
     * ({@link #scanPending}) or any thumbnail / folder-preview request pending —
     * and arms or dissolves the loading indicator on each transition. Called when
     * a scan starts, after a draw (which requests newly visible tiles) and as
     * each request completes, so the indicator spans the whole open: the scan
     * itself, then the thumbnail backlog, rather than any single tile.
     */
    private void refreshLoadingState() {
        boolean loading = scanPending || !pending.isEmpty() || !folderPending.isEmpty();
        if (loading == loadingActive) return;
        loadingActive = loading;
        if (loading) scheduleLoadingIndicator();
        else hideLoadingIndicator();
    }

    /**
     * Arms the loading indicator behind the viewer's configured delay (the shared
     * gate): if the backlog clears within {@link AppSettings#viewerLoadingIndicatorDelayMs}
     * the indicator never shows, sparing a flash on a fast folder; only a backlog
     * still loading when the delay elapses reveals it. A zero delay shows it at
     * once, and {@link LoadingIndicator#NONE} arms nothing. Idempotent while
     * already armed or shown.
     */
    private void scheduleLoadingIndicator() {
        if (loadingShown) return;
        if (loadingIndicatorDelay.getStatus() == Animation.Status.RUNNING) return;
        if (settings.viewerLoadingIndicator() == LoadingIndicator.NONE) return;
        int delayMs = settings.viewerLoadingIndicatorDelayMs();
        if (delayMs <= 0) {
            showLoadingIndicator();
            return;
        }
        loadingIndicatorDelay.setDuration(Duration.millis(delayMs));
        loadingIndicatorDelay.playFromStart();
    }

    /**
     * Shows whichever loading indicator the viewer's setting selects over the
     * grid: {@link LoadingIndicator#DEFAULT} a centred "Loading…" pill, {@link
     * LoadingIndicator#GAME_CONSOLE} the bottom-left spinning-CD overlay. Reached
     * via {@link #scheduleLoadingIndicator} once the delay gate elapses.
     */
    private void showLoadingIndicator() {
        loadingShown = true;
        switch (settings.viewerLoadingIndicator()) {
            case DEFAULT -> loadingLabel.setVisible(true);
            case GAME_CONSOLE -> loadingOverlay.start();
            case NONE -> { }
        }
    }

    /**
     * Dissolves the loading indicator once the decode backlog clears (or on a
     * hide): cancels the delay gate, hides the "Loading…" pill and fades out the
     * Game Console overlay.
     */
    private void hideLoadingIndicator() {
        loadingShown = false;
        loadingIndicatorDelay.stop();
        loadingLabel.setVisible(false);
        loadingOverlay.stop();
    }

    /**
     * Immediate feedback that a directory scan has begun (the listing is being
     * built off the FX thread, the slow content-sniffing part of an open): names
     * the target in the location bar / title and arms the loading indicator at once,
     * so an open into a large/slow folder no longer looks frozen until the scan
     * lands. The host (main window) calls this the moment it kicks off the scan,
     * for navigation from any source. A no-op while hidden; cleared by the next
     * {@link #rebuild} once the listing arrives.
     */
    public void onDirectoryScanStarted(Path target) {
        if (!active) return;
        scanPending = true;
        if (target != null) {
            locationField.setText(target.toString());
            title.set((target.getFileName() != null
                    ? target.getFileName().toString() : target.toString()) + " — Mosaic");
        }
        refreshLoadingState();
    }

    /**
     * Repaints just one grid cell in place on the (persistent) canvas. Used for
     * selection changes that don't scroll: only the tiles whose selected-state
     * flipped need redrawing, so there's no need to clear and redraw every
     * visible tile. {@link #drawTile} repaints a cell's full {@code outer} box,
     * which erases the previous selection marker; the inter-tile margins stay
     * black (the marker never extends into them), so nothing ghosts.
     */
    private void repaintCell(GraphicsContext g, int index) {
        if (index < 0 || index >= items.size()) return;
        long startedAt = MosaicTelemetry.now();
        try {
            int outer = tileSize + 2 * borderWidth;
            int strideX = outer + margin;
            int strideY = outer + margin;
            double x = margin + (index % columns) * (double) strideX;
            double y = margin + (index / columns) * (double) strideY - scrollY;
            if (y + outer < 0 || y > canvas.getHeight()) return;   // scrolled off-screen
            drawTile(g, items.get(index), x, y, outer, selection.contains(index), index == selected);
        } finally {
            MosaicTelemetry.recordRepaint(MosaicTelemetry.elapsedSince(startedAt));
        }
    }

    /** Draws one tile: border frame, black content box, then type-specific content. */
    private void drawTile(GraphicsContext g, DirEntry item, double x, double y,
                          int outer, boolean isSelected, boolean isLead) {
        if (borderWidth > 0) {
            g.setFill(borderColor);
            g.fillRect(x, y, outer, outer);
        }
        double cx = x + borderWidth, cy = y + borderWidth;
        g.setFill(Color.BLACK);
        g.fillRect(cx, cy, tileSize, tileSize);

        switch (item.type()) {
            case MEDIA -> drawMediaContent(g, item, cx, cy);
            case DIRECTORY -> drawFolderContent(g, item, cx, cy);
            case PARENT -> drawParentContent(g, cx, cy);
            case OTHER -> drawOtherContent(g, item, cx, cy);
        }

        // The brightness pulse (PULSE style) tints the lead/cursor tile, painted
        // over the content but under the selection marker so the marker stays
        // crisp. See updateAnimationTimer / pulseOverlayFor.
        if (isLead && pulseOverlay != null) {
            g.setFill(pulseOverlay);
            g.fillRect(x, y, outer, outer);
        }

        // Keyboard-selection marker, painted last so it stays on top of the
        // rendition. See drawSelectionBorder: kept inside the tile cell so the
        // grid never reflows/expands regardless of border/margin settings. The
        // lead tile's dots crawl when the MARCHING_ANTS style is animating (a
        // non-zero cycle phase); every other selected tile stays static.
        if (isSelected) {
            double phase = (isLead
                    && selectionAnimation.get() == MosaicSelectionAnimation.MARCHING_ANTS)
                    ? marchPhase : 0;
            drawSelectionBorder(g, x, y, outer, phase);
        }
    }

    /** A media tile: its fitted rendition, or a kind-aware placeholder glyph. */
    private void drawMediaContent(GraphicsContext g, DirEntry item, double cx, double cy) {
        Image img = thumbnailsVisible.get() ? imageFor(item.path()) : null;
        if (img != null) {
            drawScaled(g, item.path(), img, cx, cy, tileSize, tileSize);
        } else if (!thumbnailsVisible.get()) {
            drawMediaGlyph(g, item, cx, cy);
        } else {
            String glyph = empties.contains(item.path())
                    ? (item.mediaKind() == MediaKind.AUDIO ? "\u266a" : "\u2014")
                    : "\u2026";
            drawGlyph(g, glyph, cx, cy, tileSize);
        }
        if (mediaLabelsVisible.get()) drawCaption(g, item.displayName(), cx, cy);
    }

    /**
     * A folder tile: an N×N collage of the first N² child visual media — images
     * and video first-frames (scanned and decoded lazily), captioned with the
     * folder name; folders with no visual media (or with previews disabled) fall
     * back to a plain folder glyph.
     */
    private void drawFolderContent(GraphicsContext g, DirEntry item, double cx, double cy) {
        List<Path> children = thumbnailsVisible.get() && folderPreviewGrid > 0
                ? folderPreviewFor(item.path()) : null;
        boolean drew = false;
        if (children != null && !children.isEmpty()) {
            int n = folderPreviewGrid;
            double sub = tileSize / (double) n;
            int count = Math.min(children.size(), n * n);
            for (int k = 0; k < count; k++) {
                Image img = imageFor(children.get(k));
                if (img == null) continue;
                drawScaled(g, children.get(k), img,
                        cx + (k % n) * sub, cy + (k / n) * sub, sub, sub);
                drew = true;
            }
        }
        if (!drew) drawFolderGlyph(g, cx, cy);
        if (dirLabelsVisible.get()) drawCaption(g, item.displayName(), cx, cy);
    }

    /** The {@code ..} parent tile: an up-arrow glyph captioned "..". */
    private void drawParentContent(GraphicsContext g, double cx, double cy) {
        drawGlyph(g, "\u2191", cx, cy, tileSize);
        if (dirLabelsVisible.get()) drawCaption(g, "..", cx, cy);
    }

    /** A non-media file tile: its (faint) uppercase extension, or a dash. */
    private void drawOtherContent(GraphicsContext g, DirEntry item, double cx, double cy) {
        String ext = item.extension();
        // The extension label is trimmed to <=4 chars and sized to fit the tile,
        // so it needs no clip. Avoiding g.clip() here is important: a clip forces
        // NGCanvas to validate and GL-clear a full-canvas-sized buffer per call
        // (NGCanvas.initClip -> nClearBuffers), which on a large window dominates
        // render time and stalls scrolling in file-heavy directories.
        drawGlyph(g, ext.isEmpty() ? "\u2014" : trimExtensionLabel(ext.toUpperCase()), cx, cy, tileSize * 0.55);
        if (fileLabelsVisible.get()) drawCaption(g, item.displayName(), cx, cy);
    }

    /** Extension labels show at most 4 chars; 5+ are cut to 3 plus an ellipsis. */
    private static String trimExtensionLabel(String ext) {
        return ext.length() <= 4 ? ext : ext.substring(0, 3) + "\u2026";
    }

    /** A lightweight non-thumbnail marker for media tiles while thumbnails are hidden. */
    private void drawMediaGlyph(GraphicsContext g, DirEntry item, double cx, double cy) {
        String ext = item.extension();
        if (!ext.isEmpty()) {
            drawGlyph(g, trimExtensionLabel(ext.toUpperCase()), cx, cy, tileSize * 0.55);
        } else {
            drawGlyph(g, item.mediaKind() == MediaKind.AUDIO ? "\u266a" : "\u2014",
                    cx, cy, tileSize);
        }
    }

    /** Draws an image fitted (aspect-preserved, centred) into a w×h box at (x,y). */
    private void drawFitted(GraphicsContext g, Image img, double x, double y,
                            double w, double h) {
        double iw = img.getWidth(), ih = img.getHeight();
        double scale = Math.min(w / iw, h / ih);
        double dw = iw * scale, dh = ih * scale;
        g.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
    }

    /**
     * Like {@link #drawFitted}, but first resolves a {@link #displayImage}
     * pre-scaled to the on-screen box so repaints blit a small image instead of
     * downscaling the full (≤ {@code maxEdge}) rendition every frame.
     */
    private void drawScaled(GraphicsContext g, Path path, Image img,
                            double x, double y, double w, double h) {
        // An AAE crop is shown at draw time too (the same "above the decoders,
        // touch no cache" seam as rotation): blit just the crop sub-rectangle.
        // Cropped tiles use the full ≤maxEdge rendition for sharpness; uncropped
        // ones use the pre-scaled display image for cheap blits.
        double[] crop = cropFractionFor(path);
        Image src = crop != null ? img : displayImage(path, img, Math.max(w, h));
        Adjustments adj = adjustmentsFor(path);
        // Colour adjustments (grayscale/invert) are a cached pixel pass; geometry
        // (rotation/mirror) stays a free draw-time canvas transform below.
        src = colorAdjusted(path, src, adj);
        if (!adj.hasGeometry()) {
            drawFittedRegion(g, src, crop, x, y, w, h);
            return;
        }
        // Draw-time user geometry: mirror (a -1 scale per flipped axis) then spin
        // the GraphicsContext about the (square) box centre and fit normally. The
        // box is always square here (tileSize×tileSize for media tiles, sub×sub
        // for collage cells), so an odd quarter-turn needs no width/height swap.
        // The scale-then-rotate order matches RasterFrames.apply (rotate first,
        // then mirror, in image space). Nothing is re-decoded and no thumbnail is
        // touched — rotating, mirroring (and cropping) is free.
        g.save();
        double cx = x + w / 2, cy = y + h / 2;
        g.translate(cx, cy);
        g.scale(adj.mirrorH() ? -1 : 1, adj.mirrorV() ? -1 : 1);
        g.rotate(adj.quarterTurnsCw() * 90);
        g.translate(-cx, -cy);
        drawFittedRegion(g, src, crop, x, y, w, h);
        g.restore();
    }

    /**
     * The AAE crop rectangle for {@code path} as master fractions
     * ({@code [fx,fy,fw,fh]}), or null when none applies. Only applied to FIT
     * renditions: a FILL thumbnail is already cover-cropped to a square, which
     * breaks the master→thumbnail mapping, so those keep the uncropped fill (the
     * viewer still shows the true crop). The store lookup is an O(1) cached map
     * read, safe to call per tile per repaint.
     */
    private double[] cropFractionFor(Path path) {
        if (thumbnailMode != ThumbnailMode.FIT) {
            return null;
        }
        double[] cached = cropCache.get(path);
        if (cached != null) {
            return cached == NO_CROP ? null : cached;
        }
        double[] crop = aaeStore.forImage(path).flatMap(AaeSidecar::cropFraction).orElse(null);
        cropCache.put(path, crop == null ? NO_CROP : crop);
        return crop;
    }

    /**
     * The draw-time user adjustments (rotation + mirror + colour) for {@code path},
     * cached so a scroll frame doesn't re-acquire the store's lock (and
     * re-allocate) for every tile. Invalidated per-path on an adjustment and
     * wholesale on a directory change (see {@link #invalidateAdjustment}).
     */
    private Adjustments adjustmentsFor(Path path) {
        Adjustments cached = adjustmentsCache.get(path);
        if (cached != null) {
            return cached;
        }
        Adjustments adj = rotationStore.adjustments(path);
        adjustmentsCache.put(path, adj);
        return adj;
    }

    /**
     * The colour-adjusted (grayscale/invert) form of {@code src} for {@code path},
     * built once from the current source image and reused while that image and
     * the adjustment are unchanged. Returns {@code src} untouched when {@code adj}
     * has no colour part. The geometric part is applied separately, at draw time.
     */
    private Image colorAdjusted(Path path, Image src, Adjustments adj) {
        if (!adj.hasColor()) {
            return src;
        }
        if (colorSource.get(path) == src) {
            Image cached = colorOutput.get(path);
            if (cached != null) {
                return cached;
            }
        }
        Image out = applyColor(src, adj.grayscale(), adj.invert());
        colorSource.put(path, src);
        colorOutput.put(path, out);
        return out;
    }

    /**
     * A colour-processed copy of {@code src}: grayscale (Rec. 709 luma, matching
     * {@link io.github.ghosthack.mediabrowser.media.RasterFrames#grayscale}) and/or invert,
     * alpha preserved. Reads the pixels once into an int[] and writes a new
     * {@link WritableImage}. Returns {@code src} unchanged when it has no readable
     * pixels or no adjustment is requested.
     */
    private static Image applyColor(Image src, boolean grayscale, boolean invert) {
        if (!grayscale && !invert) {
            return src;
        }
        int w = (int) Math.round(src.getWidth());
        int h = (int) Math.round(src.getHeight());
        PixelReader reader = src.getPixelReader();
        if (w <= 0 || h <= 0 || reader == null) {
            return src;
        }
        int[] px = new int[w * h];
        var fmt = PixelFormat.getIntArgbInstance();
        reader.getPixels(0, 0, w, h, fmt, px, 0, w);
        for (int i = 0; i < px.length; i++) {
            int argb = px[i];
            int a = argb >>> 24;
            int r = (argb >> 16) & 0xFF;
            int gr = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            if (grayscale) {
                int y = (int) Math.round(0.2126 * r + 0.7152 * gr + 0.0722 * b);
                r = gr = b = y;
            }
            if (invert) {
                r = 255 - r;
                gr = 255 - gr;
                b = 255 - b;
            }
            px[i] = (a << 24) | (r << 16) | (gr << 8) | b;
        }
        var out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h, fmt, px, 0, w);
        return out;
    }

    /** Drops the per-path draw-loop caches for {@code path} so the next draw
     * re-reads its adjustments and rebuilds any colour-processed image. */
    private void invalidateAdjustment(Path path) {
        adjustmentsCache.remove(path);
        colorSource.remove(path);
        colorOutput.remove(path);
    }

    /**
     * Draws {@code img} fitted (aspect-preserved, centred) into the w×h box at
     * (x,y); when {@code crop} is non-null, only that fractional source
     * sub-rectangle is fitted (a draw-time crop via the 8-arg blit).
     */
    private void drawFittedRegion(GraphicsContext g, Image img, double[] crop,
                                  double x, double y, double w, double h) {
        if (crop == null) {
            drawFitted(g, img, x, y, w, h);
            return;
        }
        double iw = img.getWidth(), ih = img.getHeight();
        double sx = crop[0] * iw, sy = crop[1] * ih;
        double sw = Math.max(1, crop[2] * iw), sh = Math.max(1, crop[3] * ih);
        double scale = Math.min(w / sw, h / sh);
        double dw = sw * scale, dh = sh * scale;
        g.drawImage(img, sx, sy, sw, sh, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
    }

    /**
     * A copy of {@code src} scaled down to roughly the on-screen box's device
     * pixels ({@code boxEdge} × the HiDPI render scale), cached by path and
     * reused until the on-screen tile size changes. Sources already at (or below)
     * the target are returned as-is. Built once via an off-graph {@link Canvas}
     * snapshot, so subsequent frames are cheap blits rather than rescales.
     */
    private Image displayImage(Path path, Image src, double boxEdge) {
        Image cached = scaled.get(path);
        if (cached != null) return cached;
        double targetPx = boxEdge * scaledScale;
        double srcEdge = Math.max(src.getWidth(), src.getHeight());
        if (srcEdge <= targetPx * 1.2) {          // already small enough; no win
            scaled.put(path, src);
            return src;
        }
        double s = targetPx / srcEdge;
        int dw = Math.max(1, (int) Math.round(src.getWidth() * s));
        int dh = Math.max(1, (int) Math.round(src.getHeight() * s));
        Canvas c = new Canvas(dw, dh);
        GraphicsContext cg = c.getGraphicsContext2D();
        cg.setImageSmoothing(true);
        cg.drawImage(src, 0, 0, dw, dh);
        var params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        long startedAt = MosaicTelemetry.now();
        Image out = c.snapshot(params, null);
        MosaicTelemetry.recordDisplayScale(MosaicTelemetry.elapsedSince(startedAt));
        scaled.put(path, out);
        return out;
    }

    /** The window's current HiDPI device-pixel scale (≥ 1). */
    private double renderScale() {
        double s = stage().getOutputScaleX();
        return s > 0 ? s : 1.0;
    }

    /**
     * Draws the selected-tile marker as a dotted border kept entirely within
     * the tile's {@code outer} cell, so it never expands the mosaic or bleeds
     * into neighbouring tiles (works the same with borders/margins on or off).
     * A solid semi-opaque dark backing line keeps the white dashes legible over
     * light renditions. The rect is snapped to whole pixels and the nominal
     * {@link #SELECTION_DASH_PERIOD} stretched to divide the edge exactly;
     * each edge is stroked as its own line so the dash pattern restarts at
     * every corner — a dot lands on each corner and the dash seam of a closed
     * path never shows. {@code phase} in {@code [0, 1)} slides the dots one
     * period along the perimeter (0 for a static marker; advanced per frame by
     * the marching-ants animation) — because the period divides each edge
     * exactly, a dot leaving one edge's end corner coincides with one entering
     * the next, so the crawl stays seamless around corners.
     */
    private void drawSelectionBorder(GraphicsContext g, double x, double y,
                                     int outer, double phase) {
        double lw = 2;
        double inset = lw / 2.0 + 1;
        double side = Math.rint(outer - 2 * inset);
        if (side <= 4) return;
        double x0 = Math.rint(x + inset), y0 = Math.rint(y + inset);
        double x1 = x0 + side, y1 = y0 + side;
        double period = side / Math.max(1, Math.rint(side / SELECTION_DASH_PERIOD));
        // Edges ordered around the perimeter, each drawn start → end so the
        // marching offset crawls in one continuous direction.
        double[][] edges = {
                {x0, y0, x1, y0},
                {x1, y0, x1, y1},
                {x1, y1, x0, y1},
                {x0, y1, x0, y0},
        };
        // Round caps turn each zero-length dash into a round dot; the period is
        // (dash + gap), so (0, period) yields evenly spaced dots.
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineDashes(0, period);
        g.setLineDashOffset(phase * period);
        g.setLineWidth(lw + 1.5);          // slightly larger dark dots = a halo
        g.setStroke(Color.rgb(0, 0, 0, 0.55));
        for (double[] e : edges) g.strokeLine(e[0], e[1], e[2], e[3]);
        g.setLineWidth(lw);                // white dots on top
        g.setStroke(Color.WHITE);
        for (double[] e : edges) g.strokeLine(e[0], e[1], e[2], e[3]);
        g.setLineDashes();                 // reset shared GraphicsContext state
        g.setLineDashOffset(0);
        g.setLineCap(StrokeLineCap.SQUARE);
    }

    /** A faint centred glyph sized to {@code span} (capped), for empty/loading tiles. */
    private void drawGlyph(GraphicsContext g, String glyph, double cx, double cy, double span) {
        g.setFill(GLYPH_FILL);
        g.setFont(font(Math.min(40, span / 3.0)));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.fillText(glyph, cx + tileSize / 2.0, cy + tileSize / 2.0);
    }

    /** Available pre-resampled folder-photo asset max-edges, ascending. */
    private static final int[] FOLDER_IMAGE_SIZES = {128, 256, 512, 1024};
    /** Lazily loaded folder-photo renditions, keyed by their resource max-edge. */
    private static final Map<Integer, Image> folderImageCache = new java.util.HashMap<>();

    /**
     * The photographic folder rendition sized for an on-screen {@code tileSize}
     * box at the current HiDPI scale: the smallest pre-resampled asset whose max
     * edge covers the device-pixel tile (capped at the largest available). Each
     * size loads once and is cached across windows; null if the asset is absent.
     */
    private Image folderPhoto() {
        double devicePx = tileSize * renderScale();
        int pick = FOLDER_IMAGE_SIZES[FOLDER_IMAGE_SIZES.length - 1];
        for (int s : FOLDER_IMAGE_SIZES) {
            if (s >= devicePx) { pick = s; break; }
        }
        return folderImageCache.computeIfAbsent(pick, sz -> {
            var url = MosaicWindow.class.getResource("folder-" + sz + ".png");
            return url == null ? null : new Image(url.toExternalForm());
        });
    }

    /**
     * A folder glyph centred in the tile, per {@link #folderGlyph}: a
     * photographic folder image fitted on the black tile (falling back to the
     * plain glyph if the asset is absent), the muted vector folder shape
     * (body + tab) on the black tile — square-cornered or rounded — or its
     * inverse — a black folder shape on a tile filled with the glyph gray.
     */
    private void drawFolderGlyph(GraphicsContext g, double cx, double cy) {
        MosaicFolderGlyph style = folderGlyph.get();
        if (style == MosaicFolderGlyph.IMAGE) {
            Image photo = folderPhoto();
            if (photo != null) {
                drawFitted(g, photo, cx, cy, tileSize, tileSize);
                return;
            }
        }
        boolean inverse = style == MosaicFolderGlyph.INVERSE;
        double w = tileSize * 0.46, h = tileSize * 0.34;
        double fx = cx + (tileSize - w) / 2.0, fy = cy + (tileSize - h) / 2.0;
        double tabW = w * 0.42, tabH = h * 0.22;
        if (inverse) {
            g.setFill(FOLDER_GLYPH_FILL);
            g.fillRect(cx, cy, tileSize, tileSize);
        }
        g.setFill(inverse ? Color.BLACK : FOLDER_GLYPH_FILL);
        if (style == MosaicFolderGlyph.ROUNDED) {
            // Corner arc diameter; the tab dips a full arc into the body so its
            // rounded bottom corners are buried in the body fill and the joint
            // between the two round-rects shows no notch.
            double arc = Math.min(tabH, tileSize * 0.08);
            g.fillRoundRect(fx, fy - tabH, tabW, tabH + arc, arc, arc);   // tab
            g.fillRoundRect(fx, fy, w, h, arc, arc);                      // body
        } else {
            g.fillRect(fx, fy - tabH, tabW, tabH + 2);   // tab
            g.fillRect(fx, fy, w, h);                     // body
        }
    }

    /** Width (px) over which an overflowing caption fades out on its right edge. */
    private static final double CAPTION_FADE = 20;

    /**
     * A translucent name strip across the bottom of a folder/parent/file tile.
     * A name that fits is centred and fully opaque; one that overflows the strip
     * is left-aligned (so its start stays readable) and its rightmost {@link
     * #CAPTION_FADE}px fade to transparent rather than being hard-clipped.
     */
    private void drawCaption(GraphicsContext g, String text, double cx, double cy) {
        double h = Math.min(22, tileSize * 0.16);
        g.setFill(CAPTION_BG);
        g.fillRect(cx, cy + tileSize - h, tileSize, h);
        double pad = 3;
        double avail = tileSize - 2 * pad;
        Font font = font(Math.min(13, Math.max(9, tileSize * 0.1)));
        double textW = measureCaptionWidth(text, font);
        double textY = cy + tileSize - h / 2.0;
        // No g.clip() here: clipping forces NGCanvas to GL-clear a full-canvas
        // clip buffer (NGCanvas.initClip -> RenderBuf.validate -> nClearBuffers)
        // on every captioned tile, every frame — the dominant scroll cost in
        // folder/file-heavy directories on a large window. It isn't needed: a
        // caption that fits is centred within the strip, and an overflowing one
        // is left-aligned and painted with a gradient whose tail is fully
        // transparent past the strip's right edge, so nothing visibly bleeds.
        g.setFont(font);
        g.setTextBaseline(VPos.CENTER);
        if (textW <= avail) {
            g.setFill(CAPTION_FG);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(text, cx + tileSize / 2.0, textY);
        } else {
            // Overflow: left-align and paint with a gradient that holds the text
            // colour across the strip, then fades to transparent over its last
            // CAPTION_FADE px (NO_CYCLE clamps the opaque colour to the left).
            double rightEdge = cx + tileSize - pad;
            double fadeStart = rightEdge - Math.min(CAPTION_FADE, avail);
            g.setFill(new LinearGradient(fadeStart, 0, rightEdge, 0, false,
                    CycleMethod.NO_CYCLE,
                    new Stop(0, CAPTION_FG),
                    new Stop(1, CAPTION_FG_FADE)));
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(text, cx + pad, textY);
        }
    }

    /**
     * Measures a caption's rendered width in the given font (FX thread, no
     * scene), memoised per (font, text) so a scroll frame doesn't re-run a text
     * layout for every captioned tile. A given pair always measures the same, so
     * the cache never goes stale; it is cleared on a directory change.
     */
    private double measureCaptionWidth(String text, Font font) {
        Map<String, Double> byText = captionWidths.computeIfAbsent(font, f -> new java.util.HashMap<>());
        Double cached = byText.get(text);
        if (cached != null) {
            return cached;
        }
        textMeasure.setText(text);
        textMeasure.setFont(font);
        double width = textMeasure.getLayoutBounds().getWidth();
        byText.put(text, width);
        return width;
    }

    /** A font of the given point size, cached and reused across tiles and frames. */
    private Font font(double size) {
        Font cached = fontCache.get(size);
        if (cached == null) {
            cached = Font.font(size);
            fontCache.put(size, cached);
        }
        return cached;
    }

    /**
     * The cached image for a path, requesting a rendition on first sight.
     * Returns {@code null} while loading or when the item has no visual. Used
     * both for media tiles and for folder-collage child images.
     */
    private Image imageFor(Path path) {
        if (!thumbnailsVisible.get()) return null;
        Image img = images.get(path);
        if (img != null) return img;
        if (empties.contains(path) || pending.contains(path)) return null;
        pending.add(path);
        MosaicTelemetry.recordThumbnailRequested(pending.size());
        int gen = generation;
        long requestedAt = MosaicTelemetry.now();
        long submitStartedAt = MosaicTelemetry.now();
        service.thumbnail(path, maxEdge, thumbnailMode).whenComplete((thumb, error) -> {
            long completedAt = MosaicTelemetry.now();
            MosaicTelemetry.recordThumbnailTotal(elapsedBetween(requestedAt, completedAt));
            Platform.runLater(() -> {
                long fxStartedAt = MosaicTelemetry.now();
                MosaicTelemetry.recordThumbnailFxDelay(elapsedBetween(completedAt, fxStartedAt));
                onThumbnail(gen, path, thumb, error);
                MosaicTelemetry.recordThumbnailFxApply(
                        MosaicTelemetry.elapsedSince(fxStartedAt), pending.size());
            });
        });
        MosaicTelemetry.recordThumbnailSubmitOnFx(MosaicTelemetry.elapsedSince(submitStartedAt));
        return null;
    }

    /**
     * The cached child visual-media paths (images and videos) for a folder's
     * collage, scanning on first sight. Returns {@code null} while the scan is
     * in flight.
     */
    private List<Path> folderPreviewFor(Path dir) {
        if (!thumbnailsVisible.get()) return null;
        List<Path> cached = folderPreviews.get(dir);
        if (cached != null) return cached;
        if (folderPending.contains(dir)) return null;
        folderPending.add(dir);
        MosaicTelemetry.recordFolderRequested(folderPending.size());
        int gen = generation;
        long requestedAt = MosaicTelemetry.now();
        service.folderPreview(dir, folderPreviewGrid * folderPreviewGrid)
                .whenComplete((paths, error) -> {
                    long completedAt = MosaicTelemetry.now();
                    MosaicTelemetry.recordFolderTotal(elapsedBetween(requestedAt, completedAt));
                    Platform.runLater(() -> {
                        long fxStartedAt = MosaicTelemetry.now();
                        MosaicTelemetry.recordFolderFxDelay(elapsedBetween(completedAt, fxStartedAt));
                        onFolderPreview(gen, dir, paths, error);
                        MosaicTelemetry.recordFolderFxApply(
                                MosaicTelemetry.elapsedSince(fxStartedAt), folderPending.size());
                    });
                });
        return null;
    }

    private static long elapsedBetween(long startNanos, long endNanos) {
        return startNanos == 0L || endNanos == 0L ? 0L : Math.max(0L, endNanos - startNanos);
    }

    private void onFolderPreview(int gen, Path dir, List<Path> paths, Throwable error) {
        if (gen != generation) return; // directory changed since the scan
        folderPending.remove(dir);
        folderPreviews.put(dir, error != null || paths == null ? List.of() : paths);
        refreshLoadingState();
        requestDraw();
    }

    private void onThumbnail(int gen, Path path, Thumbnail thumb, Throwable error) {
        if (gen != generation) return; // directory changed since the request
        pending.remove(path);
        if (error != null || thumb == null) {
            empties.add(path); // draw a placeholder rather than retry endlessly
        } else if (thumb.frame().isPresent()) {
            long startedAt = MosaicTelemetry.now();
            images.put(path, FxImages.toImage(thumb.frame().get()));
            MosaicTelemetry.recordFxImageConvert(MosaicTelemetry.elapsedSince(startedAt));
        } else {
            empties.add(path);
        }
        refreshLoadingState();
        requestDraw();
    }

    // --- interaction ---------------------------------------------------------

    /** The item index under a viewport point, or -1 if it falls in a gap/border. */
    private int indexAt(double px, double py) {
        if (items.isEmpty() || columns <= 0) return -1;
        int outer = tileSize + 2 * borderWidth;
        int strideX = outer + margin;
        int strideY = outer + margin;
        double leftPad = margin;

        double localX = px - leftPad;
        double localY = py + scrollY - margin;
        if (localX < 0 || localY < 0) return -1;
        int col = (int) (localX / strideX);
        int row = (int) (localY / strideY);
        if (col >= columns) return -1;
        // reject the margin gap after a tile
        if (localX - col * strideX > outer || localY - row * strideY > outer) return -1;
        int i = row * columns + col;
        return i < items.size() ? i : -1;
    }

    private void onKey(javafx.scene.input.KeyEvent e) {
        // While the menu bar is being driven from the keyboard (an in-window menu
        // open, or the bar itself focused via F10/Alt and being arrowed through),
        // its navigation keys (Left/Right between menus, Up/Down/Enter within one)
        // travel through this same scene. This capturing-phase filter would
        // otherwise consume them and move the grid selection as if no menu were
        // open — so step aside and let the menu have them.
        if (menuBarActive()) return;
        // Likewise while a text input (the location field, the Metadata filter)
        // holds keyboard focus: arrows/Home/End/Backspace/Enter are editing keys
        // there, not grid navigation.
        if (textInputFocused()) return;
        switch (e.getCode()) {
            // Cmd+arrows alias the navigation keys: Up=Backspace (parent),
            // Down=Enter (open), Left/Right=history back/forward. Bare arrows
            // still move the grid selection. Escape deliberately does nothing:
            // leaving the mosaic is Window ▸ Browser (Shortcut+Shift+B) or
            // Mosaic ▸ Close Mosaic.
            case ENTER -> { activateSelected(); e.consume(); }
            // Quick-move the selection to moveHistory[0..3] when the transient
            // quick-move toggle is on; otherwise the key passes through.
            case F1 -> { if (moveController.quickMove(0)) e.consume(); }
            case F2 -> { if (moveController.quickMove(1)) e.consume(); }
            case F3 -> { if (moveController.quickMove(2)) e.consume(); }
            case F4 -> { if (moveController.quickMove(3)) e.consume(); }
            case BACK_SPACE -> { navigateToParent(); e.consume(); }
            // '/' switches to the viewer on the selected media (taking focus),
            // the mirror of the viewer's own '/' (toggle 1:1).
            case SLASH -> { switchToViewer(); e.consume(); }
            // modifier1+B toggles Seamless (also on the Mosaic menu / toolbar);
            // modifier1+Shift+B must fall through to the Window ▸ Browser
            // accelerator, and a bare 'b' to type-to-select.
            case B -> { if (e.isShortcutDown() && !e.isShiftDown()) { seamless.set(!seamless.get()); e.consume(); } }
            // Rotate the selection a quarter-turn: F6 left (counter-clockwise),
            // F8 right (clockwise); persisted and applied at draw time.
            case F6 -> { rotateSelection(-1); e.consume(); }
            case F8 -> { rotateSelection(1); e.consume(); }
            // Non-destructive adjustments on the selection, persisted like
            // rotation: F5 mirror horizontal, F7 mirror vertical, F9 black &
            // white, Shift+F9 invert.
            case F5 -> { mirrorSelection(true); e.consume(); }
            case F7 -> { mirrorSelection(false); e.consume(); }
            case F9 -> {
                adjustSelection(e.isShiftDown()
                        ? rotationStore::toggleInvert : rotationStore::toggleGrayscale);
                e.consume();
            }
            // modifier1+C copies the selected tiles' paths; modifier1+M opens the
            // Move dialog (both also on the per-tile popup). A bare 'c'/'m' falls
            // through to type-to-select.
            case C -> { if (e.isShortcutDown()) { copySelectionPaths(); e.consume(); } }
            // modifier1+Shift+M must fall through to the Window ▸ Mosaic accelerator.
            case M -> { if (e.isShortcutDown() && !e.isShiftDown()) { openMove(); e.consume(); } }
            // The platform "menu" key (and Shift+F10) opens the per-tile popup on
            // the lead tile, mirroring a right-click.
            case CONTEXT_MENU -> { showTileMenuForSelection(); e.consume(); }
            // Cmd/Ctrl+A selects every tile; a bare 'a' falls through to typeahead.
            case A -> { if (e.isShortcutDown()) { selectAll(); e.consume(); } }
            // modifier1+Shift+Left/Right tile the mosaic to a screen half (Window
            // ▸ Tile Left / Right); modifier1+arrow is history back/forward; a
            // bare arrow moves the grid selection. Handled here because this
            // filter consumes arrows before the menu accelerators get a look in.
            case LEFT -> {
                if (e.isShortcutDown() && e.isShiftDown()) snapLeft();
                else if (e.isShortcutDown()) backHandler.run();
                else step(-1, e.isShiftDown());
                e.consume();
            }
            case RIGHT -> {
                if (e.isShortcutDown() && e.isShiftDown()) snapRight();
                else if (e.isShortcutDown()) forwardHandler.run();
                else step(1, e.isShiftDown());
                e.consume();
            }
            case UP -> { if (e.isShortcutDown()) navigateToParent(); else step(-columns, e.isShiftDown()); e.consume(); }
            case DOWN -> { if (e.isShortcutDown()) activateSelected(); else step(columns, e.isShiftDown()); e.consume(); }
            case HOME -> { if (e.isShiftDown()) extendSelectionTo(0); else selectSingle(0); e.consume(); }
            case END -> { if (e.isShiftDown()) extendSelectionTo(items.size() - 1); else selectSingle(items.size() - 1); e.consume(); }
            case PAGE_UP -> { step(-pageRows() * columns, e.isShiftDown()); e.consume(); }
            case PAGE_DOWN -> { step(pageRows() * columns, e.isShiftDown()); e.consume(); }
            default -> { }
        }
    }

    /**
     * True while the in-window menu bar owns the keyboard — either a menu is
     * open (one of its top-level menus is showing) or the bar itself holds focus
     * (F10/Alt, before a menu is opened). In that state {@link #onKey} must keep
     * its hands off so menu navigation isn't hijacked by grid selection. (On
     * macOS the menu is hoisted to the system bar and its keys never reach this
     * scene, so this is naturally false there.)
     */
    private boolean menuBarActive() {
        if (menuBar == null) return false;
        for (var menu : menuBar.getMenus()) {
            if (menu.isShowing()) return true;
        }
        var owner = menuBar.getScene() == null ? null : menuBar.getScene().getFocusOwner();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == menuBar) return true;
        }
        return false;
    }

    /**
     * True while a text input (the location field, the Metadata filter) holds
     * keyboard focus, so {@link #onKey} and the type-to-select handler keep
     * their hands off the keys it is editing with.
     */
    private boolean textInputFocused() {
        var scene = canvas.getScene();
        return scene != null && scene.getFocusOwner() instanceof TextInputControl;
    }

    /**
     * Location bar Go/Enter: navigates the shared location, and on a valid
     * target hands focus back to the grid; an invalid path leaves the field
     * focused for fixing. Mirrors the main window's address bar.
     */
    private void navigateFromLocation() {
        Path target = Path.of(locationField.getText().trim()).toAbsolutePath().normalize();
        if (Files.isDirectory(target)) {
            navigator.accept(target);
            canvas.requestFocus();
        } else {
            statusLabel.setText("Not a folder: " + target);
        }
    }

    /** Backspace (and the viewer relaying parent navigation): navigates the
     * shared location to the current folder's parent. */
    @Override
    public void navigateToParent() {
        Path dir = dirSupplier != null ? dirSupplier.get() : null;
        Path parent = dir == null ? null : dir.getParent();
        if (parent != null) {
            pendingSelectPath = dir;   // select the folder we came from in the parent
            navigator.accept(parent);
        }
    }

    /** Arrow/page step: extend the range with Shift held, else move the cursor. */
    private void step(int delta, boolean extend) {
        if (extend) extendSelection(delta);
        else moveSelection(delta);
    }

    /** Rows per viewport height: the PgUp/PgDn cursor stride, in rows. */
    private int pageRows() {
        int strideY = tileSize + 2 * borderWidth + margin;
        return Math.max(1, (int) (canvas.getHeight() / strideY));
    }

    private void moveSelection(int delta) {
        if (items.isEmpty()) return;
        selectSingle(selected < 0 ? 0 : selected + delta);
    }

    /** Shift+arrow: grow/shrink the Shift-range from the fixed {@link #anchor}. */
    private void extendSelection(int delta) {
        if (items.isEmpty()) return;
        if (anchor < 0) anchor = selected < 0 ? 0 : selected;
        extendSelectionTo(selected < 0 ? 0 : selected + delta);
    }

    /** Replace the selection with the single tile {@code index} and reveal it. */
    private void selectSingle(int index) {
        if (items.isEmpty()) return;
        index = clampIndex(index);
        anchor = index;
        Set<Integer> next = new HashSet<>();
        next.add(index);
        commitSelection(next, index);
    }

    /** Select the inclusive range from {@link #anchor} to {@code index} (the lead). */
    private void extendSelectionTo(int index) {
        if (items.isEmpty()) return;
        if (anchor < 0) anchor = selected < 0 ? 0 : selected;
        index = clampIndex(index);
        int lo = Math.min(anchor, index), hi = Math.max(anchor, index);
        Set<Integer> next = new HashSet<>();
        for (int i = lo; i <= hi; i++) next.add(i);
        commitSelection(next, index);
    }

    /** Cmd/Ctrl+click: add or remove one tile, leaving the rest selected. */
    private void toggleSelection(int index) {
        if (items.isEmpty()) return;
        index = clampIndex(index);
        Set<Integer> next = new HashSet<>(selection);
        if (!next.add(index)) next.remove(index);
        anchor = index;
        // Keep the lead on a still-selected tile when the lead was toggled off.
        int lead = next.contains(index) ? index
                : (next.isEmpty() ? index : next.stream().min(Integer::compare).get());
        commitSelection(next, lead);
    }

    /** Cmd/Ctrl+A: select every tile, keeping the current lead. */
    private void selectAll() {
        if (items.isEmpty()) return;
        Set<Integer> next = new HashSet<>();
        for (int i = 0; i < items.size(); i++) next.add(i);
        commitSelection(next, selected < 0 ? 0 : selected);
    }

    /**
     * F8 / F6: rotates every selected media tile a quarter-turn clockwise
     * ({@code delta == 1}) or counter-clockwise ({@code delta == -1}), persisting
     * the new user rotation to the directory sidecar.
     */
    private void rotateSelection(int delta) {
        adjustSelection(path -> rotationStore.rotate(path, delta));
    }

    /** F5 / F7: toggles the horizontal ({@code true}) or vertical mirror on every
     * selected media tile, persisted to the directory sidecar. */
    private void mirrorSelection(boolean horizontal) {
        adjustSelection(horizontal ? rotationStore::toggleMirrorH : rotationStore::toggleMirrorV);
    }

    /**
     * Applies {@code op} (a persisting {@link RotationStore} adjustment) to every
     * selected media tile and repaints just those cells. Non-media tiles
     * ({@code ..}, folders, other files) are skipped. Nothing is re-decoded and
     * no thumbnail is regenerated — geometry is a draw-time transform and colour a
     * cached pixel pass (see {@link #drawScaled}). An auto-opened viewer showing
     * the same file is re-baked in step (a no-op when it isn't showing it).
     */
    private void adjustSelection(java.util.function.Consumer<Path> op) {
        if (items.isEmpty() || selection.isEmpty()) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        for (int i : selection) {
            if (i < 0 || i >= items.size()) continue;
            DirEntry entry = items.get(i);
            if (entry.type() != DirEntry.Type.MEDIA) continue;
            op.accept(entry.path());
            invalidateAdjustment(entry.path());   // re-read the new value on next draw
            repaintCell(g, i);
            viewer.refreshAdjustments(entry.path());
        }
    }

    // --- per-tile context menu ----------------------------------------------

    /**
     * The right-click / menu-key actions popup, built lazily once the shared
     * accelerator scheme is known (after {@link #installMenuBar}). The
     * accelerators are shown for discoverability only; the keystrokes themselves
     * are handled in {@link #onKey} (and, for Move, the shared menu bar), so the
     * popup's brief scene-accelerator registration never shadows them.
     */
    private ContextMenu tileContextMenu() {
        if (tileMenu != null) return tileMenu;
        var copyPath = new MenuItem("Copy Path");
        if (keys != null) copyPath.setAccelerator(keys.mod1(KeyCode.C));
        copyPath.setOnAction(e -> copySelectionPaths());
        var move = new MenuItem("Move\u2026");
        if (keys != null) move.setAccelerator(keys.mod1(KeyCode.M));
        // Move opens a modal dialog (showAndWait); calling that synchronously from
        // the popup's action — while the ContextMenu is mid-hide — trips JavaFX's
        // "showAndWait is not allowed during animation or layout processing". Close
        // the popup first and open on the next pulse, once the hide has settled.
        move.setOnAction(e -> {
            tileMenu.hide();
            Platform.runLater(this::openMove);
        });
        var rotateLeft = new MenuItem("Rotate Left");
        rotateLeft.setAccelerator(new KeyCodeCombination(KeyCode.F6));
        rotateLeft.setOnAction(e -> rotateSelection(-1));
        var rotateRight = new MenuItem("Rotate Right");
        rotateRight.setAccelerator(new KeyCodeCombination(KeyCode.F8));
        rotateRight.setOnAction(e -> rotateSelection(1));
        var mirrorH = new MenuItem("Mirror Horizontal");
        mirrorH.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        mirrorH.setOnAction(e -> mirrorSelection(true));
        var mirrorV = new MenuItem("Mirror Vertical");
        mirrorV.setAccelerator(new KeyCodeCombination(KeyCode.F7));
        mirrorV.setOnAction(e -> mirrorSelection(false));
        var blackWhite = new MenuItem("Black & White");
        blackWhite.setAccelerator(new KeyCodeCombination(KeyCode.F9));
        blackWhite.setOnAction(e -> adjustSelection(rotationStore::toggleGrayscale));
        var invert = new MenuItem("Invert");
        invert.setAccelerator(new KeyCodeCombination(KeyCode.F9, KeyCombination.SHIFT_DOWN));
        invert.setOnAction(e -> adjustSelection(rotationStore::toggleInvert));
        var adjustMenu = new javafx.scene.control.Menu("Adjust\u2026", null,
                rotateLeft, rotateRight, new SeparatorMenuItem(),
                mirrorH, mirrorV, new SeparatorMenuItem(),
                blackWhite, invert);
        albumMenu = new AlbumMenu(albumStore, settings, this::albumSelectionPaths,
                stage(), statusLabel::setText);
        tileMenu = new ContextMenu(copyPath, move, albumMenu.menu(),
                new SeparatorMenuItem(), adjustMenu);
        // Dismiss readily (any press closes it, and isn't swallowed) and open
        // with the first item highlighted; shared with the viewer's view menu.
        WindowChrome.makeDismissive(tileMenu, canvas.getScene());
        return tileMenu;
    }

    /**
     * Mouse right-click: pops the per-tile menu at the cursor, over the tile
     * under it. A tile that is not already part of the selection becomes the
     * (single) selection first, so the action targets what was clicked; a tile
     * inside a multi-selection leaves that selection intact. With no tile under
     * the cursor it is a no-op. The keyboard trigger is handled in {@link #onKey}
     * (the canvas isn't focusable), so it is ignored here.
     */
    private void showTileMenuForMouse(ContextMenuEvent e) {
        if (e.isKeyboardTrigger()) return;
        if (items.isEmpty() || columns <= 0) return;
        Point2D local = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
        int hit = indexAt(local.getX(), local.getY());
        if (hit < 0) return;
        if (!selection.contains(hit)) selectSingle(hit);
        tileContextMenu();
        albumMenu.refresh();
        tileMenu.show(canvas, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    /** Menu key / Shift+F10: pops the per-tile menu anchored on the lead tile. */
    private void showTileMenuForSelection() {
        if (selected < 0 || selected >= items.size() || columns <= 0) return;
        int outer = tileSize + 2 * borderWidth;
        int strideX = outer + margin;
        int strideY = outer + margin;
        double x = margin + (selected % columns) * (double) strideX;
        double y = margin + (selected / columns) * (double) strideY - scrollY;
        Point2D screen = canvas.localToScreen(x + outer / 2.0, y + outer / 2.0);
        if (screen == null) return;
        tileContextMenu();
        albumMenu.refresh();
        tileMenu.show(canvas, screen.getX(), screen.getY());
    }

    /**
     * The selected tiles' paths for the "Add to Album" menu — every selected
     * tile except the {@code ..} parent link, falling back to the lead tile.
     * Order follows the listing.
     */
    private List<Path> albumSelectionPaths() {
        var paths = new ArrayList<Path>();
        for (int i : selection) {
            if (i >= 0 && i < items.size()
                    && items.get(i).type() != DirEntry.Type.PARENT) {
                paths.add(items.get(i).path());
            }
        }
        if (paths.isEmpty() && selected >= 0 && selected < items.size()
                && items.get(selected).type() != DirEntry.Type.PARENT) {
            paths.add(items.get(selected).path());
        }
        return paths;
    }

    /**
     * modifier1+C / Copy Path: puts the selected tiles' absolute paths on the
     * system clipboard (one per line, in listing order) and notes it in the
     * status bar. Falls back to the lead tile when the selection set is empty.
     */
    private void copySelectionPaths() {
        var paths = new ArrayList<String>();
        for (int i : selection) {
            if (i >= 0 && i < items.size()) {
                paths.add(items.get(i).path().toAbsolutePath().normalize().toString());
            }
        }
        if (paths.isEmpty() && selected >= 0 && selected < items.size()) {
            paths.add(items.get(selected).path().toAbsolutePath().normalize().toString());
        }
        if (paths.isEmpty()) return;
        var content = new ClipboardContent();
        content.putString(String.join(System.lineSeparator(), paths));
        Clipboard.getSystemClipboard().setContent(content);
        String msg = paths.size() == 1
                ? "Copied path: " + paths.get(0)
                : "Copied " + paths.size() + " paths";
        statusLabel.setText(msg);
    }

    /**
     * Make {@code next} the selection with {@code lead} as the cursor, scroll the
     * lead into view, and repaint. When no scroll is needed only the tiles whose
     * selected-state flipped are repainted in place; a scroll (or a large change)
     * forces a full redraw via the scrollbar listener / {@link #requestDraw}.
     */
    private void commitSelection(Set<Integer> next, int lead) {
        Set<Integer> changed = new HashSet<>();
        for (int i : selection) if (!next.contains(i)) changed.add(i);   // deselected
        for (int i : next) if (!selection.contains(i)) changed.add(i);   // newly selected
        selection.clear();
        selection.addAll(next);
        selected = clampIndex(lead);
        updateSelectionPanels();
        if (scrollLeadIntoView(selected)) {
            maybeAutoOpen();            // the scroll already triggered a full redraw
            return;
        }
        if (changed.size() > 64) {
            requestDraw();              // too many tiles changed to bother diffing
        } else if (!items.isEmpty() && columns > 0) {
            GraphicsContext g = canvas.getGraphicsContext2D();
            for (int i : changed) repaintCell(g, i);
        }
        maybeAutoOpen();
    }

    /**
     * Scrolls so the {@code lead} tile's row is fully visible. Returns whether it
     * scrolled (in which case the scrollbar listener drives a full repaint).
     */
    private boolean scrollLeadIntoView(int lead) {
        if (lead < 0 || columns <= 0) return false;
        int outer = tileSize + 2 * borderWidth;
        int strideY = outer + margin;
        int row = lead / columns;
        double rowTop = margin + row * (double) strideY;
        double rowBottom = rowTop + outer;
        double target = scrollY;
        if (rowTop - margin < scrollY) {
            target = rowTop - margin;
        } else if (rowBottom + margin > scrollY + canvas.getHeight()) {
            target = rowBottom + margin - canvas.getHeight();
        }
        target = clamp(target, 0, vbar.getMax());
        if (target != scrollY) {
            vbar.setValue(target);      // listener (scrollY <- value) -> requestDraw()
            return true;
        }
        return false;
    }

    /** Resets the selection to a single index (or clears it when {@code < 0}). */
    private void resetSelectionTo(int index) {
        selection.clear();
        if (index >= 0) selection.add(index);
        selected = index;
        anchor = index;
    }

    /**
     * Enter / double-click: {@code ..} and folders navigate the shared location
     * (mirrored back into this grid), viewable media opens in the viewer, other
     * files do nothing.
     */
    private void activateSelected() {
        if (selected < 0 || selected >= items.size()) return;
        DirEntry entry = items.get(selected);
        switch (entry.type()) {
            // Route ../parent activation through navigateToParent so it records
            // the folder we came from as the post-rebuild selection, matching
            // Backspace (otherwise the parent listing resets to its first tile).
            case PARENT -> navigateToParent();
            case DIRECTORY -> navigator.accept(entry.path());
            // Pass this mosaic as the viewer host so Escape/Enter come back
            // here and arrow-browsing in the viewer mirrors back into the tile.
            // Keep Focus only applies across separate windows (the viewer opens
            // without stealing focus); the single window always switches.
            case MEDIA -> {
                MediaItem media = entry.toMediaItem();
                if (viewer.isShowing(media)) {
                    viewer.toFront();
                } else {
                    viewer.open(media, viewerRing(),
                            shell.singleWindow() || !keepFocus.get(), this);
                }
            }
            case OTHER -> { }
        }
    }

    /**
     * '/': switches focus to the viewer on the selected media, opening it if
     * needed. Unlike Enter, which honours Keep Focus and can leave this mosaic
     * in front, this always brings the viewer forward. A no-op unless a viewable
     * tile is selected.
     */
    private void switchToViewer() {
        if (selected < 0 || selected >= items.size()) return;
        DirEntry entry = items.get(selected);
        if (entry.type() != DirEntry.Type.MEDIA) return;
        MediaItem media = entry.toMediaItem();
        if (viewer.isShowing(media)) {
            viewer.toFront();
        } else {
            viewer.open(media, viewerRing(), true, this);
        }
    }

    /** The current grid's viewable media, as the viewer's navigation ring. */
    private List<MediaItem> viewerRing() {
        return items.stream()
                .filter(DirEntry::viewable)
                .map(DirEntry::toMediaItem)
                .toList();
    }

    /** Window ▸ Maximize / Restore: maximizes the window or restores it. */
    public void toggleMaximize() {
        shell.maximizer().toggle(stage());
    }

    /** Window ▸ Tile Left: tiles the window to the screen's left half (or
     * restores when already there). */
    public void snapLeft() {
        shell.maximizer().snapLeft(stage());
    }

    /** Window ▸ Tile Right: tiles the window to the screen's right half. */
    public void snapRight() {
        shell.maximizer().snapRight(stage());
    }

    /**
     * Up/Down forwarded from the viewer: run them through the grid's own key
     * handler so they step the selection a row at a time (and, in auto-open
     * mode, drive the viewer) exactly as if pressed here.
     */
    @Override
    public void forwardNavigationKey(javafx.scene.input.KeyEvent event) {
        onKey(event);
        // A viewable landing is handled by auto-open/mirroring; if the row move
        // instead lands on a tile with nothing to show (a folder, .., or other
        // file), hide the viewer rather than leave it on the prior item.
        if (selected < 0 || selected >= items.size() || !items.get(selected).viewable()) {
            viewer.hideForNonViewableTarget();
        }
    }

    /**
     * Mirrors the item the viewer is showing back into the selected tile, so
     * arrow-browsing in a viewer this mosaic opened keeps the highlighted tile
     * (and the scroll position) in step. No-op when the item is not in the
     * current grid.
     */
    @Override
    public void mirrorViewerItem(MediaItem item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path().equals(item.path())) {
                // Mirroring the viewer's own item back must not re-open it.
                suppressAutoOpen = true;
                try {
                    selectSingle(i);
                } finally {
                    suppressAutoOpen = false;
                }
                // A post-move focus armed while this grid is inactive (source
                // detached, so no rebuild has consumed it yet) is superseded by
                // whatever the viewer browsed on to: retarget it so the rebuild
                // on re-activation lands on the item actually being viewed, not
                // on the entry that happened to follow the moved file.
                if (pendingSelectPath != null) pendingSelectPath = item.path();
                return;
            }
        }
    }

    /**
     * A move the viewer performed: re-list this grid around the focus target,
     * exactly like the grid's own move refresh, so a move made from a viewer
     * this mosaic opened is reflected in the tiles.
     */
    @Override
    public void refreshAfterViewerMove(Path focusPath) {
        refreshAfterMove(focusPath);
    }

    /**
     * An adjustment the viewer just persisted for {@code path} (rotation, mirror,
     * black&amp;white or invert): repaint that tile so its draw-time form (read
     * live from the store) matches the viewer. Only a visible cell is redrawn
     * ({@link #repaintCell} skips off-screen ones), and nothing is re-listed or
     * re-decoded.
     */
    @Override
    public void mirrorViewerAdjustments(Path path) {
        invalidateAdjustment(path);   // re-read the store's new value on next draw
        int i = indexOfPath(path);
        if (i >= 0) repaintCell(canvas.getGraphicsContext2D(), i);
    }

    private void backToMain() {
        // Leave the mosaic: the single window swaps back to the previous view;
        // separate windows hide the mosaic's and refocus the one before it.
        shell.back(AppShell.AppView.MOSAIC);
    }

    // --- helpers -------------------------------------------------------------

    private int clampIndex(int i) {
        return Math.max(0, Math.min(items.size() - 1, i));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Persists settings, swallowing IO errors (the live state still applies). */
    private void saveSettingsQuietly() {
        try {
            settings.save();
        } catch (java.io.IOException ignored) {
            // a failed save is non-fatal; the live setting still applies
        }
    }

    private static Color parseColor(String web, Color fallback) {
        try {
            return Color.web(web);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

}
