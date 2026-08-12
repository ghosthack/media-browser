package io.github.ghosthack.mediabrowser.media.superres;

/**
 * Curated Real-ESRGAN models exposed by the viewer. Both are fixed 4× models;
 * keeping this list small avoids presenting arbitrary community checkpoints as
 * interchangeable or equally trustworthy.
 */
public enum SuperResolutionModel {

    /** General photography and other natural imagery (RealESRGAN_x4plus). */
    PHOTO(
            "Photo",
            "upscayl-standard-4x",
            33_424_520L,
            "713ee713b0353afaa27976f0563a64a5043bd70b9bd8936c2e26e25ebcdbcddf",
            116_029L,
            "35330ececcea33b6c397a72548e788d5d53becee4734c50b7fada36e89f10a86"),

    /** Drawn art, animation frames, and clean line work (anime 6B model). */
    ARTWORK(
            "Artwork",
            "digital-art-4x",
            8_943_500L,
            "fe01c269cfd10cdef8e018ab66ebe750cf79c7af4d1f9c16c737e1295229bacc",
            30_290L,
            "2b8fb6e0ae4d2d85704ca08c119a2f5ea40add4f2ecd512eb7f4cd44b6127ed4");

    public static final int SCALE = 4;

    private final String label;
    private final String fileBase;
    private final long binBytes;
    private final String binSha256;
    private final long paramBytes;
    private final String paramSha256;

    SuperResolutionModel(String label, String fileBase,
                         long binBytes, String binSha256,
                         long paramBytes, String paramSha256) {
        this.label = label;
        this.fileBase = fileBase;
        this.binBytes = binBytes;
        this.binSha256 = binSha256;
        this.paramBytes = paramBytes;
        this.paramSha256 = paramSha256;
    }

    public String label() {
        return label;
    }

    String fileBase() {
        return fileBase;
    }

    long binBytes() {
        return binBytes;
    }

    String binSha256() {
        return binSha256;
    }

    long paramBytes() {
        return paramBytes;
    }

    String paramSha256() {
        return paramSha256;
    }
}
