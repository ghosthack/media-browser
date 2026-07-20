package io.github.ghosthack.panama.media.cgimage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * CoreGraphics geometry struct layouts — {@code CGPoint}, {@code CGSize},
 * {@code CGRect}. Mirrors the {@code CGGeometry.h} header from CoreGraphics.
 * All sizes use {@code CGFloat} which is 8 bytes on 64-bit macOS.
 */
public final class CGGeometry {
    private CGGeometry() {}

    /** {@code CGPoint}: two doubles {@code (x, y)}. */
    public static final class CGPoint {
        private CGPoint() {}

        public static final StructLayout LAYOUT = MemoryLayout.structLayout(
                ValueLayout.JAVA_DOUBLE.withName("x"),
                ValueLayout.JAVA_DOUBLE.withName("y")
        );

        public static MemorySegment allocate(Arena arena, double x, double y) {
            MemorySegment p = arena.allocate(LAYOUT);
            p.set(ValueLayout.JAVA_DOUBLE, 0, x);
            p.set(ValueLayout.JAVA_DOUBLE, 8, y);
            return p;
        }
    }

    /** {@code CGSize}: two doubles {@code (width, height)}. */
    public static final class CGSize {
        private CGSize() {}

        public static final StructLayout LAYOUT = MemoryLayout.structLayout(
                ValueLayout.JAVA_DOUBLE.withName("width"),
                ValueLayout.JAVA_DOUBLE.withName("height")
        );

        public static MemorySegment allocate(Arena arena, double width, double height) {
            MemorySegment s = arena.allocate(LAYOUT);
            s.set(ValueLayout.JAVA_DOUBLE, 0, width);
            s.set(ValueLayout.JAVA_DOUBLE, 8, height);
            return s;
        }
    }

    /** {@code CGRect}: {@code CGPoint origin + CGSize size}, 4 doubles total. */
    public static final class CGRect {
        private CGRect() {}

        public static final StructLayout LAYOUT = MemoryLayout.structLayout(
                ValueLayout.JAVA_DOUBLE.withName("x"),
                ValueLayout.JAVA_DOUBLE.withName("y"),
                ValueLayout.JAVA_DOUBLE.withName("width"),
                ValueLayout.JAVA_DOUBLE.withName("height")
        );

        public static MemorySegment allocate(Arena arena,
                                              double x, double y,
                                              double width, double height) {
            MemorySegment r = arena.allocate(LAYOUT);
            r.set(ValueLayout.JAVA_DOUBLE, 0, x);
            r.set(ValueLayout.JAVA_DOUBLE, 8, y);
            r.set(ValueLayout.JAVA_DOUBLE, 16, width);
            r.set(ValueLayout.JAVA_DOUBLE, 24, height);
            return r;
        }
    }
}
