# Third-party dependencies

Maven downloads the artifacts below at build time. GitHub source archives
contain source only; the self-contained installers on the Releases page also
convey the runtime dependency JARs and the single matching platform's native
libraries. This list is a courtesy overview, not a license text — each
project's own license governs.

| Dependency | Version | License | Used for |
|---|---|---|---|
| [Azul Zulu OpenJDK](https://www.azul.com/downloads/) | 26 | OpenJDK licenses and notices included under the packaged runtime's `legal/` directory | self-contained Java runtime |
| [OpenJFX (JavaFX)](https://openjfx.io) | 26 | GPLv2 + Classpath Exception | UI toolkit |
| [LWJGL 3](https://www.lwjgl.org) (core, opengl, glfw) | 3.4.1 | BSD 3-Clause | offscreen GL video rendering |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (14 plugins) | 3.13.1 | BSD 3-Clause | still-image decoding (JPEG/CMYK, TIFF, WebP, PSD, …) |
| [jcodec](https://github.com/jcodec/jcodec) (core, javase) | 0.2.5 | BSD 2-Clause (FreeBSD) | pure-Java H.264/MPEG/ProRes video backend |
| [ffmpeg-ffm](https://github.com/ghosthack/ffmpeg-ffm) | 8.1.2-0.3.1 | MIT bindings; natives convey FFmpeg 8.1.2, LGPL v2.1+ (no GPL/version3/nonfree components) | bundled FFmpeg — stills + video for the default backend |
| [turbojpeg-ffm](https://github.com/ghosthack/turbojpeg-ffm) | 3.2.0-0.2.0 | MIT bindings; natives convey libjpeg-turbo 3.2.0 (BSD 3-Clause, IJG, zlib) | baseline-JPEG thumbnail fast path |
| [libraw-ffm](https://github.com/ghosthack/libraw-ffm) | 0.22.2-0.2.0 | MIT bindings; natives convey LibRaw 0.22.2 (LGPL 2.1 or CDDL 1.0) | camera-RAW decoding |

The three `*-ffm` artifacts ship natives as per-platform classifier jars
(macos-arm64, windows-x64, linux-x64); each artifact's own repository
documents exactly what its native bundle contains.

The pom also declares test-scope dependencies that are not part of the built
application: JUnit Jupiter 5.11.4 (EPL-2.0) and the Bytedeco
JavaCV/JavaCPP/FFmpeg-preset stack 1.5.13 (Apache-2.0 or GPLv2+CE dual),
used only by decoder-comparison tests, which this source distribution does
not include.

The `apple` and `windows-native` backends use operating-system frameworks
(Apple ImageIO/AVFoundation; Windows WIC/Media Foundation) through Java's
Panama FFM API — no third-party code involved.
