package io.github.ghosthack.mediabrowser.media.archive.stream;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;

/**
 * An {@link InflaterInputStream} that fails with an {@link IOException} once
 * it has produced more than {@code limit} bytes, so a deflate bomb in an
 * untrusted container cannot be expanded onto disk or into memory without
 * bound. The caller's cleanup (e.g. the archive cache's {@code .part}
 * removal) runs on that exception as on any other read failure.
 */
final class BoundedInflaterInputStream extends InflaterInputStream {

    private final long limit;
    private final String what;
    private long produced;

    BoundedInflaterInputStream(InputStream in, long limit, String what) {
        super(in);
        if (limit < 0) throw new IllegalArgumentException("limit < 0: " + limit);
        this.limit = limit;
        this.what = what;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            produced += n;
            if (produced > limit) {
                throw new IOException(what + " inflates past " + limit
                        + " bytes; refusing a likely decompression bomb");
            }
        }
        return n;
    }

    // InflaterInputStream.read() and skip() both funnel through read(byte[],int,int).
}
