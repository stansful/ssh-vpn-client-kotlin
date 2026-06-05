#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -x "$ROOT_DIR/gradlew" ]]; then
  printf '%s\n' "$ROOT_DIR/gradlew"
  exit 0
fi

if command -v gradle >/dev/null 2>&1; then
  printf '%s\n' "gradle"
  exit 0
fi

cat >&2 <<'EOF'
Gradle was not found.

Install Gradle 8.x or run:
  ./scripts/create-gradle-wrapper.sh

That script also requires a local gradle command for the initial wrapper creation.
EOF
exit 1
