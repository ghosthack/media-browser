package io.github.ghosthack.mediabrowser.win;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
 * <p>Every {@link #associate} also (idempotently) registers the app itself
 * under the "Default Programs" scheme, so Windows Settings' Default Apps
 * page can offer it as a target at all:</p>
 * <pre>{@code
 * Software\Clients\MediaBrowser\Capabilities             ApplicationName/Icon/Description
 * Software\Clients\MediaBrowser\Capabilities\FileAssociations   .<ext> = MediaBrowser.AssocFile.<ext>  (one per associated extension)
 * Software\RegisteredApplications                         "Media Browser" = Software\Clients\MediaBrowser\Capabilities
 * }</pre>
 *
 * <p><b>Windows 8+ UserChoice caveat:</b> once Explorer has recorded a
 * {@code UserChoice} for an extension (in
 * {@code HKCU\...\Explorer\FileExts\.ext\UserChoice}), writing {@code .ext}'s
 * default value is silently ineffective for the double-click path — that's
 * deliberate OS anti-hijack protection, not something a registry write can
 * override. {@link #query} detects this and reports
 * {@link AssocState#AVAILABLE_NOT_DEFAULT} rather than falsely claiming
 * {@link AssocState#DEFAULT}. There's no registry-only workaround for this:
 * {@link #openAppDefaultsSettings} deep-links straight to this app's own
 * Default Apps page, which is the only remaining self-service path (see its
 * javadoc for why the legacy {@code OpenAs_RunDLL} shell trick doesn't
 * work).</p>
 */
public final class FileAssociationRegistry {

    private FileAssociationRegistry() {}

    private static final String CLASSES_ROOT = "Software\\Classes\\";
    private static final String USER_CHOICE_ROOT =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";
    private static final String CLIENT_ROOT = "Software\\Clients\\MediaBrowser\\Capabilities";
    private static final String REGISTERED_APPS_ROOT = "Software\\RegisteredApplications";
    private static final String REGISTERED_APP_NAME = "Media Browser";

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

            ensureRegisteredApplication(arena, exePath);
            MemorySegment fileAssocKey = Advapi32.regCreateKeyEx(arena, Advapi32.HKEY_CURRENT_USER,
                    CLIENT_ROOT + "\\FileAssociations", Advapi32.KEY_WRITE);
            try {
                Advapi32.regSetValueString(arena, fileAssocKey, "." + extension, progId);
            } finally {
                Advapi32.regCloseKey(fileAssocKey);
            }
        }
        Shell32.notifyAssociationsChanged();
        return query(extension);
    }

    /**
     * Idempotently registers this app under the "Default Programs" scheme
     * ({@code Software\RegisteredApplications} pointing at a
     * {@code Capabilities} key) so Windows Settings' Default Apps page can
     * offer it as a target at all — a bare {@code .ext} ProgId association
     * (the rest of {@link #associate}) is invisible to that page on its own.
     */
    private static void ensureRegisteredApplication(Arena arena, Path exePath) {
        MemorySegment capsKey = Advapi32.regCreateKeyEx(arena, Advapi32.HKEY_CURRENT_USER,
                CLIENT_ROOT, Advapi32.KEY_WRITE);
        try {
            Advapi32.regSetValueString(arena, capsKey, "ApplicationName", REGISTERED_APP_NAME);
            Advapi32.regSetValueString(arena, capsKey, "ApplicationIcon", quote(exePath) + ",0");
            Advapi32.regSetValueString(arena, capsKey, "ApplicationDescription",
                    "A JavaFX desktop media browser");
        } finally {
            Advapi32.regCloseKey(capsKey);
        }
        MemorySegment registeredKey = Advapi32.regCreateKeyEx(arena, Advapi32.HKEY_CURRENT_USER,
                REGISTERED_APPS_ROOT, Advapi32.KEY_WRITE);
        try {
            Advapi32.regSetValueString(arena, registeredKey, REGISTERED_APP_NAME, CLIENT_ROOT);
        } finally {
            Advapi32.regCloseKey(registeredKey);
        }
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

            // Keeps the Default-Apps-page listing in sync; the app's own
            // Capabilities/RegisteredApplications registration is left in
            // place even if this was the last extension — real installed
            // apps don't unregister themselves from Default Programs just
            // because one file type was unchecked.
            MemorySegment fileAssocKey = Advapi32.regOpenKeyExOrNull(arena, Advapi32.HKEY_CURRENT_USER,
                    CLIENT_ROOT + "\\FileAssociations", rw);
            if (fileAssocKey != null) {
                try {
                    Advapi32.regDeleteValue(arena, fileAssocKey, "." + extension);
                } finally {
                    Advapi32.regCloseKey(fileAssocKey);
                }
            }
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
     * Best-effort: opens Windows Settings' Default Apps page, deep-linked
     * straight to this app's own entry (every extension it's registered for,
     * each with its own inline default-picker) via the documented
     * {@code ms-settings:defaultapps?registeredAppUser=<name>} query
     * parameter — which only resolves to anything once {@link #associate}
     * has registered the app under {@code Software\RegisteredApplications}
     * (see the class docs). Falls back to the generic (unfiltered) Default
     * Apps page if the shell refuses the deep link.
     *
     * <p>Dispatched through {@link Shell32#shellOpen} (ShellExecuteW), with
     * the registered name's space left literal: that form is verified to land
     * on Apps ▸ Default apps ▸ Media Browser on Windows 11. The earlier
     * {@code explorer.exe <uri>} spawn with a URL-encoded name opened a plain
     * Explorer folder window instead — explorer.exe treats an argument it
     * cannot parse as a folder path.</p>
     *
     * <p>This is the only self-service path left: Windows has not let a
     * third-party app set itself as a default via the registry since Windows
     * 8, and the older workaround — shelling out to the classic
     * {@code rundll32 shell32.dll,OpenAs_RunDLL <file>} "Open with" chooser —
     * stopped offering an "Always" choice at all on Windows 10 1809+ (only
     * "Just once," which just launches the app against whatever placeholder
     * file was pointed at it — not useful, and surprising). Only the user,
     * through this Settings page, can actually flip the default.</p>
     *
     * @return whether a settings page (deep-linked or generic) was launched
     */
    public static boolean openAppDefaultsSettings() {
        return Shell32.shellOpen("ms-settings:defaultapps?registeredAppUser=" + REGISTERED_APP_NAME)
                || Shell32.shellOpen("ms-settings:defaultapps");
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
