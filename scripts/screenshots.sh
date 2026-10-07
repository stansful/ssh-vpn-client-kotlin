#!/usr/bin/env bash
# Renders Compose UI to PNG on the JVM (Robolectric native graphics + Roborazzi; no emulator).
# Output: app/build/screenshots/<Name>.png (density 2.0, overwritten on every run).
#
# Usage: scripts/screenshots.sh [TestClassOrPattern ...]
#   scripts/screenshots.sh ScreenshotSamplesTest        # one class
#   scripts/screenshots.sh HomeScreenshotTest.connected # one method
#   scripts/screenshots.sh                              # every test under ...sshvpnclient.screenshots
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/env.sh"
GRADLE="$("$ROOT_DIR/scripts/resolve-gradle.sh")"
OUT_DIR="$ROOT_DIR/app/build/screenshots"

filters=()
if (($# > 0)); then
  for pattern in "$@"; do
    filters+=(--tests "*$pattern*")
  done
else
  filters+=(--tests "com.stansful.sshvpnclient.screenshots.*")
fi

mkdir -p "$OUT_DIR"
marker="$(mktemp)"
trap 'rm -f "$marker"' EXIT

# --rerun: render even when the test task is UP-TO-DATE.
"$GRADLE" -p "$ROOT_DIR" :app:testDebugUnitTest "${filters[@]}" --rerun

echo "Screenshots written:"
find "$OUT_DIR" -name '*.png' -newer "$marker" | sort
