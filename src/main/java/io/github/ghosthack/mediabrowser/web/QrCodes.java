package io.github.ghosthack.mediabrowser.web;

import io.nayuki.qrcodegen.QrCode;

/**
 * QR codes for a phone camera to read off the screen, as a module grid the UI
 * paints itself (black on white whatever the theme: cameras need the contrast
 * and the light border). Same encoder and settings as micro-harness-manager's
 * phone link: Nayuki qrcodegen, medium error correction.
 */
public final class QrCodes {

    /** Quiet zone around the code, in modules: the standard's minimum. */
    public static final int BORDER = 4;

    private QrCodes() {}

    /**
     * {@code text} as a square grid of dark ({@code true}) modules, the quiet
     * zone included, indexed {@code [y][x]}.
     */
    public static boolean[][] modules(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int size = qr.size + BORDER * 2;
        boolean[][] grid = new boolean[size][size];
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                grid[y + BORDER][x + BORDER] = qr.getModule(x, y);
            }
        }
        return grid;
    }
}
