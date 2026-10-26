#!/usr/bin/env bash
set -euo pipefail

ANDROID_STUDIO_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
DEFAULT_ANDROID_HOME="/Users/stansful/Library/Android/sdk"

if [[ -z "${JAVA_HOME:-}" && -x "$ANDROID_STUDIO_JAVA_HOME/bin/java" ]]; then
  export JAVA_HOME="$ANDROID_STUDIO_JAVA_HOME"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ -z "${ANDROID_HOME:-}" && -d "$DEFAULT_ANDROID_HOME" ]]; then
  export ANDROID_HOME="$DEFAULT_ANDROID_HOME"
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" ]]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
