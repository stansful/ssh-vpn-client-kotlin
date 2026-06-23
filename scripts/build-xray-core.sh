#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/env.sh"

LIBXRAY_COMMIT="9bb7cad11a225f1039274dc8afd9810bcf458038"
XRAY_CORE_COMMIT="94ffd50060f1cfd5d7482ec90a23a92bdefdff68"
GOMOBILE_VERSION="v0.0.0-20260611195102-4dd8f1dbf5d2"
WORK_DIR="$ROOT_DIR/build/xray-core"
LIBXRAY_DIR="$WORK_DIR/libXray"
XRAY_CORE_DIR="$WORK_DIR/Xray-core"
OUTPUT_DIR="$ROOT_DIR/app/libs"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

checkout_pinned_repository() {
  local url="$1"
  local directory="$2"
  local commit="$3"

  if [[ ! -d "$directory/.git" ]]; then
    git clone --filter=blob:none --no-checkout "$url" "$directory"
  fi
  git -C "$directory" fetch --depth 1 origin "$commit"
  git -C "$directory" checkout --detach "$commit"

  local actual_commit
  actual_commit="$(git -C "$directory" rev-parse HEAD)"
  if [[ "$actual_commit" != "$commit" ]]; then
    echo "Pinned source verification failed for $directory" >&2
    exit 1
  fi
}

require_command git
require_command go
require_command python3

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is not configured." >&2
  exit 1
fi

if ! find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d -print -quit 2>/dev/null | grep -q .; then
  echo "Android NDK is required for gomobile." >&2
  echo "Install 'NDK (Side by side)' from Android Studio > SDK Manager > SDK Tools." >&2
  exit 1
fi

mkdir -p "$WORK_DIR" "$OUTPUT_DIR"
checkout_pinned_repository "https://github.com/XTLS/libXray.git" "$LIBXRAY_DIR" "$LIBXRAY_COMMIT"
checkout_pinned_repository "https://github.com/XTLS/Xray-core.git" "$XRAY_CORE_DIR" "$XRAY_CORE_COMMIT"

export GOTOOLCHAIN=auto
export PATH="$(go env GOPATH)/bin:$PATH"

(
  cd "$LIBXRAY_DIR"
  go mod edit -replace="github.com/xtls/xray-core=../Xray-core"
  go mod tidy
  go run ./download_geo/main.go
  go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
  go get -tool "golang.org/x/mobile/cmd/gobind@$GOMOBILE_VERSION"
  gomobile init
  rm -f libXray.aar libXray-sources.jar
  gomobile bind \
    -target android \
    -androidapi 26 \
    -ldflags="-checklinkname=0 -s -w -extldflags=-Wl,-z,max-page-size=16384"
)

cp "$LIBXRAY_DIR/libXray.aar" "$OUTPUT_DIR/libXray.aar"
if [[ -f "$LIBXRAY_DIR/libXray-sources.jar" ]]; then
  cp "$LIBXRAY_DIR/libXray-sources.jar" "$OUTPUT_DIR/libXray-sources.jar"
fi

echo "Pinned Xray Android binding: $OUTPUT_DIR/libXray.aar"
echo "libXray commit: $LIBXRAY_COMMIT"
echo "Xray-core commit: $XRAY_CORE_COMMIT"
