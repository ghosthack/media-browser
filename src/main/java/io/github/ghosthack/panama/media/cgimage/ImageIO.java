package io.github.ghosthack.panama.media.cgimage;

import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.Dimensions;
import io.github.ghosthack.panama.media.core.ImageDimensions;
import io.github.ghosthack.panama.media.core.PixelFormat;
import io.github.ghosthack.panama.media.corefoundation.CoreFoundation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thick helper API over Apple's CGImageSource / CGImageDestination pipeline
 * for still-image decode, thumbnail, and transcode.
 * <p>
 * This class is <em>a helper, not a requirement</em>. Every primitive it
 * calls is also available as a public static method on its own sibling
 * class in this package, so clients that want a custom pipeline can
 * replicate any of the flows below using the same building blocks:
 * <ul>
 *   <li>{@link CGImageSource} — source creation, properties, thumbnail /
 *       image decode, CF constant keys for options and GIF metadata</li>
 *   <li>{@link CGImageDestination} — destination creation, add image,
 *       finalise, lossy-quality key</li>
 *   <li>{@link CGImage} — {@code CGImageGetWidth} / {@code GetHeight}</li>
 *   <li>{@link CGColorSpace} — {@code CGColorSpaceCreateDeviceRGB}</li>
 *   <li>{@link CGBitmapContext} — bitmap context constructor, snapshot to
 *       {@code CGImage}, {@link CGBitmapContext.BitmapInfo} constants</li>
 *   <li>{@link CGContext} — draw image, set interpolation quality</li>
 *   <li>{@link CGGeometry} — {@code CGPoint} / {@code CGSize} /
 *       {@code CGRect} layouts and allocators</li>
 * </ul>
 * <p>
 * EXIF orientation is applied automatically by {@link #decode} /
 * {@link #decodeThumbnail} (via
 * {@code kCGImageSourceCreateThumbnailWithTransform=true}) and by
 * {@link #getSize} (dimensions are swapped for 90/270 rotations).
 * <p>
 * Decoded images are always 32-bit BGRA with premultiplied alpha. Thread-safe
 * when each thread uses its own {@link Arena}. Only functional on macOS —
 * {@link #isAvailable()} returns {@code false} elsewhere.
 */
public final class ImageIO {

    private ImageIO() {}

    /** Bytes per pixel for 32-bit output. */
    private static final int RGBA_BPP = 4;

    // ── Availability ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the native frameworks were loaded successfully.
     * Always returns {@code false} on non-macOS platforms.
     */
    public static boolean isAvailable() {
        return Frameworks.AVAILABLE;
    }

    // ── Format detection ────────────────────────────────────────────────

    /**
     * Probes whether Apple's ImageIO can identify (and therefore decode) the
     * given header bytes.
     */
    public static boolean canDecode(byte[] header, int len) {
        if (!Frameworks.AVAILABLE || len <= 0) return false;

        // Fast-path: WebP magic bytes. CGImageSourceCreateWithData does not
        // reliably identify WebP from partial header data, so recognise the
        // RIFF....WEBP magic directly.
        if (len >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return true;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(len);
            MemorySegment.copy(header, 0, buf, ValueLayout.JAVA_BYTE, 0, len);
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, buf, len, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData)) return false;

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc)) return false;
                return CGImageSource.getStatus(imgSrc) >= CGImageSource.STATUS_INCOMPLETE;
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Size / orientation queries ──────────────────────────────────────

    /**
     * Returns display-oriented dimensions (EXIF orientation applied).
     *
     * @throws IllegalStateException if the native frameworks are unavailable
     * @throws DecodeException       if the data is not a valid image
     */
    public static Dimensions getSize(byte[] imageData) {
        ensureAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, buf,
                    (long) imageData.length, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return getSizeFromSource(imgSrc);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /** See {@link #getSize(byte[])}. */
    public static Dimensions getSize(MemorySegment imageData, long size) {
        ensureAvailable();
        MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(
                MemorySegment.NULL, imageData, size, CoreFoundation.kCFAllocatorNull());
        if (MemorySegment.NULL.equals(cfData))
            throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

        MemorySegment imgSrc = MemorySegment.NULL;
        try {
            imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(imgSrc))
                throw new DecodeException("Unsupported image format");
            return getSizeFromSource(imgSrc);
        } finally {
            CoreFoundation.cfRelease(imgSrc);
            CoreFoundation.cfRelease(cfData);
        }
    }

    /** See {@link #getSize(byte[])}. Reads only as much as CG needs from disk. */
    public static Dimensions getSize(String path) {
        ensureAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfUrl = CoreFoundation.cfUrlCreate(arena, path);
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format: " + path);
                return getSizeFromSource(imgSrc);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfUrl);
            }
        }
    }

    /**
     * Returns the raw EXIF orientation tag (1–8), or 1 if missing. Unlike
     * {@link #getSize}, this does <em>not</em> swap dimensions.
     */
    public static int getOrientation(byte[] imageData) {
        ensureAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, buf,
                    (long) imageData.length, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return readOrientation(imgSrc);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /** See {@link #getOrientation(byte[])}. */
    public static int getOrientation(MemorySegment imageData, long size) {
        ensureAvailable();
        MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(
                MemorySegment.NULL, imageData, size, CoreFoundation.kCFAllocatorNull());
        if (MemorySegment.NULL.equals(cfData))
            throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

        MemorySegment imgSrc = MemorySegment.NULL;
        try {
            imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(imgSrc))
                throw new DecodeException("Unsupported image format");
            return readOrientation(imgSrc);
        } finally {
            CoreFoundation.cfRelease(imgSrc);
            CoreFoundation.cfRelease(cfData);
        }
    }

    /** See {@link #getOrientation(byte[])}. */
    public static int getOrientation(String path) {
        ensureAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfUrl = CoreFoundation.cfUrlCreate(arena, path);
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format: " + path);
                return readOrientation(imgSrc);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfUrl);
            }
        }
    }

    // ── Property introspection ──────────────────────────────────────────

    /**
     * Metadata extracted from a {@code CGImageSource} properties dictionary
     * in a single open. Equivalent to calling {@link #getSize},
     * {@link #getOrientation}, and one libexif pass per file — but
     * amortised into one {@code CGImageSourceCopyPropertiesAtIndex}.
     * <p>
     * {@code width} / {@code height} are <em>display-oriented</em> (swapped
     * for 90/270 rotations) to match {@link #getSize}. {@code orientation}
     * is the raw EXIF tag value (1–8); 1 if the tag is absent, matching
     * {@link #getOrientation} semantics.
     * <p>
     * {@code bitDepth}, {@code colorModel}, {@code hasAlpha}, and
     * {@code colorProfile} are nullable — CG doesn't always populate them
     * (e.g. {@code colorProfile} is absent when no embedded ICC profile).
     * <p>
     * {@link #exif()}, {@link #tiff()}, and {@link #gps()} are flat maps
     * keyed by the tag's short name (no namespace prefix):
     * {@code "DateTimeOriginal"}, {@code "ExposureTime"}, {@code "Make"},
     * {@code "Latitude"}. Values are typed:
     * <ul>
     *   <li>{@link Long} — {@code CFNumber} integer types</li>
     *   <li>{@link Double} — {@code CFNumber} floating-point types</li>
     *   <li>{@link Boolean} — {@code CFBoolean}</li>
     *   <li>{@link String} — {@code CFString}</li>
     *   <li>{@link List}{@code <Object>} — {@code CFArray} (entries follow
     *       the same type rules, recursively)</li>
     *   <li>{@link Map}{@code <String, Object>} — nested {@code CFDictionary}</li>
     * </ul>
     * Unsupported CF types ({@code CFData}, {@code CFDate}) are omitted.
     * All returned collections are immutable.
     * <p>
     * <b>Lazy sub-dicts.</b> The EXIF / TIFF / GPS sub-dictionaries are
     * flattened on first access, not at read time. Callers that only look at
     * scalars ({@code width}, {@code orientation}, {@code bitDepth}, …) pay
     * zero cost for the native→Java conversion of the sub-dicts they don't
     * touch. The underlying {@code CFDictionary} is retained for the
     * lifetime of this object and released when it becomes unreachable
     * (via {@link java.lang.ref.Cleaner}).
     * <p>
     * Instances are safe to share across threads: scalar fields are final,
     * and lazy flattening uses double-checked locking to produce an
     * immutable snapshot.
     * <p>
     * Implements {@link AutoCloseable} for callers who want deterministic
     * release of the native dictionary rather than waiting for GC.
     * Typical use:
     * <pre>{@code
     * try (var p = ImageIO.readProperties(path)) {
     *     record(p.width(), p.orientation(), p.exif().get("Make"));
     * }   // CFDict released here, not when the GC fires
     * }</pre>
     * Close semantics:
     * <ul>
     *   <li>Scalar accessors ({@link #width}, {@link #orientation}, …) stay
     *       valid — they're plain Java fields.</li>
     *   <li>Sub-dicts already materialised before {@link #close} remain
     *       accessible (they're immutable Java {@link Map}s by then).</li>
     *   <li>Sub-dicts <em>not</em> yet materialised throw
     *       {@link IllegalStateException} on access after close.</li>
     *   <li>{@link #close} is idempotent and unregisters from the
     *       {@link Cleaner}, so leaving an instance unclosed is safe — the
     *       Cleaner is a backstop, not the primary release path.</li>
     * </ul>
     */
    public static final class ImageProperties implements AutoCloseable {
        private static final Cleaner CLEANER = Cleaner.create();

        private final int width;
        private final int height;
        private final int orientation;
        private final Integer bitDepth;
        private final String colorModel;
        private final Boolean hasAlpha;
        private final String colorProfile;
        private final RetainedProps retained;
        private final Cleaner.Cleanable cleanable;

        ImageProperties(int width, int height, int orientation,
                        Integer bitDepth, String colorModel, Boolean hasAlpha,
                        String colorProfile, MemorySegment propsDict) {
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.bitDepth = bitDepth;
            this.colorModel = colorModel;
            this.hasAlpha = hasAlpha;
            this.colorProfile = colorProfile;
            this.retained = new RetainedProps(propsDict);
            // Cleaner tracks `this`; when it becomes unreachable, the
            // retained CFDict is released. RetainedProps holds no
            // back-reference to `this`, so no cycle keeps us alive.
            // close() calls cleanable.clean() to run the cleanup eagerly
            // and unregister from the Cleaner in one step.
            this.cleanable = CLEANER.register(this, retained);
        }

        public int width() { return width; }
        public int height() { return height; }
        public int orientation() { return orientation; }
        public Integer bitDepth() { return bitDepth; }
        public String colorModel() { return colorModel; }
        public Boolean hasAlpha() { return hasAlpha; }
        public String colorProfile() { return colorProfile; }

        /** EXIF sub-dictionary, flattened on first call and cached. */
        public Map<String, Object> exif() { return retained.exif(); }

        /** TIFF sub-dictionary, flattened on first call and cached. */
        public Map<String, Object> tiff() { return retained.tiff(); }

        /** GPS sub-dictionary, flattened on first call and cached. */
        public Map<String, Object> gps()  { return retained.gps(); }

        /**
         * Looks up a single tag in the EXIF sub-dictionary without
         * materialising the whole sub-dict. Returns {@code null} if the
         * sub-dict or tag is absent.
         * <p>
         * Value type follows the same rules as {@link #exif()}: {@link Long}
         * / {@link Double} / {@link Boolean} / {@link String} /
         * {@link List} / {@link Map}, or {@code null} for unsupported CF
         * types. For callers that want a handful of tags this skips the full
         * flatten — each call is a {@code CFDictionaryGetValue} probe plus
         * a transient {@code CFString} for the key.
         * <p>
         * If the EXIF sub-dict has already been materialised by a prior
         * {@link #exif()} call, the lookup goes through the cached
         * {@link Map} instead of the native dict — same result, no
         * redundant native work.
         *
         * @param tag EXIF tag short name, e.g. {@code "Make"},
         *            {@code "DateTimeOriginal"}, {@code "ExposureTime"}
         * @return the tag's value, or {@code null}
         * @throws IllegalStateException if called after {@link #close()} and
         *                               the EXIF sub-dict was not
         *                               materialised before release
         */
        public Object exifValue(String tag) { return retained.exifValue(tag); }

        /** See {@link #exifValue}. Looks up into the TIFF sub-dictionary. */
        public Object tiffValue(String tag) { return retained.tiffValue(tag); }

        /** See {@link #exifValue}. Looks up into the GPS sub-dictionary. */
        public Object gpsValue(String tag)  { return retained.gpsValue(tag);  }

        /**
         * Releases the retained native dictionary eagerly. Idempotent.
         * After close, already-materialised sub-dicts remain accessible
         * but un-materialised ones throw {@link IllegalStateException}.
         */
        @Override
        public void close() {
            cleanable.clean();
        }
    }

    /**
     * Owns the retained {@code CFDictionary} and the lazily-materialised
     * sub-dict snapshots. Separate from {@link ImageProperties} so the
     * {@link Cleaner} cleanup action doesn't transitively reference the
     * object it's tracking.
     */
    private static final class RetainedProps implements Runnable {
        private volatile MemorySegment propsDict;          // released on cleanup
        private volatile Map<String, Object> exifMap;
        private volatile Map<String, Object> tiffMap;
        private volatile Map<String, Object> gpsMap;

        RetainedProps(MemorySegment propsDict) {
            this.propsDict = propsDict;
        }

        Map<String, Object> exif() { return lazy("exif()", () -> exifMap, m -> exifMap = m,
                CGImageSource.kExifDictionary()); }

        Map<String, Object> tiff() { return lazy("tiff()", () -> tiffMap, m -> tiffMap = m,
                CGImageSource.kTIFFDictionary()); }

        Map<String, Object> gps()  { return lazy("gps()",  () -> gpsMap,  m -> gpsMap  = m,
                CGImageSource.kGPSDictionary()); }

        Object exifValue(String tag) { return lookup("exifValue", tag,
                CGImageSource.kExifDictionary(), () -> exifMap); }

        Object tiffValue(String tag) { return lookup("tiffValue", tag,
                CGImageSource.kTIFFDictionary(), () -> tiffMap); }

        Object gpsValue(String tag)  { return lookup("gpsValue",  tag,
                CGImageSource.kGPSDictionary(),  () -> gpsMap); }

        /**
         * Shared double-checked-locking body for the three sub-dict getters.
         * Returns a cached snapshot if present; otherwise flattens from the
         * retained native dict. If the dict has already been released (via
         * {@code close()} or Cleaner) and no cached snapshot exists, throws
         * {@link IllegalStateException}.
         */
        private Map<String, Object> lazy(String accessor,
                                         java.util.function.Supplier<Map<String, Object>> getter,
                                         java.util.function.Consumer<Map<String, Object>> setter,
                                         MemorySegment subKey) {
            Map<String, Object> m = getter.get();
            if (m != null) return m;
            synchronized (this) {
                m = getter.get();
                if (m != null) return m;
                MemorySegment d = propsDict;
                if (d == null)
                    throw new IllegalStateException(
                            "ImageProperties." + accessor + " called after close(); "
                                    + "this sub-dict was never materialised before release");
                m = flattenSubdict(d, subKey);
                setter.accept(m);
            }
            return m;
        }

        /**
         * Single-tag lookup shared by {@code exifValue} / {@code tiffValue}
         * / {@code gpsValue}. Short-circuits through the cached flatten Map
         * if one has already been materialised; otherwise probes the native
         * sub-dict directly with a transient {@code CFString} key. Throws
         * {@link IllegalStateException} if the native dict has been released
         * and no cached snapshot exists.
         */
        private Object lookup(String accessor, String tag, MemorySegment subKey,
                              java.util.function.Supplier<Map<String, Object>> cachedGetter) {
            if (tag == null) throw new IllegalArgumentException("tag must be non-null");
            Map<String, Object> cached = cachedGetter.get();
            if (cached != null) return cached.get(tag);
            synchronized (this) {
                cached = cachedGetter.get();
                if (cached != null) return cached.get(tag);
                MemorySegment d = propsDict;
                if (d == null)
                    throw new IllegalStateException(
                            "ImageProperties." + accessor + "(\"" + tag + "\") called after close(); "
                                    + "the enclosing sub-dict was not materialised before release");
                MemorySegment sub = CoreFoundation.cfDictionaryGetValue(d, subKey);
                if (MemorySegment.NULL.equals(sub) || !CoreFoundation.isCFDictionary(sub)) return null;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment cfKey = CoreFoundation.cfStringCreate(arena, tag);
                    try {
                        MemorySegment valRef = CoreFoundation.cfDictionaryGetValue(sub, cfKey);
                        if (MemorySegment.NULL.equals(valRef)) return null;
                        return convertCFValue(valRef);
                    } finally {
                        CoreFoundation.cfRelease(cfKey);
                    }
                }
            }
        }

        @Override
        public void run() {
            // Idempotent; Cleaner + close() can both try to release.
            MemorySegment d;
            synchronized (this) {
                d = propsDict;
                propsDict = null;
            }
            if (d != null) CoreFoundation.cfRelease(d);
        }
    }

    /**
     * Reads all metadata from the bytes in a single source-open. See
     * {@link ImageProperties}.
     *
     * @throws IllegalStateException if the native frameworks are unavailable
     * @throws DecodeException       if the data is not a valid image
     */
    public static ImageProperties readProperties(byte[] imageData) {
        ensureAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, buf,
                    (long) imageData.length, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return readPropertiesFromSource(imgSrc);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /** See {@link #readProperties(byte[])}. */
    public static ImageProperties readProperties(MemorySegment imageData, long size) {
        ensureAvailable();
        MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(
                MemorySegment.NULL, imageData, size, CoreFoundation.kCFAllocatorNull());
        if (MemorySegment.NULL.equals(cfData))
            throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

        MemorySegment imgSrc = MemorySegment.NULL;
        try {
            imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(imgSrc))
                throw new DecodeException("Unsupported image format");
            return readPropertiesFromSource(imgSrc);
        } finally {
            CoreFoundation.cfRelease(imgSrc);
            CoreFoundation.cfRelease(cfData);
        }
    }

    /**
     * See {@link #readProperties(byte[])}. Reads only as much as CG needs
     * from disk — avoids buffering into the Java heap.
     */
    public static ImageProperties readProperties(String path) {
        ensureAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfUrl = CoreFoundation.cfUrlCreate(arena, path);
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format: " + path);
                return readPropertiesFromSource(imgSrc);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfUrl);
            }
        }
    }

    /**
     * Pulls top-level fields from the image source's properties dictionary
     * and transfers ownership of the dict to the returned
     * {@link ImageProperties} for lazy sub-dict flattening. Caller owns
     * {@code imgSrc}; we own {@code props} until construction succeeds, at
     * which point ownership transfers and {@code ImageProperties} releases
     * via {@link Cleaner} when unreachable.
     */
    private static ImageProperties readPropertiesFromSource(MemorySegment imgSrc) {
        MemorySegment props = CGImageSource.copyPropertiesAtIndex(imgSrc, 0L, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(props))
            throw new DecodeException("Failed to read image properties");

        boolean ownershipTransferred = false;
        try {
            int rawW = dictGetInt(props, CGImageSource.kPixelWidth(), -1);
            int rawH = dictGetInt(props, CGImageSource.kPixelHeight(), -1);
            if (rawW <= 0 || rawH <= 0)
                throw new DecodeException("Invalid image dimensions: " + rawW + "x" + rawH);
            ImageDimensions.validateDimensions(rawW, rawH);

            int rawOrientation = dictGetInt(props, CGImageSource.kOrientation(), 1);
            int orientation = (rawOrientation >= 1 && rawOrientation <= 8) ? rawOrientation : 1;

            // Display-oriented width/height (orientation 5–8 = 90/270 rotation swaps the axes).
            int displayW = (orientation >= 5 && orientation <= 8) ? rawH : rawW;
            int displayH = (orientation >= 5 && orientation <= 8) ? rawW : rawH;

            Integer bitDepth   = dictGetIntBoxed(props, CGImageSource.kDepth());
            String colorModel  = dictGetStringOrNull(props, CGImageSource.kColorModel());
            Boolean hasAlpha   = dictGetBooleanOrNull(props, CGImageSource.kHasAlpha());
            String colorProf   = dictGetStringOrNull(props, CGImageSource.kProfileName());

            ImageProperties p = new ImageProperties(displayW, displayH, orientation,
                    bitDepth, colorModel, hasAlpha, colorProf, props);
            ownershipTransferred = true;
            return p;
        } finally {
            if (!ownershipTransferred) CoreFoundation.cfRelease(props);
        }
    }

    private static Integer dictGetIntBoxed(MemorySegment dict, MemorySegment key) {
        MemorySegment val = CoreFoundation.cfDictionaryGetValue(dict, key);
        if (MemorySegment.NULL.equals(val) || !CoreFoundation.isCFNumber(val)) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(ValueLayout.JAVA_INT);
            return CoreFoundation.cfNumberGetInt(val, buf)
                    ? buf.get(ValueLayout.JAVA_INT, 0) : null;
        }
    }

    private static String dictGetStringOrNull(MemorySegment dict, MemorySegment key) {
        MemorySegment val = CoreFoundation.cfDictionaryGetValue(dict, key);
        if (MemorySegment.NULL.equals(val) || !CoreFoundation.isCFString(val)) return null;
        return CoreFoundation.cfStringToJavaString(val);
    }

    private static Boolean dictGetBooleanOrNull(MemorySegment dict, MemorySegment key) {
        MemorySegment val = CoreFoundation.cfDictionaryGetValue(dict, key);
        if (MemorySegment.NULL.equals(val) || !CoreFoundation.isCFBoolean(val)) return null;
        return CoreFoundation.cfBooleanGetValue(val);
    }

    /**
     * Returns an empty, immutable map if the parent dict doesn't contain a
     * sub-dictionary at {@code subKey}; otherwise flattens it.
     */
    private static Map<String, Object> flattenSubdict(MemorySegment parent, MemorySegment subKey) {
        if (subKey == null) return Map.of();
        MemorySegment sub = CoreFoundation.cfDictionaryGetValue(parent, subKey);
        if (MemorySegment.NULL.equals(sub) || !CoreFoundation.isCFDictionary(sub)) return Map.of();
        return flattenCFDictionary(sub);
    }

    /** Walks a CFDictionary, converts keys to Java strings and values via {@link #convertCFValue}. */
    private static Map<String, Object> flattenCFDictionary(MemorySegment dict) {
        long n = CoreFoundation.cfDictionaryGetCount(dict);
        if (n <= 0) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>((int) Math.min(n, 1024));
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment keys   = temp.allocate(ValueLayout.ADDRESS, n);
            MemorySegment values = temp.allocate(ValueLayout.ADDRESS, n);
            CoreFoundation.cfDictionaryGetKeysAndValues(dict, keys, values);
            for (long i = 0; i < n; i++) {
                MemorySegment keyRef = keys.getAtIndex(ValueLayout.ADDRESS, i);
                MemorySegment valRef = values.getAtIndex(ValueLayout.ADDRESS, i);
                if (!CoreFoundation.isCFString(keyRef)) continue;
                String key = CoreFoundation.cfStringToJavaString(keyRef);
                if (key == null) continue;
                Object val = convertCFValue(valRef);
                if (val != null) out.put(key, val);
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Converts a {@code CFTypeRef} to its Java equivalent.
     * {@code CFNumber} → {@code Long}/{@code Double}; {@code CFBoolean} →
     * {@code Boolean}; {@code CFString} → {@code String}; {@code CFArray}
     * and {@code CFDictionary} recurse. Unsupported types
     * ({@code CFData}, {@code CFDate}) return {@code null}.
     */
    private static Object convertCFValue(MemorySegment ref) {
        if (MemorySegment.NULL.equals(ref)) return null;
        if (CoreFoundation.isCFString(ref))     return CoreFoundation.cfStringToJavaString(ref);
        if (CoreFoundation.isCFBoolean(ref))    return CoreFoundation.cfBooleanGetValue(ref);
        if (CoreFoundation.isCFNumber(ref)) {
            try (Arena a = Arena.ofConfined()) {
                if (CoreFoundation.cfNumberIsFloatType(ref)) {
                    MemorySegment buf = a.allocate(ValueLayout.JAVA_DOUBLE);
                    return CoreFoundation.cfNumberGetDouble(ref, buf)
                            ? buf.get(ValueLayout.JAVA_DOUBLE, 0) : null;
                } else {
                    MemorySegment buf = a.allocate(ValueLayout.JAVA_LONG);
                    return CoreFoundation.cfNumberGetLongLong(ref, buf)
                            ? buf.get(ValueLayout.JAVA_LONG, 0) : null;
                }
            }
        }
        if (CoreFoundation.isCFArray(ref)) {
            long n = CoreFoundation.cfArrayGetCount(ref);
            List<Object> out = new ArrayList<>((int) Math.min(n, 1024));
            for (long i = 0; i < n; i++) {
                Object item = convertCFValue(CoreFoundation.cfArrayGetValueAtIndex(ref, i));
                if (item != null) out.add(item);
            }
            return List.copyOf(out);
        }
        if (CoreFoundation.isCFDictionary(ref)) return flattenCFDictionary(ref);
        return null;
    }

    // ── Full-size decode ────────────────────────────────────────────────

    /** Decodes an image into Arena-managed BGRA pixel memory. */
    public static DecodedImage<PixelFormat> decode(Arena arena, byte[] imageData) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment buf = temp.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, buf,
                    (long) imageData.length, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return decodeFromSource(arena, temp, imgSrc, 0, InterpolationQuality.HIGH);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /** See {@link #decode(Arena, byte[])}. */
    public static DecodedImage<PixelFormat> decode(Arena arena, MemorySegment imageData, long size) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(
                    MemorySegment.NULL, imageData, size, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return decodeFromSource(arena, temp, imgSrc, 0, InterpolationQuality.HIGH);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /** Decodes directly from a file path without buffering into the Java heap. */
    public static DecodedImage<PixelFormat> decodeFromPath(Arena arena, String path) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cfUrl = CoreFoundation.cfUrlCreate(temp, path);
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format: " + path);
                return decodeFromSource(arena, temp, imgSrc, 0, InterpolationQuality.HIGH);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfUrl);
            }
        }
    }

    // ── Thumbnail decode ────────────────────────────────────────────────

    /**
     * Decodes an image as a thumbnail, capping the longer edge at
     * {@code maxPixelSize}. Delegates to
     * {@code CGImageSourceCreateThumbnailAtIndex} with
     * {@code kCGImageSourceThumbnailMaxPixelSize}, so CG performs the
     * downscale natively.
     * <p>
     * {@code maxPixelSize} is a cap, not a contract — CG may return a
     * smaller embedded thumbnail. When {@code maxPixelSize} ≥ full
     * dimension this is equivalent to {@link #decode}. EXIF orientation is
     * applied automatically.
     */
    public static DecodedImage<PixelFormat> decodeThumbnail(
            Arena arena, byte[] imageData, int maxPixelSize) {
        return decodeThumbnail(arena, imageData, maxPixelSize, InterpolationQuality.HIGH);
    }

    /**
     * See {@link #decodeThumbnail(Arena, byte[], int)}. {@code quality}
     * applies only when CG returns a {@code CGImage} whose size differs from
     * the requested cap (common with embedded thumbnails).
     */
    public static DecodedImage<PixelFormat> decodeThumbnail(
            Arena arena, byte[] imageData, int maxPixelSize, InterpolationQuality quality) {
        ensureAvailable();
        requirePositive(maxPixelSize);
        requireQuality(quality);

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment buf = temp.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, buf,
                    (long) imageData.length, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return decodeFromSource(arena, temp, imgSrc, maxPixelSize, quality);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /** See {@link #decodeThumbnail(Arena, byte[], int)}. */
    public static DecodedImage<PixelFormat> decodeThumbnail(
            Arena arena, MemorySegment imageData, long size, int maxPixelSize) {
        return decodeThumbnail(arena, imageData, size, maxPixelSize, InterpolationQuality.HIGH);
    }

    /** See {@link #decodeThumbnail(Arena, byte[], int, InterpolationQuality)}. */
    public static DecodedImage<PixelFormat> decodeThumbnail(
            Arena arena, MemorySegment imageData, long size, int maxPixelSize,
            InterpolationQuality quality) {
        ensureAvailable();
        requirePositive(maxPixelSize);
        requireQuality(quality);

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, imageData, size,
                    CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return decodeFromSource(arena, temp, imgSrc, maxPixelSize, quality);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /**
     * Decodes a thumbnail directly from a file path. Avoids loading the
     * full file into the Java heap. See
     * {@link #decodeThumbnail(Arena, byte[], int)}.
     */
    public static DecodedImage<PixelFormat> decodeThumbnailFromPath(
            Arena arena, String path, int maxPixelSize) {
        return decodeThumbnailFromPath(arena, path, maxPixelSize, InterpolationQuality.HIGH);
    }

    /** See {@link #decodeThumbnail(Arena, byte[], int, InterpolationQuality)}. */
    public static DecodedImage<PixelFormat> decodeThumbnailFromPath(
            Arena arena, String path, int maxPixelSize, InterpolationQuality quality) {
        ensureAvailable();
        requirePositive(maxPixelSize);
        requireQuality(quality);

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cfUrl = CoreFoundation.cfUrlCreate(temp, path);
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format: " + path);
                return decodeFromSource(arena, temp, imgSrc, maxPixelSize, quality);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfUrl);
            }
        }
    }

    private static void requirePositive(int maxPixelSize) {
        if (maxPixelSize <= 0)
            throw new IllegalArgumentException("maxPixelSize must be > 0, got " + maxPixelSize);
    }

    private static void requireQuality(InterpolationQuality quality) {
        if (quality == null)
            throw new IllegalArgumentException("quality must be non-null");
    }

    // ── Transcode ───────────────────────────────────────────────────────

    /**
     * Encodes an input image into one or more outputs in a single call,
     * amortising the source decode. Each {@link EncodeTarget} picks its own
     * size, format, destination, quality, orientation policy, and
     * interpolation.
     *
     * @return array positionally aligned with {@code targets}; entry is
     *         {@code null} for targets with a non-null {@code path}
     *         (written to disk), otherwise the encoded byte array
     */
    public static byte[][] transcode(byte[] imageData, List<EncodeTarget> targets) {
        ensureAvailable();
        requireTargets(targets);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
            MemorySegment cfData = CoreFoundation.cfDataCreateNoCopy(MemorySegment.NULL, buf,
                    (long) imageData.length, CoreFoundation.kCFAllocatorNull());
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateWithBytesNoCopy returned NULL");

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithData(cfData, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format");
                return transcodeFromImgSrc(arena, imgSrc, targets);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfData);
            }
        }
    }

    /** See {@link #transcode(byte[], List)}. */
    public static byte[][] transcodeFromPath(String path, List<EncodeTarget> targets) {
        ensureAvailable();
        requireTargets(targets);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfUrl = CoreFoundation.cfUrlCreate(arena, path);
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path: " + path);

            MemorySegment imgSrc = MemorySegment.NULL;
            try {
                imgSrc = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(imgSrc))
                    throw new DecodeException("Unsupported image format: " + path);
                return transcodeFromImgSrc(arena, imgSrc, targets);
            } finally {
                CoreFoundation.cfRelease(imgSrc);
                CoreFoundation.cfRelease(cfUrl);
            }
        }
    }

    private static void requireTargets(List<EncodeTarget> targets) {
        if (targets == null || targets.isEmpty())
            throw new IllegalArgumentException("targets must be non-empty");
        for (int i = 0; i < targets.size(); i++)
            if (targets.get(i) == null)
                throw new IllegalArgumentException("targets[" + i + "] is null");
    }

    private static byte[][] transcodeFromImgSrc(Arena arena, MemorySegment imgSrc,
                                                List<EncodeTarget> targets) {
        MemorySegment props = CGImageSource.copyPropertiesAtIndex(imgSrc, 0L, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(props))
            throw new DecodeException("Failed to read image properties");

        int rawW;
        int rawH;
        int orientation;
        try {
            rawW = dictGetInt(props, CGImageSource.kPixelWidth(), -1);
            rawH = dictGetInt(props, CGImageSource.kPixelHeight(), -1);
            orientation = dictGetInt(props, CGImageSource.kOrientation(), 1);
        } finally {
            CoreFoundation.cfRelease(props);
        }
        if (rawW <= 0 || rawH <= 0)
            throw new DecodeException("Invalid image dimensions: " + rawW + "x" + rawH);
        ImageDimensions.validateDimensions(rawW, rawH);

        int uprightW = (orientation >= 5 && orientation <= 8) ? rawH : rawW;
        int uprightH = (orientation >= 5 && orientation <= 8) ? rawW : rawH;

        MemorySegment uprightImg = MemorySegment.NULL;
        MemorySegment rawImg = MemorySegment.NULL;

        byte[][] results = new byte[targets.size()][];
        try {
            for (int i = 0; i < targets.size(); i++) {
                EncodeTarget t = targets.get(i);
                MemorySegment srcImg;
                int srcW;
                int srcH;
                if (t.bakesOrientation()) {
                    if (MemorySegment.NULL.equals(uprightImg)) {
                        uprightImg = createTransformedImage(arena, imgSrc, Math.max(uprightW, uprightH));
                        if (MemorySegment.NULL.equals(uprightImg))
                            throw new DecodeException("Source decode (transformed) returned NULL");
                    }
                    srcImg = uprightImg;
                    srcW = uprightW;
                    srcH = uprightH;
                } else {
                    if (MemorySegment.NULL.equals(rawImg)) {
                        rawImg = CGImageSource.createImageAtIndex(imgSrc, 0L, MemorySegment.NULL);
                        if (MemorySegment.NULL.equals(rawImg))
                            throw new DecodeException("Source decode (raw) returned NULL");
                    }
                    srcImg = rawImg;
                    srcW = rawW;
                    srcH = rawH;
                }
                results[i] = encodeOneTarget(arena, srcImg, srcW, srcH, t);
            }
        } finally {
            CoreFoundation.cfRelease(rawImg);
            CoreFoundation.cfRelease(uprightImg);
        }
        return results;
    }

    /**
     * Decodes a fully-transformed (upright) full-size CGImage using the
     * thumbnail API with the natural max dim — this applies EXIF
     * orientation without scaling.
     */
    private static MemorySegment createTransformedImage(Arena arena, MemorySegment imgSrc, int maxDim) {
        MemorySegment opts = createThumbnailOptions(arena, maxDim);
        try {
            return CGImageSource.createThumbnailAtIndex(imgSrc, 0L, opts);
        } finally {
            CoreFoundation.cfRelease(opts);
        }
    }

    private static byte[] encodeOneTarget(Arena arena, MemorySegment srcImg,
                                          int srcW, int srcH, EncodeTarget t) {
        int tW;
        int tH;
        if (t.maxPixelSize() == 0 || t.maxPixelSize() >= Math.max(srcW, srcH)) {
            tW = srcW;
            tH = srcH;
        } else {
            double ratio = (double) t.maxPixelSize() / Math.max(srcW, srcH);
            tW = Math.max(1, (int) Math.round(srcW * ratio));
            tH = Math.max(1, (int) Math.round(srcH * ratio));
        }

        MemorySegment colorSpace = MemorySegment.NULL;
        MemorySegment ctx = MemorySegment.NULL;
        MemorySegment scaledImg = MemorySegment.NULL;
        try {
            colorSpace = CGColorSpace.createDeviceRGB();
            int stride = tW * RGBA_BPP;
            MemorySegment pixelData = arena.allocate((long) stride * tH, 16);

            ctx = CGBitmapContext.create(pixelData, tW, tH, 8L, stride, colorSpace,
                    CGBitmapContext.BitmapInfo.BGRA_PREMULTIPLIED_LITTLE_ENDIAN);
            if (MemorySegment.NULL.equals(ctx))
                throw new DecodeException("CGBitmapContextCreate returned NULL");

            CGContext.setInterpolationQuality(ctx, t.interpolation().cgValue);
            MemorySegment rect = CGGeometry.CGRect.allocate(arena, 0, 0, tW, tH);
            CGContext.drawImage(ctx, rect, srcImg);

            scaledImg = CGBitmapContext.createImage(ctx);
            if (MemorySegment.NULL.equals(scaledImg))
                throw new DecodeException("CGBitmapContextCreateImage returned NULL");
        } finally {
            CoreFoundation.cfRelease(ctx);
            CoreFoundation.cfRelease(colorSpace);
        }

        try {
            return (t.path() != null)
                    ? writeScaledToPath(arena, scaledImg, t)
                    : writeScaledToBytes(arena, scaledImg, t);
        } finally {
            CoreFoundation.cfRelease(scaledImg);
        }
    }

    private static byte[] writeScaledToPath(Arena arena, MemorySegment cgImage, EncodeTarget t) {
        MemorySegment cfUrl = MemorySegment.NULL;
        MemorySegment cfUti = MemorySegment.NULL;
        MemorySegment dest = MemorySegment.NULL;
        MemorySegment props = MemorySegment.NULL;
        try {
            cfUrl = CoreFoundation.cfUrlCreate(arena, t.path());
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path: " + t.path());

            cfUti = CoreFoundation.cfStringCreate(arena, t.uti());
            dest = CGImageDestination.createWithURL(cfUrl, cfUti, 1L, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(dest))
                throw new DecodeException("CGImageDestinationCreateWithURL failed for UTI '"
                        + t.uti() + "'");

            props = buildWriteProperties(arena, t.quality(), t.orientation());
            CGImageDestination.addImage(dest, cgImage, props);

            if (!CGImageDestination.finalize_(dest))
                throw new DecodeException("CGImageDestinationFinalize failed for: " + t.path());
            return null;
        } finally {
            CoreFoundation.cfRelease(props);
            CoreFoundation.cfRelease(dest);
            CoreFoundation.cfRelease(cfUti);
            CoreFoundation.cfRelease(cfUrl);
        }
    }

    private static byte[] writeScaledToBytes(Arena arena, MemorySegment cgImage, EncodeTarget t) {
        MemorySegment cfData = MemorySegment.NULL;
        MemorySegment cfUti = MemorySegment.NULL;
        MemorySegment dest = MemorySegment.NULL;
        MemorySegment props = MemorySegment.NULL;
        try {
            cfData = CoreFoundation.cfDataCreateMutable(0L);
            if (MemorySegment.NULL.equals(cfData))
                throw new DecodeException("CFDataCreateMutable returned NULL");

            cfUti = CoreFoundation.cfStringCreate(arena, t.uti());
            dest = CGImageDestination.createWithData(cfData, cfUti, 1L, MemorySegment.NULL);
            if (MemorySegment.NULL.equals(dest))
                throw new DecodeException("CGImageDestinationCreateWithData failed for UTI '"
                        + t.uti() + "'");

            props = buildWriteProperties(arena, t.quality(), t.orientation());
            CGImageDestination.addImage(dest, cgImage, props);

            if (!CGImageDestination.finalize_(dest))
                throw new DecodeException("CGImageDestinationFinalize failed");

            return cfDataToByteArray(cfData);
        } finally {
            CoreFoundation.cfRelease(props);
            CoreFoundation.cfRelease(dest);
            CoreFoundation.cfRelease(cfUti);
            CoreFoundation.cfRelease(cfData);
        }
    }

    // ── Writer API ──────────────────────────────────────────────────────

    /** Uniform Type Identifier for JPEG. */
    public static final String UTI_JPEG = "public.jpeg";
    /** Uniform Type Identifier for PNG. */
    public static final String UTI_PNG = "public.png";
    /** Uniform Type Identifier for HEIC (requires macOS 10.13+). */
    public static final String UTI_HEIC = "public.heic";
    /** Uniform Type Identifier for AVIF (requires macOS 13+). */
    public static final String UTI_AVIF = "public.avif";
    /** Uniform Type Identifier for WebP (requires macOS 11+ for encode). */
    public static final String UTI_WEBP = "org.webmproject.webp";
    /** Uniform Type Identifier for TIFF. */
    public static final String UTI_TIFF = "public.tiff";
    /** Uniform Type Identifier for GIF. */
    public static final String UTI_GIF = "com.compuserve.gif";

    /** Sentinel value for "don't write orientation metadata". */
    public static final int ORIENTATION_UNSET = 0;

    /**
     * Returns {@code true} if the current macOS host can encode the given
     * UTI. Probes via a throwaway {@code CGImageDestination}.
     */
    public static boolean canEncode(String uti) {
        if (!Frameworks.AVAILABLE || uti == null || uti.isEmpty()) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfUti = CoreFoundation.cfStringCreate(arena, uti);
            MemorySegment cfData = CoreFoundation.cfDataCreateMutable(0L);
            MemorySegment dest = MemorySegment.NULL;
            try {
                if (MemorySegment.NULL.equals(cfUti) || MemorySegment.NULL.equals(cfData))
                    return false;
                dest = CGImageDestination.createWithData(cfData, cfUti, 1L, MemorySegment.NULL);
                return !MemorySegment.NULL.equals(dest);
            } finally {
                CoreFoundation.cfRelease(dest);
                CoreFoundation.cfRelease(cfData);
                CoreFoundation.cfRelease(cfUti);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Encodes BGRA pixels directly to a file on disk via
     * {@code CGImageDestinationCreateWithURL}.
     *
     * @param pixels      BGRA premultiplied pixel data
     * @param width       image width in pixels
     * @param height      image height in pixels
     * @param stride      bytes per row (typically {@code width * 4})
     * @param path        absolute file path to write
     * @param uti         destination UTI (e.g. {@link #UTI_JPEG})
     * @param quality     compression quality in {@code [0.0, 1.0]} for lossy
     *                    formats, {@code NaN} to use the format default
     * @param orientation EXIF orientation 1–8 to embed, or
     *                    {@link #ORIENTATION_UNSET}
     */
    public static void encodeToPath(MemorySegment pixels, int width, int height, int stride,
                                    String path, String uti, float quality, int orientation) {
        ensureAvailable();
        validateEncodeArgs(width, height, stride, uti);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cgImage = createCGImageFromBGRA(pixels, width, height, stride);
            if (MemorySegment.NULL.equals(cgImage))
                throw new DecodeException("Failed to build CGImage from pixel data");

            MemorySegment cfUrl = MemorySegment.NULL;
            MemorySegment cfUti = MemorySegment.NULL;
            MemorySegment dest  = MemorySegment.NULL;
            MemorySegment props = MemorySegment.NULL;
            try {
                cfUrl = CoreFoundation.cfUrlCreate(arena, path);
                if (MemorySegment.NULL.equals(cfUrl))
                    throw new DecodeException("Failed to create CFURL for path: " + path);

                cfUti = CoreFoundation.cfStringCreate(arena, uti);
                dest = CGImageDestination.createWithURL(cfUrl, cfUti, 1L, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(dest))
                    throw new DecodeException("CGImageDestinationCreateWithURL failed "
                            + "(unsupported UTI '" + uti + "' on this host?)");

                props = buildWriteProperties(arena, quality, orientation);
                CGImageDestination.addImage(dest, cgImage, props);

                if (!CGImageDestination.finalize_(dest))
                    throw new DecodeException("CGImageDestinationFinalize failed for: " + path);
            } finally {
                CoreFoundation.cfRelease(props);
                CoreFoundation.cfRelease(dest);
                CoreFoundation.cfRelease(cfUti);
                CoreFoundation.cfRelease(cfUrl);
                CoreFoundation.cfRelease(cgImage);
            }
        }
    }

    /** Encodes BGRA pixels into a byte array in memory. See {@link #encodeToPath}. */
    public static byte[] encodeToBytes(MemorySegment pixels, int width, int height, int stride,
                                       String uti, float quality, int orientation) {
        ensureAvailable();
        validateEncodeArgs(width, height, stride, uti);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cgImage = createCGImageFromBGRA(pixels, width, height, stride);
            if (MemorySegment.NULL.equals(cgImage))
                throw new DecodeException("Failed to build CGImage from pixel data");

            MemorySegment cfData = MemorySegment.NULL;
            MemorySegment cfUti  = MemorySegment.NULL;
            MemorySegment dest   = MemorySegment.NULL;
            MemorySegment props  = MemorySegment.NULL;
            try {
                cfData = CoreFoundation.cfDataCreateMutable(0L);
                if (MemorySegment.NULL.equals(cfData))
                    throw new DecodeException("CFDataCreateMutable returned NULL");

                cfUti = CoreFoundation.cfStringCreate(arena, uti);
                dest = CGImageDestination.createWithData(cfData, cfUti, 1L, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(dest))
                    throw new DecodeException("CGImageDestinationCreateWithData failed "
                            + "(unsupported UTI '" + uti + "' on this host?)");

                props = buildWriteProperties(arena, quality, orientation);
                CGImageDestination.addImage(dest, cgImage, props);

                if (!CGImageDestination.finalize_(dest))
                    throw new DecodeException("CGImageDestinationFinalize failed");

                return cfDataToByteArray(cfData);
            } finally {
                CoreFoundation.cfRelease(props);
                CoreFoundation.cfRelease(dest);
                CoreFoundation.cfRelease(cfUti);
                CoreFoundation.cfRelease(cfData);
                CoreFoundation.cfRelease(cgImage);
            }
        }
    }

    // ── Animated GIF ────────────────────────────────────────────────────

    /** A single pre-composed frame decoded from an animated GIF. */
    public record GifFrameData(byte[] pixels, int width, int height,
                               int delay, int disposal, int left, int top) {}

    /** All frames and metadata for an animated GIF decoded via CGImageSource. */
    public record AnimatedGifData(int canvasWidth, int canvasHeight, int loopCount,
                                  List<GifFrameData> frames) {}

    /**
     * Decodes all frames of an animated GIF from a file path. CG returns
     * <em>pre-composed</em> frames (disposal already applied), so every
     * frame has {@code disposal=1} and {@code left=top=0}.
     */
    public static AnimatedGifData decodeAnimatedGif(String path) {
        ensureAvailable();

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cfUrl = CoreFoundation.cfUrlCreate(temp, path);
            if (MemorySegment.NULL.equals(cfUrl))
                throw new DecodeException("Failed to create CFURL for path");

            MemorySegment src = MemorySegment.NULL;
            try {
                src = CGImageSource.createWithURL(cfUrl, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(src))
                    throw new DecodeException("Unsupported format: " + path);

                long count = CGImageSource.getCount(src);
                if (count <= 0)
                    throw new DecodeException("No frames in: " + path);

                int loopCount = readGifLoopCount(src);
                int canvasWidth = 0;
                int canvasHeight = 0;
                List<GifFrameData> frames = new ArrayList<>((int) count);

                for (long i = 0; i < count; i++) {
                    int delayMs = readGifFrameDelay(src, i);

                    MemorySegment cgImg = MemorySegment.NULL;
                    MemorySegment colorSpace = MemorySegment.NULL;
                    MemorySegment ctx = MemorySegment.NULL;
                    Arena pixArena = Arena.ofConfined();
                    try {
                        cgImg = CGImageSource.createImageAtIndex(src, i, MemorySegment.NULL);
                        if (MemorySegment.NULL.equals(cgImg))
                            throw new DecodeException("Failed to decode frame " + i + " of: " + path);

                        int iw = (int) CGImage.getWidth(cgImg);
                        int ih = (int) CGImage.getHeight(cgImg);
                        if (i == 0) { canvasWidth = iw; canvasHeight = ih; }

                        colorSpace = CGColorSpace.createDeviceRGB();
                        int stride = iw * RGBA_BPP;
                        MemorySegment pixelSeg = pixArena.allocate((long) stride * ih, 16);

                        ctx = CGBitmapContext.create(pixelSeg, iw, ih, 8L, stride, colorSpace,
                                CGBitmapContext.BitmapInfo.BGRA_PREMULTIPLIED_LITTLE_ENDIAN);
                        if (MemorySegment.NULL.equals(ctx))
                            throw new DecodeException("CGBitmapContextCreate failed for frame " + i);

                        MemorySegment rect = CGGeometry.CGRect.allocate(pixArena, 0, 0, iw, ih);
                        CGContext.drawImage(ctx, rect, cgImg);

                        // Release ctx before arena closes (ctx may reference pixelSeg)
                        CoreFoundation.cfRelease(ctx);
                        ctx = MemorySegment.NULL;

                        byte[] pixelBytes = new byte[stride * ih];
                        MemorySegment.copy(pixelSeg, ValueLayout.JAVA_BYTE, 0,
                                pixelBytes, 0, pixelBytes.length);

                        frames.add(new GifFrameData(pixelBytes, iw, ih, delayMs, 1, 0, 0));
                    } finally {
                        CoreFoundation.cfRelease(ctx);
                        CoreFoundation.cfRelease(colorSpace);
                        CoreFoundation.cfRelease(cgImg);
                        pixArena.close();
                    }
                }

                return new AnimatedGifData(canvasWidth, canvasHeight, loopCount, frames);
            } finally {
                CoreFoundation.cfRelease(src);
            }
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /** Creates a standalone CGImage from BGRA pixels via a bitmap context. */
    private static MemorySegment createCGImageFromBGRA(MemorySegment pixels,
                                                       int width, int height, int stride) {
        MemorySegment colorSpace = MemorySegment.NULL;
        MemorySegment ctx = MemorySegment.NULL;
        try {
            colorSpace = CGColorSpace.createDeviceRGB();
            ctx = CGBitmapContext.create(pixels, width, height, 8L, stride, colorSpace,
                    CGBitmapContext.BitmapInfo.BGRA_PREMULTIPLIED_LITTLE_ENDIAN);
            if (MemorySegment.NULL.equals(ctx)) return MemorySegment.NULL;
            return CGBitmapContext.createImage(ctx);
        } finally {
            CoreFoundation.cfRelease(ctx);
            CoreFoundation.cfRelease(colorSpace);
        }
    }

    /**
     * Builds the thumbnail options dict with
     * {@code kCGImageSourceCreateThumbnailFromImageAlways=true},
     * {@code kCGImageSourceCreateThumbnailWithTransform=true}, and
     * {@code kCGImageSourceThumbnailMaxPixelSize=maxDim}. Caller releases.
     */
    private static MemorySegment createThumbnailOptions(Arena arena, int maxDim) {
        MemorySegment cfMaxSize = CoreFoundation.cfNumberCreateInt(arena, maxDim);
        try {
            MemorySegment[] keys = {
                    CGImageSource.kCreateThumbnailFromImageAlways(),
                    CGImageSource.kCreateThumbnailWithTransform(),
                    CGImageSource.kThumbnailMaxPixelSize()
            };
            MemorySegment[] values = {
                    CoreFoundation.kCFBooleanTrue(),
                    CoreFoundation.kCFBooleanTrue(),
                    cfMaxSize
            };
            return CoreFoundation.cfDictionaryCreate(arena, keys, values);
        } finally {
            CoreFoundation.cfRelease(cfMaxSize);
        }
    }

    /**
     * Builds properties dict for
     * {@link CGImageDestination#addImage}. Returns {@code MemorySegment.NULL}
     * when no properties are requested (CG treats NULL as "apply defaults").
     */
    private static MemorySegment buildWriteProperties(Arena arena, float quality, int orientation) {
        boolean wantsQuality = !Float.isNaN(quality);
        boolean wantsOrientation = orientation >= 1 && orientation <= 8;
        if (!wantsQuality && !wantsOrientation) return MemorySegment.NULL;

        int n = (wantsQuality ? 1 : 0) + (wantsOrientation ? 1 : 0);
        MemorySegment[] keys   = new MemorySegment[n];
        MemorySegment[] values = new MemorySegment[n];
        int i = 0;

        MemorySegment cfQuality = MemorySegment.NULL;
        MemorySegment cfOrient  = MemorySegment.NULL;
        try {
            if (wantsQuality) {
                double q = Math.max(0.0, Math.min(1.0, quality));
                cfQuality = CoreFoundation.cfNumberCreateDouble(arena, q);
                keys[i] = CGImageDestination.kLossyCompressionQuality();
                values[i] = cfQuality;
                i++;
            }
            if (wantsOrientation) {
                cfOrient = CoreFoundation.cfNumberCreateInt(arena, orientation);
                keys[i] = CGImageSource.kOrientation();
                values[i] = cfOrient;
            }
            return CoreFoundation.cfDictionaryCreate(arena, keys, values);
        } finally {
            CoreFoundation.cfRelease(cfOrient);
            CoreFoundation.cfRelease(cfQuality);
        }
    }

    private static void validateEncodeArgs(int width, int height, int stride, String uti) {
        if (uti == null || uti.isEmpty())
            throw new IllegalArgumentException("uti must be non-empty");
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        if (stride < width * RGBA_BPP)
            throw new IllegalArgumentException(
                    "stride " + stride + " too small for width " + width);
        ImageDimensions.validateDimensions(width, height);
    }

    /**
     * Reads display-oriented dimensions from a CGImageSource. Caller owns
     * {@code imgSrc}.
     */
    private static Dimensions getSizeFromSource(MemorySegment imgSrc) {
        MemorySegment props = CGImageSource.copyPropertiesAtIndex(imgSrc, 0L, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(props))
            throw new DecodeException("Failed to read image properties");

        try {
            int rawW = dictGetInt(props, CGImageSource.kPixelWidth(), -1);
            int rawH = dictGetInt(props, CGImageSource.kPixelHeight(), -1);
            if (rawW <= 0 || rawH <= 0)
                throw new DecodeException("Invalid image dimensions: " + rawW + "x" + rawH);

            int orientation = dictGetInt(props, CGImageSource.kOrientation(), 1);
            if (orientation >= 5 && orientation <= 8) return new Dimensions(rawH, rawW);
            return new Dimensions(rawW, rawH);
        } finally {
            CoreFoundation.cfRelease(props);
        }
    }

    /** Reads {@code kCGImagePropertyOrientation}. Returns 1 if missing. */
    private static int readOrientation(MemorySegment imgSrc) {
        MemorySegment props = CGImageSource.copyPropertiesAtIndex(imgSrc, 0L, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(props)) return 1;
        try {
            int o = dictGetInt(props, CGImageSource.kOrientation(), 1);
            return (o >= 1 && o <= 8) ? o : 1;
        } finally {
            CoreFoundation.cfRelease(props);
        }
    }

    /**
     * Shared decode pipeline: read properties, create thumbnail with EXIF
     * transform baked, rasterise into BGRA, copy pixels into caller's Arena.
     *
     * @param maxPixelSize caller-supplied cap, or {@code <= 0} to use the
     *                     image's raw max dimension (full-size decode)
     * @param quality      interpolation quality applied to the bitmap
     *                     context; only matters when the returned CGImage
     *                     size differs from our context
     */
    private static DecodedImage<PixelFormat> decodeFromSource(
            Arena arena, Arena temp, MemorySegment imgSrc, int maxPixelSize,
            InterpolationQuality quality) {

        MemorySegment props = CGImageSource.copyPropertiesAtIndex(imgSrc, 0L, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(props))
            throw new DecodeException("Failed to read image properties");

        int rawW;
        int rawH;
        int orientation;
        try {
            rawW = dictGetInt(props, CGImageSource.kPixelWidth(), -1);
            rawH = dictGetInt(props, CGImageSource.kPixelHeight(), -1);
            // Surface the source EXIF tag verbatim. CG bakes the rotation
            // into the returned pixels via kCGImageSourceCreateThumbnailWithTransform,
            // but we still report the original value so callers can re-embed
            // it on encode or record it as metadata without a second source
            // open. Defaults to 1 (TIFF "no rotation") when the tag is absent
            // or out of range — matching getOrientation() semantics.
            int rawOrientation = dictGetInt(props, CGImageSource.kOrientation(), 1);
            orientation = (rawOrientation >= 1 && rawOrientation <= 8) ? rawOrientation : 1;
        } finally {
            CoreFoundation.cfRelease(props);
        }

        if (rawW <= 0 || rawH <= 0)
            throw new DecodeException("Invalid image dimensions: " + rawW + "x" + rawH);
        ImageDimensions.validateDimensions(rawW, rawH);

        int maxDim = (maxPixelSize > 0) ? maxPixelSize : Math.max(rawW, rawH);
        MemorySegment thumbOpts = createThumbnailOptions(temp, maxDim);
        if (MemorySegment.NULL.equals(thumbOpts))
            throw new DecodeException("Failed to create thumbnail options");

        MemorySegment cgImage = MemorySegment.NULL;
        MemorySegment colorSpace = MemorySegment.NULL;
        MemorySegment ctx = MemorySegment.NULL;
        try {
            cgImage = CGImageSource.createThumbnailAtIndex(imgSrc, 0L, thumbOpts);
            if (MemorySegment.NULL.equals(cgImage))
                throw new DecodeException(
                        "CGImageSourceCreateThumbnailAtIndex returned NULL - decode failed");

            long w = CGImage.getWidth(cgImage);
            long h = CGImage.getHeight(cgImage);
            if (w <= 0 || h <= 0)
                throw new DecodeException("Invalid CGImage dimensions: " + w + "x" + h);

            int iw = (int) w;
            int ih = (int) h;
            ImageDimensions.validateDimensions(iw, ih);

            colorSpace = CGColorSpace.createDeviceRGB();
            int stride = iw * RGBA_BPP;
            long outputSize = (long) stride * ih;
            MemorySegment pixelData = arena.allocate(outputSize, 16);

            ctx = CGBitmapContext.create(pixelData, w, h, 8L, stride, colorSpace,
                    CGBitmapContext.BitmapInfo.BGRA_PREMULTIPLIED_LITTLE_ENDIAN);
            if (MemorySegment.NULL.equals(ctx))
                throw new DecodeException("CGBitmapContextCreate returned NULL");

            CGContext.setInterpolationQuality(ctx, quality.cgValue);
            MemorySegment rect = CGGeometry.CGRect.allocate(temp, 0, 0, w, h);
            CGContext.drawImage(ctx, rect, cgImage);

            return new DecodedImage<>(pixelData, iw, ih, stride, PixelFormat.BGRA, orientation);
        } finally {
            CoreFoundation.cfRelease(ctx);
            CoreFoundation.cfRelease(colorSpace);
            CoreFoundation.cfRelease(cgImage);
            CoreFoundation.cfRelease(thumbOpts);
        }
    }

    private static int readGifLoopCount(MemorySegment src) {
        MemorySegment props = CGImageSource.copyPropertiesAtIndex(src, 0L, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(props)) return 0;
        try {
            MemorySegment gifDict = CoreFoundation.cfDictionaryGetValue(props, CGImageSource.kGIFDictionary());
            if (MemorySegment.NULL.equals(gifDict)) return 0;
            return dictGetInt(gifDict, CGImageSource.kGIFLoopCount(), 0);
        } finally {
            CoreFoundation.cfRelease(props);
        }
    }

    private static int readGifFrameDelay(MemorySegment src, long index) {
        MemorySegment props = CGImageSource.copyPropertiesAtIndex(src, index, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(props)) return 100;
        try {
            MemorySegment gifDict = CoreFoundation.cfDictionaryGetValue(props, CGImageSource.kGIFDictionary());
            if (MemorySegment.NULL.equals(gifDict)) return 100;
            double sec = dictGetDouble(gifDict, CGImageSource.kGIFUnclampedDelayTime(), 0.0);
            if (sec <= 0.0) sec = dictGetDouble(gifDict, CGImageSource.kGIFDelayTime(), 0.1);
            return Math.max(10, (int) Math.round(sec * 1000));
        } finally {
            CoreFoundation.cfRelease(props);
        }
    }

    private static int dictGetInt(MemorySegment dict, MemorySegment key, int defaultValue) {
        MemorySegment val = CoreFoundation.cfDictionaryGetValue(dict, key);
        if (MemorySegment.NULL.equals(val)) return defaultValue;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(ValueLayout.JAVA_INT);
            return CoreFoundation.cfNumberGetInt(val, buf)
                    ? buf.get(ValueLayout.JAVA_INT, 0) : defaultValue;
        }
    }

    private static double dictGetDouble(MemorySegment dict, MemorySegment key, double defaultValue) {
        MemorySegment val = CoreFoundation.cfDictionaryGetValue(dict, key);
        if (MemorySegment.NULL.equals(val)) return defaultValue;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(ValueLayout.JAVA_DOUBLE);
            return CoreFoundation.cfNumberGetDouble(val, buf)
                    ? buf.get(ValueLayout.JAVA_DOUBLE, 0) : defaultValue;
        }
    }

    private static byte[] cfDataToByteArray(MemorySegment cfData) {
        long len = CoreFoundation.cfDataGetLength(cfData);
        if (len <= 0 || len > Integer.MAX_VALUE)
            throw new DecodeException("Encoded data has invalid length: " + len);
        MemorySegment bytes = CoreFoundation.cfDataGetBytePtr(cfData).reinterpret(len);
        byte[] out = new byte[(int) len];
        MemorySegment.copy(bytes, ValueLayout.JAVA_BYTE, 0, out, 0, (int) len);
        return out;
    }

    private static void ensureAvailable() {
        if (!Frameworks.AVAILABLE)
            throw new IllegalStateException(
                    "Apple ImageIO is not available"
                            + (Frameworks.LOAD_ERROR != null ? ": " + Frameworks.LOAD_ERROR : ""));
    }
}
