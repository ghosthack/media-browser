package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.mediabrowser.media.Metadata;
import io.github.ghosthack.mediabrowser.media.ffm.bind.FfmpegBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Full AV metadata read through a {@link FfmpegBindings}: the container's
 * {@code AVDictionary} plus each stream's {@code AVDictionary} —
 * {@code creation_time}, {@code com.apple.quicktime.*}, encoder, {@code rotate},
 * ID3 audio tags, etc.
 *
 * <p>Opens the format context header-only (no frames are decoded) and always
 * closes it in {@code finally}.</p>
 */
final class FfmpegMetadata {

    private final FfmpegBindings ff;

    FfmpegMetadata(FfmpegBindings ff) {
        this.ff = ff;
    }

    Metadata read(Path file) {
        Metadata.Builder out = new Metadata.Builder(file);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxPtr = ff.openInput(arena, file);
            try {
                MemorySegment ctx = ff.derefFormatContext(ctxPtr);
                readDict(arena, ff.containerMetadata(ctx), "Container", out);
                int streams = ff.nbStreams(ctx);
                for (int i = 0; i < streams; i++) {
                    MemorySegment stream = ff.stream(ctx, i);
                    readDict(arena, ff.streamMetadata(stream),
                            "Stream " + i + " (" + streamKind(stream) + ")", out);
                }
            } finally {
                ff.closeInput(ctxPtr);
            }
        }
        return out.build();
    }

    /**
     * Walks an {@code AVDictionary} with the canonical iterate loop: start with
     * {@code prev = NULL}, feed each returned entry back as the next
     * {@code prev}, stop at NULL. Emits into {@code group} (created lazily, so a
     * stream with no tags adds no group).
     */
    private void readDict(Arena arena, MemorySegment dict, String group, Metadata.Builder out) {
        if (dict == null || dict.equals(MemorySegment.NULL)) return;
        MemorySegment emptyKey = arena.allocateFrom("");
        MemorySegment prev = MemorySegment.NULL;
        while (true) {
            MemorySegment entry = ff.dictGetFirst(dict, emptyKey, prev);
            if (entry.equals(MemorySegment.NULL)) break;
            String key = ff.dictEntryKey(entry);
            String value = ff.dictEntryValue(entry);
            out.add(group, key == null ? "" : key, value);
            prev = entry;
        }
    }

    /** Stream-type label for the group name, from the codec parameters. */
    private String streamKind(MemorySegment stream) {
        int type = ff.parCodecType(ff.codecpar(stream));
        if (type == ff.mediaTypeVideo()) return "video";
        if (type == ff.mediaTypeAudio()) return "audio";
        if (type == ff.mediaTypeSubtitle()) return "subtitle";
        return "other";
    }
}
