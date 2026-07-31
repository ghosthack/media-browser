# Release signing setup

The GitHub release workflow uses protected GitHub environments named `release`
and `release-signing`. Windows releases are signed through Azure Artifact
Signing. macOS signing and notarization remain optional.

Never commit the `.p12` file, its base64 representation, passwords, or Azure
configuration values. Enter secret text through protected local files or
GitHub CLI prompts rather than placing it in shell history.

## Configure the GitHub environment

Open the repository's
[environment settings](https://github.com/ghosthack/media-browser/settings/environments)
and select or create both `release` and `release-signing`.

Recommended protection:

- Add a required reviewer.
- Leave **Prevent self-review** disabled if there is only one maintainer.
- Restrict deployments to the `main` branch and tags matching `v*`.

The manual dry run uses `main`; actual releases run from `vX.Y.Z` tags.
Environment approval prevents jobs from reading environment secrets until a
reviewer approves the deployment.

GitHub documentation:
[Deployments and environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
and
[Using secrets in GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets?tool=cli).

Before using the commands below, authenticate the GitHub CLI and confirm the
target repository:

```sh
gh auth status
gh repo view ghosthack/media-browser
```

## Apple signing and notarization

An active Apple Developer Program membership is required. Create a
**Developer ID Application** certificate, not a Developer ID Installer
certificate. The application certificate signs the `.app` contained in the
DMG.

Apple documentation:

- [Create Developer ID certificates](https://developer.apple.com/help/account/certificates/create-developer-id-certificates/)
- [Export certificates and keys from Keychain Access](https://support.apple.com/guide/keychain-access/import-and-export-keychain-items-kyca35961/mac)
- [Generate an app-specific password](https://support.apple.com/en-us/102654)
- [Find the Apple Developer Team ID](https://developer.apple.com/help/glossary/team-id/)

### Export the certificate

1. Create and download a Developer ID Application certificate.
2. Install it in Keychain Access.
3. Open **My Certificates** and select the certificate with its private key.
4. Choose **File > Export Items**.
5. Export it as a password-protected `.p12` file.

Find the complete signing identity:

```sh
security find-identity -v -p codesigning
```

It will resemble:

```text
Developer ID Application: Your Name (ABCDEFGHIJ)
```

The entire identity, without the surrounding output quotes, is the
`MACOS_SIGNING_IDENTITY` value.

### Upload the Apple certificate

Pipe the encoded certificate directly into the GitHub CLI so its base64 value
is never printed:

```sh
MAC_P12=/absolute/path/to/DeveloperIDApplication.p12

test -r "$MAC_P12" &&
  openssl base64 -A -in "$MAC_P12" |
  gh secret set MACOS_CERTIFICATE_BASE64 \
    --env release \
    --repo ghosthack/media-browser

unset MAC_P12
```

Run each command separately and enter the requested value at the interactive
prompt:

```sh
gh secret set MACOS_CERTIFICATE_PASSWORD --env release --repo ghosthack/media-browser
gh secret set MACOS_SIGNING_IDENTITY --env release --repo ghosthack/media-browser
gh secret set MACOS_NOTARY_APPLE_ID --env release --repo ghosthack/media-browser
gh secret set MACOS_NOTARY_PASSWORD --env release --repo ghosthack/media-browser
gh secret set MACOS_NOTARY_TEAM_ID --env release --repo ghosthack/media-browser
```

The values are:

| Secret | Value |
| --- | --- |
| `MACOS_CERTIFICATE_PASSWORD` | Password chosen when exporting the `.p12` |
| `MACOS_SIGNING_IDENTITY` | Complete `Developer ID Application: ...` identity |
| `MACOS_NOTARY_APPLE_ID` | Apple Account email belonging to the developer team |
| `MACOS_NOTARY_PASSWORD` | Apple app-specific password, not the normal account password |
| `MACOS_NOTARY_TEAM_ID` | Ten-character Apple Developer Team ID |

Create the notarization password at
[account.apple.com](https://account.apple.com/) under
**Sign-In and Security > App-Specific Passwords**. The Team ID appears under
the Apple Developer account's **Membership details**.

## Windows signing with Azure Artifact Signing

Windows release CI authenticates to Azure through GitHub OIDC, so it does not
store a client secret, certificate private key, PFX, or PFX password in GitHub.
The Azure Artifact Signing account uses a Public Trust certificate profile.

The `release-signing` environment contains these secrets:

- `AZURE_CLIENT_ID`
- `AZURE_TENANT_ID`
- `AZURE_SUBSCRIPTION_ID`

It also contains these non-secret environment variables:

- `ARTIFACT_SIGNING_ENDPOINT`
- `ARTIFACT_SIGNING_ACCOUNT`
- `ARTIFACT_SIGNING_PROFILE`

The Microsoft Entra app registration has a federated credential whose subject
matches the `release-signing` GitHub environment. The same app has the
**Artifact Signing Certificate Profile Signer** role on the Artifact Signing
account.

The workflow:

1. Builds and smoke-tests a self-contained Windows application image.
2. Authenticates with `azure/login` using GitHub OIDC.
3. Signs the native `Media Browser.exe` launcher with a SHA-256 signature and
   RFC 3161 timestamp.
4. Builds the installer and portable ZIP from that signed application image.
5. Signs the finished installer with the same profile and timestamp service.
6. Fails unless both Authenticode signatures are valid and timestamped.

The ZIP itself is not Authenticode-signed, but it contains the signed launcher.
The installer is Authenticode-signed directly. Azure Artifact Signing keeps
the certificate private key in the managed service.

Local PFX-based signing remains supported by `scripts/package.sh` and
`scripts/sign-windows.ps1` when `WINDOWS_PFX_FILE` is set. GitHub release CI
does not use or require that path.

## Verify the configuration

GitHub displays secret names and update times, but never their values:

```sh
gh secret list \
  --env release-signing \
  --repo ghosthack/media-browser

gh variable list \
  --env release-signing \
  --repo ghosthack/media-browser
```

Run a signed packaging test without creating a tag or GitHub Release:

```sh
gh workflow run release.yml \
  --repo ghosthack/media-browser \
  --ref main \
  -f version=1.0.0 \
  -f publish=false
```

Approve the environment deployments when prompted. In the workflow log,
confirm that:

- macOS does not report that its certificate is missing.
- macOS notarization completes and stapler validation succeeds.
- Azure OIDC login succeeds.
- Both Windows signing operations succeed.
- Windows signature and timestamp verification succeeds.
- All three platform artifacts upload successfully.

Only after the dry run succeeds should a `vX.Y.Z` tag be pushed for a public
release.
