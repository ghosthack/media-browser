package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.RasterFrame;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/** Conversion from facade raster frames to JavaFX images. */
public final class FxImages {

    private FxImages() {}

    public static WritableImage toImage(RasterFrame frame) {
        var image = new WritableImage(frame.width(), frame.height());
        image.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                PixelFormat.getByteBgraInstance(), frame.bgra(), 0, frame.width() * 4);
        return image;
    }
}
