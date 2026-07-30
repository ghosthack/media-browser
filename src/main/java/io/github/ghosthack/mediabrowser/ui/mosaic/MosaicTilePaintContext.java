package io.github.ghosthack.mediabrowser.ui.mosaic;

import io.github.ghosthack.mediabrowser.media.DirEntry;
import io.github.ghosthack.mediabrowser.media.FolderVerdict;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;

/**
 * Ephemeral, allocation-free context reused by the mosaic paint loop. A tile
 * set must not retain it after {@link MosaicTileSet#paint} returns.
 */
public final class MosaicTilePaintContext {

    /**
     * Host operations that preserve the mosaic's thumbnail, adjustment,
     * caption, and current-renderer behavior without exposing its caches.
     */
    public interface Host {
        void paintCurrent(MosaicTilePaintContext context);
        boolean drawMediaThumbnail(MosaicTilePaintContext context);
        boolean drawFolderCollage(MosaicTilePaintContext context);
        void drawCaption(MosaicTilePaintContext context);
        void drawReticule(MosaicTilePaintContext context);
        Font tileFont(double size, boolean monospaced);
        double textWidth(String text, Font font);
    }

    private final Host host;
    private GraphicsContext graphics;
    private DirEntry entry;
    private MosaicTileClassifier.Base base;
    private FolderVerdict folderVerdict;
    private double x;
    private double y;
    private double size;
    private int modifiers;

    public MosaicTilePaintContext(Host host) {
        if (host == null) throw new IllegalArgumentException("host");
        this.host = host;
    }

    /**
     * Host-only reset before a synchronous paint. Public solely because tile-set
     * providers live in a separate package/module boundary.
     */
    public void reset(GraphicsContext graphics, DirEntry entry,
                      MosaicTileClassifier.Base base, FolderVerdict folderVerdict,
                      double x, double y, double size, int dynamicModifiers) {
        this.graphics = graphics;
        this.entry = entry;
        this.base = base;
        this.folderVerdict = folderVerdict;
        this.x = x;
        this.y = y;
        this.size = size;
        this.modifiers = base.modifiers() | dynamicModifiers;
    }

    public GraphicsContext graphics() { return graphics; }
    public DirEntry entry() { return entry; }
    public MosaicTileIdentity identity() { return base.identity(); }
    public String stamp() { return base.stamp(); }
    public FolderVerdict folderVerdict() { return folderVerdict; }
    public double x() { return x; }
    public double y() { return y; }
    public double size() { return size; }

    public boolean has(MosaicTileModifier modifier) {
        return (modifiers & modifier.mask()) != 0;
    }

    public void paintCurrent() { host.paintCurrent(this); }
    public boolean drawMediaThumbnail() { return host.drawMediaThumbnail(this); }
    public boolean drawFolderCollage() { return host.drawFolderCollage(this); }
    public void drawCaption() { host.drawCaption(this); }
    public void drawReticule() { host.drawReticule(this); }
    public Font font(double size, boolean monospaced) {
        return host.tileFont(size, monospaced);
    }
    public double textWidth(String text, Font font) {
        return host.textWidth(text, font);
    }
}
