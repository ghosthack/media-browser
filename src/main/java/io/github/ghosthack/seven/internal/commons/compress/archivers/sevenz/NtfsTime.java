package io.github.ghosthack.seven.internal.commons.compress.archivers.sevenz;

import java.nio.file.attribute.FileTime;
import java.time.Instant;

/** Converts the NTFS timestamps stored in 7z entry metadata. */
final class NtfsTime {
    private static final long HUNDRED_NANOS_PER_SECOND = 10_000_000L;
    private static final long WINDOWS_EPOCH_SECONDS = 11_644_473_600L;

    static FileTime ntfsTimeToFileTime(long value) {
        long seconds = Math.floorDiv(value, HUNDRED_NANOS_PER_SECOND);
        long hundredNanos = Math.floorMod(value, HUNDRED_NANOS_PER_SECOND);
        return FileTime.from(
                Instant.ofEpochSecond(
                        seconds - WINDOWS_EPOCH_SECONDS, hundredNanos * 100));
    }

    private NtfsTime() {
    }
}
