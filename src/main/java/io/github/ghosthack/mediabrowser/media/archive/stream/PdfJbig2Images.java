package io.github.ghosthack.mediabrowser.media.archive.stream;

import io.github.ghosthack.pdfmedia.PdfArchive;
import io.github.ghosthack.pdfmedia.PdfEntry;
import io.github.ghosthack.pdfmedia.PdfRasterDescriptor;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Consumer-side decoding of a raw PDF JBIG2 raster.
 *
 * <p>{@code pdf-media} deliberately exposes the original bitstream plus its
 * immutable decoder descriptor. This adapter is application policy: it turns
 * that physical object into PNG bytes for ordinary backends, or returns the
 * decoded raster directly when an MRC compositor consumes it in memory.</p>
 */
final class PdfJbig2Images {

    private PdfJbig2Images() {}

    static InputStream openPng(PdfArchive archive, PdfEntry entry) throws IOException {
        BufferedImage image = decode(archive, entry);
        try (ByteArrayOutputStream png = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", png)) {
                throw new IOException("no PNG writer is available");
            }
            return new ByteArrayInputStream(png.toByteArray());
        }
    }

    static BufferedImage decode(PdfArchive archive, PdfEntry entry) throws IOException {
        PdfRasterDescriptor descriptor = entry.raster().orElseThrow(
                () -> new IOException("JBIG2 entry has no raster descriptor: " + entry.name()));
        ImageReader decoder = decoder();
        try (InputStream encoded = encodedStream(archive, entry, descriptor);
             ImageInputStream input = new MemoryCacheImageInputStream(encoded)) {
            decoder.setInput(input, false, true);
            int encodedWidth = decoder.getWidth(0);
            int encodedHeight = decoder.getHeight(0);
            if (encodedWidth != descriptor.width()
                    || encodedHeight != descriptor.height()) {
                throw dimensionsChanged(entry, descriptor, encodedWidth, encodedHeight);
            }
            BufferedImage image = decoder.read(0, decoder.getDefaultReadParam());
            if (image.getWidth() != descriptor.width()
                    || image.getHeight() != descriptor.height()) {
                throw dimensionsChanged(
                        entry, descriptor, image.getWidth(), image.getHeight());
            }
            return image;
        } catch (RuntimeException e) {
            throw new IOException("cannot decode JBIG2 raster " + entry.name(), e);
        } finally {
            decoder.dispose();
        }
    }

    private static IOException dimensionsChanged(
            PdfEntry entry, PdfRasterDescriptor descriptor, int width, int height) {
        return new IOException("JBIG2 dimensions differ from the bounded PDF descriptor for "
                + entry.name() + ": expected " + descriptor.width() + "x"
                + descriptor.height() + ", got " + width + "x" + height);
    }

    /**
     * PDF JBIG2 global segments precede the page-local segments. Concatenating
     * them is the same stream shape PDFBox feeds its JBIG2 ImageIO decoder.
     */
    private static InputStream encodedStream(
            PdfArchive archive, PdfEntry entry, PdfRasterDescriptor descriptor)
            throws IOException {
        InputStream local = archive.openStream(entry);
        if (descriptor.jbig2GlobalsEntryIndex().isEmpty()) return local;
        try {
            int index = descriptor.jbig2GlobalsEntryIndex().getAsInt();
            PdfEntry globals = archive.entries().get(index);
            return new SequenceInputStream(archive.openStream(globals), local);
        } catch (IOException | RuntimeException e) {
            try {
                local.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static ImageReader decoder() throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JBIG2");
        if (!readers.hasNext()) {
            throw new IOException("JBIG2 ImageIO decoder is unavailable");
        }
        return readers.next();
    }
}
