# Packaging

`scripts/package.sh` builds a platform-specific, self-contained application
with the JDK's `jpackage` tool. It supports:

- macOS Apple silicon: `.dmg`
- Windows x64: `.exe` plus a portable `.zip`
- Linux x64: `.deb` plus a portable `.tar.gz`

The script first builds an `app-image` and runs `scripts/smoke-package.sh`
against the Java runtime and dependency jars inside that image. Only after the
bundled FFmpeg backend initializes successfully does it create an installer.

```sh
scripts/package.sh app-image 1.0.0
scripts/package.sh all 1.0.0
```

Release CI derives the numeric version from its `vX.Y.Z` tag. Maven uses the
same value through `-Drevision=X.Y.Z`, so the JAR manifest, staged filename,
application bundle, installer, and release assets agree.

## Optional icons

If present, the packaging script automatically uses:

- `packaging/icons/media-browser.icns` on macOS
- `packaging/icons/media-browser.ico` on Windows
- `packaging/icons/media-browser.png` on Linux

Until those files are added, `jpackage` uses its default application icon.

## Optional signing

Signing is off for local builds unless the relevant variables are set.

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
unsigned packages for pipeline testing. A public release should be signed and
the macOS DMG notarized.
