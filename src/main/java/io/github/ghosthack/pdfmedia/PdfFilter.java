package io.github.ghosthack.pdfmedia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One stage in a PDF image stream's decoding pipeline.
 *
 * <p>The filters are ordered exactly as PDF applies them to the bytes returned by
 * {@link PdfArchive#openStream(PdfEntry)}. A consumer should therefore run the first filter
 * first. Parameter values contain only immutable Java scalars, lists, and maps; no PDFBox types
 * cross the public module boundary.</p>
 *
 * @param pdfName unabbreviated PDF filter name
 * @param decoder decoder family to select
 * @param parameters filter-specific PDF DecodeParms
 */
public record PdfFilter(String pdfName, Decoder decoder, Map<String, Object> parameters) {

    /** Decoder families used by PDF stream filters. */
    public enum Decoder {
        ASCII_HEX,
        ASCII_85,
        LZW,
        FLATE,
        RUN_LENGTH,
        CCITT_FAX,
        JBIG2,
        JPEG,
        JPEG_2000,
        CRYPT,
        UNKNOWN
    }

    public PdfFilter {
        Objects.requireNonNull(pdfName, "pdfName");
        Objects.requireNonNull(decoder, "decoder");
        parameters = immutableMap(parameters);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        Objects.requireNonNull(source, "parameters");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        Objects.requireNonNull(value, "parameter value");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach(
                    (key, nested) ->
                            copy.put(
                                    Objects.requireNonNull(key).toString(),
                                    immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return List.copyOf(copy);
        }
        if (value instanceof String
                || value instanceof Long
                || value instanceof Double
                || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException(
                "unsupported PDF filter parameter value: " + value.getClass().getName());
    }
}
