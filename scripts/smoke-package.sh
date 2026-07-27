#!/usr/bin/env bash
# Exercise the real native launcher from a jpackage app image. No media fixture
# is required: App's private --package-smoke route initializes the default
# bundled backend and exits before starting JavaFX.
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
        ;;
    Linux)
        LAUNCHER="$IMAGE/bin/Media Browser"
        APP_DIR="$IMAGE/lib/app"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        LAUNCHER="$IMAGE/Media Browser.exe"
        APP_DIR="$IMAGE/app"
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
for required in LICENSE THIRD-PARTY.md; do
    [[ -f "$APP_DIR/$required" ]] || {
        echo "error: packaged notice is missing: $APP_DIR/$required" >&2
        exit 1
    }
done

"$LAUNCHER" --package-smoke
