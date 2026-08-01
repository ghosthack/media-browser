package io.github.ghosthack.pdfmedia;

/**
 * A PDF affine transform in {@code [a b c d e f]} order.
 *
 * <p>The transform maps an image's unit square into page user space.</p>
 */
public record PdfTransform(double a, double b, double c, double d, double e, double f) {}
