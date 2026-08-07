package io.github.ghosthack.mediabrowser.media.archive.stream;

import io.github.ghosthack.pdfmedia.PdfArchive;
import io.github.ghosthack.pdfmedia.PdfEntry;
import io.github.ghosthack.pdfmedia.PdfFilter;
import io.github.ghosthack.pdfmedia.PdfRasterDescriptor;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import javax.imageio.ImageIO;

/** Consumer-side presentation decoder for self-contained Flate PDF rasters. */
final class PdfFlateImages {

    private PdfFlateImages() {}

    static boolean supports(PdfEntry entry) {
        if (!entry.isRaster()) return false;
        return supports(entry.raster().orElseThrow());
    }

    static boolean supports(PdfRasterDescriptor raster) {
        if (!raster.decoderStack().equals(List.of(PdfFilter.Decoder.FLATE))) return false;
        if (raster.stencil() || raster.hardMaskEntryIndex().isPresent()
                || raster.softMaskEntryIndex().isPresent()
                || !raster.colorKeyMask().isEmpty()) {
            return false;
        }
        if (raster.bitsPerComponent() != 1 && raster.bitsPerComponent() != 2
                && raster.bitsPerComponent() != 4 && raster.bitsPerComponent() != 8
                && raster.bitsPerComponent() != 16) {
            return false;
        }
        String colorSpace = normalizedColorSpace(raster);
        if (!colorSpace.equals("devicegray") && !colorSpace.equals("devicergb")
                && !colorSpace.equals("devicecmyk")) {
            return false;
        }
        int predictor = parameter(raster, "Predictor", 1);
        return predictor == 1 || predictor == 2 || (predictor >= 10 && predictor <= 15);
    }

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
        if (!supports(entry)) {
            throw new IOException("unsupported Flate PDF raster: " + entry.name());
        }
        PdfRasterDescriptor descriptor = entry.raster().orElseThrow();
        int components = components(descriptor);
        int bits = descriptor.bitsPerComponent();
        int rowBytes;
        try {
            long rowBits = Math.multiplyExact(
                    Math.multiplyExact((long) descriptor.width(), components), bits);
            rowBytes = Math.toIntExact((rowBits + 7) / 8);
            Math.multiplyExact(rowBytes, descriptor.height());
        } catch (ArithmeticException e) {
            throw new IOException("Flate PDF raster is too large: " + entry.name(), e);
        }

        int predictor = parameter(descriptor, "Predictor", 1);
        boolean pngPredictor = predictor >= 10;
        int encodedRowBytes = rowBytes + (predictor == 15 ? 1 : 0);
        int inflatedLength;
        try {
            inflatedLength = Math.multiplyExact(encodedRowBytes, descriptor.height());
        } catch (ArithmeticException e) {
            throw new IOException("Flate PDF raster is too large: " + entry.name(), e);
        }

        byte[] inflated = new byte[inflatedLength];
        try (InputStream physical = archive.openStream(entry);
             InputStream input = new InflaterInputStream(physical)) {
            int offset = 0;
            while (offset < inflated.length) {
                int read = input.read(inflated, offset, inflated.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != inflated.length || input.read() != -1) {
                throw new IOException("inflated byte count differs from the bounded PDF "
                        + "descriptor for " + entry.name() + ": expected "
                        + inflated.length + ", got "
                        + (offset == inflated.length ? "more than " + inflated.length : offset));
            }
        } catch (IOException e) {
            throw new IOException("cannot inflate PDF raster " + entry.name()
                    + ": " + e.getMessage(), e);
        }

        byte[] samples = pngPredictor
                ? undoPngPredictor(
                        inflated, descriptor.height(), rowBytes, components, bits, predictor)
                : inflated;
        return render(samples, descriptor, components, predictor == 2, rowBytes);
    }

