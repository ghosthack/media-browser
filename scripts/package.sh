#!/usr/bin/env bash
# Build a self-contained Media Browser app image and native installer for the
# current OS/architecture. Run from any directory:
#
#   scripts/package.sh [all|app-image] [X.Y.Z]
#
# The release workflow supplies X.Y.Z from its vX.Y.Z tag. Local builds default
# to 1.0.0. Signing is optional and is enabled by the environment variables
# documented in packaging/README.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-all}"
VERSION="${2:-${APP_VERSION:-1.0.0}}"
APP_NAME="Media Browser"
APP_ID="io.github.ghosthack.mediabrowser"
MAIN_CLASS="io.github.ghosthack.mediabrowser.Launcher"
RUNTIME_MODULES="java.desktop,jdk.incubator.vector,java.xml,jdk.unsupported,jdk.zipfs"
INPUT_DIR="$ROOT/target/jpackage-input"
PACKAGE_ROOT="$ROOT/target/jpackage"
IMAGE_DIR="$PACKAGE_ROOT/images"
RELEASE_DIR="$PACKAGE_ROOT/release"

if [[ "$MODE" != "all" && "$MODE" != "app-image" ]]; then
    echo "error: mode must be 'all' or 'app-image'" >&2
    exit 2
fi
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: jpackage release version must be numeric X.Y.Z (got '$VERSION')" >&2
    exit 2
fi

case "$(uname -s)" in
    Darwin)
        PLATFORM="macos"
        ARCH="$(uname -m)"
        [[ "$ARCH" == "arm64" || "$ARCH" == "aarch64" ]] || {
            echo "error: this release currently supports macOS arm64 only" >&2
            exit 2
        }
        NATIVE_TYPE="dmg"
        IMAGE_PATH="$IMAGE_DIR/$APP_NAME.app"
        ASSET_PLATFORM="macos-arm64"
        ;;
    Linux)
        PLATFORM="linux"
        ARCH="$(uname -m)"
        [[ "$ARCH" == "x86_64" || "$ARCH" == "amd64" ]] || {
            echo "error: this release currently supports Linux x64 only" >&2
            exit 2
        }
        NATIVE_TYPE="deb"
        IMAGE_PATH="$IMAGE_DIR/$APP_NAME"
        ASSET_PLATFORM="linux-x64"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        PLATFORM="windows"
        ARCH="$(uname -m)"
        [[ "$ARCH" == "x86_64" || "$ARCH" == "amd64" ]] || {
            echo "error: this release currently supports Windows x64 only" >&2
            exit 2
        }
        NATIVE_TYPE="exe"
        IMAGE_PATH="$IMAGE_DIR/$APP_NAME"
        ASSET_PLATFORM="windows-x64"
        ;;
    *)
        echo "error: unsupported packaging host: $(uname -s)" >&2
        exit 2
        ;;
esac

rm -rf "$INPUT_DIR" "$PACKAGE_ROOT"
mkdir -p "$INPUT_DIR" "$IMAGE_DIR" "$RELEASE_DIR"

echo "==> Staging the $ASSET_PLATFORM runtime dependencies"
mvn -B -ntp -Pdist -DskipTests -Drevision="$VERSION" clean package

LICENSE_FILE="$ROOT/LICENSE"
NOTICES_FILE="$ROOT/THIRD-PARTY.md"
if [[ ! -f "$LICENSE_FILE" ]]; then
    LICENSE_FILE="$ROOT/oss/LICENSE"
    NOTICES_FILE="$ROOT/oss/THIRD-PARTY.md"
fi
[[ -f "$LICENSE_FILE" && -f "$NOTICES_FILE" ]] || {
    echo "error: LICENSE and THIRD-PARTY.md are required packaging inputs" >&2
    exit 1
}
cp "$LICENSE_FILE" "$INPUT_DIR/LICENSE"
cp "$NOTICES_FILE" "$INPUT_DIR/THIRD-PARTY.md"
bash "$ROOT/scripts/stage-release-licenses.sh" "$INPUT_DIR"

