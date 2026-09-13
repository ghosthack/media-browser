package io.github.ghosthack.mediabrowser.win;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.ghosthack.mediabrowser.media.FileAssociationCatalog;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.panama.media.fileassoc.Advapi32;
import io.github.ghosthack.panama.media.fileassoc.Shell32;

/**
 * Owns this app's file-association registry layout and business rules on
 * top of the raw {@link Advapi32}/{@link Shell32} bindings: ProgId naming,
 * associate/unassociate, and reading back the live state Explorer actually
 * uses (not just what we last wrote).
 *
 * <p>Registry layout under {@code HKEY_CURRENT_USER\Software\Classes} (HKCU
 * only — no elevation needed):</p>
 * <pre>{@code
 * MediaBrowser.AssocFile.<ext>                       (Default) = "Media Browser <KIND> file (.<ext>)"
 * MediaBrowser.AssocFile.<ext>\DefaultIcon            (Default) = "<exePath>,0"
 * MediaBrowser.AssocFile.<ext>\shell\open\command     (Default) = "\"<exePath>\" \"%1\""
 * .<ext>                                              (Default) = MediaBrowser.AssocFile.<ext>
 * .<ext>\OpenWithProgids\MediaBrowser.AssocFile.<ext> (Default) = ""
 * }</pre>
 *
 * <p><b>Windows 8+ UserChoice caveat:</b> once Explorer has recorded a
 * {@code UserChoice} for an extension (in
 * {@code HKCU\...\Explorer\FileExts\.ext\UserChoice}), writing {@code .ext}'s
 * default value is silently ineffective for the double-click path — that's
 * deliberate OS anti-hijack protection, not something a registry write can
 * override. {@link #query} detects this and reports
 * {@link AssocState#AVAILABLE_NOT_DEFAULT} rather than falsely claiming
 * {@link AssocState#DEFAULT}.</p>
 */
public final class FileAssociationRegistry {

    private FileAssociationRegistry() {}

    private static final String CLASSES_ROOT = "Software\\Classes\\";
    private static final String USER_CHOICE_ROOT =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";

    /** Whether — and how — this app is associated with an extension right now. */
    public enum AssocState { NOT_ASSOCIATED, DEFAULT, AVAILABLE_NOT_DEFAULT }

    /**
     * @param state       this app's live association state for the extension
     * @param liveProgId  the ProgId Explorer currently treats as the default
     *                    for the extension (ours, someone else's, or {@code null})
     */
    public record AssocStatus(AssocState state, String liveProgId) {}

    /** The live association state for {@code extension} (no leading dot). */
    public static AssocStatus query(String extension) {
        if (!Advapi32.isAvailable()) return new AssocStatus(AssocState.NOT_ASSOCIATED, null);
        String ourProgId = FileAssociationCatalog.progId(extension);
        try (Arena arena = Arena.ofConfined()) {
            String userChoiceProgId = readUserChoiceProgId(arena, extension);
            boolean openWithRegistered = openWithProgIdExists(arena, extension, ourProgId);
            String liveDefault = userChoiceProgId != null
                    ? userChoiceProgId
                    : readClassesExtensionDefault(arena, extension);
            if (ourProgId.equals(liveDefault)) {
                return new AssocStatus(AssocState.DEFAULT, liveDefault);
            }
            return new AssocStatus(
                    openWithRegistered ? AssocState.AVAILABLE_NOT_DEFAULT : AssocState.NOT_ASSOCIATED,
                    liveDefault);
        }
    }

