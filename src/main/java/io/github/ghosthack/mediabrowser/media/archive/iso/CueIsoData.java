package io.github.ghosthack.mediabrowser.media.archive.iso;

import io.github.ghosthack.cue.CueArchive;
import io.github.ghosthack.cue.CueTrackData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Owns a CUE/BIN set and exposes its sole ISO data track positionally. */
final class CueIsoData implements IsoData {
    private final CueArchive archive;
    private final CueTrackData track;

    private CueIsoData(CueArchive archive, CueTrackData track) {
        this.archive = archive;
        this.track = track;
    }

    static CueIsoData open(Path cue) throws IOException {
        CueArchive archive = CueArchive.open(cue);
        try {
            return new CueIsoData(archive, archive.openIsoData());
        } catch (IOException | RuntimeException e) {
            archive.close();
            throw e;
        }
    }

    @Override public long size() throws IOException { return track.size(); }
    @Override public int read(ByteBuffer target, long position) throws IOException {
        return track.read(position, target);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            track.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            archive.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
