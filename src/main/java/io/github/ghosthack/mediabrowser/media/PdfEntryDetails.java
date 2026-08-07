package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider;
import io.github.ghosthack.pdfmedia.PdfEntry;
import io.github.ghosthack.pdfmedia.PdfFilter;
import io.github.ghosthack.pdfmedia.PdfMrcComposite;
import io.github.ghosthack.pdfmedia.PdfRasterDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Preserves PDF object provenance when a private standalone rendition is used
 * to feed a media backend.
 */
final class PdfEntryDetails {
    private PdfEntryDetails() {}

    static Optional<PdfEntry> entry(Path path) {
        if (!(path.getFileSystem().provider() instanceof StreamFileSystemProvider)) {
            return Optional.empty();
        }
        try {
            Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
            return key instanceof PdfEntry pdf ? Optional.of(pdf) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<PdfMrcComposite> mrc(Path path) {
        if (!(path.getFileSystem().provider() instanceof StreamFileSystemProvider)) {
            return Optional.empty();
        }
        try {
            Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
            return key instanceof PdfMrcComposite composite
                    ? Optional.of(composite) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<MediaKind> viewableKind(Path path) {
        if (mrc(path).isPresent()) return Optional.of(MediaKind.IMAGE);
        return entry(path)
                .filter(PdfEntry::isRaster)
                .filter(pdf -> {
                    PdfRasterDescriptor raster = pdf.raster().orElseThrow();
                    List<PdfFilter.Decoder> stack = raster.decoderStack();
                    return stack.equals(List.of(PdfFilter.Decoder.JPEG))
                            || stack.equals(List.of(PdfFilter.Decoder.JPEG_2000))
                            || stack.equals(List.of(PdfFilter.Decoder.JBIG2))
                            || isViewableFlateRaster(raster)
                            || isFlateWrappedStandaloneRaster(raster);
                })
                .map(pdf -> MediaKind.IMAGE);
    }

    private static boolean isViewableFlateRaster(PdfRasterDescriptor raster) {
        return StreamFileSystemProvider.supportsPdfFlateRaster(raster);
    }

    private static boolean isFlateWrappedStandaloneRaster(PdfRasterDescriptor raster) {
        List<PdfFilter.Decoder> stack = raster.decoderStack();
        if (stack.size() != 2 || stack.getFirst() != PdfFilter.Decoder.FLATE) return false;
        Object predictor = raster.filters().getFirst().parameters().get("Predictor");
        if (predictor instanceof Number number && number.intValue() != 1) return false;
        return stack.getLast() == PdfFilter.Decoder.JPEG
                || stack.getLast() == PdfFilter.Decoder.JPEG_2000;
    }

    static MediaProbe preserveProbe(Path source, MediaProbe decoded) {
        Optional<PdfEntry> found = entry(source);
        if (found.isEmpty() || !found.get().isRaster()) {
            return remapPath(source, decoded);
        }
        PdfEntry pdf = found.get();
        PdfRasterDescriptor raster = pdf.raster().orElseThrow();
        return new MediaProbe(
                source,
                MediaKind.IMAGE,
                "PDF/" + encoding(raster),
                pdf.declaredSize().orElseGet(() -> sizeOf(source)),
                decoded.durationMicros(),
                decoded.bitRate(),
                raster.width(),
                raster.height(),
                null,
                decoded.frameRate(),
                decoded.audioCodec(),
                decoded.sampleRate(),
                decoded.channels(),
                pixelDescription(raster),
                decoded.colorProfile());
    }

    static Metadata preserveMetadata(Path source, Metadata decoded) {
        Optional<PdfEntry> found = entry(source);
        if (found.isEmpty()) return remapPath(source, decoded);

        PdfEntry pdf = found.get();
        var builder = new Metadata.Builder(source);
        String group = pdf.isRaster() ? "PDF raster source" : "PDF embedded source";
        builder.add(group, "Entry index", Integer.toString(pdf.index()));
        builder.add(group, "Entry kind", pdf.kind().toString());
        builder.add(group, "Origins", pdf.origins().stream()
                .map(Enum::name).sorted().collect(Collectors.joining(", ")));
        pdf.mediaType().ifPresent(value -> builder.add(group, "Media type", value));
        pdf.declaredSize().ifPresent(value ->
                builder.add(group, "Declared bytes", Long.toString(value)));

        pdf.raster().ifPresent(raster -> addRaster(builder, group, raster));
        for (Metadata.Group decodedGroup : decoded.groups()) {
            String name = "Decoded " + decodedGroup.name();
            for (Metadata.Entry field : decodedGroup.entries()) {
                builder.add(name, field);
            }
        }
        return builder.build();
    }

    private static void addRaster(Metadata.Builder builder, String group,
                                  PdfRasterDescriptor raster) {
        builder.add(group, "Encoding", encoding(raster));
        builder.add(group, "PDF filters", raster.filters().stream()
                .map(PdfFilter::pdfName).collect(Collectors.joining(" → ")));
        builder.add(group, "Dimensions", raster.width() + " × " + raster.height());
        builder.add(group, "Bits per component",
                raster.bitsPerComponent() == 0
                        ? "codec-defined" : Integer.toString(raster.bitsPerComponent()));
        builder.add(group, "Stencil / image mask", Boolean.toString(raster.stencil()));
        builder.add(group, "Interpolate", Boolean.toString(raster.interpolate()));
        raster.colorSpace().ifPresent(value -> builder.add(group, "Color space", value));
        if (!raster.decode().isEmpty()) {
            builder.add(group, "Decode", raster.decode().toString());
        }
        if (!raster.colorKeyMask().isEmpty()) {
            builder.add(group, "Color-key mask", raster.colorKeyMask().toString());
        }
        addIndex(builder, group, "Hard mask entry", raster.hardMaskEntryIndex());
        addIndex(builder, group, "Soft mask entry", raster.softMaskEntryIndex());
        addIndex(builder, group, "JBIG2 globals entry", raster.jbig2GlobalsEntryIndex());
        if (raster.decoderStack().equals(List.of(PdfFilter.Decoder.JBIG2))) {
            builder.add(group, "Presentation", "PNG (decoder-only rendition)");
        } else if (isViewableFlateRaster(raster)) {
            builder.add(group, "Presentation", "PNG (decoded Flate raster)");
        }
        for (int i = 0; i < raster.filters().size(); i++) {
            PdfFilter filter = raster.filters().get(i);
            if (!filter.parameters().isEmpty()) {
                builder.add(group, "Filter " + (i + 1) + " parameters",
                        filter.parameters().toString());
            }
        }
    }

    private static void addIndex(Metadata.Builder builder, String group, String key,
                                 OptionalInt value) {
        if (value.isPresent()) builder.add(group, key, Integer.toString(value.getAsInt()));
    }

    private static MediaProbe remapPath(Path source, MediaProbe decoded) {
        if (source.equals(decoded.path())) return decoded;
        return new MediaProbe(source, decoded.kind(), decoded.container(), decoded.fileSize(),
                decoded.durationMicros(), decoded.bitRate(), decoded.width(), decoded.height(),
                decoded.videoCodec(), decoded.frameRate(), decoded.audioCodec(),
                decoded.sampleRate(), decoded.channels(), decoded.pixelDescription(),
                decoded.colorProfile());
    }

    private static Metadata remapPath(Path source, Metadata decoded) {
        return source.equals(decoded.path())
                ? decoded : new Metadata(source, decoded.provider(), decoded.status(),
                        decoded.message(), decoded.groups());
    }

    private static String encoding(PdfRasterDescriptor raster) {
        List<PdfFilter.Decoder> stack = raster.decoderStack();
        if (stack.equals(List.of(PdfFilter.Decoder.JPEG))) return "JPEG";
        if (stack.equals(List.of(PdfFilter.Decoder.JPEG_2000))) return "JPEG 2000";
        if (stack.equals(List.of(PdfFilter.Decoder.JBIG2))) return "JBIG2";
        return raster.filters().stream()
                .map(PdfFilter::pdfName).collect(Collectors.joining(" → "));
    }

    private static String pixelDescription(PdfRasterDescriptor raster) {
        var parts = new java.util.ArrayList<String>();
        if (raster.bitsPerComponent() > 0) {
            parts.add(raster.bitsPerComponent() + "-bit");
        }
        if (raster.stencil()) parts.add("stencil / image mask");
        raster.colorSpace().ifPresent(parts::add);
        if (raster.decoderStack().equals(List.of(PdfFilter.Decoder.JBIG2))) {
            parts.add("viewed via PNG rendition");
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
