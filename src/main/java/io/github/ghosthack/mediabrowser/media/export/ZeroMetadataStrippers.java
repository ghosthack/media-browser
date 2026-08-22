package io.github.ghosthack.mediabrowser.media.export;

import io.github.ghosthack.metadatastripper.MediaFormat;
import io.github.ghosthack.metadatastripper.MetadataStripper;
import io.github.ghosthack.metadatastripper.UnsupportedFormatException;
import io.github.ghosthack.mediabrowser.media.ffm.FfmpegMetadataStripper;

import java.io.IOException;
import java.nio.file.Path;

/** Constructs the format-routed, payload-preserving metadata stripper. */
public final class ZeroMetadataStrippers {
    private ZeroMetadataStrippers() {}

    public static ZeroMetadataStripper create() {
        return new FormatRoutingZeroMetadataStripper();
    }

    /**
     * Selects exactly one implementation from a content probe; an implementation
     * failure is surfaced and never retried through another writer.
     */
    private static final class FormatRoutingZeroMetadataStripper implements ZeroMetadataStripper {
        private final FfmpegMetadataStripper ffmpeg = new FfmpegMetadataStripper();
        private final MetadataStripper structural = new MetadataStripper();

        @Override
        public Result stripDetailed(Path input) throws IOException {
            MediaFormat format;
            try {
                format = structural.detect(input);
            } catch (UnsupportedFormatException unsupported) {
                return ffmpeg.stripDetailed(input);
            }
            if (format == MediaFormat.MP3 || format == MediaFormat.WAV) {
                return ffmpeg.stripDetailed(input);
            }
            var result = structural.stripDetailed(input);
            return new Result(result.input(), result.output(), result.inputBytes(), result.outputBytes());
        }
    }
}
