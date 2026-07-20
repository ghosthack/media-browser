package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * The single, shared policy for the hidden per-directory <em>sidecar</em> files
 * the browser writes or consumes but never lists as browsable items: the user
 * rotation store's {@code .picasa.ini} and Apple Photos {@code .AAE} edit
 * sidecars. Centralized (per the rotation handoff's "promote the hidden-file
 * predicate" hardening) so every enumerator inherits the same rules instead of
 * re-deriving them.
 *
 * <p>The rotation sidecar is hidden unconditionally (it is purely our
 * bookkeeping). An {@code .AAE} is hidden only when it is <em>matched</em> — it
 * has a sibling media file it edits — so a stray, orphaned {@code .AAE} (whose
 * photo was deleted) still shows rather than vanishing silently.
 */
public final class Sidecars {

    private Sidecars() {}

    /** Whether {@code file} is the per-directory user-rotation sidecar ({@code .picasa.ini}). */
    public static boolean isRotationSidecar(Path file) {
        Path name = file.getFileName();
        return name != null
                && name.toString().equalsIgnoreCase(RotationStore.sidecarFileName());
    }

    /** Whether {@code file} is an Apple Photos edit sidecar (an {@code .aae}). */
    public static boolean isAaeSidecar(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 && s.substring(dot + 1).equalsIgnoreCase("aae");
    }

    /**
     * The lower-cased base name of {@code file} with its final extension removed
     * ({@code IMG_7085.HEIC} → {@code img_7085}); a leading-dot name keeps its
     * whole name (so {@code .picasa.ini} → {@code .picasa}).
     */
    public static String stem(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        String base = dot > 0 ? s.substring(0, dot) : s;
        return base.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the {@code .AAE} at {@code aae} is matched by some sibling in the
     * same directory whose (extension-stripped) stem is in {@code siblingStems}
     * — both the same stem ({@code IMG_7085.AAE} ↔ {@code IMG_7085.HEIC}) and
     * Apple's edited-copy variant ({@code IMG_7085.AAE} ↔ {@code IMG_E7085.HEIC}).
     * {@code siblingStems} must already exclude sidecars and be lower-cased.
     */
    public static boolean isMatchedAaeSidecar(Path aae, Set<String> siblingStems) {
        if (!isAaeSidecar(aae)) {
            return false;
        }
        String s = stem(aae);
        if (siblingStems.contains(s)) {
            return true;
        }
        String edited = editedVariant(s);
        return edited != null && siblingStems.contains(edited);
    }

    /**
     * Apple's edited-copy stem for an original ({@code img_7085} →
     * {@code img_e7085}), or null when the stem isn't an {@code IMG_<digits>}
     * name. Also used in reverse via {@link #originalVariant}.
     */
    static String editedVariant(String stem) {
        if (stem.startsWith("img_") && allDigits(stem, 4)) {
            return "img_e" + stem.substring(4);
        }
        return null;
    }

    /** The original stem for an edited copy ({@code img_e7085} → {@code img_7085}), or null. */
    static String originalVariant(String stem) {
        if (stem.startsWith("img_e") && allDigits(stem, 5)) {
            return "img_" + stem.substring(5);
        }
        return null;
    }

    private static boolean allDigits(String s, int from) {
        if (from >= s.length()) {
            return false;
        }
        for (int i = from; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
