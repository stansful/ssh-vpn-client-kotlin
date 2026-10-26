#!/usr/bin/env bash
# Builds the release files of the version in app/build.gradle.kts exactly as the release workflow does
# (.github/workflows/release.yml runs this script, and so does the CI's release-build job): the signed APKs
# (:app:assembleRelease: universal and one per ABI), the Xray engine per ABI (scripts/lib/package-xray-core-assets.sh)
# and SHA256SUMS over them. The list is release_assets in scripts/lib/version.sh:
#   app-universal-release-X.Y.Z.apk  app-<abi>-release-X.Y.Z.apk  libXray-X.Y.Z-<abi>.aar  SHA256SUMS
# Fails unless the build holds exactly these files and every APK is signed with one key. Prints the version, the
# signing key, the files with their sizes and the sums.
#
#   scripts/build-release.sh [--test] [--no-xray] [--out DIR]
#
#   --test     first scripts/lib/check-cancellation.sh and the unit tests (scripts/lib/test.sh)
#   --no-xray  APKs only, no libXray-*.aar (the same as SSH_VPN_SKIP_XRAY_RELEASE_ASSETS=1)
#   --out DIR  also copy the release files to DIR (they are always in build/app/outputs/apk/release)
#
# Env: SSH_VPN_RELEASE_STORE_FILE, SSH_VPN_RELEASE_STORE_PASSWORD, SSH_VPN_RELEASE_KEY_ALIAS,
#      SSH_VPN_RELEASE_KEY_PASSWORD  the release key: all four, or none for a local key in .local/signing/
#      SSH_VPN_XRAY_CORE_AAR         libXray.aar for the Xray files (default: build/xray-core/libXray/libXray.aar,
#                                    then app/libs/libXray.aar, else a Go build: scripts/lib/package-xray-core-assets.sh)
#      SSH_VPN_BUNDLE_XRAY_CORE=1    also bundle Xray into the APKs (-PbundleXrayCore=true)
# bash 3.2 (macOS) or 5 (Linux). Needs JDK 17+ (21+ for --test: Robolectric runs the unit tests on Android SDK 36)
# and the Android SDK (scripts/lib/env.sh); the Xray files also need
# ANDROID_HOME in the environment, python3 and unzip, and Go and the NDK when there is no libXray.aar yet.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib/env.sh"
# shellcheck source=scripts/lib/version.sh
source "$ROOT_DIR/scripts/lib/version.sh"

usage() {
  cat <<'EOF'
Собирает файлы релиза версии из app/build.gradle.kts так же, как GitHub Actions (release.yml запускает этот
скрипт): подписанные APK (universal и по одному на ABI), движок Xray по ABI (libXray-*.aar) и SHA256SUMS.

  scripts/build-release.sh [--test] [--no-xray] [--out DIR]

  --test     сначала проверки: scripts/lib/check-cancellation.sh и юнит-тесты (scripts/lib/test.sh, нужен JDK 21+)
  --no-xray  только APK, без libXray-*.aar (то же, что SSH_VPN_SKIP_XRAY_RELEASE_ASSETS=1)
  --out DIR  ещё и скопировать файлы релиза в DIR (они всегда лежат в build/app/outputs/apk/release)
  -h, --help эта справка

Подпись: ключ релиза из переменных SSH_VPN_RELEASE_STORE_FILE, SSH_VPN_RELEASE_STORE_PASSWORD,
SSH_VPN_RELEASE_KEY_ALIAS и SSH_VPN_RELEASE_KEY_PASSWORD (все четыре), а без них — локальный ключ в .local/signing/
(создаётся при первой сборке). Для Xray нужны ANDROID_HOME в окружении, python3 и unzip, а без готового
libXray.aar — ещё Go и Android NDK. Какой libXray.aar берётся: SSH_VPN_XRAY_CORE_AAR, иначе
build/xray-core/libXray/libXray.aar, иначе app/libs/libXray.aar.
EOF
}
say() { printf '%s\n' "$*"; }
die() {
  printf 'Ошибка: %s\n' "$*" >&2
  exit 1
}
usage_error() {
  printf 'Ошибка: %s\n\n' "$*" >&2
  usage >&2
  exit 2
}
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

