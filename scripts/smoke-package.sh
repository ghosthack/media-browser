#!/usr/bin/env bash
# Exercise the real native launcher from a jpackage app image. No media fixture
# is required: App's private --package-smoke route exercises the shared AWT
# raster bridge, initializes the default bundled backend and exits before
# starting JavaFX.
set -euo pipefail

if (( $# != 1 )); then
    echo "usage: $0 <jpackage-app-image>" >&2
    exit 2
fi
IMAGE="$1"
[[ -d "$IMAGE" ]] || {
    echo "error: app image does not exist: $IMAGE" >&2
    exit 2
}

case "$(uname -s)" in
    Darwin)
        LAUNCHER="$IMAGE/Contents/MacOS/Media Browser"
        APP_DIR="$IMAGE/Contents/app"
        RUNTIME_RELEASE="$IMAGE/Contents/runtime/Contents/Home/release"
        ;;
    Linux)
        LAUNCHER="$IMAGE/bin/Media Browser"
        APP_DIR="$IMAGE/lib/app"
        RUNTIME_RELEASE="$IMAGE/lib/runtime/release"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        LAUNCHER="$IMAGE/Media Browser.exe"
        APP_DIR="$IMAGE/app"
        RUNTIME_RELEASE="$IMAGE/runtime/release"
        ;;
    *)
        echo "error: unsupported host: $(uname -s)" >&2
        exit 2
        ;;
esac

[[ -x "$LAUNCHER" || -f "$LAUNCHER" ]] || {
    echo "error: packaged application launcher is missing: $LAUNCHER" >&2
    exit 1
}
for required in LICENSE THIRD-PARTY.md THIRD-PARTY-LICENSES/CONTENTS.txt; do
    [[ -f "$APP_DIR/$required" ]] || {
        echo "error: packaged notice is missing: $APP_DIR/$required" >&2
        exit 1
    }
done

[[ -f "$RUNTIME_RELEASE" ]] || {
    echo "error: packaged Java runtime metadata is missing: $RUNTIME_RELEASE" >&2
    exit 1
}
PACKAGED_MODULES="$(sed -n 's/^MODULES="\(.*\)"$/\1/p' "$RUNTIME_RELEASE" | tr ' ' '\n')"
for required in java.desktop jdk.incubator.vector java.xml jdk.unsupported jdk.zipfs; do
    grep -Fxq "$required" <<<"$PACKAGED_MODULES" || {
        echo "error: packaged Java runtime is missing module: $required" >&2
        exit 1
    }
done

"$LAUNCHER" --package-smoke
