package io.github.ghosthack.mediabrowser.ui.icon;

import io.github.ghosthack.mediabrowser.IconPack;

import javafx.scene.control.Labeled;

/**
 * Applies a semantic icon to a JavaFX {@link Labeled} control while preserving
 * its exact Original-pack text. The binding is retained in the control's
 * properties map and weakly observed by {@link IconPackManager}.
 */
public final class IconBinding implements IconPackAware {

    private static final Object PROPERTY_KEY = IconBinding.class.getName();

    private final Labeled control;
    private final IconView view;
    private String originalText;
    private String vectorText;
    private AppIcon icon;

    private IconBinding(Labeled control, AppIcon icon, String originalText,
                        String vectorText, String accessibleText, double size) {
        this.control = control;
        this.icon = icon;
        this.originalText = originalText == null ? "" : originalText;
        this.vectorText = vectorText == null ? "" : vectorText;
        this.view = new IconView(icon, size);
        view.colorProperty().bind(control.textFillProperty());
        if (accessibleText != null && !accessibleText.isBlank()) {
            control.setAccessibleText(accessibleText);
        }
        control.getProperties().put(PROPERTY_KEY, this);
        IconPackManager.get().register(this);
    }

    public static IconBinding install(Labeled control, AppIcon icon,
                                      String originalText, String vectorText,
                                      String accessibleText) {
        return install(control, icon, originalText, vectorText, accessibleText, 18);
    }

    public static IconBinding install(Labeled control, AppIcon icon,
                                      String originalText, String vectorText,
                                      String accessibleText, double size) {
        if (control == null) throw new IllegalArgumentException("control");
        return new IconBinding(control, icon, originalText, vectorText, accessibleText, size);
    }

    /** Changes the semantic role and both pack-specific text forms atomically. */
    public void update(AppIcon icon, String originalText, String vectorText) {
        this.icon = icon;
        this.originalText = originalText == null ? "" : originalText;
        this.vectorText = vectorText == null ? "" : vectorText;
        view.setIcon(icon);
        applyIconPack(IconPackManager.get().current());
    }

    @Override
    public void applyIconPack(IconPack pack) {
        IconPack selected = pack == null ? IconPack.ORIGINAL : pack;
        if (selected == IconPack.ORIGINAL || icon == null) {
            control.setGraphic(null);
            control.setText(selected == IconPack.ORIGINAL ? originalText : vectorText);
        } else {
            control.setText(vectorText);
            control.setGraphic(view);
        }
    }
}
