package io.github.ghosthack.mediabrowser.ui.icon;

import io.github.ghosthack.mediabrowser.IconPack;

/** A weakly tracked UI object that can repaint itself for a new icon pack. */
interface IconPackAware {
    void applyIconPack(IconPack pack);
}
