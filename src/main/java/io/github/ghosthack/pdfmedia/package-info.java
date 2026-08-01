/**
 * Read-only PDF embedded-media inspection.
 *
 * <p>The package exposes archive-like mechanics: open one PDF, inspect its immutable media
 * entry index, open attachment bytes or raw raster bitstreams with decoder metadata, and inspect
 * recognized Mixed Raster Content layer graphs. It does not render PDF pages, write host files,
 * decode raster codecs, or impose a filesystem/path policy on consumers.</p>
 */
package io.github.ghosthack.pdfmedia;
