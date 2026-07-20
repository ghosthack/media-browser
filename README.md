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
the default is `twelvemonkeys-javacv`, which behaves identically on macOS, 
Windows, and Linux:

| Backend | Stills | Video | Native? |
|---|---|---|---|
| `apple` | Apple ImageIO | AVFoundation | macOS system frameworks |
| `windows-native` | WIC | Media Foundation | Windows system APIs |
| `javacv` | JavaCV (FFmpeg) | JavaCV (FFmpeg, all codecs) | yes (~28 MB/OS) |
| `twelvemonkeys` | TwelveMonkeys ImageIO | animated GIF only | no (pure Java) |
| `twelvemonkeys-jcodec` | TwelveMonkeys ImageIO | GIF + jcodec (H.264/MPEG/ProRes) | no (pure Java) |
| `twelvemonkeys-javacv` *(default)* | TwelveMonkeys ImageIO (JPEG/CMYK, TIFF, WebP, PSD, …) | GIF + JavaCV (bundled FFmpeg, all codecs) | yes, fetched by Maven (~29 MB/OS) |

## About this repository

Licensed under the [MIT License](LICENSE). Third-party dependencies are
listed in [THIRD-PARTY.md](THIRD-PARTY.md).
