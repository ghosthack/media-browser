package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;
import io.github.ghosthack.mediabrowser.LoadingIndicator;
import io.github.ghosthack.mediabrowser.album.AlbumStore;
import io.github.ghosthack.mediabrowser.media.AaeStore;
import io.github.ghosthack.mediabrowser.media.MediaItem;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.MediaService;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.RasterFrames;
import io.github.ghosthack.mediabrowser.media.RotationStore;
import io.github.ghosthack.mediabrowser.media.VideoPlayer;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The viewer window: toolbar (arrow navigation, video play/pause,
 * loading-indicator picker / info-panel / status-bar / toolbar / full-screen toggles), a
 * viewport showing the decoded visual, an optional info panel (fed by the
 * probe metadata that {@code loadVisual} extracts as a byproduct) and a
 * status bar. The loading indicator is a three-way choice (see {@link
 * LoadingIndicator}): {@code None} (the default) keeps the current visual on
 * screen while the next one decodes; {@code Default} blanks the viewport to a
 * "Loading…" placeholder; {@code Game Console} shows a spinning-CD overlay in
 * the bottom-left over the current visual (see {@link GameConsoleLoadingOverlay}).
 * Video frames are rendered through the LWJGL offscreen pipeline
 * (see {@link FallbackVideoSink}).
 *
 * <p>One of the three views hosted by {@link AppShell}. {@link #open} loads a
 * media item's visual and makes the viewer the active view. Each {@code open}
 * records the <em>host</em> view (the main window or the mosaic) that
 * requested the viewer; Escape, Enter or a double-click on the visual area go
 * back to the previous view through the shell's back-stack (first leaving
 * full-screen mode, if active). Leaving the viewer pauses an active playback,
 * not stopping it, so returning to the item resumes the session. {@code F}
 * maximizes the window to the screen's available area and, as a focus mode,
 * hides every chrome element that is currently shown but not pinned; a second
 * press restores both the previous size and exactly those hidden elements.</p>
 *
 * <p>The viewport has three scale modes (selectable from the Viewer menu):
 * {@link ScaleMode#FIT} (the default, fit-to-window letterboxing),
 * {@link ScaleMode#ONE_TO_ONE} (1:1 native-pixel rendering, where an oversized
 * visual is centered and clipped to the viewport — flat overlay scrollbars,
 * styled like the mosaic window's, appear on whichever axes overflow and the
 * wheel/trackpad pans the visual through them) and {@link ScaleMode#CROP_TO_FILL}
 * (object-fit: cover — the visual is scaled to cover the whole viewport,
 * preserving aspect ratio, with the overflow centered and clipped instead of
 * letterboxed). {@code /} toggles 1:1 on and off, returning to whichever of fit
 * or crop-to-fill was last active. The toolbar's {@code Crop to Fill} toggle (or
 * {@code C}) flips between fit and crop-to-fill, and is a no-op while 1:1 is
 * active.</p>
 *
 * <p>The toolbar toggle hides the toolbar (and, with it, its own button); the
 * toolbar comes back by pressing {@code T} or right-clicking the viewport,
 * whose context menu also mirrors the info-panel and status-bar toggles.
 * {@code S} toggles the status bar and {@code I} the info panel. Each of the
 * three chrome elements carries a 📌 pin toggle; pinning one keeps it visible
 * through an {@code F} focus-mode maximize instead of being hidden. The pin
 * toggles are hidden by default; the toolbar's {@code Pins} toggle (or
 * {@code P}) reveals them.</p>
 *
 * <p>The toolbar's {@code Slideshow} toggle (mirrored by Viewer ▸ Slideshow)
 * starts a timed auto-advance through the directory ring: a JavaFX
 * {@link javafx.animation.Timeline} fires {@link #navigate} every
 * <em>interval</em> seconds in the configured direction, wrapping at the ends.
 * Viewer ▸ Slideshow Settings… opens a form to set the interval (a 1–10s
 * slider) and direction (forward/backward); both preview live and persist to
 * {@link AppSettings}. The toggle itself is runtime-only and turns off when the
 * viewer is hidden.</p>
 */
public final class ViewerWindow implements AppShell.ShellView {

    /** How the viewport scales the decoded visual. */
    public enum ScaleMode {
        /** Fit-to-window, preserving aspect ratio (letterboxed). The default. */
        FIT,
        /** 1:1 native pixels, centered and clipped, with pan scrollbars. */
        ONE_TO_ONE,
        /** Object-fit: cover — scaled to fill the viewport, overflow cropped. */
        CROP_TO_FILL
    }

    /** The single-window shell hosting this view; Escape/Enter go back through it. */
    private final AppShell shell;
    /**
     * The host that owns the current session (main window or mosaic), used to
     * fold a viewer-initiated move back into its listing. {@code null} (the
     * no-host fallback) skips that refresh. Re-pointed by each {@link #open}.
     */
    private ViewerHost host;
    private final MediaService service;
    private final AppSettings settings;
    /** Shared user-rotation store; baked into the displayed image on load/rotate. */
    private final RotationStore rotationStore;
    private final AaeStore aaeStore;
    /**
     * The shared logical accelerator scheme, captured when the menu bar is
     * installed; used to label the right-click popup's Copy Path / Move shortcuts.
     */
    private KeyScheme keys;
    /** Lazily-built right-click actions popup (built once the scheme is known). */
    private ContextMenu viewMenu;
    /** The "Add to Album" submenu of {@link #viewMenu}; refreshed on each show. */
    private AlbumMenu albumMenu;
    /** Shared album store (numbered {@code album-NNN.csv} files in the app dir). */
    private final AlbumStore albumStore = new AlbumStore();
    /**
     * The upright (pre-user-rotation) frame of the on-screen still, retained so a
     * rotate re-bakes from it without re-decoding. {@code null} for audio-only,
     * errored or video-playback states.
     */
    private RasterFrame lastUprightFrame;
    /** Window-top container; the shared menu bar is inserted above the toolbar. */
    private VBox topBox;
    /** This view's root, hosted by the shell while the viewer is active. */
    private final BorderPane root;
    private final ToolBar toolBar;
    /** The shared menu bar's viewer variant, captured by {@link #installMenuBar}. */
    private MenuBar menuBar;
    /** The stage title while the viewer is active; tracks the shown item. */
    private final SimpleStringProperty title = new SimpleStringProperty("Viewer");
    /**
     * True once the viewer has shown anything; unlike the old stage
     * {@code showingProperty} it never clears on a view switch, so the menu
     * items that need "the viewer has an item to go back to" stay enabled.
     */
    private final SimpleBooleanProperty hasContent = new SimpleBooleanProperty(false);

    private final ImageView imageView = new ImageView();
    private StackPane viewport;
    /** How the viewport scales the visual; see {@link ScaleMode}. */
    private final ObjectProperty<ScaleMode> scaleMode = new SimpleObjectProperty<>(ScaleMode.FIT);
    /**
     * The fit-family mode ({@link ScaleMode#FIT} or {@link ScaleMode#CROP_TO_FILL})
     * to fall back to when {@code /} turns the 1:1 override off; tracked as the
     * mode changes so leaving 1:1 restores whatever non-1:1 mode preceded it.
     */
    private ScaleMode previousFitMode = ScaleMode.FIT;
    /** Guards the two-way sync between {@link #scaleMode} and {@link #cropToggle}. */
    private boolean syncingScaleControls;
    /**
     * Pan scrollbars shown over the viewport in 1:1 mode when the visual
     * overflows the viewport on that axis (styled like the mosaic's bars).
     */
    private final ScrollBar hScroll = new ScrollBar();
    private final ScrollBar vScroll = new ScrollBar();
    /** Last shown visual size, so a same-size frame keeps the pan position. */
    private double lastImageWidth, lastImageHeight;
    /** Breadth of each pan scrollbar; matches scrollbar.css's 12px. */
    private static final double SCROLLBAR_BREADTH = 12;
    private final Label placeholder = new Label();
    private final Label statusLabel = new Label();
    /** Shows the active video presentation path while playing ("GL · CGL" / "GL · GLFW" / "PixelBuffer"). */
    private final Label presenterLabel = new Label();
    private final Label positionLabel = new Label();
    private final HBox statusBar;
    private final Button prevButton = new Button("◀");
    private final Button nextButton = new Button("▶");
    private final ToggleButton playButton = new ToggleButton();
    /** Continuous replay: when on (the default), a finished video loops from the top. */
    private final ToggleButton repeatToggle = new ToggleButton("Repeat");
    /** Autoplay: when on (the default), loading/navigating to a video starts it. */
    private final ToggleButton autoplayToggle = new ToggleButton("Autoplay");
    /**
     * Slideshow: timed auto-advance through the directory ring (forward or
     * backward). The toggle is runtime-only — only the interval and direction
     * persist (Settings ▸ Viewer slideshow config), seeded into the fields below.
     */
    private final ToggleButton slideshowToggle = new ToggleButton("Slideshow");
    /** Drives the slideshow ticks while running; {@code null} when stopped. */
    private Timeline slideshowTimeline;
    /** Slideshow auto-advance interval in seconds (1..10); seeded from settings. */
    private int slideshowIntervalSeconds;
    /** Slideshow direction: {@code false} advances forward (Next), {@code true} backward. */
    private boolean slideshowReverse;
    /**
     * Flipbook: buffered image-sequence playback — the directory's same-size
     * images are fully preloaded into memory, then looped like a video at
     * {@link #flipbookFps}. The toggle is runtime-only — only the frame rate
     * persists (Viewer ▸ Flipbook Settings…), seeded into the field below.
     */
    private final ToggleButton flipbookToggle = new ToggleButton("Flipbook");
    /** Drives the flipbook frame ticks while playing; {@code null} otherwise. */
    private Timeline flipbookTimeline;
    /** Flipbook frame rate in frames per second (1..60); seeded from settings. */
    private int flipbookFps;
    /** The active flipbook preload/buffer; {@code null} while the toggle is off. */
    private FlipbookSession flipbookSession;
    /**
     * Flipbook presentation slot: one PixelBuffer-backed image the buffered
     * frames are blitted into per tick (the video path's PixelBuffer trick),
     * so a long loop never churns N textures through Prism's VRAM cache.
     * Non-null exactly while flipbook playback owns the viewport.
     */
    private PixelBuffer<ByteBuffer> flipbookPixels;
    /** The buffer index of the flipbook frame currently on screen. */
    private int flipbookFrameIndex;
    /**
     * True while {@link #loadCurrent} or {@link #deactivate} snaps the flipbook
     * toggle off, so the teardown skips its still-restoring reload (the caller
     * is already loading, or the view is leaving the screen and must not decode
     * behind another view).
     */
    private boolean flipbookQuietTeardown;
    private final ToggleButton cropToggle = new ToggleButton("Crop to Fill");
    /**
     * Loading-indicator style; bound to the Viewer ▸ Loading Indicator submenu
     * and the toolbar picker. Seeded from {@link AppSettings} in the constructor
     * and persisted on every change; the placeholder default here is replaced
     * before the property is ever observed.
     */
    private final ObjectProperty<LoadingIndicator> loadingIndicator =
            new SimpleObjectProperty<>(LoadingIndicator.DEFAULT);
    /** The "Game Console" loading overlay, shown bottom-left in the viewport while loading. */
    private final GameConsoleLoadingOverlay gameConsoleOverlay = new GameConsoleLoadingOverlay();
    /**
     * Gates the loading indicator behind {@link AppSettings#viewerLoadingIndicatorDelayMs}:
     * armed when a load starts, it shows the indicator only if the decode is
     * still running when it fires, so a fast/cached load never flashes one up.
     * Cancelled the moment content lands (or the next load begins).
     */
    private final PauseTransition loadingIndicatorDelay = new PauseTransition();
    private final ToggleButton infoToggle = new ToggleButton("Info Panel");
    private final ToggleButton statusToggle = new ToggleButton("Status Bar");
    private final ToggleButton toolbarToggle = new ToggleButton("Toolbar");
    private final ToggleButton fullScreenToggle = new ToggleButton("Full Screen");
    private final ToggleButton toolbarPin = new ToggleButton("📌");
    private final ToggleButton infoPin = new ToggleButton("📌");
    private final ToggleButton statusPin = new ToggleButton("📌");
    private final ToggleButton pinsToggle = new ToggleButton("Pins");
    private final InfoPanel infoPanel = new InfoPanel();
    private final ToggleButton metadataToggle = new ToggleButton("Metadata");
    private final ToggleButton metadataPin = new ToggleButton("📌");
    private final MetadataPanel metadataPanel = new MetadataPanel();
    private final ToggleButton diagnosticsToggle = new ToggleButton("Diagnostics");
    /** Thumbnail-pipeline diagnostics snapshot; reuses the shared DiagnosticsPanel. */
    private final DiagnosticsPanel diagnosticsPanel;
    /** The right-edge panels share a vertical split: Info, Metadata, Diagnostics. */
    private final SplitPane rightPanels = new SplitPane();
    /**
     * True for one event tick after a click in the toolbar or a side panel, so
     * the scene focus-owner listener knows to hand keyboard focus straight back
     * to the viewport. See {@link #armFocusBounce()}; mirrors MainWindow.
     */
    private boolean bounceArmed;
    /** Menu-bar visibility (Show ▸ Menu Bar binds here in the viewer's bar); reseeded from settings (hidden by default). */
    private final BooleanProperty menuBarVisible = new SimpleBooleanProperty(true);
    /** Debounce for the opt-in Auto metadata load; (re)armed on each navigation. */
    private final PauseTransition metadataDebounce = new PauseTransition(Duration.millis(180));

    /**
     * Opener-scoped selection mirror (FX thread): the host that opened the
     * current session reflects each shown item into its own selection. Re-set
     * by every {@link #open}. The probe hand-off below stays a global hook.
     */
    private Consumer<MediaItem> onItemShown = item -> { };
    private Consumer<MediaProbe> onItemProbed = probe -> { };

    /** Media of the directory being browsed, captured when the viewer opens. */
    private List<MediaItem> siblings = List.of();
    private int index = -1;
    /**
     * Monotonic load generation; the latest navigation wins. Read on the decode
     * thread (to skip superseded decodes before the native call) as well as the
     * FX thread, so it is {@code volatile} for cross-thread visibility.
     */
    private volatile int loadSequence;
    /**
     * The {@link #loadSequence} of the frame currently on screen. The decode
     * callback paints any newer frame ({@code seq > shownSequence}) rather than
     * only the single latest one, so a held arrow key — whose in-flight decode
     * always finishes just after the next press has bumped {@link #loadSequence}
     * — keeps advancing the image instead of dropping every completed frame and
     * freezing until the user pauses. The freshness gate subsamples decodes
     * under load, so this never backs up into a long catch-up flush.
     */
    private int shownSequence;

    /**
     * Which chrome elements an {@code F} focus-mode maximize hid (each was
     * shown but unpinned); the next {@code F} brings back exactly these,
     * regardless of their pin state.
     */
    private boolean focusRestoreToolbar, focusRestoreInfo, focusRestoreStatus, focusRestoreMetadata,
            focusRestoreDiagnostics;

    /**
     * Drives the Move dialog for the on-screen item; see {@link #openMove()}.
     * Not {@code final}: the scene key filter (set up earlier in the constructor)
     * references it from a lambda, so it is assigned once afterwards.
     */
    private MoveController moveController;


    /** Active playback, if any. The session counter voids stale callbacks. */
    private VideoPlayer player;
    private int playSession;
    private long playDurationMicros = -1;
    private long playPositionMicros;

    public ViewerWindow(AppShell shell, MediaService service, AppSettings settings,
                        RotationStore rotationStore, AaeStore aaeStore) {
        this.shell = shell;
        this.service = service;
        this.diagnosticsPanel = new DiagnosticsPanel(service::thumbnailStats);
        this.settings = settings;
        this.rotationStore = rotationStore;
        this.aaeStore = aaeStore;

        prevButton.setTooltip(new Tooltip("Previous media in this directory (Left)"));
        nextButton.setTooltip(new Tooltip("Next media in this directory (Right)"));
        prevButton.setOnAction(e -> navigate(-1));
        nextButton.setOnAction(e -> navigate(1));

        playButton.textProperty().bind(Bindings.when(playButton.selectedProperty())
                .then("❚❚ Pause").otherwise("▶ Play"));
        playButton.setTooltip(new Tooltip("Play/pause video (Space)"));
        playButton.setDisable(true);
        playButton.setOnAction(e -> onPlayToggled());

        // Continuous replay: on by default, so a finished video loops from the
        // top instead of stopping. Honored by the playback end-of-stream hook.
        repeatToggle.setSelected(true);
        repeatToggle.setTooltip(new Tooltip("Continuously replay the video when it "
                + "reaches the end"));

        // Autoplay: on by default; when on, loading a video starts playback
        // immediately instead of waiting for Play (honored in loadCurrent).
        autoplayToggle.setSelected(true);
        autoplayToggle.setTooltip(new Tooltip("Automatically start playing a video "
                + "when it opens or you navigate to it"));

        // Slideshow: timed auto-advance through the directory ring. The toggle
        // starts/stops a Timeline that fires navigate() at the configured
        // interval (1..10s) in the configured direction; interval and direction
        // are seeded from settings and edited live via the Slideshow dialog.
        slideshowIntervalSeconds = settings.viewerSlideshowIntervalSeconds();
        slideshowReverse = settings.viewerSlideshowReverse();
        slideshowToggle.setTooltip(new Tooltip());
        updateSlideshowTooltip();
        slideshowToggle.selectedProperty().addListener((obs, was, on) -> {
            if (on) startSlideshow();
            else stopSlideshow();
        });

        // Flipbook: buffered image-sequence playback. The toggle kicks off a
        // full in-memory preload of the directory's same-size images, then
        // loops them like a video at the configured frame rate (1..60 fps,
        // seeded from settings and edited live via the Flipbook dialog).
        flipbookFps = settings.viewerFlipbookFps();
        flipbookToggle.setTooltip(new Tooltip());
        updateFlipbookTooltip();
        flipbookToggle.selectedProperty().addListener((obs, was, on) -> {
            if (on) startFlipbook();
            else stopFlipbook();
        });

        // The crop-to-fill toggle is a two-way mirror of the CROP_TO_FILL scale
        // mode: clicking it flips between fit and crop-to-fill, but is a no-op
        // while 1:1 is active (the toggle just snaps back). The scaleMode listener
        // keeps it in sync when the mode changes elsewhere (the / key, the menu).
        cropToggle.setTooltip(new Tooltip("Scale the visual to fill the viewport, "
                + "cropping the overflow, instead of fitting it inside (C)"));
        cropToggle.selectedProperty().addListener((obs, was, sel) -> {
            if (syncingScaleControls) return;
            if (scaleMode.get() == ScaleMode.ONE_TO_ONE) {
                syncingScaleControls = true;
                cropToggle.setSelected(false);
                syncingScaleControls = false;
                return;
            }
            scaleMode.set(sel ? ScaleMode.CROP_TO_FILL : ScaleMode.FIT);
        });
        scaleMode.addListener((obs, was, mode) -> {
            if (was == ScaleMode.FIT || was == ScaleMode.CROP_TO_FILL) {
                previousFitMode = was;
            }
            syncingScaleControls = true;
            cropToggle.setSelected(mode == ScaleMode.CROP_TO_FILL);
            syncingScaleControls = false;
            applyScaleMode();
        });

        // Seed the loading-indicator style from the persisted preference (default
        // Default), then persist every later change — set first so seeding does
        // not trigger a redundant save (mirrors the mosaic's selection animation).
        loadingIndicator.set(settings.viewerLoadingIndicator());
        loadingIndicator.addListener((obs, was, mode) -> applyLoadingIndicator(mode));
        // The delay gate (see scheduleLoadingIndicator): once it fires, the load
        // is still running, so reveal whichever indicator the user picked.
        loadingIndicatorDelay.setOnFinished(e -> showLoadingIndicator());

        infoToggle.setTooltip(new Tooltip("Show/hide the media info panel ("
                + shortcutChord(KeyCode.I) + ")"));
        infoPanel.visibleProperty().bind(infoToggle.selectedProperty());
        infoPanel.managedProperty().bind(infoToggle.selectedProperty());

        // The Metadata panel toggle only shows/hides the panel; it never triggers
        // a read. The read is driven by the panel's own Load button (manual, the
        // default) or its opt-in Auto toggle (debounced, on navigation).
        metadataToggle.setTooltip(new Tooltip("Show/hide the full metadata panel ("
                + shortcutChord(KeyCode.D) + ")"));
        metadataPanel.setOnLoadRequested(this::fireMetadataRead);
        metadataPanel.autoLoadProperty().addListener((o, was, on) -> {
            metadataDebounce.stop();
            // Turning Auto on kicks one read for the current item (panel shown);
            // turning it off just cancels any pending debounced read.
            if (on && metadataToggle.isSelected()) fireMetadataRead();
        });
        metadataDebounce.setOnFinished(e -> {
            if (metadataToggle.isSelected() && metadataPanel.isAutoLoad()) fireMetadataRead();
        });
        diagnosticsToggle.setTooltip(new Tooltip(
                "Show/hide the thumbnail-pipeline diagnostics panel"));

        // Right-edge panels live in a vertical SplitPane; membership follows each
        // toggle (a hidden panel leaves no empty divider, all-hidden collapses).
        rightPanels.setOrientation(Orientation.VERTICAL);
        infoToggle.selectedProperty().addListener((o, a, b) -> updateRightPanels());
        metadataToggle.selectedProperty().addListener((o, a, b) -> updateRightPanels());
        diagnosticsToggle.selectedProperty().addListener((o, a, b) -> updateRightPanels());

        // Seed the status bar / toolbar / menu bar from the persisted startup
        // defaults (Settings ▸ Viewer); each can still be toggled at runtime.
        statusToggle.setSelected(settings.viewerStatusBarVisible());
        statusToggle.setTooltip(new Tooltip("Show/hide the viewer status bar ("
                + shortcutChord(KeyCode.S) + ")"));

        // The toggle lives inside the toolbar, so hiding it removes the button
        // too; press T or right-click the view to bring the toolbar back.
        toolbarToggle.setSelected(settings.viewerToolbarVisible());
        toolbarToggle.setTooltip(new Tooltip("Show/hide this toolbar ("
                + shortcutChord(KeyCode.T) + "); press " + shortcutChord(KeyCode.T)
                + " or right-click the view to bring it back"));
        menuBarVisible.set(settings.viewerMenuBarVisible());

        toolbarPin.setTooltip(new Tooltip("Pin the toolbar so an F maximize keeps it visible"));
        infoPin.setTooltip(new Tooltip("Pin the info panel so an F maximize keeps it visible"));
        metadataPin.setTooltip(new Tooltip("Pin the metadata panel so an F maximize keeps it visible"));
        statusPin.setTooltip(new Tooltip("Pin the status bar so an F maximize keeps it visible"));

        // The per-panel pin toggles stay hidden until this toggle reveals them;
        // their pin state still drives F focus-mode, shown or not.
        pinsToggle.setTooltip(new Tooltip("Show/hide the \ud83d\udccc pin toggles on each panel ("
                + shortcutChord(KeyCode.P) + ")"));
        for (ToggleButton pin : List.of(toolbarPin, infoPin, metadataPin, statusPin)) {
            pin.visibleProperty().bind(pinsToggle.selectedProperty());
            pin.managedProperty().bind(pinsToggle.selectedProperty());
        }

        fullScreenToggle.setTooltip(new Tooltip("Toggle full screen (Escape leaves)"));
        fullScreenToggle.selectedProperty().addListener(
                (obs, was, sel) -> shell.setFullScreen(sel));
        // Mirrors the shell's stage; also resyncs the toggle when the shell
        // exits full screen on a view switch.
        shell.fullScreenProperty().addListener(
                (obs, was, is) -> fullScreenToggle.setSelected(is));

        // The loading indicator is a three-way choice (None / Default / Game
        // Console), so the toolbar carries a menu-button picker rather than a
        // toggle; its radios mirror the Viewer ▸ Loading Indicator submenu via
        // the shared loadingIndicator property.
        var loadingGroup = new ToggleGroup();
        var loadingMenuButton = new MenuButton("Loading", null,
                loadingRadio("None", LoadingIndicator.NONE, loadingGroup),
                loadingRadio("Basic label", LoadingIndicator.DEFAULT, loadingGroup),
                loadingRadio("Game Console", LoadingIndicator.GAME_CONSOLE, loadingGroup));
        loadingMenuButton.setTooltip(new Tooltip("Loading indicator shown while the "
                + "next media loads (None / Default placeholder / Game Console overlay)"));

        this.toolBar = new ToolBar(toolbarPin, new Separator(),
                prevButton, nextButton, new Separator(),
                playButton, repeatToggle, autoplayToggle, slideshowToggle, flipbookToggle, new Separator(), cropToggle, new Separator(),
                loadingMenuButton, infoToggle, metadataToggle, diagnosticsToggle,
                statusToggle, toolbarToggle, pinsToggle, fullScreenToggle);
        toolBar.visibleProperty().bind(toolbarToggle.selectedProperty());
        toolBar.managedProperty().bind(toolbarToggle.selectedProperty());

        placeholder.setStyle("-fx-text-fill: #dddddd;");
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        // The pan scrollbars overlay the viewport edges (only shown in 1:1 mode
        // when the visual overflows); the vertical one hugs the right edge, the
        // horizontal one the bottom, leaving the corner free when both show.
        hScroll.setOrientation(Orientation.HORIZONTAL);
        vScroll.setOrientation(Orientation.VERTICAL);
        hScroll.setMaxHeight(Region.USE_PREF_SIZE); // keep its 12px breadth
        vScroll.setMaxWidth(Region.USE_PREF_SIZE);
        hScroll.setVisible(false);
        vScroll.setVisible(false);
        StackPane.setAlignment(hScroll, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(vScroll, Pos.TOP_RIGHT);
        // The Game Console loading overlay sits in the bottom-left corner over
        // the visual (mouse-transparent, hidden until a load starts). It is added
        // below the pan scrollbars so a 1:1 overflow's bars stay clickable on top.
        StackPane.setAlignment(gameConsoleOverlay, Pos.BOTTOM_LEFT);
        StackPane.setMargin(gameConsoleOverlay, new Insets(0, 0, 6, 6));
        viewport = new StackPane(imageView, placeholder, gameConsoleOverlay, hScroll, vScroll);
        viewport.setStyle("-fx-background-color: black;");
        // The viewport is the viewer's keyboard focus target. The scene key
        // filter drives navigation (siblings, Home/End, Escape…), but a focused
        // toolbar/panel control's own InputMap (e.g. the Metadata table's
        // Home/End/arrows) still fires even when that filter consumes the event,
        // so keyboard focus must live here, not on a panel. Made focusable, and
        // bounced back whenever a toolbar/panel click steals it (see below).
        viewport.setFocusTraversable(true);
        // Clip the viewport so a 1:1 visual larger than the window stays inside
        // the viewport bounds instead of bleeding over the surrounding chrome.
        var viewportClip = new Rectangle();
        viewportClip.widthProperty().bind(viewport.widthProperty());
        viewportClip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(viewportClip);
        // The fit-bound ImageView would otherwise drive the StackPane's minimum
        // size up to the current image size; once the viewport grew to fill a
        // hidden status bar/toolbar, the BorderPane could no longer shrink the
        // center to give that chrome its space back, clipping it off the window
        // edge when re-shown. Letting the viewport shrink to nothing fixes that.
        viewport.setMinSize(0, 0);
        // Recompute the pan bars on viewport resize (keep position), scrollbar
        // drag (pan), and image change (recenter only when the size changed, so
        // successive same-size video frames don't fight a user's panning).
        hScroll.valueProperty().addListener((o, a, b) -> updatePan());
        vScroll.valueProperty().addListener((o, a, b) -> updatePan());
        viewport.widthProperty().addListener((o, a, b) -> refreshScale(false));
        viewport.heightProperty().addListener((o, a, b) -> refreshScale(false));
        imageView.imageProperty().addListener((o, was, img) -> {
            double w = img == null ? 0 : img.getWidth();
            double h = img == null ? 0 : img.getHeight();
            boolean sizeChanged = w != lastImageWidth || h != lastImageHeight;
            lastImageWidth = w;
            lastImageHeight = h;
            refreshScale(sizeChanged);
        });
        applyScaleMode();

        // Right-clicking the view opens the per-item actions popup (the same
        // Copy Path / Move / Rotate set as the mosaic's tile menu) plus a Toggle
        // submenu of the chrome toggles — the mouse-only way back to a hidden
        // toolbar. Built lazily so the shared accelerator scheme (installed after
        // construction) can label its shortcuts.
        viewport.setOnContextMenuRequested(e -> {
            viewContextMenu();
            albumMenu.refresh();
            viewMenu.show(viewport, e.getScreenX(), e.getScreenY());
        });

        // Double-clicking the visual area is a mouse alias of Enter: bring the
        // opener window forward while the viewer stays open. A single click
        // claims keyboard focus (the viewport doesn't grab it automatically the
        // way a control would), so keys land on the viewer rather than a
        // previously focused toolbar/panel control.
        viewport.setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            viewport.requestFocus();
            if (e.getClickCount() == 2) {
                goBack();
                e.consume();
            }
        });

        // In 1:1 mode the wheel/trackpad pans the oversized visual through the
        // same scrollbars (matching the mosaic's wheel scrolling).
        viewport.setOnScroll(e -> {
            if (scaleMode.get() != ScaleMode.ONE_TO_ONE) return;
            boolean handled = false;
            if (vScroll.isVisible() && e.getDeltaY() != 0) {
                vScroll.setValue(Math.max(0, Math.min(vScroll.getMax(),
                        vScroll.getValue() - e.getDeltaY())));
                handled = true;
            }
            if (hScroll.isVisible() && e.getDeltaX() != 0) {
                hScroll.setValue(Math.max(0, Math.min(hScroll.getMax(),
                        hScroll.getValue() - e.getDeltaX())));
                handled = true;
            }
            if (handled) e.consume();
        });

        // Optional convenience: drag the window by its viewport (the image area),
        // gated live on the setting so it can be toggled without a restart. The
        // handler skips inner controls (e.g. the 1:1 pan scrollbars) and the
        // double-click "back to opener" still works (a click never drags).
        WindowChrome.addDragHandle(stage(), viewport, settings::viewerDragViewport);

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        presenterLabel.setTooltip(new Tooltip("Active video presentation path"));
        statusBar = new HBox(8, statusPin, statusLabel, spacer, presenterLabel, positionLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        statusBar.visibleProperty().bind(statusToggle.selectedProperty());
        statusBar.managedProperty().bind(statusToggle.selectedProperty());

        infoPanel.addHeaderControl(infoPin);
        metadataPanel.addHeaderControl(metadataPin);

        // The shared menu bar is inserted above the toolbar by installMenuBar(...).
        topBox = new VBox(toolBar);
        this.root = new BorderPane(viewport);
        root.setTop(topBox);
        root.setRight(rightPanels);
        updateRightPanels();
        root.setBottom(statusBar);
        root.setStyle("-fx-background-color: black;");

        // The flat 1:1 pan scrollbars share the mosaic window's styling. On the
        // root (not the shared scene): Parent stylesheets sit above the scene's
        // theme overlay, preserving the old scene-css-over-theme ordering, and
        // they leave with the root when another view takes the window.
        var css = ViewerWindow.class.getResource("scrollbar.css");
        if (css != null) root.getStylesheets().add(css.toExternalForm());
        // Key filter on the view root: it only sees events while the viewer is
        // the shell's active view (a detached root gets none), so no per-key
        // "am I active" gating is needed.
        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case ESCAPE -> { goBack(); e.consume(); }
                // Command/Ctrl+W is the conventional "close window" chord and
                // here aliases Escape (leave the viewer, back to the previous view).
                case W -> { if (e.isShortcutDown()) { goBack(); e.consume(); } }
                case ENTER -> { goBack(); e.consume(); }
                // modifier1+Shift+Left/Right tile the viewer to a screen half
                // (Window ▸ Tile Left / Right); a bare modifier1+Left/Right (and
                // Backspace) backs the opener out to its parent directory; a bare
                // arrow browses siblings. Handled here because this filter
                // consumes arrows before the menu accelerators get a look in.
                case LEFT -> {
                    if (e.isShortcutDown() && e.isShiftDown()) { snapLeft(); e.consume(); }
                    else if (e.isShortcutDown()) { if (navigateOpenerToParent()) e.consume(); }
                    else { navigate(-1); e.consume(); }
                }
                case RIGHT -> {
                    if (e.isShortcutDown() && e.isShiftDown()) { snapRight(); e.consume(); }
                    else if (e.isShortcutDown()) { if (navigateOpenerToParent()) e.consume(); }
                    else { navigate(1); e.consume(); }
                }
                // Backspace backs the opener (mosaic or main window) out to its
                // parent directory, like modifier1+Left/Right.
                case BACK_SPACE -> { if (navigateOpenerToParent()) e.consume(); }
                // Up/Down and PgUp/PgDn aren't the viewer's to act on: hand them
                // to the window that opened this session (mosaic or main) so its
                // own grid/list moves selection while the viewer stays in front.
                case UP, DOWN, PAGE_UP, PAGE_DOWN -> { if (host != null) host.forwardNavigationKey(e); e.consume(); }
                case HOME -> { navigateTo(0); e.consume(); }
                case END -> { navigateTo(siblings.size() - 1); e.consume(); }
                case SPACE -> {
                    if (!playButton.isDisabled()) playButton.fire();
                    e.consume();
                }
                // The chrome toggles are gated behind modifier1 (Cmd/Ctrl),
                // matching the letter shortcuts in the mosaic and main windows
                // (Copy Path, Move, Select All): modifier1+T toolbar, +S status
                // bar, +I info panel, +D metadata panel, +P per-panel pins.
                case T -> {
                    if (e.isShortcutDown()) {
                        toolbarToggle.setSelected(!toolbarToggle.isSelected());
                        e.consume();
                    }
                }
                case S -> {
                    if (e.isShortcutDown()) {
                        statusToggle.setSelected(!statusToggle.isSelected());
                        e.consume();
                    }
                }
                case I -> {
                    if (e.isShortcutDown()) {
                        infoToggle.setSelected(!infoToggle.isSelected());
                        e.consume();
                    }
                }
                // modifier1+D shows/hides the metadata panel; Cmd+Shift+M for
                // Open Mosaic is a separate, scoped accelerator and never
                // reaches here.
                case D -> {
                    if (e.isShortcutDown()) {
                        metadataToggle.setSelected(!metadataToggle.isSelected());
                        e.consume();
                    }
                }
                case P -> {
                    if (e.isShortcutDown()) {
                        pinsToggle.setSelected(!pinsToggle.isSelected());
                        e.consume();
                    }
                }
                case SLASH -> { toggleScaleMode(); e.consume(); }
                // modifier1+C copies the on-screen item's path (right-click ▸
                // Copy Path); a bare C toggles crop-to-fill.
                case C -> {
                    if (e.isShortcutDown()) copyCurrentPath();
                    else cropToggle.setSelected(!cropToggle.isSelected());
                    e.consume();
                }
                // modifier1+M opens the Move dialog (also right-click ▸ Move…);
                // handled here so the popup's accelerator can't shadow it.
                case M -> { if (e.isShortcutDown()) { openMove(); e.consume(); } }
                // Rotate the on-screen still a quarter-turn: F6 left
                // (counter-clockwise), F8 right (clockwise); re-baked from the
                // retained upright frame.
                case F6 -> { rotateCurrent(-1); e.consume(); }
                case F8 -> { rotateCurrent(1); e.consume(); }
                // Non-destructive still adjustments, persisted like rotation and
                // re-baked from the retained upright frame: F5 mirror horizontal,
                // F7 mirror vertical, F9 black & white, Shift+F9 invert.
                case F5 -> { toggleAdjustment(Adjustment.MIRROR_H); e.consume(); }
                case F7 -> { toggleAdjustment(Adjustment.MIRROR_V); e.consume(); }
                case F9 -> {
                    toggleAdjustment(e.isShiftDown()
                            ? Adjustment.INVERT : Adjustment.GRAYSCALE);
                    e.consume();
                }
                case F -> { toggleMaximize(); e.consume(); }
                // Quick-move the on-screen item to moveHistory[0..3] when the
                // quick-move toggle is on; otherwise the key passes through.
                case F1 -> { if (moveController.quickMove(0)) e.consume(); }
                case F2 -> { if (moveController.quickMove(1)) e.consume(); }
                case F3 -> { if (moveController.quickMove(2)) e.consume(); }
                case F4 -> { if (moveController.quickMove(3)) e.consume(); }
                default -> { }
            }
        });

        // Keyboard focus belongs on the viewport. Clicking the toolbar or a
        // side panel otherwise parks focus on the clicked control, where its own
        // key bindings hijack the viewer's navigation keys (the Metadata table
        // eating Home/End/arrows being the visible symptom). So: a click in
        // those areas arms a one-tick "bounce", and the instant the control
        // takes focus we hand it straight back to the viewport — synchronously,
        // within the same dispatch, so no focused frame is painted. Text inputs
        // (the Metadata filter) and pop-up owners (Copy All) are left alone so
        // they remain usable. Mirrors MainWindow's and the mosaic's focus bounce.
        // The status bar is included because it carries a focusable control (the
        // status pin); the mosaic's/main's status bars are label-only, so they
        // can't take focus and need no bounce.
        //
        // A drag (e.g. selecting in the Metadata table) re-grabs focus on each
        // MOUSE_DRAGGED, after the one-tick arm has lapsed, so it ends up focused
        // once the drag settles. Re-settle focus to the viewport when the mouse
        // is released over a bounce panel — by then the drag is done and the
        // control no longer needs keyboard focus. (Dragging is mouse-driven and
        // unaffected.)
        for (Node panel : List.of(toolBar, rightPanels, statusBar)) {
            panel.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> armFocusBounce());
            panel.addEventFilter(MouseEvent.MOUSE_RELEASED,
                    e -> Platform.runLater(this::settleFocusToViewport));
        }
        // All three views share the shell's scene, so gate this view's bounce
        // on being the active one; the other views' listeners coexist on the
        // same focusOwnerProperty.
        stage().getScene().focusOwnerProperty().addListener((obs, was, now) -> {
            if (!shell.isActiveNow(AppShell.AppView.VIEWER)) return;
            if (!bounceArmed || now == null || now == viewport) return;
            if (now instanceof TextInputControl || now instanceof MenuButton) return;
            viewport.requestFocus();
        });

        // The Move dialog (Cmd+M / right-click ▸ Move…): the same window-agnostic
        // controller the main window and mosaic use, here driven by a host that
        // exposes the single on-screen item as the move source and folds a
        // completed move back into the navigation ring (and the opener's listing).
        this.moveController = new MoveController(service, settings, new MoveController.Host() {
            @Override public Stage owner() { return stage(); }
            @Override public Path currentDirectory() { return currentMoveDirectory(); }
            @Override public MoveController.Selection currentSelection() { return currentMoveSelection(); }
            @Override public Path nextFocusAfterMove(List<Path> moving) { return ViewerWindow.this.nextFocusAfterMove(moving); }
            @Override public void releaseBeforeMove(List<Path> moving) { ViewerWindow.this.releaseBeforeMove(moving); }
            @Override public void refreshAfterMove(Path focusPath) { ViewerWindow.this.refreshAfterMove(focusPath); }
            @Override public void showStatus(String message) { statusLabel.setText(message); }
        });
    }

    // --- ShellView ------------------------------------------------------------

    /** The stage hosting this view: the single window, or the viewer's own. */
    private Stage stage() {
        return shell.stageFor(AppShell.AppView.VIEWER);
    }

    @Override
    public Parent root() {
        return root;
    }

    @Override
    public ReadOnlyStringProperty titleProperty() {
        return title;
    }

    /** Claims keyboard focus for the viewport as the viewer takes the window. */
    @Override
    public void activate() {
        viewport.requestFocus();
    }

    /**
     * Runs as the viewer gives the window up (view switch or app close):
     * pauses an active playback — the session stays alive so returning to the
     * same item resumes it — and stops the slideshow's timed auto-advance.
     */
    @Override
    public void deactivate() {
        pausePlayback();
        slideshowToggle.setSelected(false);
        // Quiet teardown: no still-restoring reload behind another view; the
        // last flipbook frame simply stays until the viewer is used again.
        flipbookQuietTeardown = true;
        flipbookToggle.setSelected(false);
        flipbookQuietTeardown = false;
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
     * above the toolbar, so the common menu stays available while the viewer is
     * focused. On macOS it hoists to the system menu bar unless the
     * in-window-menu setting opts out; off macOS it renders in-window (and, when
     * undecorated, also doubles as a window drag handle).
     */
    public void installMenuBar(MenuBar bar, KeyScheme keys) {
        this.keys = keys;
        this.menuBar = bar;
        topBox.getChildren().add(0, bar);
        // Runtime hide/show (Show ▸ Menu Bar, mod1+\) + modifier-2 peek; the last
        // arg hoists to the macOS system menu bar while visible (unless the
        // in-window-menu setting opts out), so the toggle can hide it there too.
        // Gated on the viewer being the active view: the three views' bars share
        // one always-focused stage, so without the gate they would race for the
        // macOS system menu bar.
        WindowChrome.bindMenuAutoHide(bar, stage().getScene(), stage(),
                menuBarVisible, shell.isActive(AppShell.AppView.VIEWER), keys,
                !settings.inWindowMenu());
    }

    /** Menu-bar visibility (Show ▸ Menu Bar binds here in the viewer's bar); reseeded from settings (hidden by default). */
    public BooleanProperty menuBarVisibleProperty() {
        return menuBarVisible;
    }

    // --- menu hooks: observable state + actions for the shared Viewer menu ---
    // (MainWindow.buildViewerMenu binds to these so every bar stays in sync;
    // the actions mirror the toolbar buttons and the scene key filter.)

    /**
     * Whether the viewer has ever shown an item (gates its menu items). Unlike
     * the old stage {@code showingProperty} this never clears: the viewer keeps
     * its item across view switches, so "switch to the viewer" stays meaningful.
     */
    public ReadOnlyBooleanProperty hasContentProperty() {
        return hasContent;
    }

    /** Makes the viewer the active view (Window menu ▸ Viewer). */
    public void toFront() {
        shell.showView(AppShell.AppView.VIEWER);
    }

    /** True when the viewer is on screen and already showing {@code item}. */
    public boolean isShowing(MediaItem item) {
        return shell.isShowingNow(AppShell.AppView.VIEWER)
                && index >= 0 && index < siblings.size()
                && siblings.get(index).path().equals(item.path());
    }

    /** Viewport scale mode (Viewer ▸ Fit / 1:1 / Crop to Fill radios bind here). */
    public ObjectProperty<ScaleMode> scaleModeProperty() {
        return scaleMode;
    }

    /** Toolbar visibility (Viewer ▸ Toolbar binds here); hidden by default. */
    public BooleanProperty toolbarVisibleProperty() {
        return toolbarToggle.selectedProperty();
    }

    /** Status-bar visibility (Viewer ▸ Status Bar binds here); hidden by default. */
    public BooleanProperty statusBarVisibleProperty() {
        return statusToggle.selectedProperty();
    }

    /** Info-panel visibility (Viewer ▸ Info Panel binds here). */
    public BooleanProperty infoPanelVisibleProperty() {
        return infoToggle.selectedProperty();
    }

    /** Metadata-panel visibility (Viewer ▸ Metadata binds here); hidden by default. */
    public BooleanProperty metadataPanelVisibleProperty() {
        return metadataToggle.selectedProperty();
    }

    public BooleanProperty diagnosticsPanelVisibleProperty() {
        return diagnosticsToggle.selectedProperty();
    }

    /** Pin-toggle visibility (Viewer ▸ Pins binds here). */
    public BooleanProperty pinsVisibleProperty() {
        return pinsToggle.selectedProperty();
    }

    /** Loading-indicator style (Viewer ▸ Loading Indicator submenu binds here); NONE by default. */
    public ObjectProperty<LoadingIndicator> loadingIndicatorProperty() {
        return loadingIndicator;
    }

    /**
     * A {@link RadioMenuItem} for the toolbar's loading-indicator picker, bound to
     * {@link #loadingIndicator} (the menu-bar submenu builds its own equivalents
     * against the same property, so both stay in sync). Mirrors MainWindow's
     * {@code objRadio} helper.
     */
    private RadioMenuItem loadingRadio(String text, LoadingIndicator value, ToggleGroup group) {
        var item = new RadioMenuItem(text);
        item.setToggleGroup(group);
        item.setSelected(loadingIndicator.get() == value);
        item.setOnAction(e -> loadingIndicator.set(value));
        loadingIndicator.addListener((o, a, b) -> item.setSelected(b == value));
        return item;
    }

    /** Full-screen state (Viewer ▸ Full Screen binds here). */
    public BooleanProperty fullScreenProperty() {
        return fullScreenToggle.selectedProperty();
    }

    /** True when no video is loaded, so Play / Pause should be disabled. */
    public ReadOnlyBooleanProperty playDisabledProperty() {
        return playButton.disableProperty();
    }

    /** Continuous-replay (repeat) state (Viewer ▸ Repeat binds here); on by default. */
    public BooleanProperty repeatProperty() {
        return repeatToggle.selectedProperty();
    }

    /** Autoplay state (Viewer ▸ Autoplay binds here); on by default. */
    public BooleanProperty autoplayProperty() {
        return autoplayToggle.selectedProperty();
    }

    /** Slideshow (timed auto-advance) on/off (Viewer ▸ Slideshow binds here); off by default. */
    public BooleanProperty slideshowProperty() {
        return slideshowToggle.selectedProperty();
    }

    /** Flipbook (buffered image-sequence playback) on/off (Viewer ▸ Flipbook binds here); off by default. */
    public BooleanProperty flipbookProperty() {
        return flipbookToggle.selectedProperty();
    }

    /** True when the navigation ring has a single item (Previous / Next n/a). */
    public ReadOnlyBooleanProperty navigationDisabledProperty() {
        return prevButton.disableProperty();
    }

    /** Shows the previous media in the ring (Viewer ▸ Previous). */
    public void showPrevious() {
        navigate(-1);
    }

    /** Shows the next media in the ring (Viewer ▸ Next). */
    public void showNext() {
        navigate(1);
    }

    /** Toggles video playback if a video is loaded (Viewer ▸ Play / Pause). */
    public void togglePlayback() {
        if (!playButton.isDisabled()) playButton.fire();
    }

    /** Toggles the {@code F} focus-mode maximize (Viewer ▸ Full Screen Focus). */
    public void toggleFocusMode() {
        toggleMaximize();
    }

    /**
     * Tiles the window to the screen's left half (Window ▸ Tile Left). Routed
     * through the shared {@link WindowMaximizer} with the focus-mode restore
     * hook, so tiling out of a maximized focus mode brings its hidden chrome
     * back; re-selecting the same half restores the pre-tile geometry.
     */
    public void snapLeft() {
        shell.maximizer().snapLeft(stage(), this::restoreHiddenChrome);
    }

    /** Right-half counterpart of {@link #snapLeft()} (Window ▸ Tile Right). */
    public void snapRight() {
        shell.maximizer().snapRight(stage(), this::restoreHiddenChrome);
    }

    /** Leaves the viewer, viewer session kept (Viewer ▸ Back to Opener). */
    public void returnToOpener() {
        goBack();
    }

    /** Leaves the viewer for the previous view (Viewer ▸ Close Viewer). */
    public void closeViewer() {
        goBack();
    }

    /**
     * Leaves the viewer because Up/Down forwarded from it landed the host's
     * selection on a non-viewable entry (a folder, {@code ..}, or other file):
     * there is nothing left to show, so drop back to the view that forwarded
     * the key. Unlike Escape, this goes back even from full-screen, since the
     * shown item is no longer the host's selection. A no-op when the viewer is
     * not the active view.
     */
    public void hideForNonViewableTarget() {
        if (!shell.isShowingNow(AppShell.AppView.VIEWER)) return;
        if (shell.isFullScreen()) shell.setFullScreen(false);
        shell.back(AppShell.AppView.VIEWER);
    }

    /** Called on the FX thread when the shown item's probe metadata is ready. */
    public void setOnItemProbed(Consumer<MediaProbe> callback) {
        onItemProbed = callback;
    }

    /**
     * Shows {@code item} in the viewport; {@code dirListing} (the media of the
     * same directory) becomes the arrow-navigation ring. Reopening the item
     * whose playback was paused by hiding the viewer keeps that session, so
     * Play resumes where it left off.
     *
     * <p>With {@code takeFocus} false the viewer is revealed without stealing
     * keyboard focus, per the shell: separate windows show the viewer window
     * and hand focus straight back to the opener (Keep Focus / auto-open); the
     * single window can't show a second view, so there the content is only
     * refreshed when the viewer already fills the window.</p>
     *
     * <p>{@code host} is the view that opened the session (the main window or
     * the mosaic): the viewer mirrors each shown item into its selection while
     * arrow-browsing; Escape/Enter go back to it through the shell.</p>
     */
    public void open(MediaItem item, List<MediaItem> dirListing, boolean takeFocus,
                     ViewerHost host) {
        // A keep-focus ("don't take focus") open only refreshes a viewer the
        // shell could put (or keep) on screen passively; checked before any
        // load so an invisible viewer never decodes behind another view.
        if (!takeFocus && !shell.revealPassive(AppShell.AppView.VIEWER)) return;
        this.host = host;
        this.onItemShown = host != null ? host::mirrorViewerItem : i -> { };
        boolean keepPlayback = player != null && index >= 0
                && siblings.get(index).path().equals(item.path());
        siblings = dirListing.isEmpty() ? List.of(item) : List.copyOf(dirListing);
        index = 0;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).path().equals(item.path())) {
                index = i;
                break;
            }
        }
        if (keepPlayback) {
            // same item, live playback session: only refresh what depends on
            // the (possibly changed) directory listing
            positionLabel.setText((index + 1) + " / " + siblings.size());
            boolean single = siblings.size() <= 1;
            prevButton.setDisable(single);
            nextButton.setDisable(single);
        } else {
            loadCurrent();
        }
        if (takeFocus) {
            shell.showView(AppShell.AppView.VIEWER);
            viewport.requestFocus();   // keyboard focus on the viewport, not the toolbar
        }
    }

    /**
     * Toggles the 1:1 override (bound to the {@code /} key): if 1:1 is active it
     * returns to whichever fit-family mode preceded it (fit or crop-to-fill),
     * otherwise it switches to 1:1. The {@link #scaleMode} listener does the
     * actual remembering/applying.
     */
    private void toggleScaleMode() {
        scaleMode.set(scaleMode.get() == ScaleMode.ONE_TO_ONE
                ? previousFitMode : ScaleMode.ONE_TO_ONE);
    }

    /**
     * Applies the current {@link #scaleMode}: {@link ScaleMode#FIT} binds the
     * {@code ImageView}'s fit size to the viewport (preserving aspect ratio,
     * letterboxing); {@link ScaleMode#CROP_TO_FILL} unbinds and sizes it to cover
     * the viewport (cropping the overflow); {@link ScaleMode#ONE_TO_ONE} unbinds
     * and zeros it so the image renders at its native pixel size.
     */
    private void applyScaleMode() {
        switch (scaleMode.get()) {
            case ONE_TO_ONE -> {
                imageView.fitWidthProperty().unbind();
                imageView.fitHeightProperty().unbind();
                imageView.setFitWidth(0);
                imageView.setFitHeight(0);
            }
            case CROP_TO_FILL -> {
                imageView.fitWidthProperty().unbind();
                imageView.fitHeightProperty().unbind();
                applyFillFit();
            }
            case FIT -> {
                imageView.fitWidthProperty().bind(viewport.widthProperty());
                imageView.fitHeightProperty().bind(viewport.heightProperty());
            }
        }
        // Entering 1:1 recenters the (now overflowing) visual; leaving it hides
        // the bars and clears the pan offset.
        updateScrollBars(true);
    }

    /**
     * Recomputes whatever the active scale mode derives from the viewport/image
     * sizes, then refreshes the pan scrollbars. Crop-to-fill needs re-sizing on
     * every viewport resize and image swap (it can't simply bind); fit and 1:1
     * modes don't, so this is a no-op for them beyond the scrollbar refresh.
     */
    private void refreshScale(boolean recenter) {
        if (scaleMode.get() == ScaleMode.CROP_TO_FILL) applyFillFit();
        updateScrollBars(recenter);
    }

    /**
     * Sizes the {@code ImageView} to cover the viewport (object-fit: cover) for
     * crop-to-fill mode: it scales by the larger of the two viewport/image ratios
     * so the visual fills both axes, and lets {@code preserveRatio} derive the
     * other dimension. The StackPane centers the oversized result and the viewport
     * clip trims the spill.
     */
    private void applyFillFit() {
        var image = imageView.getImage();
        double vw = viewport.getWidth();
        double vh = viewport.getHeight();
        double iw = image == null ? 0 : image.getWidth();
        double ih = image == null ? 0 : image.getHeight();
        if (iw <= 0 || ih <= 0 || vw <= 0 || vh <= 0) {
            imageView.setFitWidth(0);
            imageView.setFitHeight(0);
            return;
        }
        if (vw / iw > vh / ih) {
            imageView.setFitWidth(vw);
            imageView.setFitHeight(0);
        } else {
            imageView.setFitWidth(0);
            imageView.setFitHeight(vh);
        }
    }

    /**
     * Recomputes the 1:1 pan scrollbars and the image's pan offset. The bars are
     * shown only in 1:1 mode and only on the axes where the native-size visual
     * overflows the viewport; the StackPane otherwise centers it. With
     * {@code recenter} the scroll position resets to the middle of the visual
     * (used when entering 1:1 or showing a differently sized visual); otherwise
     * the current position is kept (clamped), so a viewport resize doesn't jump.
     */
    private void updateScrollBars(boolean recenter) {
        var image = imageView.getImage();
        double vw = viewport.getWidth();
        double vh = viewport.getHeight();
        double iw = image == null ? 0 : image.getWidth();
        double ih = image == null ? 0 : image.getHeight();

        boolean oneToOne = scaleMode.get() == ScaleMode.ONE_TO_ONE;
        double maxX = oneToOne ? Math.max(0, iw - vw) : 0;
        double maxY = oneToOne ? Math.max(0, ih - vh) : 0;
        boolean showH = maxX > 0;
        boolean showV = maxY > 0;

        configureScrollBar(hScroll, maxX, vw, iw, showH, recenter);
        configureScrollBar(vScroll, maxY, vh, ih, showV, recenter);

        // Shorten each bar by the other's breadth when both show, leaving the
        // corner free instead of overlapping.
        hScroll.setPrefWidth(showV ? vw - SCROLLBAR_BREADTH : vw);
        hScroll.setMaxWidth(showV ? vw - SCROLLBAR_BREADTH : vw);
        vScroll.setPrefHeight(showH ? vh - SCROLLBAR_BREADTH : vh);
        vScroll.setMaxHeight(showH ? vh - SCROLLBAR_BREADTH : vh);

        updatePan();
    }

    /**
     * Sets one pan scrollbar's range, thumb size and value. Hidden bars are
     * zeroed; visible bars span {@code [0, max]} with a thumb proportional to
     * the visible fraction, and either recenter or keep their (clamped) value.
     */
    private void configureScrollBar(ScrollBar bar, double max, double view,
                                    double content, boolean show, boolean recenter) {
        bar.setVisible(show);
        if (!show) {
            bar.setValue(0);
            return;
        }
        double value = recenter ? max / 2 : bar.getValue();
        bar.setMin(0);
        bar.setMax(max);
        bar.setVisibleAmount(content <= 0 ? max : view / content * max);
        bar.setUnitIncrement(Math.max(1, view * 0.1));
        bar.setBlockIncrement(Math.max(1, view * 0.9));
        bar.setValue(Math.max(0, Math.min(max, value)));
    }

    /**
     * Pans the image to the scrollbars' current values. A bar at value {@code 0}
     * shows the leading edge and at {@code max} the trailing edge; the StackPane
     * centers the image, so the offset is measured from that center. A hidden bar
     * leaves its axis centered.
     */
    private void updatePan() {
        double tx = hScroll.isVisible() ? hScroll.getMax() / 2 - hScroll.getValue() : 0;
        double ty = vScroll.isVisible() ? vScroll.getMax() / 2 - vScroll.getValue() : 0;
        imageView.setTranslateX(tx);
        imageView.setTranslateY(ty);
    }

    /**
     * Toggles the window between its current size and the screen's available
     * area (the screen minus the menu bar and dock), doubling as a focus mode:
     * maximizing hides every chrome element that is shown but not pinned, and
     * un-maximizing brings exactly those back. Geometry, the remembered
     * pre-maximize size and the optional overscan are handled by the shared
     * {@link WindowMaximizer} (so a second maximize never overwrites the saved
     * size); the chrome hiding is layered on through its hooks.
     */
    private void toggleMaximize() {
        shell.maximizer().toggle(stage(),
                this::hideUnpinnedChrome, this::restoreHiddenChrome);
    }

    /**
     * On an {@code F} maximize, hides each chrome element that is currently
     * shown but unpinned, recording which ones so the next {@code F} can bring
     * back exactly those (and only those).
     */
    private void hideUnpinnedChrome() {
        focusRestoreToolbar = toolbarToggle.isSelected() && !toolbarPin.isSelected();
        focusRestoreInfo = infoToggle.isSelected() && !infoPin.isSelected();
        focusRestoreMetadata = metadataToggle.isSelected() && !metadataPin.isSelected();
        // No pin for the diagnostics panel: an F maximize always hides it.
        focusRestoreDiagnostics = diagnosticsToggle.isSelected();
        focusRestoreStatus = statusToggle.isSelected() && !statusPin.isSelected();
        if (focusRestoreToolbar) toolbarToggle.setSelected(false);
        if (focusRestoreInfo) infoToggle.setSelected(false);
        if (focusRestoreMetadata) metadataToggle.setSelected(false);
        if (focusRestoreDiagnostics) diagnosticsToggle.setSelected(false);
        if (focusRestoreStatus) statusToggle.setSelected(false);
    }

    /** Re-shows the chrome that {@link #hideUnpinnedChrome} hid for focus mode. */
    private void restoreHiddenChrome() {
        if (focusRestoreToolbar) toolbarToggle.setSelected(true);
        if (focusRestoreInfo) infoToggle.setSelected(true);
        if (focusRestoreMetadata) metadataToggle.setSelected(true);
        if (focusRestoreDiagnostics) diagnosticsToggle.setSelected(true);
        if (focusRestoreStatus) statusToggle.setSelected(true);
        focusRestoreToolbar = focusRestoreInfo = focusRestoreStatus = focusRestoreMetadata
                = focusRestoreDiagnostics = false;
    }

    /**
     * Escape/Enter: goes back to the previous view (mosaic or browser) through
     * the shell's back-stack. In full-screen mode the first press leaves full
     * screen instead; a second press goes back. Leaving the viewer pauses any
     * active playback via {@link #deactivate()}, keeping the session alive
     * for {@link #open}.
     */
    private void goBack() {
        if (shell.isFullScreen()) {
            shell.setFullScreen(false);
            return;
        }
        shell.back(AppShell.AppView.VIEWER);
    }

    /**
     * Arms the focus bounce for one event tick: the next time a toolbar or panel
     * control takes focus (from a click), the scene focus-owner listener hands it
     * straight back to the viewport, before any frame is painted. The flag clears
     * on the next pulse, so it never disturbs a later, genuine focus change (e.g.
     * Tab navigation). Mirrors MainWindow and the mosaic.
     */
    private void armFocusBounce() {
        bounceArmed = true;
        Platform.runLater(() -> bounceArmed = false);
    }

    /**
     * Hands keyboard focus back to the viewport if it has drifted onto a
     * toolbar/panel control — used after a mouse interaction (e.g. drag-selecting
     * in the Metadata table) settles, where the one-tick {@link #armFocusBounce()}
     * window has already lapsed. Leaves text inputs (the Metadata filter) and
     * pop-up owners (Copy All) focused so they stay usable.
     */
    private void settleFocusToViewport() {
        var scene = viewport.getScene();
        Node owner = scene != null ? scene.getFocusOwner() : null;
        if (owner == null || owner == viewport) return;
        if (owner instanceof TextInputControl || owner instanceof MenuButton) return;
        viewport.requestFocus();
    }

    private void navigate(int delta) {
        if (siblings.isEmpty()) return;
        index = Math.floorMod(index + delta, siblings.size());
        loadCurrent();
    }

    /**
     * Backspace / modifier1+Left / modifier1+Right: relay parent-directory
     * navigation to the opener (mosaic or main window) so it backs out a level
     * while the viewer stays in front. Defers (returns {@code false}, leaving the
     * key for the control) when a text input holds focus — the Metadata filter —
     * so Backspace and modifier1+Arrow keep editing it. Returns {@code true} when
     * the viewer claimed the key (whether or not a host acted).
     */
    private boolean navigateOpenerToParent() {
        var scene = viewport.getScene();
        Node owner = scene != null ? scene.getFocusOwner() : null;
        if (owner instanceof TextInputControl) return false;
        if (host != null) host.navigateToParent();
        return true;
    }

    /** Jumps to an absolute ring position (Home / End), clamped and a no-op if
     * already there. */
    private void navigateTo(int target) {
        if (siblings.isEmpty()) return;
        target = Math.max(0, Math.min(target, siblings.size() - 1));
        if (target == index) return;
        index = target;
        loadCurrent();
    }

    /**
     * Applies and persists a loading-indicator change. Switching off (or to any
     * non-overlay style) tears the Game Console overlay down at once instead of
     * leaving it spinning; the choice is then saved so it survives a restart (the
     * Settings dialog edits the same value).
     */
    private void applyLoadingIndicator(LoadingIndicator mode) {
        if (mode != LoadingIndicator.GAME_CONSOLE) gameConsoleOverlay.stop();
        settings.setViewerLoadingIndicator(mode);
        try {
            settings.save();
        } catch (java.io.IOException ignored) {
            // a failed save is non-fatal; the live setting still applies
        }
    }

    /**
     * Arms the loading indicator behind the configured delay (the gate): if the
     * next media lands within {@link AppSettings#viewerLoadingIndicatorDelayMs}
     * the indicator never shows, sparing a flash on fast/cached decodes; only a
     * decode still running when the delay elapses reveals it. A zero delay shows
     * it at once, and {@link LoadingIndicator#NONE} arms nothing.
     */
    private void scheduleLoadingIndicator() {
        loadingIndicatorDelay.stop();
        if (loadingIndicator.get() == LoadingIndicator.NONE) {
            return;
        }
        int delayMs = settings.viewerLoadingIndicatorDelayMs();
        if (delayMs <= 0) {
            showLoadingIndicator();
            return;
        }
        loadingIndicatorDelay.setDuration(Duration.millis(delayMs));
        loadingIndicatorDelay.playFromStart();
    }

    /**
     * Shows whichever loading indicator the user picked while the next media
     * decodes. {@link LoadingIndicator#DEFAULT} blanks the viewport to the
     * "Loading…" placeholder (the historical behaviour); {@link
     * LoadingIndicator#GAME_CONSOLE} starts the bottom-left CD overlay over the
     * current visual; {@link LoadingIndicator#NONE} leaves the visual untouched.
     * Reached via {@link #scheduleLoadingIndicator} once the delay gate elapses.
     */
    private void showLoadingIndicator() {
        switch (loadingIndicator.get()) {
            case DEFAULT -> {
                placeholder.setText("Loading…");
                imageView.setImage(null);
                infoPanel.showMessage("Loading…");
            }
            case GAME_CONSOLE -> gameConsoleOverlay.start();
            case NONE -> { }
        }
    }

    /**
     * Tears down the loading indicator once content (a still, an audio
     * placeholder, a first video frame or an error) lands. Cancels the delay gate
     * first — content within the grace period means no indicator was ever shown —
     * then dissolves the Game Console overlay; the Default placeholder text is
     * overwritten by the result it is replaced with.
     */
    private void hideLoadingIndicator() {
        loadingIndicatorDelay.stop();
        gameConsoleOverlay.stop();
    }

    private void loadCurrent() {
        stopPlayback();
        // Navigation ends a running flipbook (quietly: this load already
        // repaints the viewport, so the teardown must not reload on its own).
        if (flipbookToggle.isSelected()) {
            flipbookQuietTeardown = true;
            flipbookToggle.setSelected(false);
            flipbookQuietTeardown = false;
        }
        MediaItem item = siblings.get(index);
        int seq = ++loadSequence;

        title.set(item.fileName() + " — Viewer");
        hasContent.set(true);
        positionLabel.setText((index + 1) + " / " + siblings.size());
        statusLabel.setText("Loading " + item.fileName() + "…");
        scheduleLoadingIndicator();
        boolean single = siblings.size() <= 1;
        prevButton.setDisable(single);
        nextButton.setDisable(single);
        playButton.setDisable(item.kind() != MediaKind.VIDEO);
        onItemShown.accept(item);

        // Info panel's File section: name, size and timestamps from an async
        // filesystem stat, shown while the (potentially slow) decode is still
        // running; independent of the probe table below it.
        service.fileFacts(item.path()).whenComplete((facts, error) ->
                Platform.runLater(() -> {
                    if (seq != loadSequence) return;
                    if (error != null) {
                        infoPanel.clearFileFacts();
                    } else {
                        infoPanel.showFileFacts(
                                InfoPanel.fileFactRows(item.fileName(), facts));
                    }
                }));

        // Metadata panel: Auto OFF (default) -> reset to the placeholder for the
        // new item, never showing the previous item's metadata and never reading.
        // Auto ON (opt-in) and shown -> (re)arm the debounce so a held arrow key
        // fires at most one read, on the item we settle on.
        metadataDebounce.stop();
        metadataPanel.resetToPlaceholder();
        if (metadataToggle.isSelected() && metadataPanel.isAutoLoad()) {
            metadataDebounce.playFromStart();
        }

        // The freshness gate skips the native decode for items already
        // superseded while this request waited in the single decode thread's
        // queue, so a held arrow key doesn't pile up a backlog of full decodes
        // (which would freeze the on-screen image far behind the cursor).
        service.loadVisual(item.path(), () -> seq == loadSequence).whenComplete((result, error) ->
                Platform.runLater(() -> {
                    // a superseded request completes null (decode skipped)
                    if (result == null && error == null) return;
                    // advance past any frame newer than what's shown (monotonic),
                    // not only the single latest: at a moderate repeat rate the
                    // in-flight decode always lands one behind loadSequence, so a
                    // strict latest-only guard would drop every frame and freeze
                    // the view until the user pauses.
                    if (seq <= shownSequence) return;
                    shownSequence = seq;
                    if (error != null) {
                        logError("Cannot display " + item.fileName(), error);
                        infoPanel.showMessage(rootMessage(error));
                        // Live playback owns the viewport; only blank it for a
                        // still that itself failed to decode.
                        if (player == null) {
                            hideLoadingIndicator();
                            lastUprightFrame = null;
                            imageView.setImage(null);
                            placeholder.setText("Cannot display " + item.fileName()
                                    + "\n" + rootMessage(error));
                            statusLabel.setText(item.fileName() + " — error");
                        }
                        return;
                    }
                    // The info panel and probe hand-off track the loaded item even
                    // while a video plays: autoplay (on by default) starts playback
                    // before this decode lands, so a player-guard at the top would
                    // leave the panel stuck on the previous item for every movie.
                    infoPanel.show(result.probe());
                    onItemProbed.accept(result.probe());
                    // live playback owns the viewport; never paint a still over it
                    if (player != null) return;
                    var visual = result.frame();
                    if (visual.isPresent()) {
                        // Retain the upright (and AAE-edited) frame and bake the
                        // user adjustments into the displayed image, so the Image
                        // truly has the rotated dimensions and the FIT / 1:1 /
                        // CROP_TO_FILL scale math and pan scrollbars all compute
                        // against the right aspect. The Apple .AAE edit
                        // (crop/straighten) is composed first — it is part of the
                        // "image"; the user adjustments (rotate, mirror, B&W,
                        // invert) sit on top — and the upright frame is retained
                        // as the bake base so re-bakes need no re-decode (a no-op
                        // when there is no usable sidecar).
                        lastUprightFrame = RasterFrames.applyAae(visual.get(),
                                aaeStore.forImage(item.path()).orElse(null));
                        RasterFrame shown = RasterFrames.apply(lastUprightFrame,
                                rotationStore.adjustments(item.path()));
                        hideLoadingIndicator();
                        imageView.setImage(FxImages.toImage(shown));
                        placeholder.setText("");
                        statusLabel.setText(item.fileName() + " — "
                                + shown.width() + " × " + shown.height()
                                + " — " + item.kind());
                    } else {
                        hideLoadingIndicator();
                        lastUprightFrame = null;
                        imageView.setImage(null);
                        placeholder.setText("♫ " + item.fileName()
                                + "\n(audio — no visual)");
                        statusLabel.setText(item.fileName() + " — " + item.kind());
                    }
                }));

        // Autoplay (on by default): a video starts playing on load instead of
        // waiting for Play. Starting now sets player != null, so the loadVisual
        // callback above skips drawing the still and playback takes over.
        if (autoplayToggle.isSelected() && item.kind() == MediaKind.VIDEO) {
            playButton.setSelected(true);
            startPlayback(item);
        }
    }

    /**
     * Rebuilds the right SplitPane from whichever panels are toggled on, so a
     * hidden panel leaves no empty divider; collapses the split (handing the
     * viewport its width back) when both are hidden.
     */
    private void updateRightPanels() {
        var items = new ArrayList<Node>(3);
        if (infoToggle.isSelected()) items.add(infoPanel);
        if (metadataToggle.isSelected()) items.add(metadataPanel);
        if (diagnosticsToggle.isSelected()) items.add(diagnosticsPanel);
        if (!rightPanels.getItems().equals(items)) {
            rightPanels.getItems().setAll(items);
            if (items.size() == 2) rightPanels.setDividerPositions(0.4);
            else if (items.size() == 3) rightPanels.setDividerPositions(0.34, 0.67);
        }
        boolean any = !items.isEmpty();
        rightPanels.setVisible(any);
        rightPanels.setManaged(any);
    }

    /**
     * Starts the on-demand metadata read for the on-screen item — the only place
     * a read begins (a manual Load click, or the opt-in Auto debounce). Runs on
     * the service's dedicated {@code media-metadata} thread, off the decode
     * thread, so it never stalls fast browsing.
     *
     * <p>Staleness: we capture the <i>current</i> {@code loadSequence} <b>without</b>
     * incrementing it. The handoff sketch's {@code ++loadSequence} would also void
     * the in-flight visual decode of this same item (its callback guards on the
     * same counter), dropping the image. A later navigation bumps the counter in
     * {@link #loadCurrent}, so a stale read then sees {@code seq != loadSequence}
     * and is dropped — the same generation guard, without the collateral.</p>
     */
    private void fireMetadataRead() {
        if (index < 0 || index >= siblings.size()) return;
        MediaItem item = siblings.get(index);
        int seq = loadSequence;
        metadataPanel.showMessage("Reading\u2026");
        service.metadata(item.path()).whenComplete((md, error) ->
                Platform.runLater(() -> {
                    if (seq != loadSequence) return;
                    if (error != null) {
                        metadataPanel.showMessage(rootMessage(error));
                        return;
                    }
                    metadataPanel.show(md);
                }));
    }

    // --- right-click popup ---------------------------------------------------

    /**
     * The right-click actions popup, built lazily once the shared accelerator
     * scheme is known (after {@link #installMenuBar}). Its first items mirror the
     * mosaic's per-tile menu exactly — Copy Path (modifier1+C), Move…
     * (modifier1+M), an Adjust… submenu of the non-destructive transforms (Rotate
     * Left F6, Rotate Right F8, Mirror Horizontal F5, Mirror Vertical F7, Black &
     * White F9, Invert Shift+F9) — followed by a Toggle submenu carrying the chrome
     * toggles (Toolbar, Info Panel, Metadata, Status Bar, Pins). The accelerators
     * are shown for discoverability; the keystrokes are handled in the scene key
     * filter (and, for Move, the shared menu bar), so the popup's brief
     * scene-accelerator registration never shadows them.
     */
    /**
     * The platform shortcut chord for {@code code} as display text (e.g. {@code
     * ⌘I} on macOS, {@code Ctrl+I} elsewhere) — used in tooltips so the
     * advertised chord matches what the scene key filter checks
     * ({@link javafx.scene.input.KeyEvent#isShortcutDown()}).
     */
    private static String shortcutChord(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN).getDisplayText();
    }

    private ContextMenu viewContextMenu() {
        if (viewMenu != null) return viewMenu;
        var copyPath = new MenuItem("Copy Path");
        if (keys != null) copyPath.setAccelerator(keys.mod1(KeyCode.C));
        copyPath.setOnAction(e -> copyCurrentPath());
        var move = new MenuItem("Move\u2026");
        if (keys != null) move.setAccelerator(keys.mod1(KeyCode.M));
        move.setOnAction(e -> openMove());
        albumMenu = new AlbumMenu(albumStore, settings,
                () -> currentMoveSelection().sources(), stage(), statusLabel::setText);
        var rotateLeft = new MenuItem("Rotate Left");
        rotateLeft.setAccelerator(new KeyCodeCombination(KeyCode.F6));
        rotateLeft.setOnAction(e -> rotateCurrent(-1));
        var rotateRight = new MenuItem("Rotate Right");
        rotateRight.setAccelerator(new KeyCodeCombination(KeyCode.F8));
        rotateRight.setOnAction(e -> rotateCurrent(1));
        var mirrorH = new MenuItem("Mirror Horizontal");
        mirrorH.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        mirrorH.setOnAction(e -> toggleAdjustment(Adjustment.MIRROR_H));
        var mirrorV = new MenuItem("Mirror Vertical");
        mirrorV.setAccelerator(new KeyCodeCombination(KeyCode.F7));
        mirrorV.setOnAction(e -> toggleAdjustment(Adjustment.MIRROR_V));
        var blackWhite = new MenuItem("Black & White");
        blackWhite.setAccelerator(new KeyCodeCombination(KeyCode.F9));
        blackWhite.setOnAction(e -> toggleAdjustment(Adjustment.GRAYSCALE));
        var invert = new MenuItem("Invert");
        invert.setAccelerator(new KeyCodeCombination(KeyCode.F9, KeyCombination.SHIFT_DOWN));
        invert.setOnAction(e -> toggleAdjustment(Adjustment.INVERT));
        var adjustMenu = new Menu("Adjust\u2026", null,
                rotateLeft, rotateRight, new SeparatorMenuItem(),
                mirrorH, mirrorV, new SeparatorMenuItem(),
                blackWhite, invert);

        // The chrome toggles advertise their modifier1 chords for
        // discoverability; the keystrokes are handled in the scene key filter,
        // so the popup's brief accelerator registration never shadows them.
        var toolbarItem = new CheckMenuItem("Toolbar");
        if (keys != null) toolbarItem.setAccelerator(keys.mod1(KeyCode.T));
        toolbarItem.selectedProperty().bindBidirectional(toolbarToggle.selectedProperty());
        var infoItem = new CheckMenuItem("Info Panel");
        if (keys != null) infoItem.setAccelerator(keys.mod1(KeyCode.I));
        infoItem.selectedProperty().bindBidirectional(infoToggle.selectedProperty());
        var metadataItem = new CheckMenuItem("Metadata");
        if (keys != null) metadataItem.setAccelerator(keys.mod1(KeyCode.D));
        metadataItem.selectedProperty().bindBidirectional(metadataToggle.selectedProperty());
        var statusItem = new CheckMenuItem("Status Bar");
        if (keys != null) statusItem.setAccelerator(keys.mod1(KeyCode.S));
        statusItem.selectedProperty().bindBidirectional(statusToggle.selectedProperty());
        var pinsItem = new CheckMenuItem("Pins");
        if (keys != null) pinsItem.setAccelerator(keys.mod1(KeyCode.P));
        pinsItem.selectedProperty().bindBidirectional(pinsToggle.selectedProperty());
        var toggles = new Menu("Toggle\u2026", null,
                toolbarItem, infoItem, metadataItem, statusItem, pinsItem);

        viewMenu = new ContextMenu(copyPath, move, albumMenu.menu(),
                new SeparatorMenuItem(),
                adjustMenu, new SeparatorMenuItem(), toggles);
        WindowChrome.makeDismissive(viewMenu, viewport.getScene());
        return viewMenu;
    }

    /**
     * modifier1+C / right-click ▸ Copy Path: puts the on-screen item's absolute
     * path on the system clipboard and notes it in the status bar. No-op when the
     * ring is empty.
     */
    private void copyCurrentPath() {
        if (index < 0 || index >= siblings.size()) return;
        String path = siblings.get(index).path().toAbsolutePath().normalize().toString();
        var content = new ClipboardContent();
        content.putString(path);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied path: " + path);
    }

    private void rotateCurrent(int delta) {
        if (player != null || lastUprightFrame == null) return;
        if (index < 0 || index >= siblings.size()) return;
        MediaItem item = siblings.get(index);
        rotationStore.rotate(item.path(), delta);
        rebakeCurrentAdjustments(item);
        // Keep the opener's view (the mosaic's tile) in step with this rotate.
        if (host != null) host.mirrorViewerAdjustments(item.path());
    }

    /** The four non-destructive still toggles bound to F5/F7/F9/Shift+F9. */
    private enum Adjustment { MIRROR_H, MIRROR_V, GRAYSCALE, INVERT }

    /**
     * Toggles one non-destructive adjustment for the on-screen still (the mirror
     * / black&amp;white / invert counterpart to {@link #rotateCurrent}),
     * persisting it and re-baking from the retained upright frame (no re-decode).
     * A no-op while a video is playing (live-playback adjustments are out of
     * scope) or with no still loaded.
     */
    private void toggleAdjustment(Adjustment which) {
        if (player != null || lastUprightFrame == null) return;
        if (index < 0 || index >= siblings.size()) return;
        MediaItem item = siblings.get(index);
        switch (which) {
            case MIRROR_H -> rotationStore.toggleMirrorH(item.path());
            case MIRROR_V -> rotationStore.toggleMirrorV(item.path());
            case GRAYSCALE -> rotationStore.toggleGrayscale(item.path());
            case INVERT -> rotationStore.toggleInvert(item.path());
        }
        rebakeCurrentAdjustments(item);
        // Keep the opener's view (the mosaic's tile) in step with this change.
        if (host != null) host.mirrorViewerAdjustments(item.path());
    }

    /**
     * Re-applies the persisted adjustments for {@code path} to the on-screen
     * still when the viewer is currently showing that file — used when another
     * window (e.g. an auto-opening mosaic) rotated or otherwise adjusted the same
     * item, so the viewer mirrors the change without a re-decode. A no-op when the
     * viewer isn't showing {@code path}, a video is playing, or no still is
     * loaded.
     */
    public void refreshAdjustments(Path path) {
        if (player != null || lastUprightFrame == null) return;
        if (index < 0 || index >= siblings.size()) return;
        MediaItem item = siblings.get(index);
        if (!item.path().equals(path)) return;
        rebakeCurrentAdjustments(item);
    }

    /**
     * Bakes the store's current adjustments for {@code item} (rotation, mirror,
     * grayscale, invert) into the displayed image from the retained upright frame
     * (no re-decode), updates the status dimensions, and re-fits via
     * {@link #refreshScale} so the new dimensions re-center (covering the 180°
     * case too).
     */
    private void rebakeCurrentAdjustments(MediaItem item) {
        RasterFrame shown = RasterFrames.apply(lastUprightFrame,
                rotationStore.adjustments(item.path()));
        imageView.setImage(FxImages.toImage(shown));
        statusLabel.setText(item.fileName() + " — "
                + shown.width() + " × " + shown.height() + " — " + item.kind());
        refreshScale(true);
    }

    // --- slideshow (timed auto-advance) --------------------------------------

    /** Starts the slideshow at the current interval/direction (toggle turned on). */
    private void startSlideshow() {
        // The two auto-advance modes are exclusive; ending the flipbook here
        // restores the current still before the first slideshow tick.
        flipbookToggle.setSelected(false);
        rebuildSlideshowTimeline();
    }

    /** Tears down the slideshow timeline (toggle turned off or viewer hidden). */
    private void stopSlideshow() {
        if (slideshowTimeline != null) {
            slideshowTimeline.stop();
            slideshowTimeline = null;
        }
    }

    /**
     * (Re)builds and starts the slideshow {@link Timeline} at the current
     * interval and direction, but only while the toggle is on. Called when the
     * slideshow starts and whenever the dialog edits the interval or direction,
     * so a live change re-paces on the next tick without toggling off and on.
     * Each tick advances one sibling (forward or backward); the directory ring
     * wraps, and a single-item ring is left alone so it never reloads in place.
     */
    private void rebuildSlideshowTimeline() {
        if (slideshowTimeline != null) {
            slideshowTimeline.stop();
            slideshowTimeline = null;
        }
        if (!slideshowToggle.isSelected()) return;
        int delta = slideshowReverse ? -1 : 1;
        slideshowTimeline = new Timeline(new KeyFrame(
                Duration.seconds(slideshowIntervalSeconds),
                e -> { if (siblings.size() > 1) navigate(delta); }));
        slideshowTimeline.setCycleCount(Animation.INDEFINITE);
        slideshowTimeline.play();
    }

    /** Refreshes the toolbar toggle's tooltip to reflect the current config. */
    private void updateSlideshowTooltip() {
        slideshowToggle.getTooltip().setText("Auto-advance "
                + (slideshowReverse ? "backward" : "forward")
                + " through this directory every " + slideshowIntervalSeconds
                + "s (configure via Viewer \u25b8 Slideshow Settings\u2026)");
    }

    /**
     * Viewer ▸ Slideshow Settings…: opens a small form to configure the timed
     * auto-advance — a 1..10s interval slider, a forward/backward direction
     * choice, and a "run now" toggle that mirrors the toolbar/menu toggle. Edits
     * preview live (a running slideshow re-paces on the next tick) and are
     * persisted to {@link AppSettings} when the dialog closes.
     */
    public void showSlideshowDialog() {
        var intervalSlider = new Slider(1, 10, slideshowIntervalSeconds);
        intervalSlider.setPrefWidth(240);
        intervalSlider.setMajorTickUnit(1);
        intervalSlider.setMinorTickCount(0);
        intervalSlider.setSnapToTicks(true);
        intervalSlider.setShowTickMarks(true);
        intervalSlider.setShowTickLabels(true);
        var intervalValue = new Label(slideshowIntervalSeconds + " s");
        intervalSlider.valueProperty().addListener((o, a, v) -> {
            slideshowIntervalSeconds = (int) Math.round(v.doubleValue());
            intervalValue.setText(slideshowIntervalSeconds + " s");
            settings.setViewerSlideshowIntervalSeconds(slideshowIntervalSeconds);
            updateSlideshowTooltip();
            rebuildSlideshowTimeline();   // re-pace a running slideshow live
        });

        var directionGroup = new ToggleGroup();
        var forward = new RadioButton("Forward (Next)");
        forward.setToggleGroup(directionGroup);
        var backward = new RadioButton("Backward (Previous)");
        backward.setToggleGroup(directionGroup);
        forward.setSelected(!slideshowReverse);
        backward.setSelected(slideshowReverse);
        directionGroup.selectedToggleProperty().addListener((o, a, sel) -> {
            // The group never clears with two buttons, but guard a null anyway.
            slideshowReverse = sel == backward;
            settings.setViewerSlideshowReverse(slideshowReverse);
            updateSlideshowTooltip();
            rebuildSlideshowTimeline();
        });
        var direction = new HBox(12, forward, backward);
        direction.setAlignment(Pos.CENTER_LEFT);

        var runNow = new CheckBox("Run slideshow now");
        runNow.selectedProperty().bindBidirectional(slideshowToggle.selectedProperty());

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Interval:"), new HBox(10, intervalSlider, intervalValue));
        grid.addRow(1, new Label("Direction:"), direction);
        grid.add(runNow, 1, 2);
        var hint = new Label("Auto-advance through the current directory at the chosen "
                + "interval. Changes apply live and are remembered.");
        hint.setStyle("-fx-text-fill: gray;");
        hint.setWrapText(true);
        hint.setMaxWidth(360);
        grid.add(hint, 0, 3, 2, 1);

        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Slideshow");
        dialog.initOwner(stage());
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().add(
                new ButtonType("Close", ButtonBar.ButtonData.OK_DONE));
        dialog.showAndWait();

        // Persist whatever the live edits settled on (a failed save is non-fatal;
        // the live config still applies for the session).
        try {
            settings.save();
        } catch (java.io.IOException ignored) {
            // ignored: the in-memory config still drives the running slideshow
        }
    }

    // --- flipbook (buffered image-sequence playback) --------------------------

    /**
     * Starts the flipbook (toggle turned on): stops the exclusive modes,
     * collects the directory's images and begins buffering them all into
     * memory via a {@link FlipbookSession}. The status bar tracks preload
     * progress (frames and bytes against the computed requirement); playback
     * takes the viewport in {@link #startFlipbookPlayback} once the buffer is
     * ready. Snaps the toggle back off (async, off the listener) when the
     * directory cannot flip — fewer than 2 images, a buffer larger than the
     * JVM's free memory, or every frame unreadable — leaving the failure
     * message in the status bar and the current still untouched.
     */
    private void startFlipbook() {
        stopPlayback();
        playButton.setSelected(false);
        slideshowToggle.setSelected(false);
        var images = new ArrayList<Path>();
        for (MediaItem s : siblings) {
            if (s.kind() == MediaKind.IMAGE) images.add(s.path());
        }
        if (images.size() < 2) {
            statusLabel.setText("Flipbook needs at least 2 images in this directory");
            Platform.runLater(() -> flipbookToggle.setSelected(false));
            return;
        }
        statusLabel.setText("Flipbook: buffering " + images.size() + " frames…");
        flipbookSession = new FlipbookSession(service, rotationStore, aaeStore, images,
                new FlipbookSession.Listener() {
                    @Override
                    public void progress(int processed, int total, long buffered, long needed) {
                        statusLabel.setText("Flipbook: buffering " + processed + " / " + total
                                + (needed > 0 ? " (" + MediaProbe.humanBytes(buffered)
                                        + " of " + MediaProbe.humanBytes(needed) + ")" : ""));
                    }
                    @Override
                    public void ready() {
                        startFlipbookPlayback();
                    }
                    @Override
                    public void failed(String message) {
                        statusLabel.setText(message);
                        Platform.runLater(() -> flipbookToggle.setSelected(false));
                    }
                });
        flipbookSession.start();
    }

    /**
     * Tears the flipbook down (toggle turned off — directly, by navigation, by
     * deactivation or by a failed preload): stops the frame timeline, cancels
     * the session (releasing the buffer) and — when playback had actually
     * taken the viewport and the teardown is not
     * {@link #flipbookQuietTeardown quiet} — reloads the current item so the
     * ordinary still comes back. A teardown during buffering never reloads:
     * the still was never replaced.
     */
    private void stopFlipbook() {
        if (flipbookTimeline != null) {
            flipbookTimeline.stop();
            flipbookTimeline = null;
        }
        boolean wasPresenting = flipbookPixels != null;
        flipbookPixels = null;
        if (flipbookSession != null) {
            flipbookSession.cancel();
            flipbookSession = null;
        }
        if (wasPresenting && !flipbookQuietTeardown) loadCurrent();
    }

    /**
     * The preload finished: builds the presentation slot, shows frame 0 and
     * starts the frame timeline. From here the flipbook owns the viewport:
     * Play is disabled and the retained upright still is dropped so a rotate
     * key cannot re-bake it over the loop (the adjustment still persists and
     * bakes into the frames on the next flipbook start).
     */
    private void startFlipbookPlayback() {
        FlipbookSession session = flipbookSession;
        if (session == null || !session.isReady() || !flipbookToggle.isSelected()) return;
        int w = session.frameWidth();
        int h = session.frameHeight();
        flipbookPixels = new PixelBuffer<>(w, h, ByteBuffer.allocateDirect(w * h * 4),
                PixelFormat.getByteBgraPreInstance());
        lastUprightFrame = null;
        playButton.setDisable(true);
        hideLoadingIndicator();
        placeholder.setText("");
        flipbookFrameIndex = 0;
        showFlipbookFrame(0);
        imageView.setImage(new WritableImage(flipbookPixels));
        refreshScale(true);
        statusLabel.setText("Flipbook — " + session.frames().size() + " frames @ "
                + flipbookFps + " fps — " + w + " × " + h + " — "
                + MediaProbe.humanBytes(session.bufferedBytes()) + " buffered"
                + (session.skipped() > 0 ? " — " + session.skipped()
                        + " skipped (unreadable or a different size)" : ""));
        rebuildFlipbookTimeline();
    }

    /** Blits buffered frame {@code i} into the presentation slot (FX thread). */
    private void showFlipbookFrame(int i) {
        ByteBuffer buffer = flipbookPixels.getBuffer();
        buffer.rewind();
        buffer.put(flipbookSession.frames().get(i));
        buffer.rewind();
        flipbookPixels.updateBuffer(pb -> null);
    }

    /**
     * (Re)builds and starts the flipbook frame {@link Timeline} at the current
     * rate, but only while playback is live. Called when playback starts and
     * whenever the dialog edits the fps, so a live change re-paces on the next
     * frame without rebuffering.
     */
    private void rebuildFlipbookTimeline() {
        if (flipbookTimeline != null) {
            flipbookTimeline.stop();
            flipbookTimeline = null;
        }
        if (flipbookPixels == null || !flipbookToggle.isSelected()) return;
        flipbookTimeline = new Timeline(new KeyFrame(Duration.millis(1000.0 / flipbookFps),
                e -> {
                    flipbookFrameIndex = (flipbookFrameIndex + 1) % flipbookSession.frames().size();
                    showFlipbookFrame(flipbookFrameIndex);
                }));
        flipbookTimeline.setCycleCount(Animation.INDEFINITE);
        flipbookTimeline.play();
    }

    /** Refreshes the toolbar toggle's tooltip to reflect the current config. */
    private void updateFlipbookTooltip() {
        flipbookToggle.getTooltip().setText("Play this directory's same-size images "
                + "as a looping " + flipbookFps + " fps video, buffered fully in memory "
                + "(configure via Viewer ▸ Flipbook Settings…)");
    }

    /**
     * Viewer ▸ Flipbook Settings…: opens a small form to configure buffered
     * image-sequence playback — a 1..60 fps slider (re-pacing a live flipbook
     * on the fly), the computed buffer requirement for the current directory
     * (frames × width × height × 4 bytes, sized asynchronously against the
     * first image's probe and compared to the JVM's free memory) and a "play
     * now" toggle mirroring the toolbar/menu toggle. The frame rate persists.
     */
    public void showFlipbookDialog() {
        var fpsSlider = new Slider(1, 60, flipbookFps);
        fpsSlider.setPrefWidth(240);
        fpsSlider.setMajorTickUnit(10);
        fpsSlider.setMinorTickCount(9);
        fpsSlider.setShowTickMarks(true);
        var fpsValue = new Label(flipbookFps + " fps");
        fpsSlider.valueProperty().addListener((o, a, v) -> {
            flipbookFps = Math.max(1, (int) Math.round(v.doubleValue()));
            fpsValue.setText(flipbookFps + " fps");
            settings.setViewerFlipbookFps(flipbookFps);
            updateFlipbookTooltip();
            rebuildFlipbookTimeline();   // re-pace a live flipbook
        });

        // The buffer requirement: exact once a buffer is live, otherwise an
        // async estimate from the first image's probe (container-declared
        // dimensions — close enough to size the folder before decoding it).
        var bufferInfo = new Label();
        bufferInfo.setWrapText(true);
        bufferInfo.setMaxWidth(360);
        int imageCount = 0;
        Path firstImage = null;
        for (MediaItem s : siblings) {
            if (s.kind() == MediaKind.IMAGE) {
                if (firstImage == null) firstImage = s.path();
                imageCount++;
            }
        }
        if (flipbookSession != null && flipbookSession.isReady()) {
            bufferInfo.setText(flipbookSession.frames().size() + " frames of "
                    + flipbookSession.frameWidth() + " × " + flipbookSession.frameHeight()
                    + " buffered — " + MediaProbe.humanBytes(flipbookSession.bufferedBytes()));
        } else if (imageCount < 2) {
            bufferInfo.setText("This directory has " + (imageCount == 1 ? "1 image" : "no images")
                    + " — a flipbook needs at least 2.");
        } else {
            bufferInfo.setText(imageCount + " images — sizing the buffer…");
            int count = imageCount;
            service.probe(firstImage).whenComplete((probe, error) -> Platform.runLater(() -> {
                if (error != null || probe == null || probe.width() <= 0 || probe.height() <= 0) {
                    bufferInfo.setText(count + " images — the buffer is frames × width × "
                            + "height × 4 bytes (the first image did not report a size)");
                    return;
                }
                long needed = FlipbookSession.bytesNeeded(count, probe.width(), probe.height());
                long available = FlipbookSession.availableMemoryBytes();
                bufferInfo.setText("Buffer needed: " + count + " × " + probe.width() + " × "
                        + probe.height() + " × 4 B = " + MediaProbe.humanBytes(needed)
                        + " — about " + MediaProbe.humanBytes(available) + " of memory free"
                        + (needed > available * 0.8 ? " (too large to buffer)" : ""));
            }));
        }

        var runNow = new CheckBox("Play flipbook now");
        runNow.selectedProperty().bindBidirectional(flipbookToggle.selectedProperty());

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Frame rate:"), new HBox(10, fpsSlider, fpsValue));
        grid.addRow(1, new Label("Buffer:"), bufferInfo);
        grid.add(runNow, 1, 2);
        var hint = new Label("Plays the current directory's same-size images as a "
                + "looping video. Every frame is decoded once into a memory buffer "
                + "before playback starts, so the loop never touches the decoders. "
                + "Changes apply live and the frame rate is remembered.");
        hint.setStyle("-fx-text-fill: gray;");
        hint.setWrapText(true);
        hint.setMaxWidth(360);
        grid.add(hint, 0, 3, 2, 1);

        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Flipbook");
        dialog.initOwner(stage());
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().add(
                new ButtonType("Close", ButtonBar.ButtonData.OK_DONE));
        dialog.showAndWait();

        // Persist whatever the live edits settled on (a failed save is non-fatal;
        // the live config still applies for the session).
        try {
            settings.save();
        } catch (java.io.IOException ignored) {
            // ignored: the in-memory config still drives the running flipbook
        }
    }

    // --- move dialog ---------------------------------------------------------

    /**
     * Cmd+M / right-click ▸ Move…: opens the Move dialog for the on-screen item.
     * No-op when the ring is empty. An audio-only or errored load still counts
     * as the current ring item, so it remains movable.
     */
    public void openMove() {
        if (index < 0 || index >= siblings.size()) return;
        moveController.open();
    }

    /** The directory the on-screen item lives in; relative paths resolve against it. */
    private Path currentMoveDirectory() {
        if (index < 0 || index >= siblings.size()) return null;
        return siblings.get(index).path().getParent();
    }

    /** The move source: just the on-screen item (the viewer never shows ".."). */
    private MoveController.Selection currentMoveSelection() {
        if (index < 0 || index >= siblings.size()) {
            return new MoveController.Selection(List.of(), false);
        }
        return new MoveController.Selection(List.of(siblings.get(index).path()), false);
    }

    /**
     * The ring item to show once {@code moving} leaves the directory: the first
     * sibling after the current one that is not moving, else the first before
     * it, else {@code null} (the ring is now empty). Read against the pre-move
     * ring, mirroring the main window's and mosaic's focus rule.
     */
    private Path nextFocusAfterMove(List<Path> moving) {
        Set<Path> movingSet = new HashSet<>(moving);
        for (int i = index + 1; i < siblings.size(); i++) {
            Path p = siblings.get(i).path();
            if (!movingSet.contains(p)) return p;
        }
        for (int i = index - 1; i >= 0; i--) {
            Path p = siblings.get(i).path();
            if (!movingSet.contains(p)) return p;
        }
        return null;
    }

    /**
     * Releases the on-screen item's native handles before it is moved: if the
     * item being moved is the one currently playing, end playback so its source
     * file is closed. On Windows an open file cannot be moved or renamed, so the
     * decoder must let go first; {@link #stopPlayback()} blocks until the
     * playback thread has returned and released its native resources. Runs on the
     * FX thread just before the async move launches.
     */
    private void releaseBeforeMove(List<Path> moving) {
        if (player == null || index < 0 || index >= siblings.size()) return;
        Path current = siblings.get(index).path();
        if (moving.contains(current)) {
            stopPlayback();
        }
    }

    /**
     * Folds a completed move into the navigation ring: drops the moved item,
     * asks the opener to re-list around {@code focusPath}, then either shows that
     * next item or — when the ring is now empty — hands control back to the
     * opener and hides the viewer. Runs on the FX thread (the dialog's modal
     * loop keeps it pumping while the async move completes).
     */
    private void refreshAfterMove(Path focusPath) {
        Path movedPath = index >= 0 && index < siblings.size()
                ? siblings.get(index).path() : null;
        List<MediaItem> remaining = new ArrayList<>();
        for (MediaItem it : siblings) {
            if (!it.path().equals(movedPath)) remaining.add(it);
        }
        if (host != null) host.refreshAfterViewerMove(focusPath);

        if (remaining.isEmpty() || focusPath == null) {
            siblings = List.of();
            index = -1;
            // Nothing left to show: go back unconditionally (even from full
            // screen, unlike Escape's two-press behaviour).
            if (shell.isFullScreen()) shell.setFullScreen(false);
            shell.back(AppShell.AppView.VIEWER);
            return;
        }
        siblings = List.copyOf(remaining);
        index = 0;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).path().equals(focusPath)) {
                index = i;
                break;
            }
        }
        loadCurrent();
    }

    private void onPlayToggled() {
        MediaItem item = siblings.get(index);
        if (playButton.isSelected()) {
            if (player == null) {
                startPlayback(item);
            } else {
                player.play();
                statusLabel.setText(item.fileName() + " — playing");
            }
        } else if (player != null) {
            player.pause();
            statusLabel.setText(item.fileName() + " — paused at "
                    + timecode(playPositionMicros));
        }
    }

    private void startPlayback(MediaItem item) {
        int session = ++playSession;
        playDurationMicros = -1;
        playPositionMicros = 0;

        // Honor the persisted user rotation: the presenter bakes it into each
        // frame (free on the GPU for the GL paths, a per-frame permutation for
        // the direct fallback), so the ImageView receives upright, rotated-
        // dimension frames — the FIT / 1:1 / CROP scale math then works exactly
        // as it does for a baked-rotation still.
        int turns = rotationStore.quarterTurns(item.path());
        boolean swapDims = (turns & 1) == 1;

        var presenter = new FallbackVideoSink(imageView, turns);
        var sink = new VideoPlayer.FrameSink() {
            private long lastStatusMicros = Long.MIN_VALUE;

            @Override
            public void begin(int width, int height, long durationMicros) {
                presenter.begin(width, height, durationMicros);
                int dw = swapDims ? height : width;
                int dh = swapDims ? width : height;
                Platform.runLater(() -> {
                    if (session != playSession) return;
                    hideLoadingIndicator();
                    playDurationMicros = durationMicros;
                    placeholder.setText("");
                    presenterLabel.setText(presenter.activePresenter());
                    statusLabel.setText(item.fileName() + " — " + dw + " × "
                            + dh + " — playing");
                });
            }

            @Override
            public void frame(MemorySegment bgra, int width, int height,
                              long positionMicros) {
                presenter.frame(bgra, width, height, positionMicros);
                if (positionMicros - lastStatusMicros < 250_000) return;
                lastStatusMicros = positionMicros;
                Platform.runLater(() -> {
                    if (session != playSession) return;
                    playPositionMicros = positionMicros;
                    statusLabel.setText(item.fileName() + " — playing — "
                            + timecode(positionMicros)
                            + (playDurationMicros > 0
                                    ? " / " + timecode(playDurationMicros) : ""));
                });
            }

            @Override
            public void close() {
                presenter.close();
            }
        };

        player = service.newVideoPlayer(item.path(), sink,
                () -> Platform.runLater(() -> {
                    if (session != playSession) return;
                    if (repeatToggle.isSelected()) {
                        repeatPlayback(item);
                    } else {
                        stopPlayback();
                        statusLabel.setText(item.fileName() + " — ended");
                    }
                }),
                error -> Platform.runLater(() -> {
                    if (session != playSession) return;
                    hideLoadingIndicator();
                    logError("Cannot play " + item.fileName(), error);
                    stopPlayback();
                    placeholder.setText("Cannot play " + item.fileName()
                            + "\n" + rootMessage(error));
                    statusLabel.setText(item.fileName() + " — playback error");
                }));
        player.play();
    }

    /**
     * Continuous replay: restarts the just-ended playback from the top on the
     * same item, leaving the Play button engaged. Closes the finished one-shot
     * player (its thread has already returned, so this returns at once) and opens
     * a fresh session via {@link #startPlayback}. Reached only with the Repeat
     * toggle on; the session guard in the end-of-stream hook keeps a stale loop
     * from firing after navigation or stop.
     */
    private void repeatPlayback(MediaItem item) {
        if (player != null) {
            player.close();
            player = null;
        }
        startPlayback(item);
    }

    /**
     * Pauses an active playback session (used when the viewer is hidden);
     * the session stays alive so reopening the same item can resume it.
     */
    private void pausePlayback() {
        if (player == null || !playButton.isSelected()) return;
        playButton.setSelected(false);
        player.pause();
        statusLabel.setText(siblings.get(index).fileName() + " — paused at "
                + timecode(playPositionMicros));
    }

    /** Ends any playback session; safe to call when none is active. */
    private void stopPlayback() {
        playSession++;
        playButton.setSelected(false);
        presenterLabel.setText("");
        if (player == null) return;
        player.close();
        player = null;
    }

    private static String timecode(long micros) {
        long s = Math.max(0, micros) / 1_000_000;
        return s >= 3600
                ? "%d:%02d:%02d".formatted(s / 3600, (s % 3600) / 60, s % 60)
                : "%d:%02d".formatted(s / 60, s % 60);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /**
     * Echoes a viewer load/playback failure to the app console in addition to
     * the on-screen message. The viewport only shows {@link #rootMessage} (a
     * one-line summary), so the console gets the full stack trace — the part you
     * actually need to diagnose a backend (e.g. Homebrew FFmpeg 8 / vips) blowup.
     */
    private static void logError(String context, Throwable error) {
        System.err.println("[ViewerWindow] " + context + ": " + rootMessage(error));
        error.printStackTrace();
    }
}
