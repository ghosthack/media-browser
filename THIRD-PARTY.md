# Third-party dependencies

Dependencies below either arrive from Maven or are source-vendored with the
application. GitHub source archives contain source only; the self-contained
installers on the Releases page also convey runtime dependency JARs and the
single matching platform's native libraries. This list is a courtesy overview,
not a license text — each project's own license governs.

| Dependency | Version | License | Used for |
|---|---|---|---|
| [Azul Zulu OpenJDK](https://www.azul.com/downloads/) | 26 | OpenJDK licenses and notices included under the packaged runtime's `legal/` directory | self-contained Java runtime |
| [OpenJFX (JavaFX)](https://openjfx.io) | 26 | GPLv2 + Classpath Exception | UI toolkit |
| [LWJGL 3](https://www.lwjgl.org) (core, opengl, glfw) | 3.4.1 | BSD 3-Clause | offscreen GL video rendering |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (14 plugins) | 3.13.1 | BSD 3-Clause | still-image decoding (JPEG/CMYK, TIFF, WebP, PSD, …) |
| [jcodec](https://github.com/jcodec/jcodec) (core, javase) | 0.2.5 | BSD 2-Clause (FreeBSD) | pure-Java H.264/MPEG/ProRes video backend |
| [media-inspection `iso9660`](https://github.com/ghosthack/media-inspection) | 0.2.0 | MIT | bounded read-only ISO 9660, Joliet, and Rock Ridge inspection |
| Source-vendored robust-unrar / junrar reader lineage | snapshot in `vendor/archive/unrar` | UnRAR License (source-available, not OSI-approved; cannot be used to recreate a RAR archiver) | read-only RAR/CBR browsing |
| Source-vendored robust-seven reader slice | snapshot in `vendor/archive/seven` | Apache-2.0 (Commons Compress portions) and 0BSD (XZ for Java portions) | read-only 7z/CB7 browsing |
| Source-vendored `pdf-media` mechanics | snapshot in `vendor/archive/pdf` | Apache-2.0 | read-only PDF attachment and raster-bitstream inspection |
| [Apache PDFBox](https://pdfbox.apache.org/) | 3.0.8 | Apache-2.0 | PDF parsing used by the vendored `pdf-media` mechanics |
| [SLF4J](https://www.slf4j.org/) API + no-op provider | 2.0.17 | MIT | logging facade required by the vendored RAR decoder |
| [ffmpeg-ffm](https://github.com/ghosthack/ffmpeg-ffm) | 8.1.2-0.3.1 | MIT bindings; natives convey FFmpeg 8.1.2, LGPL v2.1+ (no GPL/version3/nonfree components) | bundled FFmpeg — stills + video for the default backend |
| [turbojpeg-ffm](https://github.com/ghosthack/turbojpeg-ffm) | 3.2.0-0.2.0 | MIT bindings; natives convey libjpeg-turbo 3.2.0 (BSD 3-Clause, IJG, zlib) | baseline-JPEG thumbnail fast path |
| [libraw-ffm](https://github.com/ghosthack/libraw-ffm) | 0.22.2-0.2.0 | MIT bindings; natives convey LibRaw 0.22.2 (LGPL 2.1 or CDDL 1.0) | camera-RAW decoding |

The three `*-ffm` artifacts ship natives as per-platform classifier jars
(macos-arm64, windows-x64, linux-x64); each artifact's own repository
documents exactly what its native bundle contains.

The vendored mechanics source, exact lineage, retained-file manifests, notices,
and license texts are preserved under `vendor/archive` and are also embedded
under `META-INF/licenses/archive` in the application JAR. The CUE/BIN adapter
and archive filesystem integration are first-party Media Browser code covered
by this repository's MIT license. PDFBox, FontBox, PDFBox IO, and Commons
Logging legal files are extracted from their runtime JARs into each release's
license bundle.

Each platform release includes a matching `-licenses.zip` artifact containing
the controlling dependency texts and notices, the exact archive-reader
allowlist, native-library build/source records extracted from the shipped
classifier JARs, and the complete `legal/` tree from the packaged Java runtime.
The same application and dependency material is installed in the application
under `THIRD-PARTY-LICENSES`.

The pom also declares test-scope dependencies that are not part of the built
application: JUnit Jupiter 5.11.4 (EPL-2.0) and the Bytedeco
JavaCV/JavaCPP/FFmpeg-preset stack 1.5.13 (Apache-2.0 or GPLv2+CE dual),
used only by decoder-comparison tests, which this source distribution does
not include.

The `apple` and `windows-native` backends use operating-system frameworks
(Apple ImageIO/AVFoundation; Windows WIC/Media Foundation) through Java's
Panama FFM API — no third-party code involved.
