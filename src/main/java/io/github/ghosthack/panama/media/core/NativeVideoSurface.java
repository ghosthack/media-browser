package io.github.ghosthack.panama.media.core;

import java.lang.foreign.MemorySegment;

/**
 * Explicitly-owned GPU/OS video surface returned without CPU readback.
 *
 * <p>The native handle remains valid until {@link #close()}. Consumers must
 * close every surface exactly once; retaining it can hold a decoder surface
 * pool slot. Heap-output APIs remain the portable fallback.</p>
 */
public interface NativeVideoSurface extends AutoCloseable {

    /** Stable native API identifier such as {@code videotoolbox}. */
    String provider();

    /** Native object kind such as {@code CVPixelBuffer}. */
    String nativeType();

    int width();

    int height();

    /** Provider-specific pixel-format name such as {@code nv12}. */
    String pixelFormat();

    HardwareAdapterInfo adapterInfo();

    /** Borrowed native handle, valid only while this surface is open. */
    MemorySegment nativeHandle();

    boolean isClosed();

    @Override
    void close();
}