    private static BufferedImage render(
            byte[] samples,
            PdfRasterDescriptor descriptor,
            int components,
            boolean tiffPredictor,
            int rowBytes) throws IOException {
        int width = descriptor.width();
        int height = descriptor.height();
        int bits = descriptor.bitsPerComponent();
        long sampleMax = (1L << bits) - 1;
        double[] decode = decodeArray(descriptor, components);
        int[] rgb;
        try {
            rgb = new int[Math.multiplyExact(width, height)];
        } catch (ArithmeticException | OutOfMemoryError e) {
            throw new IOException("Flate PDF raster is too large: " + descriptor.width()
                    + "x" + descriptor.height(), e);
        }
        long[] previous = new long[components];
        double[] values = new double[components];
        for (int y = 0; y < height; y++) {
            java.util.Arrays.fill(previous, 0);
            int rowOffset = y * rowBytes;
            for (int x = 0; x < width; x++) {
                for (int component = 0; component < components; component++) {
                    int sampleIndex = x * components + component;
                    long sample = packedSample(samples, rowOffset, sampleIndex, bits);
                    if (tiffPredictor) {
                        sample = (sample + previous[component]) & sampleMax;
                        previous[component] = sample;
                    }
                    double normalized = sample / (double) sampleMax;
                    values[component] = clamp(decode[component * 2]
                            + normalized * (decode[component * 2 + 1]
                            - decode[component * 2]));
                }
                rgb[y * width + x] = toRgb(values);
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, rgb, 0, width);
        return image;
    }

    private static byte[] undoPngPredictor(
            byte[] encoded,
            int height,
            int rowBytes,
            int components,
            int bits,
            int predictor)
            throws IOException {
        byte[] decoded = new byte[Math.multiplyExact(rowBytes, height)];
        int bytesPerPixel = Math.max(1, (components * bits + 7) / 8);
        int source = 0;
        for (int y = 0; y < height; y++) {
            int filter = predictor == 15 ? encoded[source++] & 0xff : predictor - 10;
            if (filter > 4) throw new IOException("invalid PNG predictor filter " + filter);
            int row = y * rowBytes;
            int prior = row - rowBytes;
            for (int x = 0; x < rowBytes; x++) {
                int raw = encoded[source++] & 0xff;
                int left = x >= bytesPerPixel ? decoded[row + x - bytesPerPixel] & 0xff : 0;
                int up = y > 0 ? decoded[prior + x] & 0xff : 0;
                int upperLeft = y > 0 && x >= bytesPerPixel
                        ? decoded[prior + x - bytesPerPixel] & 0xff : 0;
                int value = switch (filter) {
                    case 0 -> raw;
                    case 1 -> raw + left;
                    case 2 -> raw + up;
                    case 3 -> raw + ((left + up) >>> 1);
                    case 4 -> raw + paeth(left, up, upperLeft);
                    default -> throw new AssertionError(filter);
                };
                decoded[row + x] = (byte) value;
            }
        }
        return decoded;
    }

    private static int paeth(int left, int up, int upperLeft) {
        int estimate = left + up - upperLeft;
        int leftDistance = Math.abs(estimate - left);
        int upDistance = Math.abs(estimate - up);
        int upperLeftDistance = Math.abs(estimate - upperLeft);
        if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) return left;
        return upDistance <= upperLeftDistance ? up : upperLeft;
    }

    private static long packedSample(byte[] bytes, int rowOffset, int index, int bits) {
        long bitOffset = (long) index * bits;
        int byteOffset = rowOffset + (int) (bitOffset >>> 3);
        int shift = 8 - bits - (int) (bitOffset & 7);
        if (bits <= 8) return (bytes[byteOffset] >>> shift) & ((1 << bits) - 1);
        return ((bytes[byteOffset] & 0xffL) << 8) | (bytes[byteOffset + 1] & 0xffL);
    }

    private static double[] decodeArray(PdfRasterDescriptor descriptor, int components) {
        double[] result = new double[components * 2];
        List<Double> declared = descriptor.decode();
        for (int component = 0; component < components; component++) {
            result[component * 2] = declared.size() == components * 2
                    ? declared.get(component * 2) : 0;
            result[component * 2 + 1] = declared.size() == components * 2
                    ? declared.get(component * 2 + 1) : 1;
        }
        return result;
    }

    private static int toRgb(double[] components) {
        double red;
        double green;
        double blue;
        if (components.length == 1) {
            red = green = blue = components[0];
        } else if (components.length == 3) {
            red = components[0];
            green = components[1];
            blue = components[2];
        } else {
            double black = components[3];
            red = 1 - Math.min(1, components[0] + black);
            green = 1 - Math.min(1, components[1] + black);
            blue = 1 - Math.min(1, components[2] + black);
        }
        return (toByte(red) << 16) | (toByte(green) << 8) | toByte(blue);
    }

    private static int toByte(double value) {
        return (int) Math.round(clamp(value) * 255);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int components(PdfRasterDescriptor descriptor) {
        return switch (normalizedColorSpace(descriptor)) {
            case "devicegray" -> 1;
            case "devicergb" -> 3;
            case "devicecmyk" -> 4;
            default -> throw new IllegalArgumentException("unsupported color space");
        };
    }

    private static String normalizedColorSpace(PdfRasterDescriptor descriptor) {
        return descriptor.colorSpace().orElse("").replace("/", "")
                .toLowerCase(Locale.ROOT);
    }

    private static int parameter(
            PdfRasterDescriptor descriptor, String name, int defaultValue) {
        Map<String, Object> parameters = descriptor.filters().getFirst().parameters();
        Object value = parameters.get(name);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
