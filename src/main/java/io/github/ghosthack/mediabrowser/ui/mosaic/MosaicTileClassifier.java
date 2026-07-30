package io.github.ghosthack.mediabrowser.ui.mosaic;

import io.github.ghosthack.mediabrowser.media.DirEntry;

import java.util.Locale;
import java.util.Set;

/**
 * Name/metadata-only classification for generated mosaic artwork. No content
 * reads occur here; callers can safely cache the result per directory entry.
 */
public final class MosaicTileClassifier {

    private static final Set<String> TEXT = Set.of(
            "txt", "text", "md", "markdown", "rst", "log", "nfo",
            "java", "kt", "kts", "scala", "groovy", "clj", "cljs",
            "c", "h", "cc", "cpp", "cxx", "hpp", "m", "mm", "swift",
            "rs", "go", "py", "pyw", "rb", "php", "pl", "pm", "lua",
            "js", "jsx", "ts", "tsx", "css", "scss", "sass", "less",
            "html", "htm", "xhtml", "sh", "bash", "zsh", "fish", "ps1",
            "bat", "cmd", "sql", "ini", "cfg", "conf", "properties",
            "gitignore", "gitattributes", "editorconfig");

    private static final Set<String> DOCUMENT = Set.of(
            "pdf", "doc", "docx", "odt", "rtf", "pages",
            "xls", "xlsx", "ods", "numbers",
            "ppt", "pptx", "odp", "key",
            "epub", "mobi", "azw", "azw3", "djvu");

    private static final Set<String> DATA = Set.of(
            "json", "jsonl", "yaml", "yml", "xml", "toml", "csv", "tsv",
            "sqlite", "sqlite3", "db", "db3", "parquet", "avro",
            "plist", "reg", "ics", "vcf");

    private static final Set<String> BINARY = Set.of(
            "class", "o", "obj", "a", "lib", "dll", "so", "dylib",
            "pdb", "wasm", "pyc", "pyo", "beam", "dat", "bin", "rom",
            "pak", "idx", "cache", "ttf", "otf", "woff", "woff2");

    private static final Set<String> EXECUTABLE = Set.of(
            "exe", "com", "msi", "app", "appimage", "run", "desktop",
            "apk", "deb", "rpm", "dmg", "pkg");

    private static final Set<String> SEALED_ARCHIVE = Set.of(
            "7z", "rar", "tar", "gz", "gzip", "bz2", "xz", "zst",
            "tgz", "tbz", "tbz2", "txz", "cab", "arj", "lha", "lzh",
            "wad", "pk3", "pk4", "vpk", "pak", "jar", "war", "ear");

    /** Stable, allocation-light base classification cached by the mosaic. */
    public record Base(MosaicTileIdentity identity, String stamp, int modifiers) {
        public boolean has(MosaicTileModifier modifier) {
            return (modifiers & modifier.mask()) != 0;
        }
    }

    private MosaicTileClassifier() {}

    public static Base classify(DirEntry entry) {
        if (entry == null) return new Base(MosaicTileIdentity.UNKNOWN, "?", 0);
        int modifiers = 0;
        var traits = entry.traits();
        if (traits.hidden()) modifiers |= MosaicTileModifier.HIDDEN.mask();
        if (traits.system()) modifiers |= MosaicTileModifier.SYSTEM.mask();
        if (traits.junk()) modifiers |= MosaicTileModifier.JUNK.mask();
        if (traits.executable()) modifiers |= MosaicTileModifier.EXECUTABLE.mask();
        if (traits.symbolicLink()) modifiers |= MosaicTileModifier.SYMLINK.mask();
        if (traits.brokenLink()) modifiers |= MosaicTileModifier.BROKEN_LINK.mask();
        if (entry.type() != DirEntry.Type.DIRECTORY
                && entry.type() != DirEntry.Type.PARENT && entry.size() == 0) {
            modifiers |= MosaicTileModifier.ZERO_BYTE.mask();
        }
        if (!traits.readable()) modifiers |= MosaicTileModifier.UNREADABLE.mask();

        String ext = entry.extension().toLowerCase(Locale.ROOT);
        String stamp = ext.isEmpty() ? "" : ext.toUpperCase(Locale.ROOT);
        MosaicTileIdentity identity = switch (entry.type()) {
            case PARENT -> MosaicTileIdentity.PARENT;
            case DIRECTORY -> MosaicTileIdentity.FOLDER;
            case ARCHIVE -> MosaicTileIdentity.ARCHIVE_BROWSABLE;
            case MEDIA -> switch (entry.mediaKind()) {
                case IMAGE -> MosaicTileIdentity.MEDIA_IMAGE;
                case VIDEO -> MosaicTileIdentity.MEDIA_VIDEO;
                case AUDIO -> MosaicTileIdentity.MEDIA_AUDIO;
            };
            case OTHER -> otherIdentity(ext, traits.executable());
        };
        if (stamp.isEmpty()) {
            stamp = switch (identity) {
                case PARENT -> "UP";
                case FOLDER -> "DIR";
                case EXECUTABLE -> "+X";
                case UNKNOWN -> "?";
                default -> identity.name();
            };
        }
        return new Base(identity, stamp, modifiers);
    }

    private static MosaicTileIdentity otherIdentity(String ext, boolean executable) {
        if (EXECUTABLE.contains(ext) || executable) return MosaicTileIdentity.EXECUTABLE;
        if (SEALED_ARCHIVE.contains(ext)) return MosaicTileIdentity.ARCHIVE_SEALED;
        if (TEXT.contains(ext)) return MosaicTileIdentity.TEXT;
        if (DOCUMENT.contains(ext)) return MosaicTileIdentity.DOCUMENT;
        if (DATA.contains(ext)) return MosaicTileIdentity.DATA;
        if (BINARY.contains(ext)) return MosaicTileIdentity.BINARY;
        return MosaicTileIdentity.UNKNOWN;
    }
}
