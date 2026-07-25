package io.github.ghosthack.mediabrowser.media.archive.iso;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A read-only {@link FileSystem} over one {@link IsoImage}, so entries inside a
 * disc image are ordinary {@link Path}s and the whole media layer — listing,
 * classification, thumbnailing — works on them unchanged.
 *
 * <p>Lookups are cached, because the natural access pattern is brutal without
 * it: resolving a path walks the directory chain from the root, so listing a
 * folder and then stat-ing each of its N children would re-read the same
 * directory extents N times. Both caches are bounded and evict oldest-first —
 * a 650 MB disc with tens of thousands of files must not pin its whole tree in
 * memory just because someone scrolled past it.</p>
 */
public final class IsoFileSystem extends FileSystem {

    /** Resolved entries kept hot; enough for several large folders at once. */
    private static final int ENTRY_CACHE_MAX = 20_000;

    /** Directory listings kept hot; a browse walks few folders at a time. */
    private static final int LISTING_CACHE_MAX = 256;

    private final IsoFileSystemProvider provider;
    private final Path source;
    private final IsoImage image;
    private final IsoPath root = new IsoPath(this, "/");

    private final Map<String, IsoEntry> entries = lru(ENTRY_CACHE_MAX);
    private final Map<String, List<IsoEntry>> listings = lru(LISTING_CACHE_MAX);

    private volatile boolean open = true;

    IsoFileSystem(IsoFileSystemProvider provider, Path source, IsoImage image) {
        this.provider = provider;
        this.source = source;
        this.image = image;
    }

    /** The image file this filesystem reads. */
    public Path source() {
        return source;
    }

    /** The volume identifier, for display. */
    public String volumeName() {
        return image.volumeName();
    }

    IsoImage image() {
        return image;
    }

    IsoPath rootPath() {
        return root;
    }

    /**
     * The entry at an absolute path inside the image.
     *
     * @throws NoSuchFileException when nothing of that name exists
     */
    IsoEntry entry(String path) throws IOException {
        String key = normalize(path);
        if ("/".equals(key)) return image.root();
        synchronized (entries) {
            IsoEntry hit = entries.get(key);
            if (hit != null) return hit;
        }
        // Resolving through the parent's listing (rather than IsoImage.resolve)
        // means one directory read fills the cache for every sibling too, which
        // is exactly the order a browse then stats them in.
        int slash = key.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : key.substring(0, slash);
        String name = key.substring(slash + 1);
        for (IsoEntry child : list(parent)) {
            if (child.name().equals(name)) return child;
        }
        for (IsoEntry child : list(parent)) {          // case-insensitive fallback
            if (child.name().equalsIgnoreCase(name)) return child;
        }
        throw new NoSuchFileException(path);
    }

    /** The children of an absolute directory path inside the image. */
    List<IsoEntry> list(String path) throws IOException {
        String key = normalize(path);
        synchronized (listings) {
            List<IsoEntry> hit = listings.get(key);
            if (hit != null) return hit;
        }
        IsoEntry dir = "/".equals(key) ? image.root() : entry(key);
        List<IsoEntry> children = List.copyOf(image.children(dir));
        synchronized (listings) {
            listings.put(key, children);
        }
        synchronized (entries) {
            String prefix = "/".equals(key) ? "/" : key + "/";
            for (IsoEntry child : children) entries.put(prefix + child.name(), child);
        }
        return children;
    }

    /** Collapses {@code .}/{@code ..} and duplicate separators to a canonical key. */
    private static String normalize(String path) {
        var parts = new ArrayList<String>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(part);
        }
        return "/" + String.join("/", parts);
    }

    private static <K, V> Map<K, V> lru(int max) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    // --- FileSystem -------------------------------------------------------

    @Override
    public IsoFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        if (!open) return;
        open = false;
        provider.removed(source);
        synchronized (entries) { entries.clear(); }
        synchronized (listings) { listings.clear(); }
        image.close();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(root);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        if (more.length == 0) return new IsoPath(this, first);
        return new IsoPath(this, first + "/" + String.join("/", more));
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("path matching is not supported inside an ISO");
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("no principals inside an ISO");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("an ISO cannot change while mounted");
    }

    @Override
    public String toString() {
        return source.toString() + "!/";
    }
}
