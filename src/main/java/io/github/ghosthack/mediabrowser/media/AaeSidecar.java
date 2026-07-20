package io.github.ghosthack.mediabrowser.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
            byte[] adjustmentData = extractAdjustmentData(xml);
            if (adjustmentData == null) {
                return Optional.empty();
            }
            String json = inflateRawDeflate(adjustmentData);
            if (json == null || json.isBlank() || json.charAt(0) != '{') {
                return Optional.empty(); // legacy binary-plist payload, or not JSON
            }
            return fromJson(AaeJson.parse(json));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // --- plist (XML) → adjustmentData bytes ----------------------------------

    /**
     * Pulls the base64-decoded bytes of the {@code adjustmentData} {@code <data>}
     * value out of an Apple plist. External entity / DTD loading is disabled, so
     * the {@code DOCTYPE}'s apple.com URL is never fetched and the parser is not
     * an XXE vector.
     */
    private static byte[] extractAdjustmentData(byte[] xml) {
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
                return Base64.getDecoder().decode(b64);
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
