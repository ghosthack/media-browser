package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native-agnostic full-metadata snapshot of a media file, as produced by
 * {@link MediaFacade#readMetadata(Path)}: every field libvips exposes for
 * stills (EXIF/XMP/IPTC/ICC/PNG-text), or every {@code av_dict} tag (container
 * + per-stream) for AV media. The UI never sees vips/ffmpeg specifics.
 *
 * <p><b>Raw presentation.</b> Keys and values pass through exactly as the
 * native libraries render them — no key humanizing, no value parsing. The only
 * two transforms are applied at {@link Entry} construction:
 * <ul>
 *   <li><b>Cap</b> — {@link Entry#value()} is truncated to {@link #MAX_VALUE_CHARS}
 *       so a single 10k+ char field (e.g. a Stable-Diffusion {@code parameters}
 *       blob) can never jank a JavaFX table cell. The full string is retained in
 *       {@link Entry#fullValue()} for copy.</li>
 *   <li><b>Binary placeholder</b> — blob fields render as
 *       {@code "<binary, N bytes>"} instead of dumping garbage.</li>
 * </ul>
 *
 * <p>Groups and entries preserve insertion order (use {@link Builder}).</p>
 */
public record Metadata(Path path, List<Group> groups) {

    /**
     * Maximum number of characters kept in {@link Entry#value()} (the
     * display-safe copy). Big enough to read a prompt at a glance, small enough
     * for a table cell. The untruncated string lives in {@link Entry#fullValue()}.
     */
    public static final int MAX_VALUE_CHARS = 2048;

    public Metadata {
        groups = List.copyOf(groups);
    }

    /** An empty snapshot (the {@link MediaFacade#readMetadata} default / no coverage). */
    public static Metadata empty(Path path) {
        return new Metadata(path, List.of());
    }

    /** Whether there are no groups at all (backend has no metadata coverage / no tags). */
    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** Total number of entries across all groups. */
    public int entryCount() {
        return groups.stream().mapToInt(g -> g.entries().size()).sum();
    }

    /** A named bucket of entries (a source/IFD for stills, container/stream for AV). */
    public record Group(String name, List<Entry> entries) {
        public Group {
            entries = List.copyOf(entries);
        }
    }

    /**
     * One metadata field.
     *
     * @param key       raw native key (e.g. {@code exif-ifd0-XResolution}).
     * @param value     display-safe value: capped to {@link #MAX_VALUE_CHARS} and
     *                  binary-placeheld. Safe to drop straight into a table cell.
     * @param fullValue the untruncated value, for copy. For binary fields this is
     *                  the placeholder too (we never hex-dump megabytes).
     * @param binary    whether the native value was a blob.
     * @param truncated whether {@code value} was capped (and so differs from
     *                  {@code fullValue}).
     */
    public record Entry(String key, String value, String fullValue,
                        boolean binary, boolean truncated) {

        /**
         * A text entry, capping the raw value to {@link #MAX_VALUE_CHARS} for
         * display while keeping the full string for copy. A {@code null} raw
         * value becomes the empty string.
         */
        public static Entry of(String key, String rawValue) {
            String full = rawValue == null ? "" : rawValue;
            if (full.length() > MAX_VALUE_CHARS) {
                return new Entry(key, full.substring(0, MAX_VALUE_CHARS), full, false, true);
            }
            return new Entry(key, full, full, false, false);
        }

        /**
         * A binary-blob entry rendered as {@code "<binary, N bytes>"}; the full
         * value is the same placeholder (no hex dump).
         */
        public static Entry binary(String key, long bytes) {
            String placeholder = "<binary, " + bytes + " bytes>";
            return new Entry(key, placeholder, placeholder, true, false);
        }
    }

    /**
     * Accumulates entries into insertion-ordered groups, so the readers don't
     * each reinvent the {@code LinkedHashMap} bookkeeping. Groups appear in the
     * order they are first added; entries in the order added. Empty groups are
     * never materialized.
     */
    public static final class Builder {
        private final Path path;
        private final Map<String, List<Entry>> groups = new LinkedHashMap<>();

        public Builder(Path path) {
            this.path = path;
        }

        public Builder add(String group, Entry entry) {
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(entry);
            return this;
        }

        /** Adds a capped text entry to {@code group}. */
        public Builder add(String group, String key, String rawValue) {
            return add(group, Entry.of(key, rawValue));
        }

        /** Adds a {@code "<binary, N bytes>"} entry to {@code group}. */
        public Builder addBinary(String group, String key, long bytes) {
            return add(group, Entry.binary(key, bytes));
        }

        public Metadata build() {
            var built = new ArrayList<Group>(groups.size());
            groups.forEach((name, entries) -> built.add(new Group(name, entries)));
            return new Metadata(path, built);
        }
    }
}
