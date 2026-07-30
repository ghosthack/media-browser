package io.github.ghosthack.mediabrowser.ui.icon;

import io.github.ghosthack.mediabrowser.IconPack;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * A square JavaFX icon node. Dynamic instances follow {@link IconPackManager};
 * fixed instances are used by the Settings pack previews.
 */
public final class IconView extends Pane implements IconPackAware {

    private static final double VIEW_BOX = 24;

    private final boolean dynamic;
    private final ObjectProperty<Paint> color =
            new SimpleObjectProperty<>(this, "color", Color.BLACK);
    private AppIcon icon;
    private final IconPack fixedPack;
    private Group art;

    /** Creates an icon that follows the process-wide selected pack. */
    public IconView(AppIcon icon, double iconSize) {
        this(icon, iconSize, null, true);
    }

    /** Creates an icon locked to {@code pack}, suitable for a pack preview. */
    public IconView(IconPack pack, AppIcon icon, double iconSize) {
        this(icon, iconSize, pack == null ? IconPack.ORIGINAL : pack, false);
    }

    private IconView(AppIcon icon, double iconSize, IconPack fixedPack, boolean dynamic) {
        if (!(iconSize > 0)) throw new IllegalArgumentException("iconSize");
        this.icon = icon;
        this.fixedPack = fixedPack;
        this.dynamic = dynamic;
        setMinSize(iconSize, iconSize);
        setPrefSize(iconSize, iconSize);
        setMaxSize(iconSize, iconSize);
        setMouseTransparent(true);
        setFocusTraversable(false);
        if (dynamic) IconPackManager.get().register(this);
        else applyIconPack(this.fixedPack);
    }

    public ObjectProperty<Paint> colorProperty() {
        return color;
    }

    public Paint getColor() {
        return color.get();
    }

    public void setColor(Paint color) {
        this.color.set(color);
    }

    public AppIcon icon() {
        return icon;
    }

    public void setIcon(AppIcon icon) {
        if (this.icon == icon) return;
        this.icon = icon;
        applyIconPack(dynamic ? IconPackManager.get().current() : fixedPack);
    }

    @Override
    public void applyIconPack(IconPack pack) {
        getChildren().clear();
        art = null;
        if (icon == null) return;

        IconPack selected = pack == null ? IconPack.ORIGINAL : pack;
        if (selected == IconPack.ORIGINAL) {
            var label = new Label(icon.originalGlyph());
            label.setMouseTransparent(true);
            label.textFillProperty().bind(color);
            label.setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
            label.setPrefSize(USE_COMPUTED_SIZE, USE_COMPUTED_SIZE);
            label.setStyle("-fx-padding: 0;");
            getChildren().add(label);
            return;
        }

        var glyph = GeneratedIconPacks.glyph(selected, icon);
        // Generated-pack validation is covered by tests. This fallback keeps a
        // hand-edited or partially regenerated development build usable.
        if (glyph == null) {
            var fallback = new Label(icon.originalGlyph());
            fallback.textFillProperty().bind(color);
            getChildren().add(fallback);
            return;
        }

        var viewport = new Rectangle(VIEW_BOX, VIEW_BOX, Color.TRANSPARENT);
        var path = new SVGPath();
        path.setContent(glyph.svgPath());
        if (glyph.paintMode() == GeneratedIconPacks.PaintMode.STROKE) {
            path.setFill(Color.TRANSPARENT);
            path.strokeProperty().bind(color);
            path.setStrokeWidth(1.8);
            path.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            path.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        } else {
            path.fillProperty().bind(color);
            path.setStroke(Color.TRANSPARENT);
        }
        art = new Group(viewport, path);
        art.setMouseTransparent(true);
        getChildren().add(art);
    }

    @Override
    protected void layoutChildren() {
        if (getChildren().isEmpty()) return;
        var node = getChildren().getFirst();
        if (art == null) {
            double w = node.prefWidth(-1);
            double h = node.prefHeight(-1);
            node.resizeRelocate((getWidth() - w) / 2.0, (getHeight() - h) / 2.0, w, h);
            return;
        }
        double scale = Math.min(getWidth(), getHeight()) / VIEW_BOX;
        art.setScaleX(scale);
        art.setScaleY(scale);
        // JavaFX scales around the layout-bounds centre. Offsetting the unscaled
        // 24px group by half the size delta leaves its transformed bounds centred.
        art.relocate((getWidth() - VIEW_BOX) / 2.0, (getHeight() - VIEW_BOX) / 2.0);
    }
}
