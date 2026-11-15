package io.github.ghosthack.mediabrowser.web;

import io.github.ghosthack.mediabrowser.media.RasterFrame;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * JPEG through the JDK's own ImageIO writer (native libjpeg inside the JDK).
 * The turbojpeg-ffm binding this app depends on exposes decompression only
 * ({@code tj3Decompress8} and friends, no {@code tj3Compress8}), so the
 * TurboJPEG encoder is not reachable without a new binding; see
 * docs/web-mode.md for the measured cost of this path.
 *
 * <p>The frame is repacked once into a {@code TYPE_3BYTE_BGR} image — the
 * layout the JDK writer encodes without a colour conversion pass — with
 * straight alpha composited over black. The JDK's writer is picked explicitly
 * over TwelveMonkeys' wrapper (also registered here), which only delegates to
 * it. Writers are not thread-safe, so each call takes its own.</p>
 */
public final class JpegEncoder implements WebEncoder {

    @Override
    public String mediaType() {
        return "image/jpeg";
    }

    @Override
    public String id() {
        return "jpeg1";
    }

    @Override
    public byte[] encode(RasterFrame frame, int quality) {
        BufferedImage image = toBgr(frame);
        ImageWriter writer = jdkWriter();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(1, Math.min(100, quality)) / 100f);
            var bytes = new ByteArrayOutputStream(frame.width() * frame.height() / 4 + 1024);
            try (var out = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("JPEG encode failed: " + e.getMessage(), e);
        } finally {
            writer.dispose();
        }
    }

    /** BGRA (straight alpha) → packed BGR over black. */
    static BufferedImage toBgr(RasterFrame frame) {
        int w = frame.width(), h = frame.height();
        var image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] dst = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        byte[] src = frame.bgra();
        for (int s = 0, d = 0, n = w * h; n > 0; n--, s += 4, d += 3) {
            int a = src[s + 3] & 0xff;
            if (a == 255) {
                dst[d] = src[s];
                dst[d + 1] = src[s + 1];
                dst[d + 2] = src[s + 2];
            } else {
                dst[d] = (byte) (((src[s] & 0xff) * a + 127) / 255);
                dst[d + 1] = (byte) (((src[s + 1] & 0xff) * a + 127) / 255);
                dst[d + 2] = (byte) (((src[s + 2] & 0xff) * a + 127) / 255);
            }
        }
        return image;
    }

    private static ImageWriter jdkWriter() {
        ImageWriter fallback = null;
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg"); it.hasNext(); ) {
            ImageWriter w = it.next();
            if (w.getClass().getName().startsWith("com.sun.imageio.")) {
                if (fallback != null) fallback.dispose();
                return w;
            }
            if (fallback == null) fallback = w;
            else w.dispose();
        }
        if (fallback == null) throw new IllegalStateException("no JPEG ImageIO writer");
        return fallback;
    }
}
