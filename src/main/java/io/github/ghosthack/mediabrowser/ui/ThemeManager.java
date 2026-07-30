package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.Theme;

import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Process-wide owner of the current {@link Theme}. Scenes (and the occasional
 * dialog pane) register here; the manager keeps their stylesheet list carrying
 * exactly the current theme's overlay, and re-applies live to every registered
 * target when the theme changes. Reapplication is idempotent and replaces the
 * old theme in one list mutation: controls must never see an intermediate pulse
 * with only Modena styling while a dialog closes or a live preview changes.
 *
 * <p>The theme stylesheet is inserted at the front of each target's stylesheet
 * list so window-specific sheets (e.g. {@code mosaic.css}, {@code scrollbar.css})
 * added afterwards keep priority over it. Targets are held weakly, so closing a
 * window lets its scene be collected without unregistering.</p>
 */
public final class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();

    public static ThemeManager get() {
        return INSTANCE;
    }

    /** Every theme's stylesheet URL, removed from a target before the current is added. */
    private final List<String> allThemeUrls = new ArrayList<>();
    private final Set<Scene> scenes = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Parent> parents = Collections.newSetFromMap(new WeakHashMap<>());
    private Theme current = Theme.DEFAULT;

    private ThemeManager() {
        for (Theme t : Theme.values()) {
            String url = t.stylesheetUrl();
            if (url != null) allThemeUrls.add(url);
        }
    }

    /** The theme currently applied to all registered targets. */
    public Theme current() {
        return current;
    }

    /** Switches the theme and re-applies it live to every registered target. */
    public void setCurrent(Theme theme) {
        current = theme == null ? Theme.DEFAULT : theme;
        for (Scene s : scenes) applyTo(s);
        for (Parent p : parents) applyTo(p.getStylesheets());
    }

    /** Tracks {@code scene} for live theme changes and applies the current theme now. */
    public void register(Scene scene) {
        if (scene == null) return;
        scenes.add(scene);
        applyTo(scene);
    }

    /**
     * Tracks a node subtree (e.g. a {@code DialogPane}) for live theme changes and
     * applies the current theme now. Useful for dialogs, whose scene is not
     * available until shown.
     */
    public void register(Parent parent) {
        if (parent == null) return;
        parents.add(parent);
        applyTo(parent.getStylesheets());
    }

    private void applyTo(ObservableList<String> sheets) {
        String url = current.stylesheetUrl();
        applyThemeStylesheet(sheets, url, allThemeUrls);
    }

    /**
     * Normalizes one target's theme overlay without exposing a transient list
     * in which the previous theme has been removed but its replacement has not
     * yet been added. {@code setAll} also gives same-theme reapplications no
     * observable mutation, avoiding an unnecessary CSS/layout pulse when a
     * settings dialog closes without changing the theme.
     */
    static void applyThemeStylesheet(
            ObservableList<String> sheets,
            String currentUrl,
            List<String> allThemeUrls) {
        var desired = new ArrayList<String>(sheets.size() + (currentUrl == null ? 0 : 1));
        if (currentUrl != null) desired.add(currentUrl);
        for (String sheet : sheets) {
            if (!allThemeUrls.contains(sheet)) desired.add(sheet);
        }
        if (!sheets.equals(desired)) sheets.setAll(desired);
    }

    /**
     * Applies both CSS and the otherwise-hidden scene fill. EXTENDED stages use
     * the fill's brightness to choose legible platform caption-button glyphs,
     * so it must follow live light/dark theme changes.
     */
    private void applyTo(Scene scene) {
        applyTo(scene.getStylesheets());
        scene.setFill(switch (current) {
            case DEFAULT -> Color.WHITE;
            case PLAIN_DARK -> Color.BLACK;
            case PLAIN_DARK_GRAY -> Color.web("#333333");
            case CUPERTINO_LIGHT -> Color.web("#e5e5ea");
            case CUPERTINO_DARK -> Color.web("#2c2c2e");
        });
    }
}
