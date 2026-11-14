package io.github.ghosthack.panama.media.opengl;

import java.lang.foreign.MemorySegment;

/**
 * Resolves an OpenGL entry point by name for the current context — the
 * platform's {@code *GetProcAddress}, the framework's exports, or both.
 */
@FunctionalInterface
public interface ProcLookup {

    /** The function's address, or {@code null}/{@link MemorySegment#NULL} when absent. */
    MemorySegment find(String name);
}