run_tests='' no_xray='' out=''
[[ "${SSH_VPN_SKIP_XRAY_RELEASE_ASSETS:-0}" != "1" ]] || no_xray=1
while [ $# -gt 0 ]; do
  case $1 in
  -h | --help)
    usage
    exit 0
    ;;
  --test) run_tests=1 ;;
  --no-xray) no_xray=1 ;;
  --out)
    [ $# -ge 2 ] && [ -n "$2" ] || usage_error "--out: укажите каталог"
    out=$2
    shift
    ;;
  --out=?*) out=${1#--out=} ;;
  *) usage_error "неизвестный параметр $1" ;;
  esac
  shift
done

version="$(app_version_get "$ROOT_DIR")" ||
  die "в $APP_VERSION_FILE должна быть ровно одна строка val appVersionName = \"X.Y.Z\"."
code="$(version_code "$version")" ||
  die "в $APP_VERSION_FILE appVersionName = «$version», а не X.Y.Z (без ведущих нулей и «v», Y и Z до 999)."
assets="$(release_assets "$version" ${no_xray:+--no-xray} | tr '\n' ' ')"
built="${assets% SHA256SUMS }"

if [[ -n "$run_tests" ]]; then
  say "== Проверки: scripts/lib/check-cancellation.sh"
  "$ROOT_DIR/scripts/lib/check-cancellation.sh" || die "scripts/lib/check-cancellation.sh не прошёл."
  say "== Юнит-тесты: scripts/lib/test.sh"
  "$ROOT_DIR/scripts/lib/test.sh" || die "юнит-тесты не прошли."
fi

bundle_xray_core=false
if [[ "${SSH_VPN_BUNDLE_XRAY_CORE:-0}" == "1" ]]; then
  if [[ ! -f "$ROOT_DIR/app/libs/libXray.aar" ]]; then
    "$ROOT_DIR/scripts/lib/build-xray-core.sh"
  fi
  bundle_xray_core=true
fi
GRADLE="$("$ROOT_DIR/scripts/lib/resolve-gradle.sh")"

# --- signing: the release key from the environment, or a local key that only this machine has
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

missing_vars=''
missing_count=0
for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    missing_vars="$missing_vars $var_name"
    missing_count=$((missing_count + 1))
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

  die "не найден keytool: установите JDK 17+ или Android Studio (JBR)."
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

if ((missing_count == 0)); then
  signing="ключ релиза (SSH_VPN_RELEASE_*)"
elif ((missing_count == ${#required_vars[@]})); then
  if [[ ! -f "$LOCAL_SIGNING_ENV" ]]; then
    say "Переменные SSH_VPN_RELEASE_* не заданы: создаю локальный ключ подписи в .local/signing/."
    create_local_signing_env
  fi

  # shellcheck source=/dev/null
  source "$LOCAL_SIGNING_ENV"

  if local_signing_needs_rotation; then
    say "Локальная настройка подписи устарела: сохраняю её в .local/signing/backup-*/ и создаю новую."
    backup_local_signing
    create_local_signing_env
    # shellcheck source=/dev/null
    source "$LOCAL_SIGNING_ENV"
  fi

  if [[ ! -f "$SSH_VPN_RELEASE_STORE_FILE" ]]; then
    say "Создаю локальный ключ подписи: $SSH_VPN_RELEASE_STORE_FILE"
    create_local_keystore
  fi

  signing="локальный ключ $SSH_VPN_RELEASE_STORE_FILE (такие APK не обновят приложение из релизов GitHub)"
else
  die "заданы не все переменные подписи, нет:$missing_vars. Задайте все четыре SSH_VPN_RELEASE_* или ни одной (тогда будет локальный ключ)."
fi

# --- the build
release_output_dir="$ROOT_DIR/build/app/outputs/apk/release"
rm -rf "$release_output_dir"

say "== Сборка $version (versionCode $code): :app:assembleRelease, подпись: $signing"
if [[ "$bundle_xray_core" == "true" ]]; then
  "$GRADLE" -p "$ROOT_DIR" -PbundleXrayCore=true :app:assembleRelease
else
  "$GRADLE" -p "$ROOT_DIR" :app:assembleRelease
fi

if [[ -z "$no_xray" ]]; then
  say "== Xray: scripts/lib/package-xray-core-assets.sh"
  "$ROOT_DIR/scripts/lib/package-xray-core-assets.sh" "$release_output_dir"
fi

[[ -d "$release_output_dir" ]] || die "сборка прошла, но каталога build/app/outputs/apk/release нет."

# Exactly the release files stay: Gradle's output-metadata.json and the like go, an unexpected APK or AAR fails
# the build (a new ABI in app/build.gradle.kts needs RELEASE_ABIS in scripts/lib/version.sh).
missing='' unsigned='' unexpected=''
for f in $built; do
  [[ -s "$release_output_dir/$f" ]] || missing="$missing $f"
done
for path in "$release_output_dir"/* "$release_output_dir"/.[!.]*; do
  [[ -e "$path" ]] || continue
  name=${path##*/}
  case " $built " in *" $name "*) continue ;; esac
  case $name in
  *-unsigned*.apk) unsigned="$unsigned $name" ;;
  *.apk | *.aar) unexpected="$unexpected $name" ;;
  esac
  rm -rf "$path"
