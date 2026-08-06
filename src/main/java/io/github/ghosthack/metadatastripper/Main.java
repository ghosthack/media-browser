package io.github.ghosthack.metadatastripper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        boolean force = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "-h", "--help" -> {
                    printUsage(out);
                    return 0;
                }
                case "-f", "--force" -> force = true;
                default -> positional.add(arg);
            }
        }
        if (positional.size() != 1) {
            printUsage(err);
            return 2;
        }

        try {
            Path output = new MetadataStripper().strip(Path.of(positional.getFirst()), force);
            out.println(output);
            return 0;
        } catch (UnsupportedFormatException e) {
            err.println(e.getMessage());
            return 3;
        } catch (IllegalArgumentException | IOException e) {
            err.println("Could not strip metadata: " + e.getMessage());
            return 1;
        }
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: java -jar metadata-stripper.jar [--force] <input-file>");
        stream.println("Creates <original-name>_0m.<original-extension> beside the input.");
        stream.println("Supported: JPEG, PNG/APNG, GIF, WebP, JPEG XL, MP3, FLAC, WAV.");
    }
}

