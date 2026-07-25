package io.github.ghosthack.mediabrowser.media.archive.iso;

import java.util.List;

/**
 * One name in an ISO 9660 directory: a file or a subdirectory, already
 * decoded to its display name (Joliet UCS-2 or Rock Ridge {@code NM} where
 * present, otherwise the 8.3 identifier with its {@code ;1} version suffix
 * stripped).
 *
 * <p>The content is described as a list of {@link Extent}s rather than one
 * offset/length pair because ISO 9660 splits a file larger than 4 GiB − 1
 * across several directory records chained by the multi-extent flag; for the
 * overwhelmingly common single-extent file the list holds exactly one element.
 * Extents are byte offsets into the image, already multiplied out from the
 * logical block address, so a reader never needs the block size again.</p>
 *
 * @param mtimeMillis the recording timestamp in millis since epoch, or
 *                    {@code 0} when the record's date field is unset — ISO
 *                    dates carry no sub-second precision and pre-1900 dates
 *                    are not representable, so {@code 0} means "unknown"
 *                    rather than the epoch itself
 */
public record IsoEntry(String name, boolean directory, List<Extent> extents,
                       long size, long mtimeMillis) {

    /** A contiguous run of bytes in the image backing (part of) an entry. */
    public record Extent(long offset, long length) {}

    public IsoEntry {
        extents = List.copyOf(extents);
    }
}
