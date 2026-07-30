package io.github.ghosthack.cue;

import io.github.ghosthack.cue.internal.CompanionResolver;
import io.github.ghosthack.cue.internal.CueParser;
import io.github.ghosthack.cue.internal.CueParser.ParsedCue;
import io.github.ghosthack.cue.internal.CueParser.ParsedFile;
import io.github.ghosthack.cue.internal.CueParser.ParsedTrack;
import io.github.ghosthack.cue.internal.NormalizedTrackChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A read-only, bounded view of one CUE sheet and its BINARY companions.
 *
 * <p>Supported data tracks are exposed as independent seekable channels whose
 * logical sectors are always 2048 bytes. Mode 1 raw-sector headers and Mode 2
 * Form 1 headers are removed by the channel. The archive itself does not parse
 * ISO 9660; {@link #openIsoTrack()} is the bridge to an existing ISO reader.
 */
public final class CueArchive implements AutoCloseable {
    private static final int LOGICAL_SECTOR_BYTES = 2048;
    private static final long ISO_DESCRIPTOR_IDENTIFIER = 16L * LOGICAL_SECTOR_BYTES + 1;

    private final Path source;
    private final CueOpenOptions options;
    private final List<CueFile> files;
    private final List<CueTrack> tracks;
    private final List<CueSource> sources;
    private final Map<Path, Snapshot> snapshots;
    private final Set<NormalizedTrackChannel> openChannels = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private CueArchive(
            Path source,
            CueOpenOptions options,
            List<CueFile> files,
            List<CueTrack> tracks,
            List<CueSource> sources,
            Map<Path, Snapshot> snapshots) {
        this.source = source;
        this.options = options;
        this.files = List.copyOf(files);
        this.tracks = List.copyOf(tracks);
        this.sources = List.copyOf(sources);
        this.snapshots = Map.copyOf(snapshots);
    }

    /** Opens a CUE/BIN set with finite defaults and confined companion paths. */
    public static CueArchive open(Path source) throws IOException {
        return open(source, CueOpenOptions.defaults());
    }

    /** Opens a CUE/BIN set with explicit parser budgets and path policy. */
    public static CueArchive open(Path source, CueOpenOptions options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Path normalized = source.toAbsolutePath().normalize();
        try {
            BasicFileAttributes cueAttributes =
                    Files.readAttributes(normalized, BasicFileAttributes.class);
            if (!cueAttributes.isRegularFile()) {
                throw new CueArchiveException("CUE source is not a regular file: " + normalized);
            }
            ParsedCue parsed = CueParser.parse(normalized, options);
            return build(normalized, options, cueAttributes, parsed);
        } catch (CueArchiveException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new CueArchiveException("cannot open CUE sheet: " + normalized, e);
        }
    }

    /** Returns the normalized CUE path. */
    public Path source() {
        return source;
    }

    /** Returns resolved companion metadata in declaration order. */
    public List<CueFile> files() {
        return files;
    }

    /** Returns immutable track metadata in disc order. */
    public List<CueTrack> tracks() {
        return tracks;
    }

    /** Returns every track this adapter can expose as 2048-byte logical sectors. */
    public List<CueTrack> dataTracks() {
        return tracks.stream().filter(CueTrack::supportedData).toList();
    }

    /** Returns the CUE and unique companions that determine source freshness. */
    public List<CueSource> sources() {
        return sources;
    }

    /**
     * Opens one supported data track as a read-only normalized channel.
     *
     * <p>The returned channel owns an independent source handle. Closing it
     * does not close the archive or other track channels.
     */
    public SeekableByteChannel openTrack(CueTrack track) throws IOException {
        Objects.requireNonNull(track, "track");
        ensureOpen();
        validateTrackIdentity(track);
        if (!track.supportedData()) {
            throw new CueArchiveException(
                    "track " + track.number() + " is not a supported data track");
        }
        ensureSourcesUnchanged();

        FileChannel sourceChannel =
                FileChannel.open(track.file().path(), StandardOpenOption.READ);
        final NormalizedTrackChannel[] holder = new NormalizedTrackChannel[1];
        NormalizedTrackChannel channel =
                new NormalizedTrackChannel(
                        track, sourceChannel, () -> openChannels.remove(holder[0]));
        holder[0] = channel;
        openChannels.add(channel);
        if (closed && openChannels.remove(channel)) {
            channel.close();
            throw new IOException("CUE archive is closed");
        }
        return channel;
    }

    /**
     * Opens a supported track through the positional facade used by filesystem parsers.
     */
    public CueTrackData openTrackData(CueTrack track) throws IOException {
        return new CueTrackData(openTrack(track));
    }

    /** Tests whether a supported data track carries {@code CD001} at sector 16. */
    public boolean isIso9660(CueTrack track) throws IOException {
        ensureOpen();
        validateTrackIdentity(track);
        if (!track.supportedData() || track.logicalBytes() < 17L * LOGICAL_SECTOR_BYTES) {
            return false;
        }
        ByteBuffer identifier = ByteBuffer.allocate(5);
        try (SeekableByteChannel channel = openTrack(track)) {
            channel.position(ISO_DESCRIPTOR_IDENTIFIER);
            readFully(channel, identifier);
        } catch (CueSectorException e) {
            return false;
        }
        return identifier.flip().mismatch(ByteBuffer.wrap(new byte[] {'C', 'D', '0', '0', '1'}))
                == -1;
    }

    /** Returns every supported data track with an ISO 9660 descriptor signature. */
    public List<CueTrack> iso9660Tracks() throws IOException {
        ensureOpen();
        List<CueTrack> result = new ArrayList<>();
        for (CueTrack track : dataTracks()) {
            if (isIso9660(track)) result.add(track);
        }
        return List.copyOf(result);
    }

    /**
     * Opens the only ISO 9660 data track.
     *
     * @throws CueArchiveException when zero or multiple tracks are mountable
     */
    public SeekableByteChannel openIsoTrack() throws IOException {
        List<CueTrack> candidates = iso9660Tracks();
        if (candidates.size() != 1) {
            throw new CueArchiveException(
                    "expected exactly one ISO 9660 data track, found " + candidates.size());
        }
        return openTrack(candidates.getFirst());
    }

    /** Opens the only ISO 9660 data track as a thread-safe positional source. */
    public CueTrackData openIsoData() throws IOException {
        List<CueTrack> candidates = iso9660Tracks();
        if (candidates.size() != 1) {
            throw new CueArchiveException(
                    "expected exactly one ISO 9660 data track, found " + candidates.size());
        }
        return openTrackData(candidates.getFirst());
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (NormalizedTrackChannel channel : List.copyOf(openChannels)) {
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        openChannels.clear();
        if (failure != null) throw failure;
    }

    private static CueArchive build(
            Path cuePath,
            CueOpenOptions options,
            BasicFileAttributes cueAttributes,
            ParsedCue parsed)
            throws IOException {
        Path directory = Optional.ofNullable(cuePath.getParent()).orElse(Path.of("."));
        List<CueFile> files = new ArrayList<>(parsed.files().size());
        Map<Path, Snapshot> snapshots = new LinkedHashMap<>();
        snapshots.put(cuePath, Snapshot.of(cueAttributes));

        for (ParsedFile parsedFile : parsed.files()) {
            if (parsedFile.type != CueFileType.BINARY) {
                throw new CueArchiveException(
                        "FILE type "
                                + parsedFile.type
                                + " is not supported: "
                                + parsedFile.declaredName);
            }
            Path companion =
                    CompanionResolver.resolve(directory, parsedFile.declaredName, options);
            BasicFileAttributes attributes =
                    Files.readAttributes(companion, BasicFileAttributes.class);
            snapshots.putIfAbsent(companion, Snapshot.of(attributes));
            files.add(
                    new CueFile(
                            parsedFile.index,
                            parsedFile.declaredName,
                            companion,
                            parsedFile.type,
                            attributes.size()));
        }

        List<CueTrack> tracks = new ArrayList<>(parsed.tracks().size());
        for (ParsedFile parsedFile : parsed.files()) {
            layoutFile(parsedFile, files.get(parsedFile.index), tracks);
        }
        tracks.sort(java.util.Comparator.comparingInt(CueTrack::index));

        List<CueSource> sources = new ArrayList<>(snapshots.size());
        for (Map.Entry<Path, Snapshot> entry : snapshots.entrySet()) {
            Snapshot snapshot = entry.getValue();
            sources.add(
                    new CueSource(
                            entry.getKey(),
                            snapshot.size,
                            snapshot.modified,
                            Optional.ofNullable(snapshot.fileKey),
                            entry.getKey().equals(cuePath)));
        }
        return new CueArchive(cuePath, options, files, tracks, sources, snapshots);
    }

    private static void layoutFile(
            ParsedFile parsedFile, CueFile file, List<CueTrack> output)
            throws CueArchiveException {
        List<ParsedTrack> tracks = parsedFile.tracks;
        long[] segmentFrames = new long[tracks.size()];
        long[] segmentOffsets = new long[tracks.size()];
        for (int index = 0; index < tracks.size(); index++) {
            ParsedTrack track = tracks.get(index);
            CueIndex index01 = track.index(1);
            CueIndex index00 = track.index(0);
            segmentFrames[index] = index00 == null ? index01.frames() : index00.frames();
            if (track.mode.storedSectorBytes() <= 0) {
                throw new CueArchiveException(
                        "track " + track.number + " has unsupported sector geometry");
            }
            try {
                if (index == 0) {
                    segmentOffsets[index] =
                            Math.multiplyExact(
                                    segmentFrames[index], track.mode.storedSectorBytes());
                } else {
                    ParsedTrack previous = tracks.get(index - 1);
                    long frameDelta =
                            Math.subtractExact(segmentFrames[index], segmentFrames[index - 1]);
                    if (frameDelta < 0) {
                        throw new CueArchiveException(
                                "track timestamps decrease within FILE "
                                        + parsedFile.declaredName);
                    }
                    segmentOffsets[index] =
                            Math.addExact(
                                    segmentOffsets[index - 1],
                                    Math.multiplyExact(
                                            frameDelta,
                                            previous.mode.storedSectorBytes()));
                }
            } catch (ArithmeticException e) {
                throw new CueArchiveException("track layout overflows", e);
            }
        }

        for (int index = 0; index < tracks.size(); index++) {
            ParsedTrack parsedTrack = tracks.get(index);
            CueIndex index01 = parsedTrack.index(1);
            long dataOffset;
            long dataEnd = index + 1 < tracks.size() ? segmentOffsets[index + 1] : file.size();
            try {
                dataOffset =
                        Math.addExact(
                                segmentOffsets[index],
                                Math.multiplyExact(
                                        Math.subtractExact(
                                                index01.frames(), segmentFrames[index]),
                                        parsedTrack.mode.storedSectorBytes()));
            } catch (ArithmeticException e) {
                throw new CueArchiveException("track data offset overflows", e);
            }
            if (dataOffset < 0 || dataEnd < dataOffset || dataEnd > file.size()) {
                throw new CueArchiveException(
                        "track "
                                + parsedTrack.number
                                + " lies outside companion "
                                + file.declaredName());
            }
            long storedBytes = dataEnd - dataOffset;
            int sectorBytes = parsedTrack.mode.storedSectorBytes();
            if (storedBytes % sectorBytes != 0) {
                throw new CueArchiveException(
                        "track "
                                + parsedTrack.number
                                + " ends with an incomplete "
                                + sectorBytes
                                + "-byte sector");
            }
            long logicalBytes;
            try {
                logicalBytes =
                        parsedTrack.mode.supportedData()
                                ? Math.multiplyExact(
                                        storedBytes / sectorBytes, (long) LOGICAL_SECTOR_BYTES)
                                : 0;
            } catch (ArithmeticException e) {
                throw new CueArchiveException("logical track size overflows", e);
            }
            output.add(
                    new CueTrack(
                            parsedTrack.index,
                            parsedTrack.number,
                            parsedTrack.mode,
                            file,
                            parsedTrack.indices,
                            parsedTrack.pregapFrames,
                            parsedTrack.postgapFrames,
                            dataOffset,
                            storedBytes,
                            logicalBytes));
        }
    }

    private void validateTrackIdentity(CueTrack track) throws CueArchiveException {
        if (track.index() >= tracks.size() || !tracks.get(track.index()).equals(track)) {
            throw new CueArchiveException("track does not belong to this CUE archive");
        }
    }

    private void ensureSourcesUnchanged() throws IOException {
        for (Map.Entry<Path, Snapshot> entry : snapshots.entrySet()) {
            BasicFileAttributes current =
                    Files.readAttributes(entry.getKey(), BasicFileAttributes.class);
            if (!entry.getValue().matches(current)) {
                throw new CueArchiveException(
                        "CUE/BIN source changed after it was opened: " + entry.getKey());
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("CUE archive is closed");
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer target)
            throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) throw new CueArchiveException("truncated ISO descriptor signature");
            if (read == 0) throw new CueArchiveException("data-track channel made no progress");
        }
    }

    private record Snapshot(long size, FileTime modified, String fileKey) {
        private static Snapshot of(BasicFileAttributes attributes) {
            Object key = attributes.fileKey();
            return new Snapshot(
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    key == null ? null : key.toString());
        }

        private boolean matches(BasicFileAttributes attributes) {
            Object currentKey = attributes.fileKey();
            return attributes.isRegularFile()
                    && size == attributes.size()
                    && modified.equals(attributes.lastModifiedTime())
                    && Objects.equals(fileKey, currentKey == null ? null : currentKey.toString());
        }
    }
}
