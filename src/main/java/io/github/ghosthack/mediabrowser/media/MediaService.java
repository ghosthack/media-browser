package io.github.ghosthack.mediabrowser.media;

import io.github.ghosthack.mediabrowser.MosaicTelemetry;
import io.github.ghosthack.mediabrowser.media.archive.ArchiveEntryCache;
import io.github.ghosthack.mediabrowser.media.archive.ArchiveFormat;
import io.github.ghosthack.mediabrowser.media.archive.ArchiveInfo;
import io.github.ghosthack.mediabrowser.media.archive.ArchivePaths;
import io.github.ghosthack.mediabrowser.media.archive.stream.StreamFileSystemProvider.PdfMrcView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Asynchronous wrapper around the {@link MediaFacade}: browse/probe/viewer
 * visual calls are serialized on one background thread, while optional
 * thumbnail decoration and on-demand metadata use separate workers so they
 * cannot stall navigation.
 */
public final class MediaService implements AutoCloseable {

    /**
     * OS/sidecar filenames that are never media; skipped before sniffing so we
     * don't pay for a native probe (and never misclassify them). Matched
     * case-insensitively.
     */
    private static final Set<String> JUNK_NAMES = Set.of(
            ".ds_store", "thumbs.db", "desktop.ini",
            // The rest of the usual per-directory cruft. These four already
            // carry this meaning in ArchivePaths.IGNORED_WHEN_DESCENDING; the
            // two after them matter on external volumes, which is where the
            // droppings outnumber the media.
            "__macosx", ".trashes", ".spotlight-v100", ".fseventsd",
            ".localized", "system volume information");

    /**
     * Directory listings a single {@link #barren} check will make before it
     * gives up and answers {@link Barren#UNKNOWN}. Four times the p99 subtree
     * size measured by {@code scripts/empty-tree-survey.py} over 324,843
     * folders (p50 1, p90 4, p99 50), so better than 99% of folders get an
     * exact answer, while the tail — one folder whose subtree was the entire
     * 324,843-folder disk — costs a fifth of a second instead of eight
     * minutes.
     */
    public static final int DEFAULT_BARREN_BUDGET = 200;

    /** Default in-memory rendition budget when none is supplied (256 MiB). */
    public static final long DEFAULT_THUMBNAIL_BUDGET_BYTES = 256L * 1024 * 1024;

