package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider;
import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider.PdfMrcView;
import io.github.ghosthack.pdfmedia.PdfEntry;
import io.github.ghosthack.pdfmedia.PdfFilter;
import io.github.ghosthack.pdfmedia.PdfRasterDescriptor;
import io.github.ghosthack.pdfmedia.PdfTransform;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Consumer-side decoding and truthful presentation of a virtual PDF MRC layer graph. */
final class PdfMrcMedia {

    private PdfMrcMedia() {}

    static Optional<PdfMrcView> view(Path path) {
        if (!(path.getFileSystem().provider() instanceof StreamFileSystemProvider provider)) {
            return Optional.empty();
        }
        try {
            return provider.pdfMrcView(path);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static MediaProbe probe(Path path, PdfMrcView view) {
        var descriptor = view.descriptor();
        return new MediaProbe(
                path,
                MediaKind.IMAGE,
                "PDF/MRC composite",
                -1,
                -1,
                -1,
                descriptor.width(),
                descriptor.height(),
                null,
                -1,
                null,
                -1,
                -1,
                "3-layer compound raster · high-resolution 1-bit mask grid");
    }

    static VisualResult load(
            Path path,
            PdfMrcView view,
            MediaFacade facade,
            Function<Path, Path> decodable) {
        RasterFrame frame = compose(view, facade, decodable,
                view.descriptor().width(), view.descriptor().height());
        return new VisualResult(probe(path, view), Optional.of(frame));
    }

    static Thumbnail thumbnail(
            PdfMrcView view,
            MediaFacade facade,
            Function<Path, Path> decodable,
            int maxEdge,
            ThumbnailMode mode) {
        int width = view.descriptor().width();
        int height = view.descriptor().height();
        int[] target = mode == ThumbnailMode.FILL
                ? fillWorkingSize(width, height, maxEdge)
                : Thumbnails.fittedSize(width, height, maxEdge);
        RasterFrame composed = compose(view, facade, decodable, target[0], target[1]);
        return new Thumbnail(Optional.of(Thumbnails.scale(composed, maxEdge, mode)),
                MediaKind.IMAGE);
    }

    static Metadata metadata(Path path, PdfMrcView view) {
        var descriptor = view.descriptor();
        var builder = new Metadata.Builder(path);
        String composite = "PDF MRC composite";
        builder.add(composite, "Page", Integer.toString(descriptor.pageIndex() + 1));
        builder.add(composite, "Composition grid",
                descriptor.width() + " × " + descriptor.height() + " (mask resolution)");
        builder.add(composite, "Standalone byte stream", "none — virtual layer graph");
        builder.add(composite, "Layer order", "background → masked foreground");
        builder.add(composite, "Placement", transform(descriptor.placement()));
        addLayer(builder, "PDF MRC background", view.background(), view.backgroundEntry());
        addLayer(builder, "PDF MRC foreground", view.foreground(), view.foregroundEntry());
        addLayer(builder, "PDF MRC hard mask", view.mask(), view.maskEntry());
        return builder.build();
    }

    private static RasterFrame compose(
            PdfMrcView view,
            MediaFacade facade,
            Function<Path, Path> decodable,
            int outputWidth,
            int outputHeight) {
        RasterFrame background = colorLayer(
                "background", view.background(), view.backgroundEntry(), facade, decodable);
        RasterFrame foreground = colorLayer(
                "foreground", view.foreground(), view.foregroundEntry(), facade, decodable);
        BufferedImage mask;
        try {
            StreamFileSystemProvider provider =
                    (StreamFileSystemProvider) view.mask().getFileSystem().provider();
            mask = provider.decodePdfRaster(view.mask()).orElseThrow(
                    () -> new IOException("MRC hard-mask decoder is unavailable"));
        } catch (IOException | RuntimeException e) {
            throw new MediaException("cannot decode PDF MRC hard mask: " + e.getMessage(), e);
        }
        if (mask.getWidth() != view.descriptor().width()
                || mask.getHeight() != view.descriptor().height()) {
            throw new MediaException("PDF MRC hard-mask dimensions changed: expected "
                    + view.descriptor().width() + "x" + view.descriptor().height()
                    + ", got " + mask.getWidth() + "x" + mask.getHeight());
        }
        return composite(background, foreground, mask,
                view.backgroundEntry().raster().orElseThrow().interpolate(),
                view.foregroundEntry().raster().orElseThrow().interpolate(),
                view.maskEntry().raster().orElseThrow(), outputWidth, outputHeight);
    }

    private static RasterFrame colorLayer(
            String role,
            Path path,
            PdfEntry entry,
            MediaFacade facade,
            Function<Path, Path> decodable) {
        VisualResult decoded = facade.loadVisual(decodable.apply(path));
        RasterFrame frame = decoded.frame().orElseThrow(
                () -> new MediaException("PDF MRC " + role + " has no decoded raster"));
        PdfRasterDescriptor descriptor = entry.raster().orElseThrow();
        if (frame.width() != descriptor.width() || frame.height() != descriptor.height()) {
            throw new MediaException("PDF MRC " + role + " dimensions changed: expected "
                    + descriptor.width() + "x" + descriptor.height()
                    + ", got " + frame.width() + "x" + frame.height());
        }
        return frame;
    }

    static RasterFrame composite(
            RasterFrame background,
            RasterFrame foreground,
            BufferedImage mask,
            boolean backgroundInterpolate,
            boolean foregroundInterpolate,
            PdfRasterDescriptor maskDescriptor,
            int outputWidth,
            int outputHeight) {
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new MediaException("invalid PDF MRC output dimensions");
        }
        int length;
        try {
            length = Math.multiplyExact(Math.multiplyExact(outputWidth, outputHeight), 4);
        } catch (ArithmeticException e) {
            throw new MediaException("PDF MRC output is too large", e);
        }
        byte[] output = new byte[length];
        Raster maskRaster = mask.getRaster();
        int sampleBits = maskRaster.getSampleModel().getSampleSize(0);
        long sampleMax = sampleBits >= 31 ? Integer.MAX_VALUE : (1L << sampleBits) - 1;
        boolean reversed = maskDescriptor.decode().size() >= 2
                && maskDescriptor.decode().get(0) > maskDescriptor.decode().get(1);
        for (int y = 0; y < outputHeight; y++) {
            int maskY = Math.min(mask.getHeight() - 1,
                    (int) (((long) y * 2 + 1) * mask.getHeight() / (outputHeight * 2L)));
            for (int x = 0; x < outputWidth; x++) {
                int maskX = Math.min(mask.getWidth() - 1,
                        (int) (((long) x * 2 + 1) * mask.getWidth() / (outputWidth * 2L)));
                double maskValue;
                if (maskDescriptor.interpolate()) {
                    maskValue = sampleBilinear(
                            maskRaster, 0, x, y, outputWidth, outputHeight) / sampleMax;
                } else {
                    maskValue = maskRaster.getSample(maskX, maskY, 0) / (double) sampleMax;
                }
                double foregroundCoverage = reversed ? maskValue : 1 - maskValue;
                if (foregroundCoverage <= 0) {
                    copyScaled(background, backgroundInterpolate,
                            x, y, outputWidth, outputHeight, output);
                } else if (foregroundCoverage >= 1) {
                    copyScaled(foreground, foregroundInterpolate,
                            x, y, outputWidth, outputHeight, output);
                } else {
                    blendScaled(
                            background, backgroundInterpolate,
                            foreground, foregroundInterpolate,
                            foregroundCoverage, x, y, outputWidth, outputHeight, output);
                }
            }
        }
        return new RasterFrame(outputWidth, outputHeight, output);
    }

    private static void copyScaled(
            RasterFrame source,
            boolean interpolate,
            int x,
            int y,
            int outputWidth,
            int outputHeight,
            byte[] output) {
        int destination = (y * outputWidth + x) * 4;
        if (!interpolate) {
            int sourceX = Math.min(source.width() - 1,
                    (int) (((long) x * 2 + 1) * source.width() / (outputWidth * 2L)));
            int sourceY = Math.min(source.height() - 1,
                    (int) (((long) y * 2 + 1) * source.height() / (outputHeight * 2L)));
            System.arraycopy(source.bgra(),
                    (sourceY * source.width() + sourceX) * 4, output, destination, 4);
            return;
        }
        for (int channel = 0; channel < 4; channel++) {
            output[destination + channel] = (byte) Math.round(
                    sampleBilinear(source, channel, x, y, outputWidth, outputHeight));
        }
    }

    private static void blendScaled(
            RasterFrame background,
            boolean backgroundInterpolate,
            RasterFrame foreground,
            boolean foregroundInterpolate,
            double foregroundCoverage,
            int x,
            int y,
            int outputWidth,
            int outputHeight,
            byte[] output) {
        int destination = (y * outputWidth + x) * 4;
        for (int channel = 0; channel < 4; channel++) {
            double backgroundValue = sample(
                    background, backgroundInterpolate, channel,
                    x, y, outputWidth, outputHeight);
            double foregroundValue = sample(
                    foreground, foregroundInterpolate, channel,
                    x, y, outputWidth, outputHeight);
            output[destination + channel] = (byte) Math.round(
                    backgroundValue * (1 - foregroundCoverage)
                            + foregroundValue * foregroundCoverage);
        }
    }

    private static double sample(
            RasterFrame source,
            boolean interpolate,
            int channel,
            int x,
            int y,
            int outputWidth,
            int outputHeight) {
        if (interpolate) {
            return sampleBilinear(source, channel, x, y, outputWidth, outputHeight);
        }
        int sourceX = Math.min(source.width() - 1,
                (int) (((long) x * 2 + 1) * source.width() / (outputWidth * 2L)));
        int sourceY = Math.min(source.height() - 1,
                (int) (((long) y * 2 + 1) * source.height() / (outputHeight * 2L)));
        return source.bgra()[(sourceY * source.width() + sourceX) * 4 + channel] & 0xff;
    }

    private static double sampleBilinear(
            RasterFrame source,
            int channel,
            int x,
            int y,
            int outputWidth,
            int outputHeight) {
        double sourceX = (x + 0.5) * source.width() / outputWidth - 0.5;
        double sourceY = (y + 0.5) * source.height() / outputHeight - 0.5;
        int x0 = Math.max(0, Math.min(source.width() - 1, (int) Math.floor(sourceX)));
        int y0 = Math.max(0, Math.min(source.height() - 1, (int) Math.floor(sourceY)));
        int x1 = Math.min(source.width() - 1, x0 + 1);
        int y1 = Math.min(source.height() - 1, y0 + 1);
        double fx = Math.max(0, sourceX - x0);
        double fy = Math.max(0, sourceY - y0);
        byte[] pixels = source.bgra();
        int p00 = (y0 * source.width() + x0) * 4;
        int p10 = (y0 * source.width() + x1) * 4;
        int p01 = (y1 * source.width() + x0) * 4;
        int p11 = (y1 * source.width() + x1) * 4;
        double top = (pixels[p00 + channel] & 0xff) * (1 - fx)
                + (pixels[p10 + channel] & 0xff) * fx;
        double bottom = (pixels[p01 + channel] & 0xff) * (1 - fx)
                + (pixels[p11 + channel] & 0xff) * fx;
        return top * (1 - fy) + bottom * fy;
    }

    private static double sampleBilinear(
            Raster raster,
            int band,
            int x,
            int y,
            int outputWidth,
            int outputHeight) {
        double sourceX = (x + 0.5) * raster.getWidth() / outputWidth - 0.5;
        double sourceY = (y + 0.5) * raster.getHeight() / outputHeight - 0.5;
        int x0 = Math.max(0, Math.min(raster.getWidth() - 1, (int) Math.floor(sourceX)));
        int y0 = Math.max(0, Math.min(raster.getHeight() - 1, (int) Math.floor(sourceY)));
        int x1 = Math.min(raster.getWidth() - 1, x0 + 1);
        int y1 = Math.min(raster.getHeight() - 1, y0 + 1);
        double fx = Math.max(0, sourceX - x0);
        double fy = Math.max(0, sourceY - y0);
        double top = raster.getSample(x0, y0, band) * (1 - fx)
                + raster.getSample(x1, y0, band) * fx;
        double bottom = raster.getSample(x0, y1, band) * (1 - fx)
                + raster.getSample(x1, y1, band) * fx;
        return top * (1 - fy) + bottom * fy;
    }

    private static int[] fillWorkingSize(int width, int height, int maxEdge) {
        if (maxEdge <= 0 || Math.min(width, height) <= maxEdge) {
            return new int[] {width, height};
        }
        double scale = maxEdge / (double) Math.min(width, height);
        return new int[] {
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))
        };
    }

    private static void addLayer(
            Metadata.Builder builder,
            String group,
            Path mountedPath,
            PdfEntry entry) {
        PdfRasterDescriptor raster = entry.raster().orElseThrow();
        builder.add(group, "Entry index", Integer.toString(entry.index()));
        builder.add(group, "Mounted name", mountedPath.getFileName().toString());
        builder.add(group, "Encoding", encoding(raster));
        builder.add(group, "PDF filters", raster.filters().stream()
                .map(PdfFilter::pdfName).collect(Collectors.joining(" → ")));
        builder.add(group, "Native dimensions", raster.width() + " × " + raster.height());
        builder.add(group, "Bits per component",
                raster.bitsPerComponent() == 0
                        ? "codec-defined" : Integer.toString(raster.bitsPerComponent()));
        builder.add(group, "Stencil / image mask", Boolean.toString(raster.stencil()));
        builder.add(group, "Interpolate", Boolean.toString(raster.interpolate()));
        raster.colorSpace().ifPresent(value -> builder.add(group, "Color space", value));
        entry.declaredSize().ifPresent(value ->
                builder.add(group, "Encoded bytes", Long.toString(value)));
    }

    private static String encoding(PdfRasterDescriptor raster) {
        List<PdfFilter.Decoder> stack = raster.decoderStack();
        if (stack.equals(List.of(PdfFilter.Decoder.JPEG))) return "JPEG";
        if (stack.equals(List.of(PdfFilter.Decoder.JPEG_2000))) return "JPEG 2000";
        if (stack.equals(List.of(PdfFilter.Decoder.JBIG2))) return "JBIG2";
        return raster.filters().stream()
                .map(PdfFilter::pdfName).collect(Collectors.joining(" → "));
    }

    private static String transform(PdfTransform transform) {
        return String.format(Locale.ROOT, "[%.5f %.5f %.5f %.5f %.5f %.5f]",
                transform.a(), transform.b(), transform.c(),
                transform.d(), transform.e(), transform.f());
    }
}
