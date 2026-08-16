package io.github.ghosthack.mediabrowser.ui;

import io.github.ghosthack.mediabrowser.media.RasterFrame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Writes a composed display raster as a clean, metadata-free PNG. */
public final class BakedPngExporter {

    private BakedPngExporter() {}

    /**
     * The default save name: remove the source's final extension, when present,
     * then append {@code -export.png}. A leading dot by itself is part of a
     * dotfile name rather than an extension.
     */
    public static String suggestedFileName(Path source) {
        Objects.requireNonNull(source, "source");
        Path fileName = source.getFileName();
        String name = fileName == null ? source.toString() : fileName.toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "-export.png";
    }

    /** Encodes {@code frame} to {@code target}, replacing only after a complete write. */
    public static void write(RasterFrame frame, Path target) throws IOException {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(target, "target");

        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IOException("Export folder does not exist: " + directory);
        }

        Path temporary = Files.createTempFile(directory,
                "." + absolute.getFileName() + "-", ".tmp");
        boolean moved = false;
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                if (!ImageIO.write(image(frame), "png", out)) {
                    throw new IOException("No PNG writer is available");
                }
            }
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    /** Converts the app's tightly packed straight-alpha BGRA into PNG ARGB pixels. */
    private static BufferedImage image(RasterFrame frame) {
        BufferedImage image = new BufferedImage(
                frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB);
        int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        byte[] bgra = frame.bgra();
        for (int pixel = 0, offset = 0; pixel < argb.length; pixel++, offset += 4) {
            int blue = bgra[offset] & 0xFF;
            int green = bgra[offset + 1] & 0xFF;
            int red = bgra[offset + 2] & 0xFF;
            int alpha = bgra[offset + 3] & 0xFF;
            argb[pixel] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        return image;
    }
}
