package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.MediaFailures;
import io.github.ghosthack.mediabrowser.ui.mosaic.MosaicTileModifier;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.util.Optional;

/**
 * Whether the views label corrupt data (the {@code ui.markCorrupt} setting,
 * "Mark corrupt files"; default on): the mosaic's amber
 * {@link MosaicTileModifier#CORRUPT} sign and badges, the browser row's ⚠, and
 * the "Corrupt: …" text in the Info panels, the viewer and the status bars.
 *
 * <p>Off changes presentation only. {@code MediaService} keeps recording
 * verdicts (cheap), and a failure stays a failure: a corrupt file that did
 * not decode keeps {@link MosaicTileModifier#THUMBNAIL_FAILED} and shows its
 * plain error message ({@link MediaFailures#plainMessage}) — never a success,
 * never hidden. The views listen to {@link #property()} and repaint/refresh
 * when it changes, so a Settings save applies without a restart.</p>
 */
public final class CorruptMarking {

    private static final SimpleBooleanProperty ENABLED =
            new SimpleBooleanProperty(CorruptMarking.class, "markCorrupt", true);

    private CorruptMarking() {}

    /** Applies the persisted {@code ui.markCorrupt} value; call on the FX thread once views exist. */
    public static void configure(boolean enabled) {
        ENABLED.set(enabled);
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    /** Observed by the views to re-render when the setting changes. */
    public static ReadOnlyBooleanProperty property() {
        return ENABLED;
    }

    /** The one-line failure text a view shows: labelled "Corrupt: …" only while marking is on. */
    public static String message(Throwable error) {
        return enabled() ? MediaFailures.message(error) : MediaFailures.plainMessage(error);
    }

    /** Whether to label {@code error} corrupt (it is corrupt data and marking is on). */
    public static boolean isMarked(Throwable error) {
        return enabled() && MediaFailures.isCorrupt(error);
    }

    /** A recorded verdict ({@code MediaService.corruption}) as the views show it: empty while off. */
    public static Optional<String> shown(Optional<String> corruption) {
        return enabled() ? corruption : Optional.empty();
    }

    /**
     * The mosaic state modifiers of a media tile. A failed thumbnail always
     * carries {@code THUMBNAIL_FAILED}; {@code CORRUPT} refines it (or, on a
     * decoded thumbnail, adds the corner badge) only while marking is on.
     *
     * @param failed          the thumbnail decode failed (or produced nothing)
     * @param failedCorrupt   that failure was labelled corrupt data
     * @param decodedCorrupt  a thumbnail is shown but its file is labelled corrupt
     */
    public static int mediaTileModifiers(boolean failed, boolean failedCorrupt,
                                         boolean decodedCorrupt, boolean mark) {
        if (failed) {
            return MosaicTileModifier.THUMBNAIL_FAILED.mask()
                    | (mark && failedCorrupt ? MosaicTileModifier.CORRUPT.mask() : 0);
        }
        return mark && decodedCorrupt ? MosaicTileModifier.CORRUPT.mask() : 0;
    }
}
