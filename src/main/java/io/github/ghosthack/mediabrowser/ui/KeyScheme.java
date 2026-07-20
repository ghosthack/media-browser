package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.AppSettings;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCombination.Modifier;

import java.util.Locale;

/**
 * The application's logical menu-accelerator modifier scheme. Menu accelerators
 * are built from two <em>logical</em> modifiers rather than hard-wired physical
 * keys, so the chord set can follow platform convention by default yet be
 * remapped from Settings&nbsp;&rsaquo;&nbsp;Keys:
 *
 * <ul>
 *   <li><b>modifier&nbsp;1</b> &mdash; the primary "shortcut" modifier. Default
 *       ({@link ModifierChoice#AUTO}) is Command on macOS and Control elsewhere
 *       (JavaFX {@link KeyCombination#SHORTCUT_DOWN}).</li>
 *   <li><b>modifier&nbsp;2</b> &mdash; the secondary modifier. Default
 *       ({@link ModifierChoice#AUTO}) is Option on macOS and Alt elsewhere
 *       (JavaFX {@link KeyCombination#ALT_DOWN}).</li>
 * </ul>
 *
 * <p>Each logical modifier can instead be pinned to a concrete physical modifier
 * (Control, Command/Meta or Alt/Option) regardless of platform &mdash; e.g. mapping
 * modifier&nbsp;1 to Command/Meta on Windows. The mapping is read once at startup
 * from {@link AppSettings}; changes take effect on the next start (accelerators are
 * baked into the menu bar when it is built).</p>
 */
public final class KeyScheme {

    /** Which logical slot a {@link ModifierChoice} is being resolved for. */
    public enum Slot { MOD1, MOD2 }

    /**
     * The physical modifier a logical slot maps to (the Settings&nbsp;&rsaquo;&nbsp;Keys
     * choices). {@link #AUTO} defers to the platform default for that slot.
     */
    public enum ModifierChoice {
        AUTO("Auto (platform default)"),
        CONTROL("Control"),
        COMMAND("Command / Meta"),
        ALT("Alt / Option");

        public final String label;

        ModifierChoice(String label) {
            this.label = label;
        }

        /** The lowercase token persisted in {@code app.properties}. */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parse a persisted token; unknown/blank tokens fall back to {@link #AUTO}. */
        public static ModifierChoice fromToken(String token) {
            if (token != null) {
                try {
                    return valueOf(token.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // fall through to AUTO
                }
            }
            return AUTO;
        }
    }

    private final Modifier mod1;
    private final Modifier mod2;

    private KeyScheme(Modifier mod1, Modifier mod2) {
        this.mod1 = mod1;
        this.mod2 = mod2;
    }

    /** Build the scheme from the persisted Keys settings (+ platform defaults). */
    public static KeyScheme fromSettings(AppSettings settings) {
        return new KeyScheme(
                resolve(ModifierChoice.fromToken(settings.keysModifier1()), Slot.MOD1),
                resolve(ModifierChoice.fromToken(settings.keysModifier2()), Slot.MOD2));
    }

    private static Modifier resolve(ModifierChoice choice, Slot slot) {
        return switch (choice) {
            case AUTO -> slot == Slot.MOD1
                    ? KeyCombination.SHORTCUT_DOWN
                    : KeyCombination.ALT_DOWN;
            case CONTROL -> KeyCombination.CONTROL_DOWN;
            case COMMAND -> KeyCombination.META_DOWN;
            case ALT -> KeyCombination.ALT_DOWN;
        };
    }

    /** {@code modifier1 + code} — e.g. Open Folder (Cmd/Ctrl+O). */
    public KeyCombination mod1(KeyCode code) {
        return new KeyCodeCombination(code, mod1);
    }

    /** {@code modifier1 + Shift + code} — e.g. Window ▸ Mosaic (Cmd/Ctrl+Shift+M). */
    public KeyCombination mod1Shift(KeyCode code) {
        return new KeyCodeCombination(code, mod1, KeyCombination.SHIFT_DOWN);
    }

    /** {@code modifier2 + code} — the secondary (Alt/Option) modifier. */
    public KeyCombination mod2(KeyCode code) {
        return new KeyCodeCombination(code, mod2);
    }

    /**
     * Whether {@code code} is the physical key that produces logical modifier 2
     * (Alt/Option by default; Control or Command/Meta if remapped). Used to peek
     * a hidden menu bar while that modifier is held — the press/release of the
     * modifier key alone, matched by key code so it is unambiguous across the
     * press/release pair.
     */
    public boolean isMod2Key(KeyCode code) {
        if (mod2 == KeyCombination.CONTROL_DOWN) {
            return code == KeyCode.CONTROL;
        }
        if (mod2 == KeyCombination.META_DOWN) {
            return code == KeyCode.META || code == KeyCode.COMMAND || code == KeyCode.WINDOWS;
        }
        return code == KeyCode.ALT;   // ALT_DOWN — the modifier-2 default
    }
}
