package io.github.ghosthack.mediabrowser.ui.mosaic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/** Built-in and service-loaded mosaic tile-set registry. */
public final class MosaicTileSets {

    public static final MosaicTileSet CURRENT = new CurrentMosaicTileSet();
    public static final MosaicTileSet XEDGE = new XedgeMosaicTileSet();
    public static final MosaicTileSet XEDGE_COLOR = XedgeMosaicTileSet.colorVariant();
    public static final MosaicTileSet XEDGE_SHARP = XedgeMosaicTileSet.sharpVariant();
    public static final MosaicTileSet XEDGE_LITE = XedgeMosaicTileSet.liteVariant();
    public static final MosaicTileSet XEDGE_ADDITIVE =
            XedgeMosaicTileSet.additiveVariant();
    public static final MosaicTileSet DARKROOM = new DarkroomMosaicTileSet();
    public static final MosaicTileSet FACTORY = new FactoryMosaicTileSet();
    public static final MosaicTileSet BLACKLINE = new BlacklineMosaicTileSet();

    private static final List<MosaicTileSet> SETS = discover();

    private MosaicTileSets() {}

    public static List<MosaicTileSet> values() {
        return SETS;
    }

    /** Resolves a persisted ID, falling back to the fresh-install default. */
    public static MosaicTileSet resolve(String id) {
        if (id != null) {
            String wanted = id.trim().toLowerCase(Locale.ROOT);
            for (MosaicTileSet set : SETS) {
                if (set.id().trim().toLowerCase(Locale.ROOT).equals(wanted)) return set;
            }
        }
        return XEDGE_ADDITIVE;
    }

    private static List<MosaicTileSet> discover() {
        var byId = new LinkedHashMap<String, MosaicTileSet>();
        add(byId, CURRENT);
        add(byId, XEDGE);
        add(byId, XEDGE_COLOR);
        add(byId, XEDGE_SHARP);
        add(byId, XEDGE_LITE);
        add(byId, XEDGE_ADDITIVE);
        add(byId, DARKROOM);
        add(byId, FACTORY);
        add(byId, BLACKLINE);
        try {
            for (MosaicTileSet set : ServiceLoader.load(MosaicTileSet.class)) {
                add(byId, set);
            }
        } catch (RuntimeException | java.util.ServiceConfigurationError e) {
            System.err.println("Cannot load mosaic tile-set provider: " + e);
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }

    private static void add(
            LinkedHashMap<String, MosaicTileSet> byId, MosaicTileSet set) {
        if (set == null || set.id() == null) return;
        String id = set.id().trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9.-]*")) {
            System.err.println("Ignoring mosaic tile set with invalid id: " + set.id());
            return;
        }
        byId.putIfAbsent(id, set);
    }
}
