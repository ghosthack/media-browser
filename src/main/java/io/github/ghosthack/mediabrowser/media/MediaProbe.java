package io.github.ghosthack.mediabrowser.media;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of probing a media file. Numeric fields use -1 when unknown,
 * string fields use null.
 */
public record MediaProbe(
        Path path,
        MediaKind kind,
        String container,
        long fileSize,
        long durationMicros,
        long bitRate,
        int width,
        int height,
        String videoCodec,
        double frameRate,
        String audioCodec,
        int sampleRate,
        int channels,
        String pixelDescription) {

    /** Ordered display rows for the info panel; empty/unknown fields are skipped. */
    public Map<String, String> describe() {
        var rows = new LinkedHashMap<String, String>();
        rows.put("Name", path.getFileName().toString());
        rows.put("Kind", kind.toString());
        if (container != null) rows.put("Format", container);
        if (fileSize >= 0) rows.put("Size", humanBytes(fileSize));
        if (durationMicros >= 0) rows.put("Duration", humanDuration(durationMicros));
        if (bitRate > 0) rows.put("Bit rate", (bitRate / 1000) + " kb/s");
        if (width > 0 && height > 0) rows.put("Dimensions", width + " × " + height);
        if (videoCodec != null) rows.put("Video codec", videoCodec);
        if (frameRate > 0) rows.put("Frame rate", String.format("%.3f fps", frameRate));
        if (audioCodec != null) rows.put("Audio codec", audioCodec);
        if (sampleRate > 0) rows.put("Sample rate", sampleRate + " Hz");
        if (channels > 0) rows.put("Channels", Integer.toString(channels));
        if (pixelDescription != null) rows.put("Pixels", pixelDescription);
        rows.put("Path", path.toString());
        return rows;
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        for (String unit : new String[] {"KiB", "MiB", "GiB", "TiB"}) {
            v /= 1024.0;
            if (v < 1024) return String.format("%.1f %s", v, unit);
        }
        return String.format("%.1f PiB", v / 1024.0);
    }

    public static String humanDuration(long micros) {
        long totalSec = micros / 1_000_000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        return h > 0
                ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%d:%02d", m, s);
    }
}
