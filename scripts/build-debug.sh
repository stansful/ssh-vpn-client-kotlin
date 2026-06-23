#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/env.sh"
bundle_xray_core=false
if [[ "${SSH_VPN_BUNDLE_XRAY_CORE:-0}" == "1" ]]; then
  if [[ ! -f "$ROOT_DIR/app/libs/libXray.aar" ]]; then
    "$ROOT_DIR/scripts/build-xray-core.sh"
  fi
  bundle_xray_core=true
fi
GRADLE="$("$ROOT_DIR/scripts/resolve-gradle.sh")"

if [[ "$bundle_xray_core" == "true" ]]; then
  "$GRADLE" -p "$ROOT_DIR" -PbundleXrayCore=true :app:assembleDebug
else
  "$GRADLE" -p "$ROOT_DIR" :app:assembleDebug
fi
