package io.github.ghosthack.mediabrowser.media;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free recursive-descent JSON reader, just enough to parse
 * the {@code adjustmentData} payload of an Apple Photos {@code .AAE} sidecar
 * (see {@link AaeSidecar}). The project ships no JSON library, and pulling one
 * in for one small, well-bounded payload is not worth the dependency.
 *
 * <p>Produces the conventional Java object graph: a JSON object becomes a
 * {@link java.util.LinkedHashMap}{@code <String,Object>} (insertion-ordered),
 * an array a {@link java.util.List}{@code <Object>}, a string a {@link String},
 * a number a {@link Double}, the literals a {@link Boolean} or {@code null}.
 * Strict enough to reject trailing garbage; lenient on nothing. Throws
 * {@link IllegalArgumentException} on malformed input.
 */
final class AaeJson {

    private final String s;
    private int i;

    private AaeJson(String s) {
        this.s = s;
    }

    /** Parses one complete JSON document; rejects trailing non-whitespace. */
    static Object parse(String text) {
        AaeJson p = new AaeJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing content after JSON at index " + p.i);
        }
        return v;
    }

    private Object value() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f' -> literalBool();
            case 'n' -> literalNull();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            map.put(key, value());
            ws();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at index " + (i - 1));
            }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at index " + (i - 1));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (i == start) {
            throw new IllegalArgumentException("expected a value at index " + i);
        }
        return Double.valueOf(s.substring(start, i));
    }

    private Boolean literalBool() {
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("expected boolean at index " + i);
    }

    private Object literalNull() {
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw new IllegalArgumentException("expected null at index " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        return s.charAt(i++);
    }

    private void expect(char c) {
        char got = next();
        if (got != c) {
            throw new IllegalArgumentException("expected '" + c + "' at index " + (i - 1)
                    + " but found '" + got + "'");
        }
    }
}
