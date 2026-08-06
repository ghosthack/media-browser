package io.github.ghosthack.metadatastripper;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface ContainerStripper {
    void strip(Path input, Path output) throws IOException;
}

