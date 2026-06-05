#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v gradle >/dev/null 2>&1; then
  cat >&2 <<'EOF'
The local gradle command is required to create the Gradle Wrapper.
Install Gradle 8.x first, then rerun this script.
EOF
  exit 1
fi

gradle -p "$ROOT_DIR" wrapper --gradle-version 8.7 --distribution-type bin
