# Release license bundle inputs

These files supply controlling license notices for runtime dependencies whose
Maven JARs do not carry their own license file.

The release packager combines them with:

- the Media Browser `LICENSE` and `THIRD-PARTY.md`;
- the inspection-dependency metadata selected by
  `vendor/archive/REDISTRIBUTION.allowlist`;
- every license, notice, copyright, source, and build-information file found
  inside the staged runtime JARs; and
- the `legal/` tree from the exact Java runtime placed in the application
  image.

The result is published beside each installer as
`media-browser-X.Y.Z-PLATFORM-licenses.zip` and is also included inside the
installed application.

The dependency notices below reproduce the text from the named upstream
release where a release tag exists:

| File | Component | Upstream release |
|---|---|---|
| `FFM-BINDINGS-LICENSE` | ffmpeg-ffm, turbojpeg-ffm, libraw-ffm Java bindings | corresponding Maven artifact |
| `GLFW-LICENSE.md` | GLFW | 3.4 |
| `JCODEC-LICENSE` | JCodec | 0.2.5 release lineage |
| `LWJGL-LICENSE.md` | LWJGL | 3.4.1 |
| `TWELVEMONKEYS-LICENSE.txt` | TwelveMonkeys ImageIO | 3.13.1 |

Native-component licenses and build/source coordinates come from their
classifier JARs rather than this static directory, so the bundle follows the
platform actually packaged.
