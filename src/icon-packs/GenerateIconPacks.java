import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * No-dependency source generator for the application's vector icon packs.
 *
 * <p>Run from the repository root:</p>
 * <pre>java src/icon-packs/GenerateIconPacks.java</pre>
 */
public final class GenerateIconPacks {

    private static final Path APP_ICON = Path.of(
            "src/main/java/io/github/ghosthack/mediabrowser/ui/icon/AppIcon.java");
    private static final Path PACK_DIR = Path.of("src/icon-packs");
    private static final Path OUTPUT = Path.of(
            "src/main/java/io/github/ghosthack/mediabrowser/ui/icon/GeneratedIconPacks.java");
    private static final Pattern ENUM_LINE =
            Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\(\".*");

    private record Glyph(String mode, String path) {}

    public static void main(String[] args) throws IOException {
        List<String> roles = readRoles();
        var packs = new LinkedHashMap<String, Map<String, Glyph>>();
        try (var files = Files.list(PACK_DIR)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".iconpack"))
                    .sorted().toList()) {
                String fileName = file.getFileName().toString();
                String pack = fileName.substring(0, fileName.length() - ".iconpack".length())
                        .toUpperCase(Locale.ROOT);
                packs.put(pack, readPack(file, roles));
            }
        }
        if (packs.isEmpty()) throw new IllegalStateException("No .iconpack manifests found");
        Files.writeString(OUTPUT, generate(roles, packs));
        System.out.println("Generated " + OUTPUT + " (" + roles.size()
                + " roles × " + packs.size() + " packs)");
    }

    private static List<String> readRoles() throws IOException {
        var roles = new ArrayList<String>();
        for (String line : Files.readAllLines(APP_ICON)) {
            var match = ENUM_LINE.matcher(line);
            if (match.matches()) roles.add(match.group(1));
        }
        if (roles.isEmpty()) throw new IllegalStateException("No AppIcon roles found");
        return List.copyOf(roles);
    }

    private static Map<String, Glyph> readPack(Path file, List<String> roles)
            throws IOException {
        var glyphs = new LinkedHashMap<String, Glyph>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\|", 3);
            if (fields.length != 3 || fields[2].isBlank()) {
                throw new IllegalArgumentException(file + ":" + lineNumber
                        + ": expected ROLE|STROKE_OR_FILL|SVG_PATH");
            }
            String role = fields[0].trim();
            String mode = fields[1].trim();
            if (!mode.equals("STROKE") && !mode.equals("FILL")) {
                throw new IllegalArgumentException(file + ":" + lineNumber
                        + ": bad paint mode " + mode);
            }
            if (glyphs.put(role, new Glyph(mode, fields[2].trim())) != null) {
                throw new IllegalArgumentException(file + ":" + lineNumber
                        + ": duplicate role " + role);
            }
        }
        if (!glyphs.keySet().equals(new java.util.LinkedHashSet<>(roles))) {
            var missing = new java.util.LinkedHashSet<>(roles);
            missing.removeAll(glyphs.keySet());
            var unknown = new java.util.LinkedHashSet<>(glyphs.keySet());
            unknown.removeAll(roles);
            throw new IllegalArgumentException(file + ": role mismatch; missing="
                    + missing + ", unknown=" + unknown);
        }
        return Map.copyOf(glyphs);
    }

    private static String generate(
            List<String> roles, Map<String, Map<String, Glyph>> packs) {
        var out = new StringBuilder("""
                package io.github.ghosthack.mediabrowser.ui.icon;

                import io.github.ghosthack.mediabrowser.IconPack;

                import java.util.Map;

                /**
                 * Generated vector geometry for non-original icon packs.
                 *
                 * <p>Regenerate with {@code java src/icon-packs/GenerateIconPacks.java};
                 * do not hand-edit geometry here. Every path uses a normalized 24×24
                 * coordinate system.</p>
                 */
                public final class GeneratedIconPacks {

                    /** How JavaFX should paint an SVG path. */
                    public enum PaintMode { STROKE, FILL }

                    /** One complete icon, including any disconnected path segments. */
                    public record Glyph(PaintMode paintMode, String svgPath) {
                        public Glyph {
                            if (paintMode == null) throw new IllegalArgumentException("paintMode");
                            if (svgPath == null || svgPath.isBlank()) {
                                throw new IllegalArgumentException("svgPath");
                            }
                        }
                    }

                """);
        for (var pack : packs.entrySet()) {
            out.append("    private static final Map<AppIcon, Glyph> ")
                    .append(pack.getKey()).append(" = Map.ofEntries(\n");
            for (int i = 0; i < roles.size(); i++) {
                String role = roles.get(i);
                Glyph glyph = pack.getValue().get(role);
                out.append("            entry(AppIcon.").append(role)
                        .append(", PaintMode.").append(glyph.mode())
                        .append(", \"").append(escape(glyph.path())).append("\")")
                        .append(i + 1 == roles.size() ? "\n" : ",\n");
            }
            out.append("    );\n\n");
        }
        out.append("""
                    private GeneratedIconPacks() {}

                    /** Returns vector geometry, or {@code null} for the text-backed Original pack. */
                    public static Glyph glyph(IconPack pack, AppIcon icon) {
                        if (pack == null || icon == null || pack == IconPack.ORIGINAL) return null;
                        return switch (pack) {
                            case ORIGINAL -> null;
                """);
        for (String pack : packs.keySet()) {
            out.append("            case ").append(pack).append(" -> ")
                    .append(pack).append(".get(icon);\n");
        }
        out.append("""
                        };
                    }

                    private static Map.Entry<AppIcon, Glyph> entry(
                            AppIcon icon, PaintMode mode, String svgPath) {
                        return Map.entry(icon, new Glyph(mode, svgPath));
                    }
                }
                """);
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