shopt -s nullglob
APP_JARS=("$INPUT_DIR/media-browser-$VERSION.jar")
if (( ${#APP_JARS[@]} != 1 )); then
    echo "error: expected one staged media-browser-$VERSION.jar" >&2
    exit 1
fi
MAIN_JAR="$(basename "${APP_JARS[0]}")"

case "$PLATFORM" in
    macos)   ICON="$ROOT/packaging/icons/media-browser.icns" ;;
    windows) ICON="$ROOT/packaging/icons/media-browser.ico" ;;
    linux)   ICON="$ROOT/packaging/icons/media-browser.png" ;;
esac

echo "==> Creating the self-contained application image"
IMAGE_ARGS=(
    --type app-image
    --dest "$IMAGE_DIR"
    --name "$APP_NAME"
    --app-version "$VERSION"
    --vendor "ghosthack contributors"
    --description "A JavaFX desktop media browser"
    --copyright "Copyright © 2026 ghosthack contributors"
    --input "$INPUT_DIR"
    --main-jar "$MAIN_JAR"
    --main-class "$MAIN_CLASS"
    --add-modules "$RUNTIME_MODULES"
    --java-options "--add-modules=$RUNTIME_MODULES"
    --java-options "-Djavafx.enablePreview=true"
    --java-options "-Djavafx.suppressPreviewWarning=true"
    --java-options "--enable-native-access=ALL-UNNAMED"
    --java-options "--sun-misc-unsafe-memory-access=allow"
)
if [[ -f "$ICON" ]]; then
    IMAGE_ARGS+=(--icon "$ICON")
fi
if [[ "$PLATFORM" == "macos" ]]; then
    IMAGE_ARGS+=(
        --mac-package-identifier "$APP_ID"
        --mac-package-name "$APP_NAME"
        --mac-app-category photography
    )
fi
jpackage "${IMAGE_ARGS[@]}"

if [[ "$PLATFORM" == "macos" && -n "${MACOS_SIGNING_IDENTITY:-}" ]]; then
    echo "==> Signing the macOS application image"
    SIGN_ARGS=(
        --type app-image
        --app-image "$IMAGE_PATH"
        --mac-sign
        --mac-app-image-sign-identity "$MACOS_SIGNING_IDENTITY"
    )
    if [[ -n "${MACOS_KEYCHAIN:-}" ]]; then
        SIGN_ARGS+=(--mac-signing-keychain "$MACOS_KEYCHAIN")
    fi
    jpackage "${SIGN_ARGS[@]}"
fi

if [[ "$PLATFORM" == "windows" && -n "${WINDOWS_PFX_FILE:-}" ]]; then
    echo "==> Signing the Windows application launcher"
    pwsh -NoProfile -File "$ROOT/scripts/sign-windows.ps1" \
        -Path "$IMAGE_PATH/$APP_NAME.exe"
fi

if [[ "${SKIP_PACKAGE_SMOKE:-false}" != "true" ]]; then
    echo "==> Smoke-testing the packaged runtime and native backend"
    bash "$ROOT/scripts/smoke-package.sh" "$IMAGE_PATH"
fi

if [[ "$MODE" == "app-image" ]]; then
    echo "application image: $IMAGE_PATH"
    exit 0
fi

