package io.github.ghosthack.epubmedia.internal;

import io.github.ghosthack.epubmedia.EpubArchiveException;
import io.github.ghosthack.epubmedia.EpubEntry;
import io.github.ghosthack.epubmedia.EpubEntry.Kind;
import io.github.ghosthack.epubmedia.EpubEntry.Origin;
import io.github.ghosthack.epubmedia.EpubOpenOptions;
import io.github.ghosthack.epubmedia.EpubPackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Secure, bounded parsing of the EPUB container and package documents. */
public final class EpubParser {
    private static final String EPUB_MIMETYPE = "application/epub+zip";
    private static final String CONTAINER_PATH = "META-INF/container.xml";
    private static final String DC_NAMESPACE = "http://purl.org/dc/elements/1.1/";

    private EpubParser() {}

    public record Selected(EpubPackage publication, List<EpubEntry> entries, List<ZipEntry> zipEntries) {
        public Selected {
            entries = List.copyOf(entries);
            zipEntries = List.copyOf(zipEntries);
        }
    }

    public static boolean hasEpubMimetype(ZipFile zip) throws IOException {
        var entries = zip.entries();
        if (!entries.hasMoreElements()) return false;
        ZipEntry first = entries.nextElement();
        if (!"mimetype".equals(first.getName()) || first.getMethod() != ZipEntry.STORED) {
            return false;
        }
        try (InputStream input = zip.getInputStream(first)) {
            byte[] bytes = input.readNBytes(EPUB_MIMETYPE.length() + 1);
            return bytes.length == EPUB_MIMETYPE.length()
                    && EPUB_MIMETYPE.equals(new String(bytes, StandardCharsets.US_ASCII));
        }
    }

    public static Selected parse(
            ZipFile zip,
            List<ZipEntry> physicalEntries,
            EpubOpenOptions options)
            throws IOException {
        if (!hasEpubMimetype(zip)) {
            throw new EpubArchiveException(
                    "EPUB mimetype must be the first uncompressed ZIP member");
        }
        Map<String, List<ZipEntry>> byPath = new LinkedHashMap<>();
        for (ZipEntry entry : physicalEntries) {
            String path = safePhysicalPath(entry.getName(), options.maxPathCharacters());
            byPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(entry);
        }
        ZipEntry container = unique(byPath, CONTAINER_PATH, "EPUB container document");
        String packagePath = parseContainer(read(zip, container, options.maxXmlBytes()));
        packagePath = safePhysicalPath(packagePath, options.maxPathCharacters());
        ZipEntry packageEntry = unique(byPath, packagePath, "EPUB package document");
        PackageData data = parsePackage(
                read(zip, packageEntry, options.maxXmlBytes()),
                packagePath,
                options.maxManifestItems());

        Map<String, ManifestItem> itemsById = new HashMap<>();
        for (ManifestItem item : data.items) {
            if (itemsById.putIfAbsent(item.id, item) != null) {
                throw new EpubArchiveException("duplicate EPUB manifest ID: " + item.id);
            }
        }
        Set<String> epub2CoverIds = data.epub2CoverId == null
                ? Set.of() : Set.of(data.epub2CoverId);
        Optional<String> guideCoverPath =
                resolve(packagePath, data.guideCoverHref, options.maxPathCharacters());
        int missing = 0;
        int remote = 0;
        List<EpubEntry> selected = new ArrayList<>();
        List<ZipEntry> selectedPhysical = new ArrayList<>();
        for (ManifestItem item : data.items) {
            Kind kind = kind(item.mediaType);
            if (kind == null) continue;
            Optional<String> resolved =
                    resolve(packagePath, item.href, options.maxPathCharacters());
            if (resolved.isEmpty()) {
                remote++;
                continue;
            }
            List<ZipEntry> matches = byPath.get(resolved.get());
            if (matches == null || matches.isEmpty()) {
                missing++;
                continue;
            }
            EnumSet<Origin> origins = EnumSet.of(Origin.MANIFEST);
            if (item.properties.contains("cover-image")) {
                origins.add(Origin.EPUB3_COVER_IMAGE);
            }
            if (epub2CoverIds.contains(item.id)) {
                origins.add(Origin.EPUB2_COVER_META);
            }
            if (guideCoverPath.filter(resolved.get()::equals).isPresent()) {
                origins.add(Origin.GUIDE_COVER);
            }
            for (ZipEntry physical : matches) {
                if (selected.size() >= options.maxMediaEntries()) {
                    throw new EpubArchiveException(
                            "EPUB media entry count exceeds budget "
                                    + options.maxMediaEntries());
                }
                selected.add(
                        new EpubEntry(
                                selected.size(),
                                resolved.get(),
                                item.id,
                                item.mediaType,
                                kind,
                                origins,
                                physical.getSize(),
                                physical.getCompressedSize()));
                selectedPhysical.add(physical);
            }
        }
        EpubPackage publication =
                new EpubPackage(
                        packagePath,
                        data.version,
                        optional(data.title),
                        optional(data.creator),
                        optional(data.language),
                        optional(data.identifier),
                        data.items.size(),
                        data.spineCount,
                        missing,
                        remote);
        return new Selected(publication, selected, selectedPhysical);
    }

