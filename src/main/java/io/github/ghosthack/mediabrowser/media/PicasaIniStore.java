package io.github.ghosthack.mediabrowser.media;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes per-directory {@code .picasa.ini} sidecar files to persist
 * <strong>non-destructive</strong> image rotations, reusing Picasa's on-disk
 * format so existing {@code rotate=rotate(N)} entries are honored and the files
 * stay interoperable with Picasa and other tools that consume them.
 *
 * <p>Vendored from the iris94 Swing media browser, which uses the same format.
 * The file is a sequence of {@code [section]} headers (one per filename, plus
 * the conventional {@code [Picasa]} album header) followed by {@code key=value}
 * lines:
 * <pre>
 *   [Picasa]
 *   name=My Album
 *   [IMG_0001.jpg]
 *   rotate=rotate(1)
 *   backuphash=5116
 * </pre>
 *
 * <p>Rotation is stored as {@code rotate=rotate(N)} where {@code N} is the number
 * of 90&deg; <strong>clockwise</strong> steps (0&ndash;3) to apply to the
 * already-EXIF-upright image for display. All other sections and keys
 * (e.g.&nbsp;{@code name}, {@code backuphash}) are preserved verbatim across a
 * read/modify/write cycle, and original section ordering is retained.
 *
 * <p>Parsed documents are cached per directory; writes are performed
 * atomically (temp sibling + {@code fsync} + atomic rename) so a crash mid-write
 * cannot truncate an existing sidecar. Instances are internally synchronized for
 * defensive safety (the map may first be loaded off the FX thread).
 */
public final class PicasaIniStore {

    /** Conventional sidecar filename (hidden on POSIX via the leading dot). */
    public static final String INI_FILENAME = ".picasa.ini";

    private static final String ROTATE_KEY = "rotate";

    /**
     * Per-file key marking a file as user-<em>filtered</em> (hidden from normal
     * browsing unless the "show filtered" override is active). Stored as
     * {@code filtered=1}; any other/absent value means "not filtered". This is an
     * iris extension to the Picasa format; other tools ignore the unknown key and
     * this store preserves their keys verbatim.
     */
    private static final String FILTERED_KEY = "filtered";

    /**
     * Per-file boolean keys for the user-owned, non-destructive adjustments that
     * compose on top of the (already-upright) image alongside {@code rotate}:
     * horizontal/vertical mirror, desaturate (black&amp;white) and colour invert.
     * Stored as {@code key=1}; absent / any non-truthy value means "off". Like
     * {@code filtered}, these are extensions to the Picasa format — other tools
     * ignore the unknown keys, and this store preserves theirs verbatim.
     */
    public static final String MIRROR_H_KEY = "mirrorh";
    public static final String MIRROR_V_KEY = "mirrorv";
    public static final String GRAYSCALE_KEY = "grayscale";
    public static final String INVERT_KEY = "invert";

