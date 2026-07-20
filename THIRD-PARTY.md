# Third-party dependencies

This repository distributes **source only**; Maven downloads the artifacts
below at build time. This list is a courtesy overview, not a license text —
each project's own license governs.

| Dependency | Version | License | Used for |
|---|---|---|---|
| [OpenJFX (JavaFX)](https://openjfx.io) | 24.0.2 | GPLv2 + Classpath Exception | UI toolkit |
| [LWJGL 3](https://www.lwjgl.org) (core, opengl, glfw) | 3.4.1 | BSD 3-Clause | offscreen GL video rendering |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (14 plugins) | 3.13.1 | BSD 3-Clause | still-image decoding (JPEG/CMYK, TIFF, WebP, PSD, …) |
| [JavaCV](https://github.com/bytedeco/javacv) | 1.5.13 | Apache-2.0 or GPLv2+CE (dual) | video decoding wrapper |
| [JavaCPP](https://github.com/bytedeco/javacpp) | 1.5.13 | Apache-2.0 or GPLv2+CE (dual) | native bridge for JavaCV |
| [Bytedeco FFmpeg presets](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg) | 8.0.1-1.5.13 | LGPL v3+ (as built: no GPL/nonfree components) | bundled FFmpeg natives for the JavaCV backends |
| [jcodec](https://github.com/jcodec/jcodec) (core, javase) | 0.2.5 | BSD 2-Clause (FreeBSD) | pure-Java H.264/MPEG/ProRes video backend |
| [JUnit Jupiter](https://junit.org) | 5.11.4 | EPL-2.0 | tests only |

The `apple` and `windows-native` backends use operating-system frameworks
(Apple ImageIO/AVFoundation; Windows WIC/Media Foundation) through Java's
Panama FFM API — no third-party code involved.
