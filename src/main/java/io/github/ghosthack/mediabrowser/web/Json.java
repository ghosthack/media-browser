package io.github.ghosthack.mediabrowser.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The few JSON shapes the phone page's API answers with, written by hand: the
 * values are strings, numbers, booleans, null, nested maps and lists. Maps
 * keep their iteration order (callers pass {@link java.util.LinkedHashMap}).
 */
final class Json {

    private Json() {}

    static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    static String write(Object value) {
        var out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Number n -> out.append(n.doubleValue());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (var e : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    string(out, String.valueOf(e.getKey()));
                    out.append(':');
                    write(out, e.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(out, list.get(i));
                }
                out.append(']');
            }
            default -> string(out, value.toString());
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Control characters, plus < > & so a value can never close
                    // a tag if someone inlines the JSON into HTML.
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
