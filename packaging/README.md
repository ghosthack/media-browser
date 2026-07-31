# Packaging

`scripts/package.sh` builds a platform-specific, self-contained application
with the JDK's `jpackage` tool. It supports:

- macOS Apple silicon: `.dmg`
- Windows x64: `.exe` plus a portable `.zip`
- Linux x64 with glibc 2.38+ (including Ubuntu 24.04): `.deb` plus a
  portable `.tar.gz`

Each full packaging run also emits a platform-specific
`media-browser-X.Y.Z-PLATFORM-licenses.zip`. It contains the application
license, dependency notices, source-vendored archive licensing/provenance,
legal and build-information files extracted from the runtime JARs, and the
`legal/` tree from the exact packaged Java runtime. The non-runtime portion is
also installed under `THIRD-PARTY-LICENSES`.

The script first builds an `app-image` and runs `scripts/smoke-package.sh`
against the Java runtime and dependency jars inside that image. Only after the
bundled FFmpeg backend initializes successfully does it create an installer.

```sh
scripts/package.sh app-image 1.0.0
scripts/package.sh installer 1.0.0
scripts/package.sh all 1.0.0
```

`installer` mode reuses the application image and staged inputs from a
preceding `app-image` run. Release CI uses this split on Windows so it can sign
the native launcher before `jpackage` embeds that launcher in the installer
and portable ZIP.

When testing a quarantined macOS app during development, clear the quarantine
attribute from the installed bundle:

```sh
sudo xattr -dr com.apple.quarantine "/Applications/Media Browser.app"
```

Release CI derives the numeric version from its `vX.Y.Z` tag. Maven uses the
same value through `-Drevision=X.Y.Z`, so the JAR manifest, staged filename,
application bundle, installer, and release assets agree.

## Application icons

The packaging script automatically uses the disco-ball application icon in
the native format for each platform:

- `packaging/icons/media-browser.icns` on macOS
- `packaging/icons/media-browser.ico` on Windows
- `packaging/icons/media-browser.png` on Linux

## Optional signing

Signing is off for local builds unless the relevant variables are set.
See [Release signing setup](SIGNING.md) for the complete GitHub environment,
certificate, notarization, and verification procedure.

macOS:

- `MACOS_SIGNING_IDENTITY`: the complete `Developer ID Application: ...`
  identity
- `MACOS_KEYCHAIN`: optional keychain containing that identity

Windows:

- `WINDOWS_PFX_FILE`: path to the Authenticode `.pfx`
- `WINDOWS_PFX_PASSWORD`: certificate password, if any
- `WINDOWS_TIMESTAMP_URL`: RFC 3161 timestamp service; defaults to DigiCert

Local PFX signing remains available through these variables. GitHub release CI
uses Azure Artifact Signing instead: it signs the app launcher before creating
the Windows installer and portable ZIP, then signs and verifies the installer.
The macOS release path continues to use the protected `release` environment
and notarizes/staples a signed DMG.

Configure GitHub environments named `release` and `release-signing`. macOS,
Linux, and publication use `release`; Windows packaging uses
`release-signing`. Either environment can require maintainer approval. Add the
macOS environment secrets below to `release` as applicable:

- `MACOS_CERTIFICATE_BASE64`
- `MACOS_CERTIFICATE_PASSWORD`
- `MACOS_SIGNING_IDENTITY`
- `MACOS_NOTARY_APPLE_ID`
- `MACOS_NOTARY_PASSWORD`
- `MACOS_NOTARY_TEAM_ID`

The macOS certificate value is a base64 encoding of the binary PKCS#12 file.
The Windows `release-signing` environment requires `AZURE_CLIENT_ID`,
`AZURE_TENANT_ID`, and `AZURE_SUBSCRIPTION_ID` secrets plus
`ARTIFACT_SIGNING_ENDPOINT`, `ARTIFACT_SIGNING_ACCOUNT`, and
`ARTIFACT_SIGNING_PROFILE` variables. See
[Release signing setup](SIGNING.md) for details.
