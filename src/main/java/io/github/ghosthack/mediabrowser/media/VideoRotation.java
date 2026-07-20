package io.github.ghosthack.mediabrowser.media;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Bakes a container/display rotation into the BGRA frames of a video stream,
 * so the decoders' coded-orientation output matches what the Apple
 * (AVFoundation) backend produces natively — an upright frame at display
 * dimensions, with any 90&deg; quarter-turn already applied to the pixels.
 *
 * <p>User rotation still lives <em>above</em> the decoders (see
 * {@link RasterFrames#rotateCw}); this composes the lower, decoder-owned seam:
 * the rotation a phone/camera baked into the file's display matrix
 * ({@code rotate}/display-matrix metadata). A {@link io.github.ghosthack.mediabrowser.media.VideoStream}
 * holds one of these (only when the file is rotated), reusing a single native
 * destination buffer and two heap scratch arrays so per-frame playback
 * allocates nothing.</p>
 *
 * <p>Confined to the stream's thread (owns a confined {@link Arena}).</p>
 */
public final class VideoRotation implements AutoCloseable {

    private final int quarterTurns;     // 1..3 (0 never constructs an instance)
    private final int srcW;
    private final int srcH;
    private final int dstW;
    private final int dstH;
    private final byte[] srcBytes;
    private final byte[] dstBytes;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment dst;

    /**
     * @param codedW        the decoder's coded frame width
     * @param codedH        the decoder's coded frame height
     * @param quarterTurnsCw clockwise quarter-turns to bake in (1..3; 0 is invalid —
     *                       callers must skip rotation entirely)
     */
    public VideoRotation(int codedW, int codedH, int quarterTurnsCw) {
        int q = ((quarterTurnsCw % 4) + 4) % 4;
        if (q == 0) {
            throw new IllegalArgumentException("VideoRotation needs a non-zero rotation");
        }
        if (codedW <= 0 || codedH <= 0) {
            throw new IllegalArgumentException("bad coded size: " + codedW + "x" + codedH);
        }
        this.quarterTurns = q;
        this.srcW = codedW;
        this.srcH = codedH;
        boolean swap = (q & 1) != 0;
        this.dstW = swap ? codedH : codedW;
        this.dstH = swap ? codedW : codedH;
        int bytes = Math.multiplyExact(Math.multiplyExact(codedW, codedH), 4);
        this.srcBytes = new byte[bytes];
        this.dstBytes = new byte[bytes];
        this.dst = arena.allocate(bytes);
    }

    /** Display (post-rotation) width — what {@code VideoStream.width()} should report. */
    public int displayWidth() {
        return dstW;
    }

    /** Display (post-rotation) height — what {@code VideoStream.height()} should report. */
    public int displayHeight() {
        return dstH;
    }

    /**
     * Rotates the coded BGRA frame in {@code codedBgra} ({@code codedW × codedH},
     * tightly packed) into this instance's reusable native buffer and returns
     * that buffer ({@code displayWidth × displayHeight}). The result is only
     * valid until the next call.
     */
    public MemorySegment rotate(MemorySegment codedBgra) {
        MemorySegment.copy(codedBgra, ValueLayout.JAVA_BYTE, 0, srcBytes, 0, srcBytes.length);
        RasterFrames.rotateCwInto(srcBytes, srcW, srcH, quarterTurns, dstBytes);
        MemorySegment.copy(dstBytes, 0, dst, ValueLayout.JAVA_BYTE, 0, dstBytes.length);
        return dst;
    }

    @Override
    public void close() {
        arena.close();
    }

    /**
     * Clockwise quarter-turns (0..3) that undo a baked rotation of
     * {@code ccwDegrees} counter-clockwise — the convention shared by FFmpeg's
     * {@code av_display_rotation_get} / JavaCV's {@code getDisplayRotation()}
     * (which report e.g. {@code -90}) and the pure stack's
     * {@code rotationDegreesCcw()} (which reports e.g. {@code 270}); both denote
     * the same rotation mod&nbsp;360. Rounds to the nearest quarter-turn.
     */
    public static int quarterTurnsCw(double ccwDegrees) {
        if (Double.isNaN(ccwDegrees)) {
            return 0;
        }
        long turns = Math.round(-ccwDegrees / 90.0);
        return (int) (((turns % 4) + 4) % 4);
    }
}
