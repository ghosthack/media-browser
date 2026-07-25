package io.github.ghosthack.mediabrowser.media.archive.iso;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The descriptive contents of an ISO 9660 primary volume descriptor: who
 * published the disc, when it was mastered, how it is named.
 *
 * <p>Worth surfacing because it is the only place a disc's own identity
 * survives. A ripped {@code .iso} carries the filesystem timestamp of whenever
 * it was ripped or downloaded — for a 1993 Walnut Creek CD that is typically
 * decades late — while the volume creation date recorded here is the real
 * mastering date. Likewise the publisher string, which on old shareware discs
 * is often a company name and phone number that appears nowhere else.</p>
 *
 * @param namingScheme  which of the three naming schemes the image is read
 *                      under, which is what explains the names the user sees
 * @param fields        ordered, display-ready facts with <em>empty ones
 *                      omitted</em>: roughly half the descriptor's text fields
 *                      are blank on a typical disc, and rendering a column of
 *                      dashes for them is worse than not showing them
 */
public record IsoVolumeInfo(String namingScheme, boolean bootable,
                            long contentBytes, int blockSize,
                            Map<String, String> fields) {

    public IsoVolumeInfo {
        // Deliberately not Map.copyOf: that returns an unmodifiable map with
        // *unspecified* iteration order, which would scramble the descriptor
        // order the fields are meant to be read in.
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
