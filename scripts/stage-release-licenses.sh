#!/usr/bin/env bash
# Assemble the license material that travels inside the application image.
# Usage: stage-release-licenses.sh <jpackage-input-directory>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ ! -f "$ROOT/vendor/archive/REDISTRIBUTION.allowlist" &&
      -f "$ROOT/../vendor/archive/REDISTRIBUTION.allowlist" ]]; then
    ROOT="$(cd "$ROOT/.." && pwd)"
fi
if (( $# != 1 )); then
    echo "usage: $0 <jpackage-input-directory>" >&2
    exit 2
fi

INPUT_DIR_ARG="$1"
[[ -d "$INPUT_DIR_ARG" ]] || {
    echo "error: jpackage input directory does not exist: $INPUT_DIR_ARG" >&2
    exit 2
}
INPUT_DIR="$(cd "$INPUT_DIR_ARG" && pwd)"

LICENSE_FILE="$ROOT/LICENSE"
NOTICES_FILE="$ROOT/THIRD-PARTY.md"
STATIC_LICENSES="$ROOT/licenses"
ARCHIVE_ROOT="$ROOT/vendor/archive"
ARCHIVE_ALLOWLIST="$ARCHIVE_ROOT/REDISTRIBUTION.allowlist"
if [[ ! -f "$LICENSE_FILE" ]]; then
    LICENSE_FILE="$ROOT/oss/LICENSE"
    NOTICES_FILE="$ROOT/oss/THIRD-PARTY.md"
    STATIC_LICENSES="$ROOT/oss/licenses"
fi
for required in \
    "$LICENSE_FILE" \
    "$NOTICES_FILE" \
    "$STATIC_LICENSES/README.md" \
    "$ARCHIVE_ALLOWLIST"; do
    [[ -f "$required" ]] || {
        echo "error: required license-bundle input is missing: $required" >&2
        exit 1
    }
done

BUNDLE="$INPUT_DIR/THIRD-PARTY-LICENSES"
rm -rf "$BUNDLE"
mkdir -p \
    "$BUNDLE/dependencies/declared" \
    "$BUNDLE/dependencies/embedded" \
    "$BUNDLE/vendor/archive"
cp "$LICENSE_FILE" "$BUNDLE/APPLICATION-LICENSE"
cp "$NOTICES_FILE" "$BUNDLE/THIRD-PARTY.md"
cp -R "$STATIC_LICENSES/." "$BUNDLE/dependencies/declared/"

if ! cmp -s <(LC_ALL=C sort -u "$ARCHIVE_ALLOWLIST") "$ARCHIVE_ALLOWLIST"; then
    echo "error: archive redistribution allowlist must be sorted and unique" >&2
    exit 1
fi
while IFS= read -r rel || [[ -n "$rel" ]]; do
    [[ -n "$rel" ]] || continue
    if [[ "$rel" == /* || "$rel" == .. || "$rel" == ../* ||
          "$rel" == */.. || "$rel" == */../* || "$rel" == *\\* ]]; then
        echo "error: unsafe archive redistribution path: $rel" >&2
        exit 1
    fi
    source_file="$ARCHIVE_ROOT/$rel"
    [[ -f "$source_file" ]] || {
        echo "error: allowlisted archive metadata is missing: $rel" >&2
        exit 1
    }
    mkdir -p "$BUNDLE/vendor/archive/$(dirname "$rel")"
    cp "$source_file" "$BUNDLE/vendor/archive/$rel"
done < "$ARCHIVE_ALLOWLIST"

shopt -s nullglob
RUNTIME_JARS=("$INPUT_DIR"/*.jar)
(( ${#RUNTIME_JARS[@]} > 0 )) || {
    echo "error: no runtime JARs found in $INPUT_DIR" >&2
    exit 1
}

for dependency_jar in "${RUNTIME_JARS[@]}"; do
    jar_name="$(basename "$dependency_jar")"
    legal_entries=()
    while IFS= read -r entry; do
        [[ -n "$entry" ]] && legal_entries+=("$entry")
    done < <(
        jar tf "$dependency_jar" |
            grep -E -i '(^|/)(LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT|PATENTS|BUILD-INFO|SOURCE-OFFER)(\.[^/]*)?$' |
            LC_ALL=C sort -u || true
    )
    (( ${#legal_entries[@]} > 0 )) || continue

    for entry in "${legal_entries[@]}"; do
        if [[ "$entry" == /* || "$entry" == .. || "$entry" == ../* ||
              "$entry" == */.. || "$entry" == */../* || "$entry" == *\\* ]]; then
            echo "error: unsafe legal-metadata path in $jar_name: $entry" >&2
            exit 1
        fi
    done

    extraction_dir="$BUNDLE/dependencies/embedded/$jar_name"
    mkdir -p "$extraction_dir"
    (
        cd "$extraction_dir"
        jar xf "$dependency_jar" "${legal_entries[@]}"
    )
done

for required in \
    "$BUNDLE/dependencies/declared/FFM-BINDINGS-LICENSE" \
    "$BUNDLE/dependencies/declared/GLFW-LICENSE.md" \
    "$BUNDLE/dependencies/declared/JCODEC-LICENSE" \
    "$BUNDLE/dependencies/declared/LWJGL-LICENSE.md" \
    "$BUNDLE/dependencies/declared/TWELVEMONKEYS-LICENSE.txt"; do
    [[ -s "$required" ]] || {
        echo "error: declared dependency license is missing: $required" >&2
        exit 1
    }
done

for pattern in \
    'ffmpeg-ffm-natives-*/natives/*/COPYING.LGPLv2.1' \
    'libraw-ffm-natives-*/libraw-natives/*/LICENSE.LGPL' \
    'turbojpeg-ffm-natives-*/turbojpeg-natives/*/LICENSE.md' \
    'slf4j-api-*/META-INF/LICENSE.txt'; do
    matches=("$BUNDLE/dependencies/embedded"/$pattern)
    (( ${#matches[@]} > 0 )) || {
        echo "error: required embedded dependency license was not staged: $pattern" >&2
        exit 1
    }
done

(
    cd "$BUNDLE"
    find . -type f -print | sed 's|^\./||' | LC_ALL=C sort > CONTENTS.txt
)
