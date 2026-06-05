#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/env.sh"
GRADLE="$("$ROOT_DIR/scripts/resolve-gradle.sh")"

"$GRADLE" -p "$ROOT_DIR" :app:assembleDebug
