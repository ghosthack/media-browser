package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.AaeSidecar;

/** Pure geometry for the viewer's eight-handle crop editor. */
final class CropEditorGeometry {

    enum Handle {
        NONE, NEW, MOVE,
        NORTH, EAST, SOUTH, WEST,
        NORTH_WEST, NORTH_EAST, SOUTH_EAST, SOUTH_WEST
    }

    private CropEditorGeometry() {}

    /**
     * Resolves a pointer in canvas coordinates to a handle. Corners win over
     * edges, and edges win over the interior, so small crops remain usable.
     */
    static Handle hitTest(AaeSidecar.Crop crop, double viewX, double viewY,
                          double scale, double pointerX, double pointerY,
                          double tolerance) {
        if (crop == null || scale <= 0) return Handle.NONE;
        double left = viewX + crop.x() * scale;
        double top = viewY + crop.y() * scale;
        double right = left + crop.width() * scale;
        double bottom = top + crop.height() * scale;
        boolean nearLeft = Math.abs(pointerX - left) <= tolerance;
        boolean nearRight = Math.abs(pointerX - right) <= tolerance;
        boolean nearTop = Math.abs(pointerY - top) <= tolerance;
        boolean nearBottom = Math.abs(pointerY - bottom) <= tolerance;
        boolean withinX = pointerX >= left - tolerance && pointerX <= right + tolerance;
        boolean withinY = pointerY >= top - tolerance && pointerY <= bottom + tolerance;

        if (nearLeft && nearTop) return Handle.NORTH_WEST;
        if (nearRight && nearTop) return Handle.NORTH_EAST;
        if (nearRight && nearBottom) return Handle.SOUTH_EAST;
        if (nearLeft && nearBottom) return Handle.SOUTH_WEST;
        if (nearTop && withinX) return Handle.NORTH;
        if (nearRight && withinY) return Handle.EAST;
        if (nearBottom && withinX) return Handle.SOUTH;
        if (nearLeft && withinY) return Handle.WEST;
        if (pointerX > left && pointerX < right && pointerY > top && pointerY < bottom) {
            return Handle.MOVE;
        }
        return Handle.NONE;
    }

    /**
     * Applies one drag in master pixels. Resize handles stop at one pixel rather
     * than flipping through their opposite edge; moves preserve size and remain
     * wholly inside the master.
     */
    static AaeSidecar.Crop drag(AaeSidecar.Crop start, Handle handle,
                                int anchorX, int anchorY, int pointerX, int pointerY,
                                int masterWidth, int masterHeight) {
        if (masterWidth <= 0 || masterHeight <= 0 || handle == Handle.NONE) return start;
        if (handle == Handle.NEW) {
            int left = Math.min(anchorX, pointerX);
            int top = Math.min(anchorY, pointerY);
            int right = Math.max(anchorX, pointerX) + 1;
            int bottom = Math.max(anchorY, pointerY) + 1;
            return new AaeSidecar.Crop(left, top, right - left, bottom - top);
        }
        if (start == null) return null;
        if (handle == Handle.MOVE) {
            int x = clamp(start.x() + pointerX - anchorX,
                    0, Math.max(0, masterWidth - start.width()));
            int y = clamp(start.y() + pointerY - anchorY,
                    0, Math.max(0, masterHeight - start.height()));
            return new AaeSidecar.Crop(x, y, start.width(), start.height());
        }

        int left = start.x();
        int top = start.y();
        int right = Math.min(masterWidth, start.x() + start.width());
        int bottom = Math.min(masterHeight, start.y() + start.height());
        switch (handle) {
            case NORTH, NORTH_WEST, NORTH_EAST -> top = clamp(pointerY, 0, bottom - 1);
            case SOUTH, SOUTH_WEST, SOUTH_EAST ->
                    bottom = clamp(pointerY + 1, top + 1, masterHeight);
            default -> { }
        }
        switch (handle) {
            case WEST, NORTH_WEST, SOUTH_WEST -> left = clamp(pointerX, 0, right - 1);
            case EAST, NORTH_EAST, SOUTH_EAST ->
                    right = clamp(pointerX + 1, left + 1, masterWidth);
            default -> { }
        }
        return new AaeSidecar.Crop(left, top, right - left, bottom - top);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
