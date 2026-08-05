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

## Download

Self-contained installers are published on the
[GitHub Releases](https://github.com/ghosthack/media-browser/releases) page:

- macOS Apple silicon: DMG
- Windows x64: EXE installer or portable ZIP
- Linux x64 (glibc 2.38+, including Ubuntu 24.04): Debian package or
  portable tarball

The installers include their own Java 26 runtime and the correct native
libraries for that platform; users do not need to install Java.

Maintainers create a release by pushing an exact numeric tag:

```sh
git tag v1.0.0
git push origin v1.0.0
```

The public release workflow builds and smoke-tests all three self-contained
application images on native GitHub runners, optionally signs them, and
publishes the installers with SHA-256 checksums. See
[`packaging/README.md`](packaging/README.md) for local packaging and signing
configuration.

## Decode backends

The decode backend is chosen at startup (Preferences ▸ Media decode backend);
the default is `ffmpeg-ffm-turbojpeg-cm` everywhere — bundled FFmpeg for stills
and video, a libjpeg-turbo JPEG-thumbnail fast path, embedded-ICC conversion to
sRGB, and metadata enrichment. Natives are fetched from Maven Central for macOS
(Apple silicon), Windows x64, and Linux x64.
There is deliberately no silent fallback: if the default cannot initialize,
startup reports it visibly and switches the setting to the pure-Java
`twelvemonkeys-jcodec`; backends for another OS simply don't appear in the
menu.

| Backend | Stills | Video | Native? |
|---|---|---|---|
| `ffmpeg-ffm-turbojpeg-cm` *(default)* | FFmpeg (incl. HEIC/AVIF/JXL; camera RAW via LibRaw) + baseline-JPEG thumbnails via libjpeg-turbo, embedded-ICC → sRGB conversion, and metadata enrichment | FFmpeg (all codecs) | yes, fetched by Maven (classifier jars; color/metadata are pure Java) |
| `apple` | Apple ImageIO | AVFoundation | macOS system frameworks |
| `windows-native` | WIC | Media Foundation | Windows system APIs |
| `twelvemonkeys-jcodec` | TwelveMonkeys ImageIO | GIF + jcodec (H.264/MPEG/ProRes) | no (pure Java) |

The `twelvemonkeys-jcodec` pairing splits work by media *kind* (stills engine
vs. video engine) at classify time; it does not fall back on failure — a file
either decodes through its assigned engine or reports the error.

## About this repository

Media Browser's original code is licensed under the [MIT License](LICENSE).
Inspection dependencies retain their upstream licenses, including the non-OSI
UnRAR restriction; exact notices and provenance live under `vendor/archive`.
See [THIRD-PARTY.md](THIRD-PARTY.md) for the complete dependency overview.