done
if [[ -n "$missing" && -n "$unsigned" ]]; then
  die "собрались только неподписанные APK:$unsigned. Проверьте настройку подписи."
fi
[[ -z "$missing" ]] || die "не собраны:$missing."
[[ -z "$unexpected" ]] ||
  die "лишние файлы в сборке:$unexpected. Если в app/build.gradle.kts поменялись ABI, поправьте RELEASE_ABIS в scripts/lib/version.sh."

# --- one signing key on every APK, when the SDK has apksigner
apksigner=''
best=''
for dir in "${ANDROID_HOME:-}"/build-tools/*/; do
  v=${dir%/}
  v=${v##*/}
  version_valid "$v" && [[ -x "$dir/apksigner" ]] || continue
  if [[ -z "$best" ]] || version_gt "$v" "$best"; then
    best=$v apksigner="$dir/apksigner"
  fi
done
signer=''
if [[ -n "$apksigner" ]]; then
  for f in $built; do
    case $f in *.apk) ;; *) continue ;; esac
    # stderr only on failure: newer JDKs warn about apksigner's native access on every run
    if ! digest="$("$apksigner" verify --print-certs "$release_output_dir/$f" 2>/dev/null |
      sed -nE 's/^.*certificate SHA-256 digest: ([0-9a-f]{64})$/\1/p' | sort -u)"; then
      "$apksigner" verify "$release_output_dir/$f" >&2 || true
      die "apksigner не принял подпись $f."
    fi
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || die "у $f не одна подпись, а «$digest»."
    [[ -z "$signer" || "$digest" == "$signer" ]] || die "$f подписан ключом $digest, а остальные APK — $signer."
    signer=$digest
  done
fi

# shellcheck disable=SC2086 # $built is a list of plain file names
(cd "$release_output_dir" && sha256 $built >SHA256SUMS.tmp && mv SHA256SUMS.tmp SHA256SUMS)
(cd "$release_output_dir" && sha256 -c SHA256SUMS >/dev/null) ||
  die "файлы в $release_output_dir не совпадают с SHA256SUMS."

dest=$release_output_dir
if [[ -n "$out" ]]; then
  mkdir -p "$out"
  out="$(cd "$out" && pwd)"
  if [[ "$(cd "$out" && pwd -P)" != "$(cd "$release_output_dir" && pwd -P)" ]]; then
    for f in $assets; do
      cp "$release_output_dir/$f" "$out/$f"
    done
    (cd "$out" && sha256 -c SHA256SUMS >/dev/null) || die "файлы в $out не совпадают с SHA256SUMS."
    dest=$out
  fi
fi

say
say "Релиз $version (versionCode $code): $dest"
for f in $assets; do
  printf '  %9s  %s\n' "$(wc -c <"$dest/$f" | tr -d ' ')" "$f"
done
say
say "Подпись: $signing"
if [[ -n "$signer" ]]; then
  say "  SHA-256 сертификата: $signer"
else
  say "  не проверена: нет apksigner в \$ANDROID_HOME/build-tools"
fi
[[ -z "$no_xray" ]] || say "Без Xray (--no-xray): в релизе GitHub должны быть и libXray-$version-*.aar."
say
say "SHA256SUMS:"
sed 's/^/  /' "$dest/SHA256SUMS"
other=''
for path in "$dest"/* "$dest"/.[!.]*; do
  [[ -e "$path" ]] || continue
  case " $assets " in *" ${path##*/} "*) continue ;; esac
  other="$other ${path##*/}"
done
[[ -z "$other" ]] || say "В $dest есть и другие файлы, в релиз они не входят:$other"
