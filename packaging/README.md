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
scripts/package.sh all 1.0.0
```

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

The GitHub release workflow prepares these variables from protected secrets,
signs the app launcher before creating the Windows installer, signs the
installer afterward, and notarizes/staples a signed macOS DMG.

Configure a GitHub environment named `release`. The workflow runs all package
and publication jobs through that environment, so it can require maintainer
approval. Add these environment secrets as applicable:

- `MACOS_CERTIFICATE_BASE64`
- `MACOS_CERTIFICATE_PASSWORD`
- `MACOS_SIGNING_IDENTITY`
- `MACOS_NOTARY_APPLE_ID`
- `MACOS_NOTARY_PASSWORD`
- `MACOS_NOTARY_TEAM_ID`
- `WINDOWS_CERTIFICATE_BASE64`
- `WINDOWS_CERTIFICATE_PASSWORD`
- `WINDOWS_TIMESTAMP_URL` (optional)

The certificate values are base64 encodings of the binary PKCS#12/PFX files.
If no signing secrets are configured, manual workflow runs still produce
unsigned packages. The project currently publishes unsigned releases, so
macOS Gatekeeper and Windows SmartScreen may require users to approve the app
manually. Signing can be enabled later without changing the artifact layout.