    private static String parseContainer(byte[] xml) throws IOException {
        try {
            XMLStreamReader reader = reader(xml);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && "rootfile".equals(reader.getLocalName())) {
                        String mediaType = attribute(reader, "media-type");
                        String path = attribute(reader, "full-path");
                        if (path != null
                                && (mediaType == null
                                        || "application/oebps-package+xml".equals(mediaType))) {
                            return path;
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new EpubArchiveException("cannot parse EPUB container.xml", e);
        }
        throw new EpubArchiveException("EPUB container.xml has no package rootfile");
    }

    private static PackageData parsePackage(byte[] xml, String packagePath, int maxItems)
            throws IOException {
        PackageData data = new PackageData();
        try {
            XMLStreamReader reader = reader(xml);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    String local = reader.getLocalName();
                    switch (local) {
                        case "package" -> data.version = nullToEmpty(attribute(reader, "version"));
                        case "item" -> {
                            if (data.items.size() >= maxItems) {
                                throw new EpubArchiveException(
                                        "EPUB manifest item count exceeds budget " + maxItems);
                            }
                            String id = attribute(reader, "id");
                            String href = attribute(reader, "href");
                            String mediaType = attribute(reader, "media-type");
                            if (id != null && href != null && mediaType != null) {
                                data.items.add(
                                        new ManifestItem(
                                                id,
                                                href,
                                                mediaType.trim().toLowerCase(Locale.ROOT),
                                                words(attribute(reader, "properties"))));
                            }
                        }
                        case "itemref" -> data.spineCount++;
                        case "meta" -> {
                            if ("cover".equalsIgnoreCase(attribute(reader, "name"))) {
                                data.epub2CoverId = attribute(reader, "content");
                            }
                        }
                        case "reference" -> {
                            if (words(attribute(reader, "type")).contains("cover")) {
                                data.guideCoverHref = attribute(reader, "href");
                            }
                        }
                        case "title", "creator", "language", "identifier" -> {
                            if (DC_NAMESPACE.equals(nullToEmpty(reader.getNamespaceURI()))) {
                                String text = reader.getElementText().trim();
                                if (!text.isEmpty()) {
                                    switch (local) {
                                        case "title" -> {
                                            if (data.title == null) data.title = text;
                                        }
                                        case "creator" -> {
                                            if (data.creator == null) data.creator = text;
                                        }
                                        case "language" -> {
                                            if (data.language == null) data.language = text;
                                        }
                                        case "identifier" -> {
                                            if (data.identifier == null) data.identifier = text;
                                        }
                                        default -> {
                                            // Exhaustive above.
                                        }
                                    }
                                }
                            }
                        }
                        default -> {
                            // XHTML, navigation, bindings, and other package data are not media.
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new EpubArchiveException(
                    "cannot parse EPUB package document " + packagePath, e);
        }
        return data;
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XMLResolver rejectingResolver =
                (publicId, systemId, baseUri, namespace) -> {
                    throw new XMLStreamException("external XML resources are disabled");
                };
        factory.setXMLResolver(rejectingResolver);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static byte[] read(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream((int) Math.min(Math.max(0, entry.getSize()), limit))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total = Math.addExact(total, count);
                if (total > limit) {
                    throw new EpubArchiveException(
                            "EPUB XML member exceeds " + limit + " bytes: " + entry.getName());
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (ArithmeticException e) {
            throw new EpubArchiveException("EPUB XML size overflow", e);
        }
    }

    private static ZipEntry unique(
            Map<String, List<ZipEntry>> byPath, String path, String description)
            throws IOException {
        List<ZipEntry> entries = byPath.get(path);
        if (entries == null || entries.isEmpty()) {
            throw new EpubArchiveException(description + " is missing: " + path);
        }
        if (entries.size() != 1) {
            throw new EpubArchiveException(description + " is duplicated: " + path);
        }
        return entries.getFirst();
    }

    public static String safePhysicalPath(String raw, int maxCharacters) throws IOException {
        if (raw == null
                || raw.isBlank()
                || raw.length() > maxCharacters
                || raw.indexOf('\0') >= 0
                || raw.indexOf('\\') >= 0
                || raw.startsWith("/")
                || raw.matches("^[A-Za-z]:.*")) {
            throw new EpubArchiveException("unsafe EPUB ZIP member path: " + raw);
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty() && raw.endsWith("/") && parts.size() > 0) continue;
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new EpubArchiveException("unsafe EPUB ZIP member path: " + raw);
            }
            parts.addLast(part);
        }
        return String.join("/", parts);
    }

    private static Optional<String> resolve(
            String packagePath, String href, int maxCharacters) throws IOException {
        if (href == null || href.isBlank()) return Optional.empty();
        final URI uri;
        try {
            uri = new URI(href);
        } catch (URISyntaxException e) {
            throw new EpubArchiveException("invalid EPUB manifest href: " + href, e);
        }
        if (uri.isAbsolute() || uri.getRawAuthority() != null) return Optional.empty();
        String path = uri.getPath();
        if (path == null
                || path.isBlank()
                || path.length() > maxCharacters
                || path.startsWith("/")
                || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0) {
            throw new EpubArchiveException("unsafe EPUB manifest href: " + href);
        }
        String base = "";
        int slash = packagePath.lastIndexOf('/');
        if (slash >= 0) base = packagePath.substring(0, slash + 1);
        Deque<String> normalized = new ArrayDeque<>();
        for (String part : (base + path).split("/+")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (normalized.isEmpty()) {
                    throw new EpubArchiveException(
                            "EPUB manifest href escapes package root: " + href);
                }
                normalized.removeLast();
            } else {
                normalized.addLast(part);
            }
        }
        String result = String.join("/", normalized);
        if (result.length() > maxCharacters) {
            throw new EpubArchiveException("EPUB manifest path exceeds budget: " + href);
        }
        return Optional.of(result);
    }

    private static Kind kind(String mediaType) {
        if (mediaType.startsWith("image/")) return Kind.IMAGE;
        if (mediaType.startsWith("audio/")) return Kind.AUDIO;
        if (mediaType.startsWith("video/")) return Kind.VIDEO;
        return null;
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (localName.equals(reader.getAttributeLocalName(index))) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static Set<String> words(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.copyOf(
                new HashSet<>(
                        List.of(value.trim().toLowerCase(Locale.ROOT).split("\\s+"))));
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ManifestItem(String id, String href, String mediaType, Set<String> properties) {}

    private static final class PackageData {
        String version = "";
        String title;
        String creator;
        String language;
        String identifier;
        String epub2CoverId;
        String guideCoverHref;
        int spineCount;
        final List<ManifestItem> items = new ArrayList<>();
    }
}
