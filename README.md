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
the default is `ffmpeg-ffm-turbojpeg` everywhere — bundled FFmpeg for stills
and video plus a libjpeg-turbo JPEG-thumbnail fast path, with natives fetched
from Maven Central for macOS (Apple silicon), Windows x64, and Linux x64.
There is deliberately no silent fallback: if the default cannot initialize,
startup reports it visibly and switches the setting to the pure-Java
`twelvemonkeys-jcodec`; backends for another OS simply don't appear in the
menu.

| Backend | Stills | Video | Native? |
|---|---|---|---|
| `ffmpeg-ffm-turbojpeg` *(default)* | FFmpeg (incl. HEIC/AVIF/JXL; camera RAW via LibRaw) + baseline-JPEG thumbnails via libjpeg-turbo | FFmpeg (all codecs) | yes, fetched by Maven (classifier jars) |
| `ffmpeg-ffm` | FFmpeg (incl. HEIC/AVIF/JXL; camera RAW via LibRaw) | FFmpeg (all codecs) | yes, fetched by Maven (classifier jars) |
| `twelvemonkeys-ffmpeg-ffm` | TwelveMonkeys ImageIO (JPEG/CMYK, TIFF, WebP, PSD, …) | GIF + bundled FFmpeg (ffmpeg-ffm) | yes, fetched by Maven (classifier jars) |
| `apple` | Apple ImageIO | AVFoundation | macOS system frameworks |
| `windows-native` | WIC | Media Foundation | Windows system APIs |
| `twelvemonkeys` | TwelveMonkeys ImageIO | animated GIF only | no (pure Java) |
| `twelvemonkeys-jcodec` | TwelveMonkeys ImageIO | GIF + jcodec (H.264/MPEG/ProRes) | no (pure Java) |

The paired backends split work by media *kind* (stills engine vs. video
engine) at classify time; they do not fall back on failure — a file either
decodes through its assigned engine or reports the error.

## About this repository

Licensed under the [MIT License](LICENSE). Third-party dependencies are
listed in [THIRD-PARTY.md](THIRD-PARTY.md).
