package io.github.ghosthack.panama.media.mediafoundation;

import io.github.ghosthack.panama.media.comruntime.Ole32;
import io.github.ghosthack.panama.media.core.HardwareAdapterInfo;
import io.github.ghosthack.panama.media.core.NativeVideoSurface;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Caller-owned D3D11 texture copied from a Media Foundation decoder output. */
public final class MfD3d11Surface implements NativeVideoSurface {

    private final MemorySegment texture;
    private final int width;
    private final int height;
    private final String pixelFormat;
    private final HardwareAdapterInfo adapterInfo;
    private final AtomicBoolean closed = new AtomicBoolean();

    MfD3d11Surface(
            MemorySegment texture,
            int width,
            int height,
            String pixelFormat,
            HardwareAdapterInfo adapterInfo) {
        this.texture = Objects.requireNonNull(texture, "texture");
        if (MemorySegment.NULL.equals(texture)) {
            throw new IllegalArgumentException("texture must not be NULL");
        }
        this.width = width;
        this.height = height;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.adapterInfo = Objects.requireNonNull(adapterInfo, "adapterInfo");
    }

    @Override
    public String provider() {
        return "mediafoundation";
    }

    @Override
    public String nativeType() {
        return "ID3D11Texture2D";
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public String pixelFormat() {
        return pixelFormat;
    }

    @Override
    public HardwareAdapterInfo adapterInfo() {
        return adapterInfo;
    }

    @Override
    public MemorySegment nativeHandle() {
        return closed.get() ? MemorySegment.NULL : texture;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Ole32.release(texture);
        }
    }
}