    /**
     * Registers this app for {@code extension}, pointing its command line at
     * {@code exePath}, and tells Explorer to pick the change up immediately.
     *
     * @return the resulting live status (see the UserChoice caveat above —
     *         it may come back {@link AssocState#AVAILABLE_NOT_DEFAULT} even
     *         right after associating)
     */
    public static AssocStatus associate(String extension, MediaKind kind, Path exePath) {
        requireWindows();
        String progId = FileAssociationCatalog.progId(extension);
        String command = quote(exePath) + " \"%1\"";
        try (Arena arena = Arena.ofConfined()) {
            writeDefault(arena, CLASSES_ROOT + progId,
                    FileAssociationCatalog.description(extension, kind));
            writeDefault(arena, CLASSES_ROOT + progId + "\\DefaultIcon", quote(exePath) + ",0");
            writeDefault(arena, CLASSES_ROOT + progId + "\\shell\\open\\command", command);
            writeDefault(arena, CLASSES_ROOT + "." + extension, progId);

            MemorySegment openWithKey = Advapi32.regCreateKeyEx(arena, Advapi32.HKEY_CURRENT_USER,
                    CLASSES_ROOT + "." + extension + "\\OpenWithProgids", Advapi32.KEY_WRITE);
            try {
                Advapi32.regSetValueString(arena, openWithKey, progId, "");
            } finally {
                Advapi32.regCloseKey(openWithKey);
            }
        }
        Shell32.notifyAssociationsChanged();
        return query(extension);
    }

    /**
     * Removes this app's association for {@code extension}: deletes our
     * ProgId tree entirely, and clears {@code .ext}'s default value only if
     * it still points at us (never clobbers a default some other app claimed
     * since). Idempotent — a no-op if we were never associated.
     *
     * <p>Also tidies up the {@code .ext} and {@code .ext\OpenWithProgids}
     * container keys our own writes may have left behind as empty husks —
     * but only when {@link Advapi32#regKeyIsEmpty} confirms zero subkeys and
     * zero values, so a key another app still uses (a second
     * {@code OpenWithProgids} entry, a {@code PerceivedType}, ...) is never
     * touched.</p>
     */
    public static void unassociate(String extension) {
        requireWindows();
        String progId = FileAssociationCatalog.progId(extension);
        try (Arena arena = Arena.ofConfined()) {
            int rw = Advapi32.KEY_READ | Advapi32.KEY_WRITE;
            MemorySegment extKey = Advapi32.regOpenKeyExOrNull(arena, Advapi32.HKEY_CURRENT_USER,
                    CLASSES_ROOT + "." + extension, rw);
            if (extKey != null) {
                try {
                    String current = Advapi32.regQueryValueString(arena, extKey, null);
                    if (progId.equals(current)) {
                        Advapi32.regDeleteValue(arena, extKey, null);
                    }
                } finally {
                    Advapi32.regCloseKey(extKey);
                }
            }
            MemorySegment openWithKey = Advapi32.regOpenKeyExOrNull(arena, Advapi32.HKEY_CURRENT_USER,
                    CLASSES_ROOT + "." + extension + "\\OpenWithProgids", rw);
            if (openWithKey != null) {
                try {
                    Advapi32.regDeleteValue(arena, openWithKey, progId);
                } finally {
                    Advapi32.regCloseKey(openWithKey);
                }
            }
            Advapi32.regDeleteTree(arena, Advapi32.HKEY_CURRENT_USER, CLASSES_ROOT + progId);

            // Order matters: .ext can only be empty once OpenWithProgids
            // (its subkey) is gone too.
            deleteKeyIfEmpty(arena, CLASSES_ROOT + "." + extension + "\\OpenWithProgids");
            deleteKeyIfEmpty(arena, CLASSES_ROOT + "." + extension);
        }
        Shell32.notifyAssociationsChanged();
    }

    /** Deletes {@code subKey} only if it exists and is verifiably empty; otherwise leaves it untouched. */
    private static void deleteKeyIfEmpty(Arena arena, String subKey) {
        MemorySegment key = Advapi32.regOpenKeyExOrNull(arena, Advapi32.HKEY_CURRENT_USER,
                subKey, Advapi32.KEY_READ);
        if (key == null) return;
        boolean empty;
        try {
            empty = Advapi32.regKeyIsEmpty(arena, key);
        } finally {
            Advapi32.regCloseKey(key);
        }
        if (empty) {
            Advapi32.regDeleteTree(arena, Advapi32.HKEY_CURRENT_USER, subKey);
        }
    }

