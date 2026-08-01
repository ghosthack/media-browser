# Notices and licensing

`pdf-media-extraction` is an independent, read-only PDF media inspection
project. It is not produced, sponsored, or endorsed by the Apache Software
Foundation or the Apache PDFBox project.

## Vendored implementation

The project contains modified and package-relocated source from Apache PDFBox
3.0.8 and PDFBox IO 3.0.8. These components are licensed under Apache License
2.0. Their upstream license, notice, and dependency files are retained under
`vendor/` and embedded in the binary and Javadoc artifacts. FontBox 3.0.8 and
Apache Commons Logging 1.4.0 are inspected as upstream staging inputs. No
FontBox source, class, or resource is retained. No upstream Commons Logging
source or class is retained; its internal API contract is implemented by a
small local `System.Logger` adapter. Their upstream legal files remain under
`vendor/`, and the Commons Logging files remain embedded for attribution.

Package names, imports, reflective class names, and resource paths were
relocated below `io.github.ghosthack.pdfmedia.internal`. Sources outside the
module's parsing and media-demuxing closure—including fonts, page rendering,
forms, PDF writing, and integrations requiring Bouncy Castle, Log4j, SLF4J,
Avalon, LogKit, or servlet APIs—are removed by version-specific prune
manifests. The Bouncy Castle-backed public-key security handler is not
registered; password-based standard PDF encryption remains supported.

The PDFBox name and Apache feather marks are trademarks of the Apache Software
Foundation.
