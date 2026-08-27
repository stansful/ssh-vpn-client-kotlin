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
LOCAL_SIGNING_DIR="$ROOT_DIR/.local/signing"
LOCAL_SIGNING_ENV="$LOCAL_SIGNING_DIR/release-signing.env"
LOCAL_KEYSTORE="$LOCAL_SIGNING_DIR/shadow-ssh-release.keystore"
LOCAL_KEY_ALIAS="shadow-ssh"

required_vars=(
  SSH_VPN_RELEASE_STORE_FILE
  SSH_VPN_RELEASE_STORE_PASSWORD
  SSH_VPN_RELEASE_KEY_ALIAS
  SSH_VPN_RELEASE_KEY_PASSWORD
)

missing_vars=()
for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    missing_vars+=("$var_name")
  fi
done

generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
    return
  fi

  uuidgen | tr -d '-'
}

keytool_command() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    printf '%s\n' "$JAVA_HOME/bin/keytool"
    return
  fi

  if command -v keytool >/dev/null 2>&1; then
    command -v keytool
    return
  fi

  echo "keytool was not found. Install JDK 17+ or Android Studio JBR." >&2
  exit 1
}

create_local_signing_env() {
  mkdir -p "$LOCAL_SIGNING_DIR"
  chmod 700 "$LOCAL_SIGNING_DIR"

  local signing_password
  signing_password="$(generate_secret)"

  cat >"$LOCAL_SIGNING_ENV" <<EOF
export SSH_VPN_RELEASE_LOCAL_SIGNING_VERSION='2'
export SSH_VPN_RELEASE_STORE_FILE='$LOCAL_KEYSTORE'
export SSH_VPN_RELEASE_STORE_PASSWORD='$signing_password'
export SSH_VPN_RELEASE_KEY_ALIAS='$LOCAL_KEY_ALIAS'
export SSH_VPN_RELEASE_KEY_PASSWORD='$signing_password'
EOF
  chmod 600 "$LOCAL_SIGNING_ENV"
}

backup_local_signing() {
  local backup_dir
  backup_dir="$LOCAL_SIGNING_DIR/backup-$(date +%Y%m%d%H%M%S)"
  mkdir -p "$backup_dir"
  chmod 700 "$backup_dir"

  if [[ -f "$LOCAL_SIGNING_ENV" ]]; then
    mv "$LOCAL_SIGNING_ENV" "$backup_dir/"
  fi

  if [[ -f "$LOCAL_KEYSTORE" ]]; then
    mv "$LOCAL_KEYSTORE" "$backup_dir/"
  fi
}

local_signing_needs_rotation() {
  [[ "${SSH_VPN_RELEASE_LOCAL_SIGNING_VERSION:-}" != "2" ]] ||
    [[ "${SSH_VPN_RELEASE_STORE_PASSWORD:-}" != "${SSH_VPN_RELEASE_KEY_PASSWORD:-}" ]]
}

create_local_keystore() {
  local keytool
  keytool="$(keytool_command)"

  "$keytool" \
    -genkeypair \
    -storetype PKCS12 \
    -keystore "$SSH_VPN_RELEASE_STORE_FILE" \
    -storepass "$SSH_VPN_RELEASE_STORE_PASSWORD" \
    -keypass "$SSH_VPN_RELEASE_KEY_PASSWORD" \
    -alias "$SSH_VPN_RELEASE_KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=shadow-ssh local release, OU=Local, O=shadow-ssh, L=Local, ST=Local, C=US" \
    >/dev/null

  chmod 600 "$SSH_VPN_RELEASE_STORE_FILE"
}

if ((${#missing_vars[@]} == 0)); then
  echo "Release signing credentials found. Building production-signed release APK."
elif ((${#missing_vars[@]} == ${#required_vars[@]})); then
  if [[ ! -f "$LOCAL_SIGNING_ENV" ]]; then
    echo "Release signing credentials were not provided. Creating local install signing config."
    create_local_signing_env
  fi

  # shellcheck source=/dev/null
  source "$LOCAL_SIGNING_ENV"

  if local_signing_needs_rotation; then
    echo "Existing local signing config is not compatible. Rotating it."
    backup_local_signing
    create_local_signing_env
    # shellcheck source=/dev/null
    source "$LOCAL_SIGNING_ENV"
  fi

  if [[ ! -f "$SSH_VPN_RELEASE_STORE_FILE" ]]; then
    echo "Creating local release keystore: $SSH_VPN_RELEASE_STORE_FILE"
    create_local_keystore
  fi

  echo "Building locally signed release APK."
else
  echo "Release signing credentials are incomplete." >&2
  echo "Missing: ${missing_vars[*]}" >&2
  echo "Provide all release signing variables or unset them all to use local install signing." >&2
  exit 1
fi

release_output_dir="$ROOT_DIR/build/app/outputs/apk/release"
rm -rf "$release_output_dir"

if [[ "$bundle_xray_core" == "true" ]]; then
  "$GRADLE" -p "$ROOT_DIR" -PbundleXrayCore=true :app:assembleRelease
else
  "$GRADLE" -p "$ROOT_DIR" :app:assembleRelease
fi

if [[ "${SSH_VPN_SKIP_XRAY_RELEASE_ASSETS:-0}" != "1" ]]; then
  "$ROOT_DIR/scripts/package-xray-core-assets.sh" "$release_output_dir"
fi

release_apks=()
while IFS= read -r apk_path; do
  release_apks+=("$apk_path")
done < <(find "$release_output_dir" -maxdepth 1 -type f -name '*.apk' | sort)

signed_apks=()
unsigned_apks=()
for apk_path in "${release_apks[@]}"; do
  if [[ "$apk_path" == *-unsigned*.apk ]]; then
    unsigned_apks+=("$apk_path")
  else
    signed_apks+=("$apk_path")
  fi
done

if ((${#signed_apks[@]} > 0)); then
  echo "Release APKs:"
  printf '  %s\n' "${signed_apks[@]}"
elif ((${#unsigned_apks[@]} > 0)); then
  echo "Release build produced unsigned APKs only:" >&2
  printf '  %s\n' "${unsigned_apks[@]}"
  echo "Expected signed APKs. Check release signing configuration." >&2
  exit 1
else
  echo "Release build completed, but APK output was not found in build/app/outputs/apk/release." >&2
  exit 1
fi
