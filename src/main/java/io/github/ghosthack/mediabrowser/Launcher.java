package io.github.ghosthack.mediabrowser;

/**
 * Native-package entry point.
 *
 * <p>The JDK launcher treats a main class that directly extends
 * {@code javafx.application.Application} specially and expects JavaFX on the
 * module path. Our non-modular jpackage layout intentionally keeps all
 * dependency jars on the classpath, so its main class must not itself extend
 * Application. Development runs continue to use {@link App} through the
 * JavaFX Maven plugin.</p>
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        // Release CI invokes the real native launcher with this private
        // diagnostic argument. It exercises the shared AWT-to-BGRA raster
        // bridge, initializes the default bundled backend and exits without
        // starting JavaFX, proving that jpackage's runtime, classpath, JVM
        // options and native classifier jars work together.
        if (args.length == 1 && "--package-smoke".equals(args[0])) {
            SmokeTest.main(new String[0]);
            return;
        }
        App.main(args);
    }
}
