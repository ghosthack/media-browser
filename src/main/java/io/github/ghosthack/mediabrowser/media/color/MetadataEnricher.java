package io.github.ghosthack.mediabrowser.media.color;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.drew.metadata.xmp.XmpDirectory;
import io.github.ghosthack.mediabrowser.media.Metadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Adds metadata-extractor's EXIF/XMP/IPTC/ICC inventory after base FFmpeg data. */
final class MetadataEnricher {

    static final Metadata.Provider PROVIDER = new Metadata.Provider(
            "ffmpeg-ffm-turbojpeg-cm", "FFmpeg + metadata-extractor");

    private MetadataEnricher() {}

    static Metadata enrich(Path file, Metadata base) {
        com.drew.metadata.Metadata extracted;
        try {
            extracted = ImageMetadataReader.readMetadata(file.toFile());
        } catch (Exception ex) {
            return base;
        }

        LinkedHashMap<String, List<Metadata.Entry>> groups = new LinkedHashMap<>();
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        for (Metadata.Group group : base.groups()) {
            groups.put(group.name(), new ArrayList<>(group.entries()));
            HashSet<String> existing = new HashSet<>();
            for (Metadata.Entry entry : group.entries()) existing.add(entry.key());
            keys.put(group.name(), existing);
        }

        for (Directory directory : extracted.getDirectories()) {
            String group = directory.getName();
            if (directory instanceof XmpDirectory xmp) {
                xmp.getXmpProperties().forEach((key, value) ->
                        add(groups, keys, group, Metadata.Entry.of(key, value)));
            }
            for (Tag tag : directory.getTags()) {
                Object raw = directory.getObject(tag.getTagType());
                Metadata.Entry entry;
                String described = tag.getDescription();
                if (described != null && !described.isBlank()) {
                    entry = Metadata.Entry.of(tag.getTagName(), described);
                } else if (raw instanceof byte[] bytes) {
                    entry = Metadata.Entry.binary(tag.getTagName(), bytes.length);
                } else {
                    String value = directory.getString(tag.getTagType());
                    entry = Metadata.Entry.of(tag.getTagName(), value);
                }
                add(groups, keys, group, entry);
            }
        }

        List<Metadata.Group> built = groups.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> new Metadata.Group(entry.getKey(), entry.getValue()))
                .toList();
        Metadata.Status status = built.isEmpty()
                ? Metadata.Status.NO_TAGS : Metadata.Status.FOUND;
        return new Metadata(file, PROVIDER, status, Optional.empty(), built);
    }

    /** Base groups/keys win; enrichment is strictly additive. */
    private static void add(Map<String, List<Metadata.Entry>> groups,
                            Map<String, Set<String>> keys,
                            String group, Metadata.Entry entry) {
        Set<String> groupKeys = keys.computeIfAbsent(group, ignored -> new HashSet<>());
        if (!groupKeys.add(entry.key())) return;
        groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(entry);
    }
}
