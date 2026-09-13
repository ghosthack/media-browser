package io.github.ghosthack.mediabrowser.win;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Distinguishes a packaged app-image launch (a jpackage-built
 * {@code Media Browser.exe}) from an IDE/{@code mvn javafx:run} launch
 * (plain {@code java.exe}/{@code javaw.exe}), so the file-associations
 * dialog knows whether it has a real, stable executable path to register.
 * Registering a dev-run's {@code java.exe} as the association target would
 * break the moment the JVM/JDK used for that run changes or disappears.
 */
public final class LaunchInvocation {

    private LaunchInvocation() {}

    /**
     * The running executable's path, or empty when this is not a packaged
     * app-image launch (no reliable command, or it resolves to the JDK's
     * {@code java}/{@code javaw} launcher rather than an installed app image).
     */
    public static Optional<Path> resolvePackagedExe() {
        var command = ProcessHandle.current().info().command();
        if (command.isEmpty()) return Optional.empty();
        Path exe = Path.of(command.get());
        Path name = exe.getFileName();
        String lower = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        if (lower.equals("java.exe") || lower.equals("javaw.exe") || lower.equals("java")) {
            return Optional.empty();
        }
        return Files.isRegularFile(exe) ? Optional.of(exe) : Optional.empty();
    }
}
