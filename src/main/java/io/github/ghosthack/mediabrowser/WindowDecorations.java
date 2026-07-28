package io.github.ghosthack.mediabrowser;

import java.util.Locale;

/**
 * How the application asks JavaFX to decorate its primary windows.
 *
 * <p>{@link #AUTOMATIC} uses the themed extended-window integration when the
 * running JavaFX platform reports that it is supported and otherwise keeps
 * native decorations. {@link #THEMED} always requests the extended style
 * (JavaFX itself downgrades it to native decorations on an unsupported
 * platform). {@link #NATIVE} always keeps the operating-system frame, while
 * {@link #UNDECORATED} preserves the application's older manual drag, resize,
 * and close-button implementation.</p>
 */
public enum WindowDecorations {
    AUTOMATIC("Automatic"),
    THEMED("Themed"),
    NATIVE("Native"),
    UNDECORATED("Undecorated");

    /** Toolkit-independent result, kept separate so policy is testable headlessly. */
    public enum RequestedStyle { EXTENDED, DECORATED, UNDECORATED }

    public static final WindowDecorations DEFAULT = AUTOMATIC;

    private final String label;

    WindowDecorations(String label) {
        this.label = label;
    }

    /**
     * Resolves this preference to the style the shell should request.
     *
     * <p>The new extended/themed path is intentionally Windows-only: this
     * setting must not silently replace the established AppKit/X11 frame when
     * upgrading JavaFX. Themed deliberately returns {@code EXTENDED} on
     * Windows even when support was not advertised: {@code
     * StageStyle.EXTENDED}'s specified fallback is the ordinary decorated
     * style. Automatic performs the support check itself.</p>
     */
    public RequestedStyle requestedStyle(boolean windows, boolean extendedSupported) {
        return switch (this) {
            case AUTOMATIC -> windows && extendedSupported
                    ? RequestedStyle.EXTENDED : RequestedStyle.DECORATED;
            case THEMED -> windows
                    ? RequestedStyle.EXTENDED : RequestedStyle.DECORATED;
            case NATIVE -> RequestedStyle.DECORATED;
            case UNDECORATED -> RequestedStyle.UNDECORATED;
        };
    }

    @Override
    public String toString() {
        return label;
    }

    /** Parses a persisted enum name without allowing a hand-edited file to fail startup. */
    public static WindowDecorations fromSettings(
            String value, WindowDecorations fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
