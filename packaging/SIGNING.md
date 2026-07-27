# Release signing setup

The GitHub release workflow uses a protected GitHub environment named
`release`. It can produce unsigned packages when no credentials are present,
and that is the project's current release policy. Users may need to approve
the app manually through macOS Gatekeeper or Windows SmartScreen.

Signing remains optional. If it is enabled later, Windows packages should be
signed and the macOS DMG should be signed, notarized, and stapled.

Never commit the `.p12` or `.pfx` files, their base64 representations, or their
passwords. Enter secret text through the GitHub CLI prompts rather than placing
it in shell history.

## Configure the GitHub environment

Open the repository's
[environment settings](https://github.com/ghosthack/media-browser/settings/environments)
and select or create `release`.

Recommended protection:

- Add a required reviewer.
- Leave **Prevent self-review** disabled if there is only one maintainer.
- Restrict deployments to the `main` branch and tags matching `v*`.

The manual unsigned or signed dry run uses `main`; actual releases run from
`vX.Y.Z` tags. Environment approval prevents jobs from reading environment
secrets until a reviewer approves the deployment.

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

## Windows signing

The current workflow expects an Authenticode `.pfx` containing both the
certificate and its exportable private key. If the certificate is stored only
on a hardware token or in a cloud signing service, it cannot be used through
the current PFX-based workflow.

Upload an existing PFX without printing its base64 value:

```sh
WIN_PFX=/absolute/path/to/codesigning.pfx

test -r "$WIN_PFX" &&
  openssl base64 -A -in "$WIN_PFX" |
  gh secret set WINDOWS_CERTIFICATE_BASE64 \
    --env release \
    --repo ghosthack/media-browser

unset WIN_PFX
```

Enter the PFX password interactively:

```sh
gh secret set WINDOWS_CERTIFICATE_PASSWORD \
  --env release \
  --repo ghosthack/media-browser
```

`WINDOWS_TIMESTAMP_URL` is optional. The packaging script defaults to
`http://timestamp.digicert.com`. To override it:

```sh
gh secret set WINDOWS_TIMESTAMP_URL \
  --env release \
  --repo ghosthack/media-browser
```

### Managed Windows signing alternative

For a new signing setup, consider
[Microsoft Azure Artifact Signing](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart)
with a Public Trust certificate profile. It keeps the long-lived signing
private key out of GitHub and supports public Win32 application signing.

The current release workflow does not yet implement Artifact Signing. It must
be refactored before using that option; until then, Windows signing requires an
exportable Authenticode PFX.

## Verify the configuration

GitHub displays secret names and update times, but never their values:

```sh
gh secret list \
  --env release \
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

Approve the `release` environment deployment when prompted. In the workflow
log, confirm that:

- macOS does not report that its certificate is missing.
- macOS notarization completes and stapler validation succeeds.
- Windows does not report that its certificate is missing.
- All three platform artifacts upload successfully.

Only after the dry run succeeds should a `vX.Y.Z` tag be pushed for a public
release.