    /** Matches Picasa's {@code rotate(N)} wrapper, capturing the integer N. */
    private static final Pattern ROTATE_PATTERN =
            Pattern.compile("rotate\\(\\s*(-?\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** Per-directory parsed document cache, keyed by canonical directory path. */
    private final Map<String, IniDocument> cache = new LinkedHashMap<String, IniDocument>();

    /** Optional override filename (tests may point this at a non-hidden file). */
    private final String iniFilename;

    public PicasaIniStore() {
        this(INI_FILENAME);
    }

    PicasaIniStore(String iniFilename) {
        this.iniFilename = iniFilename;
    }

    /**
     * Returns the persisted display rotation for {@code imagePath} as a count of
     * 90&deg; clockwise steps in {@code 0..3}. Returns {@code 0} when the path is
     * {@code null}, has no parent directory, has no sidecar entry, or the stored
     * value cannot be parsed.
     */
    public synchronized int getRotationSteps(String imagePath) {
        if (imagePath == null) {
            return 0;
        }
        File file = new File(imagePath);
        File dir = file.getParentFile();
        if (dir == null) {
            return 0;
        }
        IniDocument doc = documentForDir(dir);
        LinkedHashMap<String, String> section = doc.sections.get(file.getName());
        if (section == null) {
            return 0;
        }
        return parseRotateSteps(section.get(ROTATE_KEY));
    }

    /**
     * Persists the display rotation for {@code imagePath} as {@code steps}
     * (normalized to {@code 0..3}). A zero rotation removes the {@code rotate}
     * key (preserving any other keys / the empty section). No write occurs when
     * the stored value is already the requested value.
     *
     * @throws IOException if the sidecar could not be written
     */
    public synchronized void setRotationSteps(String imagePath, int steps) throws IOException {
        int target = normalize(steps);
        if (imagePath == null) {
            throw new IOException("Cannot set rotation: null image path");
        }
        File file = new File(imagePath);
        File dir = file.getParentFile();
        if (dir == null) {
            throw new IOException("Cannot set rotation: no parent directory for " + imagePath);
        }

        IniDocument doc = documentForDir(dir);
        String name = file.getName();
        LinkedHashMap<String, String> section = doc.sections.get(name);
        int current = section == null ? 0 : parseRotateSteps(section.get(ROTATE_KEY));
        if (current == target) {
            return; // No change — avoid a redundant rewrite.
        }

        // Snapshot for rollback so a failed write leaves the cache matching disk.
        boolean createdSection = false;
        String previousRotate = section == null ? null : section.get(ROTATE_KEY);

        if (target == 0) {
            if (section != null) {
                section.remove(ROTATE_KEY);
            }
        } else {
            if (section == null) {
                section = new LinkedHashMap<String, String>();
                doc.sections.put(name, section);
                createdSection = true;
            }
            section.put(ROTATE_KEY, "rotate(" + target + ")");
        }

        try {
            writeDocument(new File(dir, iniFilename), doc);
        } catch (IOException e) {
            if (createdSection) {
                doc.sections.remove(name);
            } else if (section != null) {
                if (previousRotate == null) {
                    section.remove(ROTATE_KEY);
                } else {
                    section.put(ROTATE_KEY, previousRotate);
                }
            }
            throw e;
        }
    }

    /**
     * Adjusts the persisted rotation for {@code imagePath} by {@code deltaSteps}
     * 90&deg; clockwise steps (negative rotates counter-clockwise) and returns
     * the new normalized rotation in {@code 0..3}.
     *
     * @throws IOException if the sidecar could not be written
     */
    public synchronized int rotateBy(String imagePath, int deltaSteps) throws IOException {
        int next = normalize(getRotationSteps(imagePath) + deltaSteps);
        setRotationSteps(imagePath, next);
        return next;
    }

    /**
     * Returns whether {@code imagePath} is marked as user-<em>filtered</em>
     * (hidden from normal browsing). Returns {@code false} when the path is
     * {@code null}, has no parent directory, or has no {@code filtered} entry.
     */
    public synchronized boolean isFiltered(String imagePath) {
        return getFlag(imagePath, FILTERED_KEY);
    }

    /**
     * Marks (or unmarks) {@code imagePath} as user-filtered. Setting {@code false}
     * removes the {@code filtered} key while preserving any other keys (and the —
     * possibly now empty — section), mirroring how a zero rotation is handled. No
     * write occurs when the stored value already matches the request.
     *
     * @throws IOException if the sidecar could not be written
     */
    public synchronized void setFiltered(String imagePath, boolean filtered) throws IOException {
        setFlag(imagePath, FILTERED_KEY, filtered);
    }

    /**
     * Toggles the persisted filtered mark for {@code imagePath} and returns the
     * new value.
     *
     * @throws IOException if the sidecar could not be written
     */
    public synchronized boolean toggleFiltered(String imagePath) throws IOException {
        return toggleFlag(imagePath, FILTERED_KEY);
    }

    // ---- generic boolean per-file flags -------------------------------------

    /**
     * Returns the value of a boolean per-file {@code key} (e.g. {@link #MIRROR_H_KEY},
     * {@link #GRAYSCALE_KEY}, {@link #INVERT_KEY}, {@link #FILTERED_KEY}) for
     * {@code imagePath}. Returns {@code false} when the path is {@code null}, has
     * no parent directory, or has no such (truthy) entry.
     */
    public synchronized boolean getFlag(String imagePath, String key) {
        if (imagePath == null) {
            return false;
        }
        File file = new File(imagePath);
        File dir = file.getParentFile();
        if (dir == null) {
            return false;
        }
        IniDocument doc = documentForDir(dir);
        LinkedHashMap<String, String> section = doc.sections.get(file.getName());
        if (section == null) {
            return false;
        }
        return parseBooleanFlag(section.get(key));
    }

    /**
     * Sets (or clears) a boolean per-file {@code key} for {@code imagePath}.
     * Setting {@code false} removes the key while preserving any other keys (and
     * the — possibly now empty — section), mirroring how a zero rotation is
     * handled. No write occurs when the stored value already matches the request.
     *
     * @throws IOException if the sidecar could not be written
     */
    public synchronized void setFlag(String imagePath, String key, boolean on) throws IOException {
        if (imagePath == null) {
            throw new IOException("Cannot set " + key + ": null image path");
        }
        File file = new File(imagePath);
        File dir = file.getParentFile();
        if (dir == null) {
            throw new IOException("Cannot set " + key + ": no parent directory for " + imagePath);
        }

        IniDocument doc = documentForDir(dir);
        String name = file.getName();
        LinkedHashMap<String, String> section = doc.sections.get(name);
        boolean current = section != null && parseBooleanFlag(section.get(key));
        if (current == on) {
            return; // No change — avoid a redundant rewrite.
        }

        // Snapshot for rollback so a failed write leaves the cache matching disk.
        boolean createdSection = false;
        String previous = section == null ? null : section.get(key);

        if (!on) {
            if (section != null) {
                section.remove(key);
            }
        } else {
            if (section == null) {
                section = new LinkedHashMap<String, String>();
                doc.sections.put(name, section);
                createdSection = true;
            }
            section.put(key, "1");
        }

        try {
            writeDocument(new File(dir, iniFilename), doc);
        } catch (IOException e) {
            if (createdSection) {
                doc.sections.remove(name);
            } else if (section != null) {
                if (previous == null) {
                    section.remove(key);
                } else {
                    section.put(key, previous);
                }
            }
            throw e;
        }
    }

    /**
     * Toggles a boolean per-file {@code key} for {@code imagePath} and returns the
     * new value.
     *
     * @throws IOException if the sidecar could not be written
     */
    public synchronized boolean toggleFlag(String imagePath, String key) throws IOException {
        boolean next = !getFlag(imagePath, key);
        setFlag(imagePath, key, next);
        return next;
    }

    /**
     * Reads every persisted non-destructive adjustment for {@code imagePath} in a
     * single locked pass and returns them bit-packed, so a hot caller (the mosaic
     * draw loop) gets a consistent snapshot without five separate lookups:
     * bits&nbsp;0&ndash;1 = rotation steps {@code 0..3}, bit&nbsp;2 = mirror
     * horizontal, bit&nbsp;3 = mirror vertical, bit&nbsp;4 = grayscale,
     * bit&nbsp;5 = invert. Returns {@code 0} (identity) when the path is
     * {@code null}, has no parent, or has no section.
     */
    public synchronized int getPackedAdjustments(String imagePath) {
        if (imagePath == null) {
            return 0;
        }
        File file = new File(imagePath);
        File dir = file.getParentFile();
        if (dir == null) {
            return 0;
        }
        IniDocument doc = documentForDir(dir);
        LinkedHashMap<String, String> section = doc.sections.get(file.getName());
        if (section == null) {
            return 0;
        }
        int packed = parseRotateSteps(section.get(ROTATE_KEY)) & 0x3;
        if (parseBooleanFlag(section.get(MIRROR_H_KEY))) packed |= 1 << 2;
        if (parseBooleanFlag(section.get(MIRROR_V_KEY))) packed |= 1 << 3;
        if (parseBooleanFlag(section.get(GRAYSCALE_KEY))) packed |= 1 << 4;
        if (parseBooleanFlag(section.get(INVERT_KEY))) packed |= 1 << 5;
        return packed;
    }

    /** Drops the cached document for a directory so it is re-read on next access. */
    public synchronized void invalidate(String directoryPath) {
        if (directoryPath != null) {
            cache.remove(dirKey(new File(directoryPath)));
        }
    }

    /** Drops all cached documents so every directory is re-read on next access. */
    public synchronized void invalidateAll() {
        cache.clear();
    }

    // ---- Internal -----------------------------------------------------------

    private static int normalize(int steps) {
        return ((steps % 4) + 4) % 4;
    }

    /** Parses a {@code rotate=...} value (either {@code rotate(N)} or a bare integer). */
    private static int parseRotateSteps(String value) {
        if (value == null) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        Matcher matcher = ROTATE_PATTERN.matcher(trimmed);
        int n;
        if (matcher.find()) {
            n = parseIntOrZero(matcher.group(1));
        } else {
            n = parseIntOrZero(trimmed);
        }
        return normalize(n);
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses a boolean flag value; truthy = {@code 1/true/yes/on}. */
    private static boolean parseBooleanFlag(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.equals("1") || v.equalsIgnoreCase("true")
                || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    private IniDocument documentForDir(File dir) {
        String key = dirKey(dir);
        IniDocument doc = cache.get(key);
        if (doc == null) {
            doc = parseDocument(new File(dir, iniFilename));
            cache.put(key, doc);
        }
        return doc;
    }

    /**
     * Cache key for a directory. Uses the absolute path (not the canonical
     * path) deliberately: {@code getRotationSteps} is called once per visible
     * grid cell on every repaint, and {@code getCanonicalPath} performs a
     * filesystem syscall that would add up on the paint hot path. Image paths
     * supplied by the app are already absolute and stable, so distinct keys for
     * the same directory (e.g.&nbsp;via an unresolved symlink) are at worst a
     * harmless duplicate cache entry, never a wrong value.
     */
    private static String dirKey(File dir) {
        return dir.getAbsolutePath();
    }

    /** Reads and parses a sidecar file, returning an empty document on any error. */
    private static IniDocument parseDocument(File iniFile) {
        IniDocument doc = new IniDocument();
        if (iniFile == null || !iniFile.isFile()) {
            return doc;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(iniFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return doc; // Unreadable / mid-write — treat as no recorded rotations.
        }
        LinkedHashMap<String, String> current = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() >= 2 && line.charAt(0) == '[' && line.charAt(line.length() - 1) == ']') {
                String header = line.substring(1, line.length() - 1);
                current = doc.sections.get(header);
                if (current == null) {
                    current = new LinkedHashMap<String, String>();
                    doc.sections.put(header, current);
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && current != null) {
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    current.put(key, value);
                }
            }
            // Lines before any section header, or without '=', are ignored.
        }
        return doc;
    }

    /** Serializes a document and writes it atomically, or deletes it when empty. */
    private static void writeDocument(File iniFile, IniDocument doc) throws IOException {
        if (doc.isEmpty()) {
            // Nothing left to persist — remove an existing (now-empty) sidecar.
            try {
                Files.deleteIfExists(iniFile.toPath());
            } catch (IOException ignored) {
                // Best effort.
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LinkedHashMap<String, String>> section : doc.sections.entrySet()) {
            sb.append('[').append(section.getKey()).append(']').append('\n');
            for (Map.Entry<String, String> kv : section.getValue().entrySet()) {
                sb.append(kv.getKey()).append('=').append(kv.getValue()).append('\n');
            }
        }
        writeAtomically(iniFile, sb.toString());
    }

    /**
     * Writes {@code content} to {@code targetFile} via a temp sibling +
     * {@code fsync} + atomic rename, mirroring {@code AppSettings}'s persistence
     * pattern so a crash mid-write cannot truncate the sidecar. Falls back to a
     * non-atomic replace on filesystems that reject {@code ATOMIC_MOVE}.
     */
    private static void writeAtomically(File targetFile, String content) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Could not create parent directory: " + parent);
        }
        File tempFile = File.createTempFile(
                targetFile.getName() + ".",
                ".tmp",
                parent != null ? parent : new File(System.getProperty("java.io.tmpdir")));
        boolean tempPersisted = false;
        try {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tempFile);
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (IOException ignored) {
                    // fsync is best-effort; some filesystems (FAT32) reject it.
                }
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ignored) {
                        // Already flushed/synced above.
                    }
                }
            }
            tempPersisted = true;

            Path tempPath = tempFile.toPath();
            Path targetPath = targetFile.toPath();
            try {
                Files.move(tempPath, targetPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            tempPersisted = false;
        } finally {
            if (tempPersisted && tempFile.exists()) {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }
    }

    /**
     * In-memory model of a {@code .picasa.ini}: an ordered map of section header
     * (filename or {@code "Picasa"}) to its ordered {@code key -> value} pairs.
     */
    private static final class IniDocument {
        final LinkedHashMap<String, LinkedHashMap<String, String>> sections =
                new LinkedHashMap<String, LinkedHashMap<String, String>>();

        boolean isEmpty() {
            for (LinkedHashMap<String, String> section : sections.values()) {
                if (!section.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }
}
