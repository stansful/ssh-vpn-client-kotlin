#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/env.sh"

OUTPUT_DIR="${1:-$ROOT_DIR/build/app/outputs/apk/release}"
SOURCE_AAR="${SSH_VPN_XRAY_CORE_AAR:-}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_command python3
require_command unzip

if [[ -z "$SOURCE_AAR" ]]; then
  if [[ -f "$ROOT_DIR/build/xray-core/libXray/libXray.aar" ]]; then
    SOURCE_AAR="$ROOT_DIR/build/xray-core/libXray/libXray.aar"
  elif [[ -f "$ROOT_DIR/app/libs/libXray.aar" ]]; then
    SOURCE_AAR="$ROOT_DIR/app/libs/libXray.aar"
  else
    "$ROOT_DIR/scripts/build-xray-core.sh"
    SOURCE_AAR="$ROOT_DIR/build/xray-core/libXray/libXray.aar"
    if [[ ! -f "$SOURCE_AAR" ]]; then
      SOURCE_AAR="$ROOT_DIR/app/libs/libXray.aar"
    fi
  fi
fi

if [[ ! -f "$SOURCE_AAR" ]]; then
  echo "Xray core AAR was not found: $SOURCE_AAR" >&2
  exit 1
fi

APP_VERSION="$(
  python3 - "$ROOT_DIR/app/build.gradle.kts" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
match = re.search(r'val\s+appVersionName\s*=\s*"([^"]+)"', text)
if not match:
    raise SystemExit("Unable to read appVersionName from app/build.gradle.kts")
print(match.group(1))
PY
)"

mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT_DIR"/libXray-*.aar
rm -f "$OUTPUT_DIR"/libXray-*.sha256

find_d8() {
  if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME/build-tools" ]]; then
    return 1
  fi
  find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name d8 | sort | tail -n 1
}

D8_BIN="$(find_d8 || true)"
if [[ -z "$D8_BIN" ]]; then
  echo "Android D8 was not found under ANDROID_HOME/build-tools" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xray-core-assets.XXXXXX")"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

unzip -p "$SOURCE_AAR" classes.jar > "$TMP_DIR/classes.jar"
mkdir -p "$TMP_DIR/dex"
"$D8_BIN" --min-api 26 --output "$TMP_DIR/dex" "$TMP_DIR/classes.jar"
if [[ ! -s "$TMP_DIR/dex/classes.dex" ]]; then
  echo "D8 did not produce classes.dex for Xray runtime assets" >&2
  exit 1
fi

python3 - "$SOURCE_AAR" "$TMP_DIR/dex/classes.dex" "$OUTPUT_DIR" "$APP_VERSION" <<'PY'
import pathlib
import sys
import zipfile

source_aar = pathlib.Path(sys.argv[1])
dex_file = pathlib.Path(sys.argv[2])
output_dir = pathlib.Path(sys.argv[3])
version = sys.argv[4]
abis = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
base_entries = {"AndroidManifest.xml", "proguard.txt", "classes.jar", "R.txt", "res/"}

def clone_info(info):
    cloned = zipfile.ZipInfo(info.filename, info.date_time)
    cloned.comment = info.comment
    cloned.extra = info.extra
    cloned.internal_attr = info.internal_attr
    cloned.external_attr = info.external_attr
    cloned.create_system = info.create_system
    cloned.compress_type = zipfile.ZIP_DEFLATED
    return cloned

def generated_info(name):
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    return info

with zipfile.ZipFile(source_aar, "r") as source:
    names = set(source.namelist())
    required_base = {"classes.jar", "AndroidManifest.xml"}
    missing_base = sorted(required_base - names)
    if missing_base:
        raise SystemExit(f"Source AAR is missing required entries: {', '.join(missing_base)}")

    for abi in abis:
        native_name = f"jni/{abi}/libgojni.so"
        if native_name not in names:
            raise SystemExit(f"Source AAR is missing {native_name}")

        output_path = output_dir / f"libXray-{version}-{abi}.aar"
        with zipfile.ZipFile(output_path, "w") as target:
            for info in source.infolist():
                if info.filename.startswith("jni/") and info.filename != native_name:
                    continue
                if not info.filename.startswith("jni/") and info.filename not in base_entries:
                    continue
                data = b"" if info.is_dir() else source.read(info.filename)
                target.writestr(clone_info(info), data)
            target.writestr(generated_info("classes.dex"), dex_file.read_bytes())

        print(output_path)
PY