    /**
     * Best-effort: opens Windows' "Default apps" settings page, for the user
     * to finish picking a default once {@link AssocState#AVAILABLE_NOT_DEFAULT}
     * shows this app is registered but not chosen.
     *
     * @return whether the settings page was launched
     */
    public static boolean openDefaultAppsSettings() {
        try {
            new ProcessBuilder("explorer.exe", "ms-settings:defaultapps").start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Blocks until the user closes it: shows Windows' native "How do you
     * want to open this .ext file?" chooser for {@code extension}, the
     * standard shell dialog a real double-click of an unassociated file
     * shows, complete with its "Always use this app" checkbox. This is the
     * one path a third-party app has left, by design, to become a Windows
     * default since the OS stopped letting apps do it silently via the
     * registry (the same anti-hijack protection {@link #query} works around
     * for reading): a registry write can register us as <em>available</em>
     * ({@link AssocState#AVAILABLE_NOT_DEFAULT}), but only the user, through
     * this dialog or Settings, can make us the default.
     *
     * <p>Implemented via the classic {@code rundll32 shell32.dll,OpenAs_RunDLL
     * <file>} shell trick, which needs a real (if empty) file with the right
     * extension to point at — {@code extension} need not already be
     * associated with anything for this to work, but the caller should only
     * offer it where doing so makes sense (see {@link AssocState}).</p>
     */
    public static void promptSetDefault(String extension) throws IOException, InterruptedException {
        requireWindows();
        Path placeholder = Files.createTempFile("mediabrowser-default-", "." + extension);
        try {
            new ProcessBuilder("rundll32.exe", "shell32.dll,OpenAs_RunDLL", placeholder.toString())
                    .start()
                    .waitFor();
        } finally {
            Files.deleteIfExists(placeholder);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void requireWindows() {
        if (!Advapi32.isAvailable()) {
            throw new IllegalStateException("File associations are only available on Windows");
        }
    }

    private static void writeDefault(Arena arena, String subKey, String value) {
        MemorySegment key = Advapi32.regCreateKeyEx(arena, Advapi32.HKEY_CURRENT_USER,
                subKey, Advapi32.KEY_WRITE);
        try {
            Advapi32.regSetValueString(arena, key, null, value);
        } finally {
            Advapi32.regCloseKey(key);
        }
    }

    private static String readClassesExtensionDefault(Arena arena, String extension) {
        MemorySegment key = Advapi32.regOpenKeyExOrNull(arena, Advapi32.HKEY_CURRENT_USER,
                CLASSES_ROOT + "." + extension, Advapi32.KEY_READ);
        if (key == null) return null;
        try {
            return Advapi32.regQueryValueString(arena, key, null);
        } finally {
            Advapi32.regCloseKey(key);
        }
    }

    private static boolean openWithProgIdExists(Arena arena, String extension, String progId) {
        MemorySegment key = Advapi32.regOpenKeyExOrNull(arena, Advapi32.HKEY_CURRENT_USER,
                CLASSES_ROOT + "." + extension + "\\OpenWithProgids", Advapi32.KEY_READ);
        if (key == null) return false;
        try {
            return Advapi32.regQueryValueString(arena, key, progId) != null;
        } finally {
            Advapi32.regCloseKey(key);
        }
    }

    private static String readUserChoiceProgId(Arena arena, String extension) {
        MemorySegment key = Advapi32.regOpenKeyExOrNull(arena, Advapi32.HKEY_CURRENT_USER,
                USER_CHOICE_ROOT + "." + extension + "\\UserChoice", Advapi32.KEY_READ);
        if (key == null) return null;
        try {
            return Advapi32.regQueryValueString(arena, key, "ProgId");
        } finally {
            Advapi32.regCloseKey(key);
        }
    }

    private static String quote(Path p) {
        return "\"" + p + "\"";
    }
}