    private final MediaFacade facade;
    private volatile DetectionMode detectionMode = DetectionMode.FILE_EXTENSION;
    private volatile boolean folderPreviewSniff;
    /** Listing filters; see the setters for why the first defaults on. */
    private volatile boolean listingIgnoreJunk = true;
    /**
     * Direct-child counts learned as a by-product of listings already requested
     * by navigation or folder previews. Info panels may read this map, but never
     * trigger a directory listing merely to obtain an Items row.
     */
    private final ConcurrentMap<Path, Integer> knownFolderItemCounts =
            new ConcurrentHashMap<>();
    /**
     * What the most recent {@link #scan} dropped, so a view can put a count on
     * screen rather than omitting silently. Scans are serialized on one
     * executor and a view reads this immediately after its own listing lands,
     * matching on {@link ListingHidden#dir()}; a mismatch simply means no
     * suffix, never a wrong one.
     */
    private volatile ListingHidden lastHidden = ListingHidden.NONE;
    private volatile boolean listingHideEmptyFiles;
    private volatile boolean listingHideEmptyFolders;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-facade");
        t.setDaemon(true);
        return t;
    });

    /**
     * A dedicated single thread for blocking filesystem operations (the move
     * feature's {@code Files.move}/{@code mkdir}), kept off both the FX thread
     * and the native {@code media-facade} thread so a move never stalls (or
     * races) browsing and probing. Single-threaded so moves serialize amongst
     * themselves; the post-move re-listing runs on {@code executor} and is
     * enqueued only after the move's completion handler fires, so it always
     * observes the moved files.
     */
    private final ExecutorService fileOpExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-file-op");
        t.setDaemon(true);
        return t;
    });

    /**
     * The on-demand full-metadata read ({@link #metadata}) runs on its own
     * dedicated single thread, deliberately <b>not</b> the {@code media-facade}
     * {@code executor} that browse/probe/decode use. A fat AI-PNG metadata read
     * (10k+ char fields) must never queue in front of the next image's decode
     * while the user holds the arrow key — that is the whole fast-browsing
     * latency guarantee. Each read is an independent native open, safe to run
     * concurrently with the facade thread (like {@code thumbnailExecutor}).
     */
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-metadata");
        t.setDaemon(true);
        return t;
    });

    /**
     * Thumbnails run on their own bounded pool, separate from the single
     * {@code media-facade} thread that browse/probe use, so generating a
     * gallery of previews never stalls navigation. Each call is an independent
     * native open, safe to run concurrently (see {@link MediaFacade#loadThumbnail}).
     */
    private final ThreadPoolExecutor thumbnailExecutor;
    private final ThumbnailCache thumbnailCache;
    private final long thumbnailBudgetBytes;
    /** Renditions actually generated (decoded) so far; cache hits don't count. */
    private final LongAdder thumbnailsProcessed = new LongAdder();
    /** De-duplicates concurrent generation of the same rendition (thundering herd). */
    private final ConcurrentMap<ThumbnailKey, CompletableFuture<Thumbnail>> inFlight =
            new ConcurrentHashMap<>();

    public MediaService(MediaFacade facade) {
        this(facade, DEFAULT_THUMBNAIL_BUDGET_BYTES);
    }

    public MediaService(MediaFacade facade, long thumbnailBudgetBytes) {
        this.facade = facade;
        this.thumbnailBudgetBytes = thumbnailBudgetBytes;
        this.thumbnailCache = new LruThumbnailCache(thumbnailBudgetBytes);
        int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "media-thumb-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        // Equivalent to Executors.newFixedThreadPool(workers, factory), but
        // concretely typed so thumbnailStats() can observe the queue depth and
        // active thread count.
        this.thumbnailExecutor = new ThreadPoolExecutor(workers, workers,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
    }

    /**
     * How files are classified during a scan: by filename extension alone, or
     * extension plus a header-magic promotion of files the name can't classify
     * (see {@link #classifyByContent}). Applies to the next listing; re-scan
     * the current folder to reclassify what is already shown.
     */
    public void setDetectionMode(DetectionMode mode) {
        this.detectionMode = mode;
    }

    public DetectionMode detectionMode() {
        return detectionMode;
    }

    /**
     * All children of {@code dir} (non-recursive): subdirectories first, then
     * files, each group sorted by name; files are classified by the facade.
     * A {@code ..} entry leads the list when the directory has a parent.
     */
    public CompletableFuture<List<DirEntry>> listEntries(Path dir) {
        return CompletableFuture.supplyAsync(() -> scan(dir), executor);
    }

    /**
     * A fast, content-blind listing of {@code dir}: the same shape as
     * {@link #listEntries} (subdirs first, then files, a leading {@code ..}, and
     * matched rotation/AAE sidecars hidden) but files are classified by
     * <em>extension alone</em> — no per-file native content sniff — so even a
     * large directory returns almost immediately.
     *
     * <p>Used under {@link DetectionMode#CONTENT_SNIFF} to paint the folder
     * structure with placeholder tiles the instant a directory opens; the caller
     * then refines the classification in the background from {@link #reclassify}.
     * The extension pass can only miss the handful of files content mode adds
     * something for (an extension-less or unknown-suffix media file, an
     * animated AVIF/HEIC), which the refine pass corrects.</p>
     */
    public CompletableFuture<List<DirEntry>> listEntriesFast(Path dir) {
        return CompletableFuture.supplyAsync(() -> scanFast(dir), executor);
    }

    /**
     * Streams the content-mode classification of {@code files} on the
     * media-facade executor, the companion to {@link #listEntriesFast}: after a
     * directory has been painted from the fast extension-only listing, this
     * classifies each file in turn per {@link #classifyByContent} and reports
     * only the <em>corrections</em> — the promotions of files the extension
     * guess left as OTHER, plus the animated AVIF/HEIC refinements — to
     * {@code onChanged}, as each lands, so the listing is refined progressively
     * rather than in one swap at the end of the scan.
     *
     * <p>{@code onChanged} is invoked on the executor thread with the file and
     * its corrected kind; the caller marshals to the FX thread and coalesces.
     * Junk files and files whose content verdict matches their extension guess
     * are skipped silently (no callback), so a well-named directory streams
     * nothing. Honours {@code stillWanted}: once it reports the listing was
     * superseded (the user navigated away) the remaining files are abandoned. A
     * per-file failure is swallowed, leaving that file's extension guess in
     * place.</p>
     */
    public CompletableFuture<Void> reclassify(List<Path> files, BooleanSupplier stillWanted,
                                              BiConsumer<Path, Optional<MediaKind>> onChanged) {
        return reclassifyFrom(files, 0, stillWanted, onChanged);
    }

    /**
     * Files sniffed per {@link #reclassify} executor task before yielding. Small
     * so an interactive probe/decode submitted mid-scan waits at most a batch's
     * worth of sniffs rather than the whole directory; each batch re-submits the
     * next, so the shared {@code media-facade} thread interleaves other work in
     * between (facade access stays serialized).
     */
    private static final int RECLASSIFY_BATCH = 16;

    private CompletableFuture<Void> reclassifyFrom(List<Path> files, int from,
                                                   BooleanSupplier stillWanted,
                                                   BiConsumer<Path, Optional<MediaKind>> onChanged) {
        if (from >= files.size() || (stillWanted != null && !stillWanted.getAsBoolean())) {
            return CompletableFuture.completedFuture(null);
        }
        int end = Math.min(files.size(), from + RECLASSIFY_BATCH);
        return CompletableFuture.runAsync(() -> {
            for (int i = from; i < end; i++) {
                if (stillWanted != null && !stillWanted.getAsBoolean()) return;
                Path p = files.get(i);
                if (isJunk(p)) continue;   // forced OTHER in both passes; never changes
                Optional<MediaKind> byExtension = classifyFast(p);
                Optional<MediaKind> sniffed;
                try {
                    sniffed = classify(p);
                } catch (RuntimeException ex) {
                    continue;              // keep the extension guess on a sniff failure
                }
                if (!sniffed.equals(byExtension)) onChanged.accept(p, sniffed);
            }
        }, executor).thenCompose(v -> reclassifyFrom(files, end, stillWanted, onChanged));
    }

    public CompletableFuture<MediaProbe> probe(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<PdfMrcView> mrc = PdfMrcMedia.view(file);
            if (mrc.isPresent()) return PdfMrcMedia.probe(file, mrc.get());
            rejectIfEmpty(file);
            MediaProbe decoded = facade.probe(decodable(file));
            return EpubEntryDetails.preserveProbe(
                    file, PdfEntryDetails.preserveProbe(file, decoded));
        }, executor);
    }

    public CompletableFuture<VisualResult> loadVisual(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<PdfMrcView> mrc = PdfMrcMedia.view(file);
            if (mrc.isPresent()) {
                return PdfMrcMedia.load(file, mrc.get(), facade, MediaService::decodable);
            }
            rejectIfEmpty(file);
            return preserveVisual(file, facade.loadVisual(decodable(file)));
        }, executor);
    }

    private static VisualResult preserveVisual(Path source, VisualResult decoded) {
        MediaProbe probe = PdfEntryDetails.preserveProbe(source, decoded.probe());
        return new VisualResult(
                EpubEntryDetails.preserveProbe(source, probe), decoded.frame());
    }

    /**
     * The path to hand the native backend for {@code file}: {@code file} itself
     * for an ordinary file, or an extracted copy when it lives inside an
     * archive.
     *
     * <p>Every backend opens media by OS path, and an entry inside a mounted
     * container has none. Materializing here — at the one point where the service
     * crosses into the facade — is what lets all six backends stay unaware that
     * archives exist, and keeps browsing inside a container behave-alike with
     * browsing a folder. Note that the <em>caller-facing</em> path stays the
     * archive path throughout: cache keys, probes and the UI all continue to
     * identify the entry by where it really lives, not by its temporary copy.</p>
     */
    private static Path decodable(Path file) {
        try {
            return ArchiveEntryCache.shared().materialize(file);
        } catch (IOException e) {
            throw new MediaException("cannot extract " + file.getFileName()
                    + " from its archive: " + e.getMessage(), e);
        }
    }

    /**
     * Like {@link #loadVisual(Path)}, but skips the (expensive) native decode
     * when {@code stillWanted} reports the request was already superseded while
     * it waited in the single {@code media-facade} thread's queue, completing
     * with {@code null} instead.
     *
     * <p>This is the backpressure relief for held-arrow browsing: each press
     * enqueues a decode, and the queue grows faster than images decode. Without
     * this gate every stale item is still fully decoded before its result is
     * dropped, so the on-screen image freezes far behind the cursor until the
     * whole backlog drains. The check runs on the decode thread right before the
     * native call, so superseded tasks are discarded instantly and the thread
     * blows through the backlog to actually decode only the item the user
     * settled on. The predicate is read on the decode thread; callers must make
     * the state it observes visible across threads (e.g. a {@code volatile}
     * sequence counter).</p>
     */
    public CompletableFuture<VisualResult> loadVisual(Path file, BooleanSupplier stillWanted) {
        return CompletableFuture.supplyAsync(() -> {
            if (!stillWanted.getAsBoolean()) return null;
            Optional<PdfMrcView> mrc = PdfMrcMedia.view(file);
            if (mrc.isPresent()) {
                return PdfMrcMedia.load(file, mrc.get(), facade, MediaService::decodable);
            }
            rejectIfEmpty(file);
            return preserveVisual(file, facade.loadVisual(decodable(file)));
        }, executor);
    }

    /**
     * Reads the file's full, raw {@link Metadata} on the dedicated
     * {@code media-metadata} thread — off the {@code media-facade} thread that
     * {@link #probe}/{@link #loadVisual} use, so it never stalls fast browsing
     * (see {@code metadataExecutor}). Strictly on-demand: callers (the viewer's
     * Metadata panel) fire it only on an explicit Load / opt-in auto settle,
     * and guard the result with their own staleness check.
     */
    public CompletableFuture<Metadata> metadata(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            // A container is not media: handing it to the facade would only
            // produce a decode failure, when what it actually has to say is its
            // volume identity.
            if (ArchivePaths.isArchiveFile(file)) return archiveMetadata(file);
            Optional<PdfMrcView> mrc = PdfMrcMedia.view(file);
            if (mrc.isPresent()) return PdfMrcMedia.metadata(file, mrc.get());
            rejectIfEmpty(file);
            Metadata decoded = facade.readMetadata(decodable(file));
            return EpubEntryDetails.preserveMetadata(
                    file, PdfEntryDetails.preserveMetadata(file, decoded));
        }, metadataExecutor);
    }

    /** An archive's self-description as a {@link Metadata} snapshot. */
    private static Metadata archiveMetadata(Path archive) {
        ArchiveInfo info;
        try {
            info = ArchiveInfo.read(archive);
        } catch (IOException e) {
            throw new MediaException("cannot read " + archive.getFileName()
                    + ": " + e.getMessage(), e);
        }
        String group = info.format() == ArchiveFormat.ISO
                || info.format() == ArchiveFormat.CUE ? "Volume" : "Archive";
        var builder = new Metadata.Builder(archive);
        builder.add(group, "Format", info.summary());
        for (ArchiveInfo.Field field : info.fields()) {
            builder.add(group, field.name(), field.value());
        }
        return builder.build();
    }

    /**
     * A cached preview rendition of {@code file}, at most {@code maxEdge} on its
     * longer side, in the requested {@link ThumbnailMode}, for the mosaic view.
     * Runs entirely on the dedicated thumbnail pool: even the cache-key stat
     * stays off the caller thread, so thumbnail decoration cannot block JavaFX
     * navigation. Concurrent requests for the same rendition share one
     * generation. FIT and FILL renditions cache independently.
     */
    public CompletableFuture<Thumbnail> thumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        long queuedAt = MosaicTelemetry.now();
        return CompletableFuture.supplyAsync(() -> {
            MosaicTelemetry.recordThumbnailKeyQueue(MosaicTelemetry.elapsedSince(queuedAt));

            long keyStart = MosaicTelemetry.now();
            ThumbnailKey key = keyFor(file, maxEdge, mode);
            MosaicTelemetry.recordThumbnailKeyStat(MosaicTelemetry.elapsedSince(keyStart));

            Thumbnail hit = thumbnailCache.peek(key);
            if (hit != null) {
                MosaicTelemetry.recordThumbnailCacheHit();
                return CompletableFuture.completedFuture(hit);
            }

            AtomicBoolean created = new AtomicBoolean(false);
            CompletableFuture<Thumbnail> future = inFlight.computeIfAbsent(key, k -> {
                created.set(true);
                CompletableFuture<Thumbnail> generated = CompletableFuture.supplyAsync(() -> {
                    long loadStart = MosaicTelemetry.now();
                    try {
                        Optional<PdfMrcView> mrc = PdfMrcMedia.view(file);
                        if (mrc.isPresent()) {
                            return thumbnailCache.get(k,
                                    () -> PdfMrcMedia.thumbnail(
                                            mrc.get(), facade, MediaService::decodable,
                                            maxEdge, mode));
                        }
                        rejectIfEmpty(file);
                        return thumbnailCache.get(k,
                                () -> facade.loadThumbnail(decodable(file), maxEdge, mode));
                    } finally {
                        MosaicTelemetry.recordThumbnailLoad(
                                MosaicTelemetry.elapsedSince(loadStart));
                    }
                }, thumbnailExecutor);
                generated.whenComplete((r, e) -> {
                    inFlight.remove(k, generated);
                    thumbnailsProcessed.increment();
                });
                return generated;
            });
            if (!created.get()) MosaicTelemetry.recordThumbnailInFlightJoin();
            return future;
        }, thumbnailExecutor).thenCompose(Function.identity());
    }

    /**
     * A point-in-time snapshot of the thumbnail pipeline for the browser's
     * Diagnostics panel: renditions generated so far (cache hits excluded),
     * the worker pool's queue depth and active/total threads, and the
     * in-memory cache's entry count, retained bytes and byte budget. Fields
     * are sampled independently (not atomic across fields), which is fine for
     * a display refreshed on a timer.
     */
    public record ThumbnailStats(long processed, int queuedTasks, int activeThreads,
                                 int poolThreads, int cachedItems, long cachedBytes,
                                 long budgetBytes) {
    }

    /**
     * Live {@link ThumbnailStats}; safe to call from any thread. The queue
     * depth counts every task on the thumbnail pool — each request enqueues a
     * cheap key/cache-check task plus, on a miss, the actual generation — so
     * it reflects total backlog, not just pending decodes.
     */
    public ThumbnailStats thumbnailStats() {
        return new ThumbnailStats(
                thumbnailsProcessed.sum(),
                thumbnailExecutor.getQueue().size(),
                thumbnailExecutor.getActiveCount(),
                thumbnailExecutor.getMaximumPoolSize(),
                thumbnailCache.entryCount(),
                thumbnailCache.usedBytes(),
                thumbnailBudgetBytes);
    }

    /**
     * The first {@code limit} visual media files (by name) directly inside
     * {@code dir}: still images, videos, and audio whose leading ID3v2 tag
     * contains embedded {@code APIC}/{@code PIC} cover art. All three yield a
     * frame for a mosaic folder tile's preview collage.
     *
     * <p>Classification stays cheap: filename extension, no native probe and
     * no recursion, plus a header/frame-header-only ID3 walk for named audio.
     * With {@link #setFolderPreviewSniff the sniff toggle} on, files the name
     * can't classify additionally get the curated {@link ContentSniffer}
     * header check, and covered unnamed ID3 audio qualifies too. Children are
     * checked in name order and the scan stops as soon as the collage is full,
     * so later audio tags are never touched unnecessarily. Runs on the
     * dedicated thumbnail pool. Audio without embedded artwork is excluded.
     * Returns an empty list for {@code limit <= 0}, an unreadable directory,
     * or one with no visual media.</p>
     */
    public CompletableFuture<FolderPreview> folderPreview(Path dir, int limit) {
        boolean sniff = folderPreviewSniff;
        return CompletableFuture.supplyAsync(() -> scanFolderPreview(dir, limit, sniff),
                thumbnailExecutor);
    }

    /**
     * Returns a direct-child count only when an existing navigation listing or
     * folder-preview scan already supplied it. Performs no filesystem I/O.
     */
    public OptionalInt knownFolderItemCount(Path dir) {
        if (dir == null) return OptionalInt.empty();
        Integer count = knownFolderItemCounts.get(folderCountKey(dir));
        return count == null ? OptionalInt.empty() : OptionalInt.of(count);
    }

    /**
     * Whether nothing anywhere under {@code dir} is media — the recursive half
     * of {@code docs/empty-states.md}, and the expensive one.
     *
     * <p>Costs what the subtree costs, so it is bounded: the walk stops after
     * {@code budget} directory listings and answers {@link Barren#UNKNOWN}
     * rather than guessing. The early exit is asymmetric on purpose — one
     * media file proves {@link Barren#NO} immediately, while proving
     * {@link Barren#YES} means exhausting the subtree, which is exactly the
     * case with no shortcut. An unreadable directory anywhere below also
     * yields {@code UNKNOWN}: we cannot see in there, so we cannot claim the
     * subtree is empty, the same rule {@link FolderVerdict#UNREADABLE} follows.
     *
     * <p>Runs on the thumbnail pool, beside the folder previews, so it can
     * never stall navigation.</p>
     */
    public CompletableFuture<Barren> barren(Path dir, int budget) {
        return CompletableFuture.supplyAsync(() -> scanBarren(dir, budget), thumbnailExecutor);
    }

    /** Whether a subtree holds any media, when the answer is affordable. */
    public enum Barren {
        /** Proven: nothing below is media. */
        YES,
        /** Disproven: media found below. */
        NO,
        /** Budget spent, or something below could not be read. Draw nothing. */
        UNKNOWN
    }

    /**
     * Breadth-first so the cheap answer arrives first: media sitting directly
     * in {@code dir} disproves barrenness on listing one, without descending
     * into a deep subtree that happens to sort first.
     */
    private static Barren scanBarren(Path dir, int budget) {
        var queue = new java.util.ArrayDeque<Path>();
        queue.add(dir);
        int listings = 0;
        while (!queue.isEmpty()) {
            if (listings >= budget) return Barren.UNKNOWN;
            Path at = queue.poll();
            listings++;
            try (Stream<Path> children = Files.list(at)) {
                for (Path child : children.toList()) {
                    // Junk is an entry-name rule, not a regular-file rule.
                    // Several members of JUNK_NAMES are normally directories
                    // (.Spotlight-V100, __MACOSX, System Volume Information);
                    // do not walk those trees or let them make a barren folder
                    // look populated.
                    if (isJunk(child)) {
                        continue;
                    } else if (Files.isDirectory(child)) {
                        queue.add(child);
                    } else if (isContent(child)) {
                        return Barren.NO;
                    }
                }
            } catch (IOException | RuntimeException e) {
                return Barren.UNKNOWN;      // cannot see in there; do not guess
            }
        }
        return Barren.YES;
    }

    /**
     * Whether a file is something the browser would navigate to see: media by
     * extension, or an archive, which is browsed as a folder and so keeps its
     * holder off the dead-end list.
     */
    private static boolean isContent(Path file) {
        if (PhotoCdImagePack.hasPcdExtension(file)) {
            return PhotoCdImagePack.isImagePack(file);
        }
        return classifyFast(file).isPresent()
                || ArchiveFormat.looksLikeArchive(file);
    }

    /**
     * Whether {@link #folderPreview} header-sniffs extension-less and
     * unknown-suffix children (per {@link ContentSniffer}) instead of staying
     * strictly name-only. Off by default; independent of the listing's
     * {@link DetectionMode} so the toggle behaves the same in both modes.
     * The caller drops its cached previews when this flips.
     */
    public void setFolderPreviewSniff(boolean sniff) {
        this.folderPreviewSniff = sniff;
    }

    /**
     * Whether junk files ({@link #JUNK_NAMES}) are dropped from listings
     * instead of shown as OTHER rows. On by default: {@link #scan} already
     * drops rotation and matched {@code .AAE} sidecars unconditionally as
     * "internal bookkeeping, not browsable rows", and a {@code .DS_Store} is
     * the same thing wearing another program's name.
     *
     * <p>Independent of hidden-file handling, which is a different axis:
     * {@code Thumbs.db} is not dot-prefixed, and someone who deliberately
     * shows hidden files still does not want {@code .DS_Store} back.</p>
     */
    public void setListingIgnoreJunk(boolean ignore) {
        this.listingIgnoreJunk = ignore;
    }

    /**
     * What the last listing scan filtered out, for the status-bar disclosure.
     * Only the opt-in filters are worth reporting everywhere; a dropped
     * {@code .DS_Store} changes nothing about a folder and is disclosed only
     * where it does — on a junk-only folder's empty state.
     *
     * @param dir the directory this tally belongs to
     */
    public record ListingHidden(Path dir, int junkFiles, int emptyFiles, int emptyFolders) {
        static final ListingHidden NONE = new ListingHidden(null, 0, 0, 0);

        /** Whether anything the user opted to hide was hidden. */
        public boolean anyOptIn() {
            return emptyFiles > 0 || emptyFolders > 0;
        }
    }

    /** The tally for {@code dir}, or an all-zero tally when the last scan was elsewhere. */
    public ListingHidden hiddenIn(Path dir) {
        ListingHidden last = lastHidden;
        return last.dir() != null && last.dir().equals(dir) ? last : ListingHidden.NONE;
    }

    /** Whether zero-byte files are dropped from listings. Off by default. */
    public void setListingHideEmptyFiles(boolean hide) {
        this.listingHideEmptyFiles = hide;
    }

    /**
     * Whether {@link FolderVerdict#EMPTY} and {@link FolderVerdict#JUNK_ONLY}
     * subfolders are dropped from listings. Off by default, and deliberately
     * so: this is the one listing filter that costs I/O, one extra directory
     * listing per subfolder, paid eagerly for the whole directory rather than
     * lazily per visible tile. Bounded (never recursive), and only paid by a
     * user who asked for it.
     *
     * <p>{@link FolderVerdict#UNREADABLE} is never hidden — losing a folder
     * you own behind a permission error is not a tidier listing.</p>
     */
    public void setListingHideEmptyFolders(boolean hide) {
        this.listingHideEmptyFolders = hide;
    }

    /**
     * Where a browse into {@code dir} should land: past any run of folders that
     * hold exactly one subfolder and nothing but junk beside it.
     *
     * <p>The filesystem twin of {@link ArchivePaths#browseRoot}, which has
     * collapsed this same shape inside archives since archives shipped. Costs
     * one listing per level and is capped at {@code maxDescent}, so it is
     * bounded no matter how pathological the tree — and the levels it skips are
     * ones the user was about to click through by hand, which now never render
     * a tile or decode a thumbnail.</p>
     *
     * <p>Nothing is hidden: the location bar shows where you landed and
     * {@code ..} still walks back up through every level that was skipped.</p>
     */
    public static Path collapseChain(Path dir, int maxDescent) {
        Path at = dir;
        for (int step = 0; step < maxDescent; step++) {
            Path only = soleSubdirectory(at);
            if (only == null) return at;
            at = only;
        }
        return at;
    }

    /**
     * The one subdirectory of {@code dir} when that is all it holds (junk and
     * zero-byte files do not count against it), otherwise {@code null}.
     */
    private static Path soleSubdirectory(Path dir) {
        Path only = null;
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path p : children) {
                // The junk rule applies to directory entries too. In
                // particular, __MACOSX beside the one real folder must not
                // turn a collapsible chain into a branch.
                if (isJunk(p)) continue;
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, BasicFileAttributes.class);
                } catch (IOException unreadableChild) {
                    return null;            // something real we cannot judge
                }
                if (attrs.isDirectory()) {
                    if (only != null) return null;      // two subfolders: not a chain
                    only = p;
                } else if (!isJunk(p) && attrs.size() > 0) {
                    return null;            // real content here: this level counts
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return only;
    }

    /**
     * One listing, two answers: the collage children and the folder's verdict.
     *
     * <p>Children are read through a {@link DirectoryStream} with a single
     * {@code readAttributes} each — the same one stat per child the old
     * {@code Files.isDirectory} filter already paid — so learning the folder is
     * empty, or holds nothing but droppings, costs no additional I/O.</p>
     *
     * <p>The early stop survives: once the collage is full the remaining files
     * are never tested (no ID3 walk, no header sniff). That only ever happens
     * when previews were found, which already settles the verdict as
     * {@link FolderVerdict#NORMAL}; a folder that yields none has had every
     * file tested anyway, so {@link FolderVerdict#NO_VISUAL} is exact.</p>
     */
    private FolderPreview scanFolderPreview(Path dir, int limit, boolean sniff) {
        var files = new ArrayList<Path>();
        int subdirs = 0, junkOrEmpty = 0;
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path p : children) {
                // Judge junk by entry name before asking what kind of entry it
                // is. Several configured junk names are directories in
                // practice; they still count toward JUNK_ONLY and must never
                // become previewable children.
                if (isJunk(p)) {
                    files.add(p);
                    junkOrEmpty++;
                    continue;
                }
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, BasicFileAttributes.class);
                } catch (IOException unreadableChild) {
                    files.add(p);           // it exists; we just cannot size it
                    continue;
                }
                if (attrs.isDirectory()) {
                    subdirs++;
                } else {
                    files.add(p);
                    if (attrs.size() == 0) junkOrEmpty++;
                }
            }
        } catch (IOException | RuntimeException e) {
            return FolderPreview.UNREADABLE;
        }
        rememberFolderItemCount(dir, subdirs + files.size());
        files.sort(Comparator.comparing(p -> p.getFileName().toString(),
                String.CASE_INSENSITIVE_ORDER));

        var previews = new ArrayList<Path>(Math.max(0, limit));
        if (limit > 0) {
            for (Path file : files) {
                if (previews.size() >= limit) break;
                if (isVisualPreview(file, sniff)) previews.add(file);
            }
        }
        return FolderPreview.of(previews,
                folderVerdict(subdirs, files.size(), junkOrEmpty, !previews.isEmpty(), limit),
                junkOrEmpty);
    }

    /**
     * The flat verdict, mirroring {@code scripts/empty-tree-survey.py} exactly
     * so the field measurements keep predicting what the app does.
     *
     * <p>{@code limit <= 0} means the caller asked for no collage (previews
     * off), so no file was tested for visual content and
     * {@link FolderVerdict#NO_VISUAL} cannot be distinguished from
     * {@link FolderVerdict#NORMAL}. That costs nothing: NO_VISUAL is not
     * marked on a tile — only the dead-end verdicts are, and those are decided
     * without looking inside a single file.</p>
     */
    private static FolderVerdict folderVerdict(int subdirs, int files, int junkOrEmpty,
                                               boolean anyVisual, int limit) {
        if (subdirs == 0 && files == 0) return FolderVerdict.EMPTY;
        if (subdirs == 0 && files == junkOrEmpty) return FolderVerdict.JUNK_ONLY;
        if (limit > 0 && files > 0 && !anyVisual) return FolderVerdict.NO_VISUAL;
        return FolderVerdict.NORMAL;
    }

    /**
     * Whether a child qualifies for a folder-preview cell: a visual kind by
     * extension, covered ID3 audio, or — sniffing on — either of those kinds
     * by header magic when the name classifies as nothing. Promotion-only,
     * mirroring {@link #classifyByContent}: a present extension verdict is
     * never re-judged, and the junk gate keeps known OS droppings
     * ({@code .DS_Store} et al.) from paying the read.
     */
    private static boolean isVisualPreview(Path file, boolean sniff) {
        if (PhotoCdImagePack.hasPcdExtension(file)) {
            return PhotoCdImagePack.isImagePack(file);
        }
        Optional<MediaKind> byExtension = classifyFast(file);
        if (byExtension.isPresent()) return isVisualPreviewKind(file, byExtension.get());
        if (!sniff || isJunk(file)) return false;
        return ContentSniffer.sniff(file)
                .map(kind -> isVisualPreviewKind(file, kind))
                .orElse(false);
    }

    private static boolean isVisualPreviewKind(Path file, MediaKind kind) {
        return isVisualKind(kind)
                || (kind == MediaKind.AUDIO && Id3CoverArt.hasEmbeddedPicture(file));
    }

    /** Whether a kind yields a still frame for a folder-preview cell (image or video). */
    private static boolean isVisualKind(MediaKind kind) {
        return kind == MediaKind.IMAGE || kind == MediaKind.VIDEO;
    }

    /** Builds the cache key, stamping the file's current mtime and size. */
    private static ThumbnailKey keyFor(Path file, int maxEdge, ThumbnailMode mode) {
        long mtime = 0, size = -1;
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            mtime = attrs.lastModifiedTime().toMillis();
            size = attrs.size();
        } catch (IOException ignored) {
            // unreadable stat: key on path + maxEdge + mode alone (mtime 0, size -1)
        }
        return new ThumbnailKey(file, mtime, size, maxEdge, mode);
    }

    /**
     * A player for the file's video stream, not yet started. The player runs
     * on its own thread (independent of this service's facade thread); sink
     * and callbacks are invoked on that playback thread.
     */
    public VideoPlayer newVideoPlayer(Path file, VideoPlayer.FrameSink sink,
                                      Runnable onEnded, Consumer<Throwable> onError) {
        return new VideoPlayer(facade, decodable(file), sink, onEnded, onError);
    }

    /**
     * Facts read straight from the filesystem stat (no media probe, no native
     * call): size and the three timestamps. What the info panels' File section
     * shows the moment an item is selected, before — and independent of — the
     * probe.
     */
    public record FileFacts(long size, FileTime modified, FileTime created,
                            FileTime accessed) {
    }

    /**
     * Reads {@code file}'s {@link FileFacts} on the dedicated
     * {@code media-file-op} thread (a stat can block on network volumes, so it
     * stays off the FX thread); completes exceptionally when the attributes
     * cannot be read.
     */
    public CompletableFuture<FileFacts> fileFacts(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileFacts(a.size(), a.lastModifiedTime(),
                        a.creationTime(), a.lastAccessTime());
            } catch (IOException e) {
                throw new MediaException("cannot stat " + file + ": " + e.getMessage(), e);
            }
        }, fileOpExecutor);
    }

    /**
     * Reads a container's self-description (format, volume identity, entry
     * counts) on the {@code media-file-op} thread.
     *
     * <p>Runs when an archive is merely <em>selected</em>, so it deliberately
     * does not mount: see {@link ArchiveInfo}. Kept off the facade thread too,
     * since it is a filesystem read and has no business queueing behind a
     * decode.</p>
     */
    public CompletableFuture<ArchiveInfo> archiveInfo(Path archive) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ArchiveInfo.read(archive);
            } catch (IOException e) {
                throw new MediaException("cannot read " + archive.getFileName()
                        + ": " + e.getMessage(), e);
            }
        }, fileOpExecutor);
    }

    /**
     * Runs a blocking filesystem {@code task} on the dedicated
     * {@code media-file-op} thread, off the FX thread, completing the returned
     * future with its result (or its exception). The home for the move
     * feature's {@code Files.move}/{@code mkdir} work; marshal the result back
     * to the FX thread with {@link javafx.application.Platform#runLater}.
     */
    public <T> CompletableFuture<T> fileOp(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, fileOpExecutor);
    }

    public String nativeVersions() {
        return facade.nativeVersions();
    }



    private List<DirEntry> scan(Path dir) {
        return scan(dir, this::classify);
    }

    /**
     * The fast extension-only scan backing {@link #listEntriesFast}: same
     * enumeration as {@link #scan(Path)} but classifies files purely by name
     * ({@link ExtensionClassifier}, no I/O or native call), so it never touches
     * the slow content-sniff path.
     */
    private List<DirEntry> scanFast(Path dir) {
        return scan(dir, MediaService::classifyFast);
    }

    private List<DirEntry> scan(Path dir, Function<Path, Optional<MediaKind>> classifier) {
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.sorted(Comparator.comparing(p -> p.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            throw new MediaException("cannot list " + dir + ": " + e.getMessage(), e);
        }
        rememberFolderItemCount(dir, children.size());
        // Stems of every non-sidecar file, so a matched .AAE (one editing a file
        // we list) can be hidden while an orphaned .AAE stays visible.
        Set<String> fileStems = new HashSet<>();
        for (Path p : children) {
            if (!Files.isDirectory(p)
                    && !Sidecars.isRotationSidecar(p)
                    && !Sidecars.isAaeSidecar(p)) {
                fileStems.add(Sidecars.stem(p));
            }
        }
        var dirs = new ArrayList<DirEntry>();
        var files = new ArrayList<DirEntry>();
        int hiddenJunk = 0, hiddenEmptyFiles = 0, hiddenEmptyFolders = 0;
        for (Path p : children) {
            // Junk is an entry-name rule. Test it before isDirectory so
            // __MACOSX, .Spotlight-V100 and their peers obey the same toggle
            // and verdict rules as .DS_Store.
            if (listingIgnoreJunk && isJunk(p)) {
                hiddenJunk++;
                continue;
            } else if (Files.isDirectory(p)) {
                // The one filter that costs I/O — one listing per subfolder,
                // and only when the user turned it on. UNREADABLE is not a
                // dead end here: it is a folder we could not judge.
                if (listingHideEmptyFolders) {
                    FolderVerdict verdict = scanFolderPreview(p, 0, false).verdict();
                    if (verdict == FolderVerdict.EMPTY || verdict == FolderVerdict.JUNK_ONLY) {
                        hiddenEmptyFolders++;
                        continue;
                    }
                }
                dirs.add(new DirEntry(p, DirEntry.Type.DIRECTORY, null, 0, 0,
                        FileTraits.read(p, false)));
            } else if (Sidecars.isRotationSidecar(p)
                    || Sidecars.isMatchedAaeSidecar(p, fileStems)) {
                // Internal bookkeeping (the rotation sidecar) and matched Apple
                // .AAE edit sidecars are not browsable rows: skip them so they
                // never show as a tile or list row. An orphaned .AAE is kept.
                continue;
            } else {
                long size = sizeOf(p);
                long mtime = mtimeOf(p);
                boolean junk = isJunk(p);
                FileTraits traits = FileTraits.read(p, junk);
                if (listingHideEmptyFiles
                        && size == 0
                        && PdfEntryDetails.mrc(p).isEmpty()) {
                    hiddenEmptyFiles++;
                    continue;
                }
                DirEntry entry;
                if (junk) {
                    entry = new DirEntry(
                            p, DirEntry.Type.OTHER, null, size, mtime, traits);
                } else if (ArchivePaths.isArchiveFile(p)) {
                    // Checked in both the fast and the full scan: the check only
                    // reads bytes for names that already look like a container,
                    // and a row typed ARCHIVE in the fast listing that turned out
                    // not to be one would offer a folder the user cannot open.
                    entry = new DirEntry(
                            p, DirEntry.Type.ARCHIVE, null, size, mtime, traits);
                } else {
                    entry = classifier.apply(p)
                            .map(kind -> new DirEntry(
                                    p, DirEntry.Type.MEDIA, kind, size, mtime, traits))
                            .orElseGet(() -> new DirEntry(
                                    p, DirEntry.Type.OTHER, null, size, mtime, traits));
                }
                files.add(entry);
            }
        }
        var entries = new ArrayList<DirEntry>(dirs.size() + files.size() + 1);
        // Archive-aware: from the root of a mounted container this steps back
        // out to the folder holding the container file, which the mount's own
        // getParent() cannot do (a filesystem root has no parent).
        Path parent = ArchivePaths.parentOf(dir);
        if (parent != null) entries.add(new DirEntry(parent, DirEntry.Type.PARENT, null, 0, 0));
        entries.addAll(dirs);
        entries.addAll(files);
        lastHidden = new ListingHidden(dir, hiddenJunk, hiddenEmptyFiles, hiddenEmptyFolders);
        return entries;
    }

    private void rememberFolderItemCount(Path dir, int count) {
        knownFolderItemCounts.put(folderCountKey(dir), Math.max(0, count));
    }

    private static Path folderCountKey(Path dir) {
        return dir.normalize();
    }

    /** Classifies one file per the current {@link DetectionMode}. */
    private Optional<MediaKind> classify(Path file) {
        Optional<MediaKind> epubKind = EpubEntryDetails.viewableKind(file);
        if (epubKind.isPresent()) return epubKind;
        Optional<MediaKind> pdfKind = PdfEntryDetails.viewableKind(file);
        if (pdfKind.isPresent()) return pdfKind;
        if (PhotoCdImagePack.hasPcdExtension(file)) {
            return PhotoCdImagePack.isImagePack(file)
                    ? Optional.of(MediaKind.IMAGE) : Optional.empty();
        }
        return detectionMode == DetectionMode.FILE_EXTENSION
                ? classifyByExtension(file, ImageSequences::isAnimatedImageSequence,
                        f -> facade.classify(decodable(f)))
                : classifyByContent(file, ImageSequences::isAnimatedImageSequence,
                        f -> facade.classify(decodable(f)), ContentSniffer::sniff);
    }

    /**
     * The {@link MediaKind} for {@code file} in content detection mode: the
     * extension verdict, plus a header-magic rescue for the files the name
     * can't classify. A file whose extension already names a media kind keeps
     * the exact {@link #classifyByExtension} policy (no I/O, including its
     * animated AVIF/HEIC deferral); only when the name says nothing — no
     * extension, or an unknown suffix like {@code .dat} — is the header
     * sniffed ({@link ContentSniffer}, a curated magic-number table, one tiny
     * read), promoting misnamed media the extension mode must show as OTHER.
     *
     * <p>Deliberately promotion-only: content never overrides a present
     * extension verdict. The demotion a full per-file probe would add (a text
     * file wearing {@code .jpg} dropping to OTHER) already surfaces loudly the
     * moment the file is opened, and paying a native probe for every
     * well-named file made content mode scan slowly precisely where it could
     * never change the answer. A video renamed to {@code .jpg} is therefore
     * trusted as IMAGE here and fails visibly at view time — consistent with
     * preferring loud errors over silent reclassification.</p>
     */
    static Optional<MediaKind> classifyByContent(
            Path file,
            Predicate<Path> isAnimatedSequence,
            Function<Path, Optional<MediaKind>> facadeClassify,
            Function<Path, Optional<MediaKind>> sniff) {
        Optional<MediaKind> byExtension =
                classifyByExtension(file, isAnimatedSequence, facadeClassify);
        return byExtension.isPresent() ? byExtension : sniff.apply(file);
    }

    /**
     * The {@link MediaKind} for {@code file} in file-extension detection mode.
     * Pure name classification ({@link ExtensionClassifier}, no I/O) for
     * everything <em>except</em> an animated AVIF/HEIC: a still-image extension
     * hiding a {@code moov} animation track is the one case the name alone
     * can't settle, because whether it actually plays is backend-specific
     * (FFmpeg always demuxes the track; Windows Media Foundation / Apple only
     * when the OS can; the pure stack never). That case defers to
     * {@code facadeClassify} — the active backend's content-aware policy — so
     * the viewer never offers a Play button the backend can't honor, and never
     * freezes a playable animation on its first frame.
     *
     * <p>The facade (and its {@code moov} probe) is only consulted for the rare
     * animated AVIF/HEIC — a name {@link ExtensionClassifier} already calls an
     * IMAGE and {@code isAnimatedSequence} (a cheap, memoised {@code moov}-box
     * scan gated to that family) confirms is animated. Ordinary stills, videos
     * and audio keep the fast name-only path and touch neither disk nor the
     * native backend. Package-private and dependency-injected so the policy is
     * unit-testable without a real facade.</p>
     */
    static Optional<MediaKind> classifyByExtension(
            Path file,
            Predicate<Path> isAnimatedSequence,
            Function<Path, Optional<MediaKind>> facadeClassify) {
        Optional<MediaKind> byExtension = ExtensionClassifier.classify(file);
        if (byExtension.equals(Optional.of(MediaKind.IMAGE))
                && isAnimatedSequence.test(file)) {
            return facadeClassify.apply(file);
        }
        return byExtension;
    }

    /** Extension classification plus retained PDF raster descriptors. */
    private static Optional<MediaKind> classifyFast(Path file) {
        Optional<MediaKind> epubKind = EpubEntryDetails.viewableKind(file);
        if (epubKind.isPresent()) return epubKind;
        Optional<MediaKind> pdfKind = PdfEntryDetails.viewableKind(file);
        return pdfKind.isPresent() ? pdfKind : ExtensionClassifier.classify(file);
    }

    /** Whether the file is a known non-media OS/sidecar file to skip sniffing for. */
    private static boolean isJunk(Path file) {
        Path name = file.getFileName();
        return name != null && JUNK_NAMES.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * On-screen note for a zero-byte file, shared by every surface (info,
     * metadata, viewer, playback). See {@link #rejectIfEmpty}.
     */
    static final String EMPTY_FILE_MESSAGE = "Empty file (0 bytes)";

    /**
     * Fails fast with a plain-language {@link MediaException} when {@code file}
     * exists but has no bytes — a truncated/failed copy no backend can open. A
     * native probe/decode on it throws a message ("cannot probe …") that reads
     * like a decoder bug; catching it here lets the info panel, metadata panel,
     * viewer and playback all surface the same honest "Empty file" note through
     * their existing error paths, instead of a confusing native failure. An
     * unreadable stat (the size can't be determined) is left to the facade,
     * which reports the real I/O problem.
     */
    static void rejectIfEmpty(Path file) {
        long size;
        try {
            if (!Files.isRegularFile(file)) return;
            size = Files.size(file);
        } catch (IOException e) {
            return; // unreadable: let the facade attempt and report the real error
        }
        if (size == 0L) throw new MediaException(EMPTY_FILE_MESSAGE);
    }

    /** File size in bytes, or {@code 0} if it cannot be read. */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** File last-modified time in millis since epoch, or {@code 0} if it cannot be read. */
    private static long mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        fileOpExecutor.shutdownNow();
        metadataExecutor.shutdownNow();
        thumbnailExecutor.shutdownNow();
        thumbnailCache.clear();
        facade.close();
    }
}
