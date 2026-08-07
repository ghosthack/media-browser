package io.github.ghosthack.mediabrowser.media.color;

import io.github.ghosthack.mediabrowser.media.ColorProfile;
import io.github.ghosthack.mediabrowser.media.MediaEngineTrace;
import io.github.ghosthack.mediabrowser.media.MediaFacade;
import io.github.ghosthack.mediabrowser.media.MediaKind;
import io.github.ghosthack.mediabrowser.media.MediaProbe;
import io.github.ghosthack.mediabrowser.media.Metadata;
import io.github.ghosthack.mediabrowser.media.RasterFrame;
import io.github.ghosthack.mediabrowser.media.Thumbnail;
import io.github.ghosthack.mediabrowser.media.ThumbnailMode;
import io.github.ghosthack.mediabrowser.media.VideoStream;
import io.github.ghosthack.mediabrowser.media.VisualResult;
import io.github.ghosthack.mediabrowser.media.ffm.FfmpegFfmMediaFacade;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Still-only ICC and image-metadata decorator over the bundled FFmpeg +
 * TurboJPEG facade. Metadata enrichment also covers animated GIFs, while the
 * wrapped facade remains the sole decoder and playback engine.
 */
public final class ColorManagedMediaFacade implements MediaFacade {

    private static final Set<String> METADATA_ENRICHMENT_EXTENSIONS = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "webp", "bmp",
            "tif", "tiff", "avif", "heic", "heif", "jxl", "jp2", "j2k",
            "jpf", "jls", "pcd", "tga", "pcx", "ppm", "pgm", "pbm",
            "pnm", "pam", "pfm", "xpm", "xbm", "xwd", "sgi", "ras",
            "dds", "hdr", "exr", "ico", "qoi", "dpx", "psd", "cr2",
            "cr3", "nef", "arw", "raf", "orf", "dng", "rw2", "pef");

    private final MediaFacade delegate;
    private final ProfileCache profiles = new ProfileCache(EmbeddedProfileReader::read);

    public ColorManagedMediaFacade(MediaFacade delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    /** Reflective {@link io.github.ghosthack.mediabrowser.media.MediaBackend} factory. */
    public static ColorManagedMediaFacade withTurboJpeg() {
        return new ColorManagedMediaFacade(
                FfmpegFfmMediaFacade.withTurboJpegColorProfiles());
    }

    @Override
    public Optional<MediaKind> classify(Path file) {
        return delegate.classify(file);
    }

    @Override
    public MediaProbe probe(Path file) {
        MediaProbe base = delegate.probe(file);
        return base.kind() == MediaKind.IMAGE ? attachProfile(file, base) : base;
    }

    @Override
    public VisualResult loadVisual(Path file) {
        return manageVisual(file, delegate.loadVisual(file), null);
    }

    @Override
    public VisualResult loadVisual(Path file, MediaEngineTrace.Recorder trace) {
        return manageVisual(file, delegate.loadVisual(file, trace), trace);
    }

    private VisualResult manageVisual(Path file, VisualResult base,
                                      MediaEngineTrace.Recorder trace) {
        if (base.probe().kind() != MediaKind.IMAGE) return base;
        long started = trace == null ? 0L : trace.beginAttempt();
        MediaProbe probe = attachProfile(file, base.probe());
        if (base.frame().isEmpty()) {
            if (trace != null) trace.declined("ICC color management", started,
                    "skipped (no still raster)");
            return new VisualResult(probe, base.frame());
        }
        if (ColorPolicy.mode() == ColorPolicy.Mode.UNMANAGED) {
            if (trace != null) trace.declined("ICC color management", started,
                    bypassDetail(probe.colorProfile()));
            return new VisualResult(probe, base.frame());
        }
        IccColorConverter.Decision decision =
                IccColorConverter.convert(base.frame().orElseThrow(), probe.colorProfile());
        recordDecision(trace, started, decision);
        return new VisualResult(probe, Optional.of(decision.frame()));
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode) {
        return manageThumbnail(file, delegate.loadThumbnail(file, maxEdge, mode), null);
    }

    @Override
    public Thumbnail loadThumbnail(Path file, int maxEdge, ThumbnailMode mode,
                                   MediaEngineTrace.Recorder trace) {
        return manageThumbnail(file, delegate.loadThumbnail(file, maxEdge, mode, trace), trace);
    }

    private Thumbnail manageThumbnail(Path file, Thumbnail base,
                                      MediaEngineTrace.Recorder trace) {
        if (base.kind() != MediaKind.IMAGE) return base;
        long started = trace == null ? 0L : trace.beginAttempt();
        ColorProfile profile = base.colorProfile() != null
                ? base.colorProfile() : profiles.profile(file).orElse(null);
        if (base.frame().isEmpty()) {
            if (trace != null) trace.declined("ICC color management", started,
                    profile == null ? "skipped (untagged; no still raster)"
                            : "skipped (no still raster; " + profile.name() + ")");
            return base;
        }
        if (ColorPolicy.mode() == ColorPolicy.Mode.UNMANAGED) {
            if (trace != null) trace.declined("ICC color management", started,
                    bypassDetail(profile));
            return new Thumbnail(base.frame(), base.kind(), profile);
        }
        IccColorConverter.Decision decision =
                IccColorConverter.convert(base.frame().orElseThrow(), profile);
        recordDecision(trace, started, decision);
        return new Thumbnail(Optional.of(decision.frame()), base.kind(), profile);
    }

    private static String bypassDetail(ColorProfile profile) {
        return profile == null ? "bypassed (unmanaged; untagged)"
                : "bypassed (unmanaged; " + profile.name() + ")";
    }

    private MediaProbe attachProfile(Path file, MediaProbe probe) {
        if (probe.colorProfile() != null) return probe;
        return probe.withColorProfile(profiles.profile(file).orElse(null));
    }

    private static void recordDecision(MediaEngineTrace.Recorder trace, long started,
                                       IccColorConverter.Decision decision) {
        if (trace == null) return;
        if (decision.outcome() == IccColorConverter.Outcome.APPLIED) {
            trace.succeeded("ICC color management", started, decision.detail());
        } else {
            trace.declined("ICC color management", started, decision.detail());
        }
    }

    @Override
    public Metadata readMetadata(Path file) {
        Metadata base = delegate.readMetadata(file);
        return supportsMetadataEnrichment(file) ? MetadataEnricher.enrich(file, base) : base;
    }

    @Override
    public VideoStream openVideo(Path file) {
        return delegate.openVideo(file);
    }

    @Override
    public VideoStream openVideo(Path file, MediaEngineTrace.Recorder trace) {
        return delegate.openVideo(file, trace);
    }

    @Override
    public String nativeVersions() {
        return delegate.nativeVersions() + "; JDK LittleCMS; metadata-extractor 2.19.0";
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static boolean supportsMetadataEnrichment(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && METADATA_ENRICHMENT_EXTENSIONS.contains(
                name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
