#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/env.sh"
ANDROID_STUDIO_JAVA="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"

printf 'Project: %s\n' "$ROOT_DIR"

if command -v java >/dev/null 2>&1; then
  if ! java -version; then
    if [[ -x "$ANDROID_STUDIO_JAVA" ]]; then
      "$ANDROID_STUDIO_JAVA" -version
      printf 'Using Android Studio bundled JDK.\n'
    else
      printf 'Java command exists, but no usable JDK was found. Install JDK 17+.\n' >&2
      exit 1
    fi
  fi
elif [[ -x "$ANDROID_STUDIO_JAVA" ]]; then
  "$ANDROID_STUDIO_JAVA" -version
  printf 'Using Android Studio bundled JDK.\n'
else
  printf 'Java was not found. Install JDK 17+.\n' >&2
  exit 1
fi

if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
  printf 'ANDROID_HOME: %s\n' "$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
  printf 'ANDROID_SDK_ROOT: %s\n' "$ANDROID_SDK_ROOT"
elif [[ -f "$ROOT_DIR/local.properties" ]]; then
  printf 'local.properties found.\n'
else
  cat >&2 <<'EOF'
Android SDK was not found.

Set ANDROID_HOME or create local.properties:
  sdk.dir=/path/to/Android/sdk
EOF
  exit 1
fi

"$ROOT_DIR/scripts/resolve-gradle.sh" >/dev/null
printf 'Environment looks ready.\n'
