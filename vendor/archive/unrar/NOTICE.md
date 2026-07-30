# Notices and licensing

`robust-unrar` is an independent, read-only RAR inspection project. It is not
produced, sponsored, or endorsed by RARLAB, win.rar GmbH, the junrar project,
or their contributors. RAR and WinRAR are names associated with their
respective owners.

## Distribution classification

This repository contains a relocated and modified decoder derived from junrar
and, through it, the UnRAR lineage. The complete controlling license text is in
[`LICENSE`](LICENSE), with an unchanged upstream copy in
[`vendor/junrar/LICENSE`](vendor/junrar/LICENSE).

The UnRAR License permits use, modification, source distribution, and binary
distribution for handling RAR archives. It also imposes this restriction:

> The code may not be used to develop a RAR (WinRAR) compatible archiver.

Because that is a field-of-use restriction, this repository should be
described as **source available under the UnRAR License**, not as OSI-approved
Open Source. This summary is explanatory; the license text controls.

No MIT, BSD, Apache, GPL, or other project-wide license is claimed for the
combined work. Dependencies obtained by Maven remain under their respective
licenses and are not relicensed by this repository.

## Exact lineage

The decoder import is pinned to:

- repository: `https://github.com/junrar/junrar.git`
- tag: `v8.0.0`
- commit: `c78e224757c2dff1126fdf2539361f1e65d4af99`
- imported: 2026-07-29

[`PROVENANCE.toml`](PROVENANCE.toml) records the imported paths, relocation,
supporting-file hashes, source-set digest, and local modifications. The
preserved upstream README, changelog, license, and contributing guide are under
[`vendor/junrar`](vendor/junrar).

## Test data

The regression oracle and payload-stripped corpus were copied from the same
pinned junrar commit. Upstream's stripping process overwrote bytes the parser
did not read, removing archive payload content while retaining headers,
filenames, offsets, declared sizes, checksums, and file lengths needed to
reproduce parser behavior. See
[`vendor/junrar/CONTRIBUTING.md`](vendor/junrar/CONTRIBUTING.md) for the
upstream procedure.

Other focused fixtures were also inherited from that pinned source tree.
Future fixtures must document their origin and must either be generated for
this project or be material the contributor has the right to redistribute.
Credentials, personal information, and unrelated payloads must not be added.

Questions or credible rights concerns should be raised with the project
maintainers so affected material can be investigated and, where appropriate,
removed or replaced.
