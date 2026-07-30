# Notices and licensing

`robust-seven` is an independent, read-only 7z inspection project. It is not
produced, sponsored, or endorsed by the Apache Software Foundation, the XZ for
Java project, 7-Zip, or their contributors.

## Vendored implementation

This repository contains relocated and modified source derived from Apache
Commons Compress 1.28.0 and relocated source from XZ for Java 1.12. The
production artifact has no external runtime dependency; the imported packages
are encapsulated below `io.github.ghosthack.seven.internal` and are not
exported by the JPMS module.

Apache Commons Compress is copyright the Apache Software Foundation and is
licensed under Apache License 2.0. Its unchanged upstream license, notice, and
README are under [`vendor/commons-compress`](vendor/commons-compress).

XZ for Java is copyright its authors and contributors and is licensed under
the BSD Zero Clause License. Its unchanged upstream licensing and supporting
files are under [`vendor/xz-java`](vendor/xz-java).

[`PROVENANCE.toml`](PROVENANCE.toml) pins both upstream commits and records the
imported source sets, relocation, source digests, supporting-file hashes, and
local modifications. The Maven copies of Commons Compress and XZ are test-only
and are used to generate independent 7z fixtures.

The 7-Zip name and 7z format belong to their respective owners.
