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

python3 - "$SOURCE_AAR" "$OUTPUT_DIR" "$APP_VERSION" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

source_aar = pathlib.Path(sys.argv[1])
output_dir = pathlib.Path(sys.argv[2])
version = sys.argv[3]
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

        digest = hashlib.sha256(output_path.read_bytes()).hexdigest()
        sha_path = output_dir / f"{output_path.name}.sha256"
        sha_path.write_text(f"{digest}  {output_path.name}\n", encoding="utf-8")
        print(output_path)
        print(sha_path)
PY
