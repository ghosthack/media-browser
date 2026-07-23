# Media Browser

A pure-Java desktop media browser: JavaFX UI on Java, three views —
**browser** (directory tree + media list + info panel), **mosaic**
(black-background virtualized thumbnail grid), and **viewer** — hosted either
in a single window that swaps views in place or as classic separate windows.

## Build & run

Requires JDK 26 and Maven 3.9.x. No other installs — the default backend's
FFmpeg natives arrive as Maven artifacts.

```sh
mvn compile
mvn javafx:run                                  # opens at $HOME
mvn javafx:run -Djavafx.args="/some/dir"        # opens at /some/dir
```

## Decode backends

The decode backend is chosen at startup (Preferences ▸ Media decode backend);
the default is `ffmpeg-ffm-turbojpeg` (bundled FFmpeg for stills and video,
plus a TurboJPEG thumbnail fast path) where the ffmpeg-ffm/turbojpeg-ffm
natives cover the platform (macos-arm64, windows-x64), degrading to
`twelvemonkeys-javacv` elsewhere:

| Backend | Stills | Video | Native? |
|---|---|---|---|
| `ffmpeg-ffm-turbojpeg` *(default)* | FFmpeg + JPEG thumbnails via libjpeg-turbo | FFmpeg (all codecs) | yes, fetched by Maven (classifier jars) |
| `ffmpeg-ffm` | FFmpeg only (incl. HEIC/AVIF) | FFmpeg (all codecs) | yes, fetched by Maven (classifier jars) |
| `twelvemonkeys-ffmpeg-ffm` | TwelveMonkeys ImageIO (JPEG/CMYK, TIFF, WebP, PSD, …) | GIF + bundled FFmpeg (ffmpeg-ffm) | yes, fetched by Maven (classifier jars) |
| `apple` | Apple ImageIO | AVFoundation | macOS system frameworks |
| `windows-native` | WIC | Media Foundation | Windows system APIs |
| `javacv` | JavaCV (FFmpeg) | JavaCV (FFmpeg, all codecs) | yes (~28 MB/OS) |
| `twelvemonkeys` | TwelveMonkeys ImageIO | animated GIF only | no (pure Java) |
| `twelvemonkeys-jcodec` | TwelveMonkeys ImageIO | GIF + jcodec (H.264/MPEG/ProRes) | no (pure Java) |
| `twelvemonkeys-javacv` | TwelveMonkeys ImageIO | GIF + JavaCV (bundled FFmpeg, all codecs) | yes, fetched by Maven (~29 MB/OS) |

## About this repository

Licensed under the [MIT License](LICENSE). Third-party dependencies are
listed in [THIRD-PARTY.md](THIRD-PARTY.md).
