package io.github.ghosthack.mediabrowser.media;

import com.drew.lang.ByteArrayReader;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.icc.IccReader;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Validated embedded ICC profile carried by a {@link MediaProbe}.
 *
 * <p>The byte array is defensively copied at both boundaries. Keeping the
 * validated profile in the probe lets decode decorators make one color
 * decision and reuse it for the raster produced by that operation.</p>
 */
public final class ColorProfile {

    private static final byte[] JDK_SRGB =
            ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();

    private final byte[] icc;
    private final String name;
    private final boolean srgb;

    private ColorProfile(byte[] icc, String name, boolean srgb) {
        this.icc = icc;
        this.name = name;
        this.srgb = srgb;
    }

    /** Validates raw ICC bytes; malformed and non-RGB profiles are declined. */
    public static Optional<ColorProfile> parse(byte[] data) {
        if (data == null || data.length < 132) return Optional.empty();
        byte[] copy = data.clone();
        try {
            ICC_Profile profile = ICC_Profile.getInstance(copy);
            if (profile.getColorSpaceType() != ColorSpace.TYPE_RGB
                    || profile.getNumComponents() != 3) {
                return Optional.empty();
            }
            String name = profileName(copy).orElse("Embedded RGB ICC profile");
            String normalized = name.toLowerCase(Locale.ROOT)
                    .replace("-", "").replace("_", "").replace(" ", "");
            // The name alone is not enough: "Linear sRGB" / "sRGB-elle-V4-g10"
            // share sRGB's primaries but not its curve, and skipping their
            // conversion shows linear data as if it were gamma-encoded.
            boolean srgb = Arrays.equals(copy, JDK_SRGB)
                    || (normalized.contains("srgb") && encodesLikeSrgb(profile));
            return Optional.of(new ColorProfile(copy, name, srgb));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Mid-greys must come back within this of themselves for a profile to pass as sRGB. */
    private static final float SRGB_PROBE_TOLERANCE = 12f / 255f;

    /**
     * Whether neutral greys survive a conversion to sRGB roughly unchanged. A
     * linear curve moves mid-grey by about 60/255 and gamma 1.8 by about 19;
     * genuine sRGB variants (v2/v4, table or parametric curve) stay within a
     * few. A profile the JDK cannot evaluate keeps the name's verdict, as
     * before this probe existed.
     */
    private static boolean encodesLikeSrgb(ICC_Profile profile) {
        try {
            var space = new java.awt.color.ICC_ColorSpace(profile);
            for (float grey : new float[] {0.25f, 0.5f, 0.75f}) {
                float[] rgb = space.toRGB(new float[] {grey, grey, grey});
                for (float channel : rgb) {
                    if (Math.abs(channel - grey) > SRGB_PROBE_TOLERANCE) return false;
                }
            }
            return true;
        } catch (RuntimeException unprobeable) {
            return true;
        }
    }

    private static Optional<String> profileName(byte[] data) {
        var metadata = new com.drew.metadata.Metadata();
        new IccReader().extract(new ByteArrayReader(data), metadata);
        IccDirectory directory = metadata.getFirstDirectoryOfType(IccDirectory.class);
        if (directory == null) return Optional.empty();
        String name = directory.getDescription(IccDirectory.TAG_TAG_desc);
        if (name == null || name.isBlank()) {
            name = directory.getDescription(IccDirectory.TAG_APPLE_MULTI_LANGUAGE_PROFILE_NAME);
        }
        if (name == null || name.isBlank()) return Optional.empty();
        name = name.trim();
        // metadata-extractor renders ICC v4 mluc values as
        // "1 enUS(Display P3)". The locale/count wrapper is inventory detail;
        // diagnostics and the Info panel need the human profile name.
        int open = name.indexOf('(');
        if (open > 0 && name.endsWith(")")
                && name.substring(0, open).matches("\\d+\\s+[A-Za-z]{4}")) {
            name = name.substring(open + 1, name.length() - 1);
        }
        return Optional.of(name);
    }

    public byte[] iccData() {
        return icc.clone();
    }

    public String name() {
        return name;
    }

    public boolean isSrgb() {
        return srgb;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ColorProfile that && Arrays.equals(icc, that.icc);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(icc);
    }

    @Override
    public String toString() {
        return name;
    }
}
