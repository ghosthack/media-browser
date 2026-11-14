package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.gl.VImageVideoConverter;
import io.github.ghosthack.mediabrowser.media.VideoStream;

import javafx.scene.image.ImageView;

import java.lang.foreign.MemorySegment;
import java.util.function.Supplier;

/**
 * macOS vImage sink: no OpenGL context. VideoToolbox NV12 frames are converted
 * (and rotated) by Accelerate straight into a pooled {@code PixelBuffer} slot;
 * CPU BGRA frames are rotated by {@code vImageRotate90_ARGB8888}. Selected
 * only by the explicit {@code Presenter.VIMAGE} setting, so {@link #begin}
 * throws off macOS and playback fails loudly rather than substituting.
 *
 * <p>The output is the display-oriented frame (container rotation, then the
 * user's {@code quarterTurns}), sized like the GL presenters' readback.
 * A surface vImage cannot take (not NV12, e.g. 10-bit P010) is declined and
 * reaches {@link #frame} as CPU BGRA, as with the CGL presenter.</p>
 */
final class VImageVideoPresenter extends PooledFramePresenter {

    private final int quarterTurns;
    private final Supplier<VImageVideoConverter> converters;
    private final VideoSlotPool.Factory pools;
    private VImageVideoConverter converter;

    VImageVideoPresenter(ImageView target, int quarterTurns) {
        this(quarterTurns, VImageVideoConverter::new,
                (w, h) -> VideoSlotPool.forImageView(target, w, h));
    }

    /** Test seam: the pool is injected, so no FX toolkit is needed. */
    VImageVideoPresenter(int quarterTurns, Supplier<VImageVideoConverter> converters,
                         VideoSlotPool.Factory pools) {
        this.quarterTurns = quarterTurns;
        this.converters = converters;
        this.pools = pools;
    }

    @Override
    public void begin(int width, int height, long durationMicros) {
        // Built on the playback thread, which owns its scratch memory; throws off macOS.
        VImageVideoConverter built = converters.get();
        boolean swap = (Math.floorMod(quarterTurns, 4) & 1) == 1;
        try {
            usePool(pools.create(swap ? height : width, swap ? width : height));
        } catch (Throwable t) {
            built.close();
            throw t;
        }
        converter = built;
    }

    @Override
    public void frame(MemorySegment bgra, int width, int height, long positionMicros) {
        VideoSlotPool pool = pool();
        VideoPresenter.Slot slot = pool.acquire();
        if (slot == null) return; // the FX/render thread is behind: drop this frame
        converter.rotateBgra(bgra, width, height, quarterTurns, slot.buffer);
        pool.present(slot);
    }

    @Override
    public boolean gpuFrame(VideoStream.GpuFrame frame, long positionMicros) {
        VideoSlotPool pool = pool();
        VideoPresenter.Slot slot = pool.acquire();
        if (slot == null) return true; // dropping a frame is handling it
        // The surface is in coded orientation: container rotation, then the user's.
        if (!converter.convertSurface(frame.cvPixelBuffer(), frame.codedWidth(), frame.codedHeight(),
                frame.containerQuarterTurnsCw() + quarterTurns, frame.bt709(), frame.fullRange(),
                slot.buffer)) {
            pool.abandon(slot);
            return false; // not NV12 → BGRA path
        }
        pool.present(slot);
        return true;
    }

    @Override
    public void close() {
        revoke();
        if (converter != null) {
            converter.close();
            converter = null;
        }
    }
}
