package io.github.ghosthack.mediabrowser.ui.icon;

import io.github.ghosthack.mediabrowser.IconPack;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Process-wide owner of the selected icon pack. Registered views/bindings are
 * held weakly so virtualized cells and closed windows remain collectible.
 */
public final class IconPackManager {

    private static final IconPackManager INSTANCE = new IconPackManager();

    public static IconPackManager get() {
        return INSTANCE;
    }

    private final Set<IconPackAware> targets =
            Collections.newSetFromMap(new WeakHashMap<>());
    private IconPack current = IconPack.ORIGINAL;

    private IconPackManager() {}

    public IconPack current() {
        return current;
    }

    /** Switches every registered icon live. */
    public void setCurrent(IconPack pack) {
        current = pack == null ? IconPack.ORIGINAL : pack;
        for (IconPackAware target : targets.toArray(IconPackAware[]::new)) {
            target.applyIconPack(current);
        }
    }

    void register(IconPackAware target) {
        if (target == null) return;
        targets.add(target);
        target.applyIconPack(current);
    }
}