echo "==> Creating the $NATIVE_TYPE installer"
INSTALLER_ARGS=(
    --type "$NATIVE_TYPE"
    --dest "$RELEASE_DIR"
    --name "$APP_NAME"
    --app-version "$VERSION"
    --vendor "ghosthack contributors"
    --description "A JavaFX desktop media browser"
    --copyright "Copyright © 2026 ghosthack contributors"
    --license-file "$LICENSE_FILE"
    --app-image "$IMAGE_PATH"
)
case "$PLATFORM" in
    macos)
        INSTALLER_ARGS+=(
            --mac-package-identifier "$APP_ID"
            --mac-package-name "$APP_NAME"
        )
        ;;
    windows)
        INSTALLER_ARGS+=(
            --win-dir-chooser
            --win-menu
            --win-menu-group "$APP_NAME"
            --win-shortcut
        )
        ;;
    linux)
        INSTALLER_ARGS+=(
            --linux-package-name media-browser
            --linux-deb-maintainer "ghosthack contributors"
            --linux-app-category graphics
            --linux-menu-group Graphics
            --linux-shortcut
        )
        ;;
esac
jpackage "${INSTALLER_ARGS[@]}"

INSTALLERS=("$RELEASE_DIR"/*."$NATIVE_TYPE")
if (( ${#INSTALLERS[@]} != 1 )); then
    echo "error: expected exactly one .$NATIVE_TYPE installer in $RELEASE_DIR" >&2
    exit 1
fi
INSTALLER="$RELEASE_DIR/media-browser-$VERSION-$ASSET_PLATFORM.$NATIVE_TYPE"
mv "${INSTALLERS[0]}" "$INSTALLER"

if [[ "$PLATFORM" == "windows" && -n "${WINDOWS_PFX_FILE:-}" ]]; then
    echo "==> Signing the Windows installer"
    pwsh -NoProfile -File "$ROOT/scripts/sign-windows.ps1" -Path "$INSTALLER"
fi

case "$PLATFORM" in
    windows)
        PORTABLE="$RELEASE_DIR/media-browser-$VERSION-$ASSET_PLATFORM.zip"
        (
            cd "$IMAGE_DIR"
            7z a -bd -y "$PORTABLE" "$APP_NAME" >/dev/null
        )
        ;;
    linux)
        PORTABLE="$RELEASE_DIR/media-browser-$VERSION-$ASSET_PLATFORM.tar.gz"
        tar -C "$IMAGE_DIR" -czf "$PORTABLE" "$APP_NAME"
        ;;
esac

case "$PLATFORM" in
    macos)   RUNTIME_LEGAL="$IMAGE_PATH/Contents/runtime/Contents/Home/legal" ;;
    windows) RUNTIME_LEGAL="$IMAGE_PATH/runtime/legal" ;;
    linux)   RUNTIME_LEGAL="$IMAGE_PATH/lib/runtime/legal" ;;
esac
[[ -d "$RUNTIME_LEGAL" ]] || {
    echo "error: packaged Java runtime legal directory is missing: $RUNTIME_LEGAL" >&2
    exit 1
}

LICENSE_ARTIFACT_NAME="media-browser-$VERSION-$ASSET_PLATFORM-licenses"
LICENSE_ARTIFACT_ROOT="$PACKAGE_ROOT/license-artifact"
LICENSE_ARTIFACT_DIR="$LICENSE_ARTIFACT_ROOT/$LICENSE_ARTIFACT_NAME"
rm -rf "$LICENSE_ARTIFACT_ROOT"
mkdir -p "$LICENSE_ARTIFACT_DIR"
cp -R "$INPUT_DIR/THIRD-PARTY-LICENSES/." "$LICENSE_ARTIFACT_DIR/"
mkdir -p "$LICENSE_ARTIFACT_DIR/runtime"
cp -RL "$RUNTIME_LEGAL/." "$LICENSE_ARTIFACT_DIR/runtime/"
(
    cd "$LICENSE_ARTIFACT_DIR"
    find . -type f -print | sed 's|^\./||' | LC_ALL=C sort > CONTENTS.txt
)
jar --create --no-manifest \
    --file "$RELEASE_DIR/$LICENSE_ARTIFACT_NAME.zip" \
    -C "$LICENSE_ARTIFACT_ROOT" "$LICENSE_ARTIFACT_NAME"

echo "release artifacts:"
find "$RELEASE_DIR" -maxdepth 1 -type f -print
