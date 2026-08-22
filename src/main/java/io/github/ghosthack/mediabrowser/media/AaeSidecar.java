package io.github.ghosthack.mediabrowser.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A parsed Apple Photos {@code .AAE} edit sidecar — the non-destructive
 * adjustment description Photos writes next to an edited {@code IMG_xxxx.HEIC} /
 * {@code .JPG} / {@code .MOV}. Like user rotation (see {@link RotationStore}),
 * an AAE edit lives <em>above</em> the decoders: the backend still decodes the
 * untouched master to an upright BGRA {@link RasterFrame}, and the recoverable
 * geometric adjustments are composed on top of it (see
 * {@link RasterFrames#applyAae}).
 *
 * <h2>On-disk shape</h2>
 * The {@code .AAE} is an XML property list whose {@code adjustmentData} key is a
 * base64 {@code <data>} blob. On modern iOS (format {@code com.apple.photo}
 * 1.x) that blob is <strong>raw DEFLATE</strong>-compressed UTF-8 JSON:
 * <pre>
 *   { "metadata": { "masterWidth": 5712, "masterHeight": 4284, "orientation": 1 },
 *     "adjustments": [
 *       { "identifier": "Crop", "enabled": true,
 *         "settings": { "xOrigin": 827, "yOrigin": 1, "width": 3032, "height": 4282,
 *                       "straightenAngle": 0, "pitch": 0, "yaw": 0 } },
 *       { "identifier": "DepthEffect", "enabled": false, ... } ] }
 * </pre>
 *
 * <h2>What we reproduce, and what we don't</h2>
 * Only the <em>geometric, parametric</em> adjustments are faithfully
 * reproducible without Apple's rendering engine:
 * <ul>
 *   <li><b>Crop</b> — an axis-aligned rectangle in master/display pixels.
 *       Reproduced exactly (see {@link #crop()}).</li>
 *   <li><b>Straighten</b> — {@code straightenAngle} (radians). Reproduced
 *       best-effort by {@link RasterFrames#applyAae} (see its note on the angle
 *       convention).</li>
 * </ul>
 * Perspective ({@code pitch}/{@code yaw}) and the opaque, engine-only effects
 * (Portrait/{@code DepthEffect}, Smart HDR, Vivid/filter looks, retouch) carry
 * only parameters, not the algorithm, so they are flagged
 * ({@link #hasUnsupportedGeometry()}, {@link #hasUnsupportedEffects()}) and
 * skipped rather than approximated. Disabled adjustments are ignored entirely.
 *
 * <p>Older AAEs whose {@code adjustmentData} is a nested binary plist (not
 * deflate-JSON) are not understood; {@link #read} returns {@link Optional#empty}
 * for those (and for any unreadable/garbled sidecar), so the untouched master is
 * shown — never a wrong edit.
 */
public final class AaeSidecar {

    /** An axis-aligned crop rectangle, in the master image's pixel space. */
    public record Crop(int x, int y, int width, int height) {}

    private final int masterWidth;
    private final int masterHeight;
    private final int orientation;
    private final Crop crop;                 // nullable: only when an enabled Crop is present
    private final double straightenAngle;    // radians; 0 when none/disabled
    private final boolean hasUnsupportedGeometry;
    private final boolean hasUnsupportedEffects;
    private final List<String> enabledAdjustments;

    private AaeSidecar(int masterWidth, int masterHeight, int orientation, Crop crop,
                       double straightenAngle, boolean hasUnsupportedGeometry,
                       boolean hasUnsupportedEffects, List<String> enabledAdjustments) {
        this.masterWidth = masterWidth;
        this.masterHeight = masterHeight;
        this.orientation = orientation;
        this.crop = crop;
        this.straightenAngle = straightenAngle;
        this.hasUnsupportedGeometry = hasUnsupportedGeometry;
        this.hasUnsupportedEffects = hasUnsupportedEffects;
        this.enabledAdjustments = List.copyOf(enabledAdjustments);
    }

    /** Master width in pixels per the sidecar's own metadata (display/oriented space). */
    public int masterWidth() {
        return masterWidth;
    }

    /** Master height in pixels per the sidecar's own metadata (display/oriented space). */
    public int masterHeight() {
        return masterHeight;
    }

    /** Apple orientation code recorded in the sidecar metadata (informational). */
    public int orientation() {
        return orientation;
    }

    /** The enabled crop rectangle, if any, in master pixels. */
    public Optional<Crop> crop() {
        return Optional.ofNullable(crop);
    }

    /** The straighten angle in radians (0 when none); part of the Crop adjustment. */
    public double straightenAngleRadians() {
        return straightenAngle;
    }

    /** Whether an enabled adjustment carries perspective we don't reproduce (pitch/yaw). */
    public boolean hasUnsupportedGeometry() {
        return hasUnsupportedGeometry;
    }

    /** Whether an enabled, engine-only effect is present (Portrait/filters/retouch/…). */
    public boolean hasUnsupportedEffects() {
        return hasUnsupportedEffects;
    }

    /** Identifiers of every <em>enabled</em> adjustment, for diagnostics/UI. */
    public List<String> enabledAdjustments() {
        return enabledAdjustments;
    }

    /** Whether anything geometric is recoverable here (an enabled crop or straighten). */
    public boolean hasGeometry() {
        return crop != null || straightenAngle != 0;
    }

    /**
     * The crop as fractions of the master ({@code [fx, fy, fw, fh]} in 0..1), or
     * empty when there is no crop. Resolution-independent, so the mosaic can map
     * it onto a downscaled thumbnail without knowing the master pixel size.
     */
    public Optional<double[]> cropFraction() {
        if (crop == null || masterWidth <= 0 || masterHeight <= 0) {
            return Optional.empty();
        }
        return Optional.of(new double[] {
                crop.x() / (double) masterWidth,
                crop.y() / (double) masterHeight,
                crop.width() / (double) masterWidth,
                crop.height() / (double) masterHeight});
    }

    /**
     * Parses the {@code .AAE} at {@code aaeFile}. Returns empty when the file is
     * unreadable, is not a recognized adjustment plist, or carries an
     * {@code adjustmentData} we don't understand (e.g. the legacy binary-plist
     * form) — in every "don't understand" case the caller falls back to the
     * untouched master rather than risk a wrong edit.
     */
    public static Optional<AaeSidecar> read(Path aaeFile) {
        try {
            byte[] xml = Files.readAllBytes(aaeFile);
            PlistData plist = parsePlistData(xml);
            if (plist == null) {
                return Optional.empty();
            }
            String json = inflateRawDeflate(plist.adjustmentData());
            if (json == null || json.isBlank() || json.charAt(0) != '{') {
                return Optional.empty(); // legacy binary-plist payload, or not JSON
            }
            return fromJson(AaeJson.parse(json));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Creates or updates {@code aaeFile} with an axis-aligned crop in the
     * upright master's pixel space. Only the sidecar is written: the image file
     * is never opened for writing. An existing modern AAE keeps its other JSON
     * adjustments and plist fields; an unreadable or legacy AAE is rejected so
     * unknown edit data is never destroyed.
     *
     * <p>The replacement is written to a sibling temporary file and renamed
     * over the sidecar atomically where the filesystem supports it.</p>
     *
     * @throws IOException if the sidecar cannot be safely read or written
     */
    @SuppressWarnings("unchecked")
    public static void writeCrop(Path aaeFile, int masterWidth, int masterHeight,
                                 Crop crop) throws IOException {
        if (aaeFile == null || masterWidth <= 0 || masterHeight <= 0 || crop == null) {
            throw new IllegalArgumentException("crop and positive master dimensions are required");
        }
        int x = Math.max(0, crop.x());
        int y = Math.max(0, crop.y());
        int right = Math.min(masterWidth, (int) Math.min(Integer.MAX_VALUE,
                (long) crop.x() + crop.width()));
        int bottom = Math.min(masterHeight, (int) Math.min(Integer.MAX_VALUE,
                (long) crop.y() + crop.height()));
        if (right <= x || bottom <= y) {
            throw new IllegalArgumentException("crop does not intersect the master");
        }
        Crop bounded = new Crop(x, y, right - x, bottom - y);

        Document existingDocument = null;
        Map<String, Object> root;
        if (Files.exists(aaeFile)) {
            byte[] xml = Files.readAllBytes(aaeFile);
            PlistData plist = parsePlistData(xml);
            String json = plist == null ? null : inflateRawDeflate(plist.adjustmentData());
            Object parsed;
            try {
                parsed = json == null ? null : AaeJson.parse(json);
            } catch (RuntimeException e) {
                throw new IOException("Refusing to replace an unreadable AAE sidecar: "
                        + aaeFile, e);
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("Refusing to replace an unsupported AAE sidecar: "
                        + aaeFile);
            }
            root = (Map<String, Object>) map;
            existingDocument = plist.document();
        } else {
            root = new LinkedHashMap<>();
        }

        Map<String, Object> metadata;
        Object metadataValue = root.get("metadata");
        if (metadataValue instanceof Map<?, ?> map) {
            metadata = (Map<String, Object>) map;
        } else {
            metadata = new LinkedHashMap<>();
            root.put("metadata", metadata);
        }
        metadata.put("masterWidth", masterWidth);
        metadata.put("masterHeight", masterHeight);
        metadata.putIfAbsent("orientation", 1);

        List<Object> adjustments;
        Object adjustmentsValue = root.get("adjustments");
        if (adjustmentsValue instanceof List<?> list) {
            adjustments = (List<Object>) list;
        } else {
            adjustments = new ArrayList<>();
            root.put("adjustments", adjustments);
        }
        Map<String, Object> cropAdjustment = null;
        for (Object value : adjustments) {
            if (value instanceof Map<?, ?> map && "Crop".equals(map.get("identifier"))) {
                cropAdjustment = (Map<String, Object>) map;
            }
        }
        if (cropAdjustment == null) {
            cropAdjustment = new LinkedHashMap<>();
            cropAdjustment.put("identifier", "Crop");
            adjustments.add(cropAdjustment);
        }
        cropAdjustment.put("enabled", true);
        Map<String, Object> settings;
        Object settingsValue = cropAdjustment.get("settings");
        if (settingsValue instanceof Map<?, ?> map) {
            settings = (Map<String, Object>) map;
        } else {
            settings = new LinkedHashMap<>();
            cropAdjustment.put("settings", settings);
        }
        settings.put("xOrigin", bounded.x());
        settings.put("yOrigin", bounded.y());
        settings.put("width", bounded.width());
        settings.put("height", bounded.height());
        settings.putIfAbsent("straightenAngle", 0);
        settings.putIfAbsent("pitch", 0);
        settings.putIfAbsent("yaw", 0);

        byte[] compressed = deflateRaw(AaeJson.stringify(root));
        byte[] output = existingDocument == null
                ? newPlist(compressed)
                : updatePlist(existingDocument, compressed);
        writeAtomically(aaeFile, output);
    }

    // --- plist (XML) → adjustmentData bytes ----------------------------------

    /**
     * Pulls the base64-decoded bytes of the {@code adjustmentData} {@code <data>}
     * value out of an Apple plist. External entity / DTD loading is disabled, so
     * the {@code DOCTYPE}'s apple.com URL is never fetched and the parser is not
     * an XXE vector.
     */
    private record PlistData(Document document, byte[] adjustmentData) {}

    private static PlistData parsePlistData(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            // Swallow the default handler's stderr spew; a malformed .AAE just
            // means "no edit" (we catch the thrown SAXException below).
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            Document doc = b.parse(new java.io.ByteArrayInputStream(xml));
            Element dict = firstElement(doc.getDocumentElement(), "dict");
            if (dict == null) {
                return null;
            }
            // A plist <dict> is a flat sequence of <key> then its value element.
            NodeList kids = dict.getChildNodes();
            for (int n = 0; n < kids.getLength(); n++) {
                Node node = kids.item(n);
                if (node.getNodeType() != Node.ELEMENT_NODE
                        || !"key".equals(node.getNodeName())
                        || !"adjustmentData".equals(node.getTextContent().trim())) {
                    continue;
                }
                Element data = nextElement(node);
                if (data == null || !"data".equals(data.getNodeName())) {
                    return null;
                }
                String b64 = data.getTextContent().replaceAll("\\s+", "");
                return new PlistData(doc, Base64.getDecoder().decode(b64));
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Element firstElement(Node parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList kids = parent.getChildNodes();
        for (int n = 0; n < kids.getLength(); n++) {
            Node node = kids.item(n);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static Element nextElement(Node node) {
        for (Node n = node.getNextSibling(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) n;
            }
        }
        return null;
    }

    // --- adjustmentData bytes → JSON text ------------------------------------

    /** Raw-DEFLATE (no zlib header) inflate to a UTF-8 string, or null on failure. */
    private static String inflateRawDeflate(byte[] data) {
        Inflater inflater = new Inflater(true); // nowrap = raw deflate, wbits -15
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 3));
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int k = inflater.inflate(buf);
                if (k == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(buf, 0, k);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static byte[] deflateRaw(String json) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(json.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] newPlist(byte[] adjustmentData) {
        String b64 = Base64.getEncoder().encodeToString(adjustmentData);
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                \t<key>adjustmentBaseVersion</key>
                \t<integer>0</integer>
                \t<key>adjustmentData</key>
                \t<data>%s</data>
                \t<key>adjustmentFormatIdentifier</key>
                \t<string>com.apple.photo</string>
                \t<key>adjustmentFormatVersion</key>
                \t<string>1.11</string>
                </dict>
                </plist>
                """).formatted(b64).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] updatePlist(Document document, byte[] adjustmentData)
            throws IOException {
        Element dict = firstElement(document.getDocumentElement(), "dict");
        Element data = null;
        if (dict != null) {
            NodeList kids = dict.getChildNodes();
            for (int n = 0; n < kids.getLength(); n++) {
                Node node = kids.item(n);
                if (node.getNodeType() == Node.ELEMENT_NODE && "key".equals(node.getNodeName())
                        && "adjustmentData".equals(node.getTextContent().trim())) {
                    data = nextElement(node);
                    break;
                }
            }
        }
        if (data == null || !"data".equals(data.getNodeName())) {
            throw new IOException("AAE plist has no writable adjustmentData field");
        }
        data.setTextContent(Base64.getEncoder().encodeToString(adjustmentData));
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            if (document.getDoctype() != null) {
                String publicId = document.getDoctype().getPublicId();
                String systemId = document.getDoctype().getSystemId();
                if (publicId != null) transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, publicId);
                if (systemId != null) transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, systemId);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IOException("Could not serialize AAE sidecar", e);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null) {
            throw new IOException("AAE sidecar has no parent directory: " + target);
        }
        Files.createDirectories(directory);
        String prefix = absolute.getFileName().toString();
        if (prefix.length() < 3) prefix = "aae" + prefix;
        Path temp = Files.createTempFile(directory, prefix + ".", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // --- JSON object graph → AaeSidecar --------------------------------------

    @SuppressWarnings("unchecked")
    private static Optional<AaeSidecar> fromJson(Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Map<String, Object> meta = asMap(map.get("metadata"));
        int masterW = intOf(meta, "masterWidth", 0);
        int masterH = intOf(meta, "masterHeight", 0);
        int orientation = intOf(meta, "orientation", 1);

        Crop crop = null;
        double straighten = 0;
        boolean unsupportedGeometry = false;
        boolean unsupportedEffects = false;
        List<String> enabled = new ArrayList<>();

        Object adjustments = map.get("adjustments");
        if (adjustments instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> adj = asMap(o);
                if (adj.isEmpty() || !Boolean.TRUE.equals(adj.get("enabled"))) {
                    continue; // disabled adjustments have no visible effect
                }
                String id = String.valueOf(adj.get("identifier"));
                enabled.add(id);
                Map<String, Object> settings = asMap(adj.get("settings"));
                if ("Crop".equals(id)) {
                    crop = new Crop(intOf(settings, "xOrigin", 0), intOf(settings, "yOrigin", 0),
                            intOf(settings, "width", 0), intOf(settings, "height", 0));
                    straighten = doubleOf(settings, "straightenAngle", 0);
                    if (doubleOf(settings, "pitch", 0) != 0 || doubleOf(settings, "yaw", 0) != 0) {
                        unsupportedGeometry = true;
                    }
                } else {
                    // Any other enabled adjustment is an engine-only effect we
                    // can't reproduce (Portrait/DepthEffect, filters, retouch…).
                    unsupportedEffects = true;
                }
            }
        }
        // A degenerate or empty crop rectangle is no crop at all.
        if (crop != null && (crop.width() <= 0 || crop.height() <= 0)) {
            crop = null;
        }
        return Optional.of(new AaeSidecar(masterW, masterH, orientation, crop, straighten,
                unsupportedGeometry, unsupportedEffects, enabled));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static int intOf(Map<String, Object> m, String key, int fallback) {
        return (int) Math.round(doubleOf(m, key, fallback));
    }

    private static double doubleOf(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
