#!/usr/bin/env bash
# Tests of scripts/build-release.sh against a fake tree in a temp dir (TMPDIR), never this repository and never real
# Gradle: the fake root has the script, scripts/lib/{env,version,resolve-gradle}.sh and a small
# app/build.gradle.kts, plus stubs: gradlew (resolve-gradle.sh picks it; it writes fake APKs, as STUB_APKS says),
# scripts/lib/package-xray-core-assets.sh (fake libXray-*.aar, as STUB_XRAY says), check-cancellation.sh and test.sh
# (for --test), an Android SDK whose build-tools/37.0.0/apksigner reads the signer from the fake APK, and a JDK
# whose keytool writes a fake keystore. Runs the script under $TEST_BASH (default: bash from PATH).
#
#   scripts/tests/build-release_test.sh          KEEP=1 keeps the temp dir
#
# Prints 'ok <name>' / 'not ok <name>: <why>' and exits non-zero on a failure.
# The conditions of check() are eval'd when it runs: single quotes on purpose, and the variables they read are
# set outside them.
# shellcheck disable=SC2016,SC2034
set -u
# SIGPIPE ignored, as on the GitHub runner (every step inherits that from its Node.js), for these tests and the
# scripts they run: a reader that stops early makes its writer fail with "Broken pipe" there instead of dying quietly
trap "" PIPE

HERE="$(cd "$(dirname "$0")" && pwd -P)"
TREE="$(cd "$HERE/../.." && pwd -P)"
BASH_BIN="${TEST_BASH:-bash}"
# no temp dir stops here: an empty WORK would make the current directory (the checkout) the work dir, and the
# EXIT trap would delete it
if ! WORK="$(mktemp -d "${TMPDIR:-/tmp}/build-release-test.XXXXXX")" || ! WORK="$(cd "$WORK" && pwd -P)"; then
  echo "not ok no temp dir in ${TMPDIR:-/tmp}"
  exit 1
fi
if [ -z "${KEEP:-}" ]; then trap 'rm -rf "$WORK"' EXIT; else echo "# work dir: $WORK"; fi

K1=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
K2=fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210
V=1.2.3
CODE=1002003

# The environment of every run; a test changes a variable for one run with `VAR=value br ...`
export HOME="$WORK/home" STUB_LOG="$WORK/stub.log" STUB_KEY="$K1" STUB_KEY2="$K2"
export JAVA_HOME="$WORK/jdk" ANDROID_HOME="$WORK/sdk" ANDROID_SDK_ROOT="$WORK/sdk"
export SSH_VPN_RELEASE_STORE_FILE="$WORK/release.keystore" SSH_VPN_RELEASE_STORE_PASSWORD=store-secret
export SSH_VPN_RELEASE_KEY_ALIAS=release SSH_VPN_RELEASE_KEY_PASSWORD=key-secret
unset SSH_VPN_SKIP_XRAY_RELEASE_ASSETS SSH_VPN_BUNDLE_XRAY_CORE SSH_VPN_XRAY_CORE_AAR SSH_VPN_RELEASE_LOCAL_SIGNING_VERSION
unset STUB_APKS STUB_XRAY STUB_GRADLE_FAIL STUB_CHECK_FAIL STUB_TEST_FAIL
mkdir -p "$HOME"

pass=0 fail=0
ok() {
  echo "ok $1"
  pass=$((pass + 1))
}
nok() {
  echo "not ok $1: $2"
  fail=$((fail + 1))
}
check() {
  if eval "$2"; then ok "$1"; else nok "$1" "rc=$RC; condition: $2; output:
$OUT
stubs:
$(cat "$STUB_LOG" 2>/dev/null)"; fi
}
has() { case $OUT in *"$1"*) return 0 ;; esac; return 1; }
logged() { grep -qxF -- "$1" "$STUB_LOG" 2>/dev/null; }
ran() { grep -q "^$1" "$STUB_LOG" 2>/dev/null; }
# shellcheck disable=SC2012 # ls -l for the mode, of plain names
mode() { ls -ld "$1" | cut -c1-10; }
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}
# sums_ok <dir> <file...>: SHA256SUMS in dir lists exactly these files, in this order, with their right sums
sums_ok() {
  local dir="$1" f want=''
  shift
  for f; do want="$want$(cd "$dir" && sha256 "$f")
"; done
  [ "$(cat "$dir/SHA256SUMS")" = "${want%
}" ] && (cd "$dir" && sha256 -c SHA256SUMS >/dev/null 2>&1)
}
# listing <dir>: the names in dir, hidden ones too, sorted, one line
# shellcheck disable=SC2012 # the release files have plain names
listing() { (cd "$1" && ls -A | LC_ALL=C sort | tr '\n' ' '); }
sorted() { printf '%s\n' "$@" | LC_ALL=C sort | tr '\n' ' '; }

APKS="app-universal-release-$V.apk app-arm64-v8a-release-$V.apk app-armeabi-v7a-release-$V.apk app-x86-release-$V.apk app-x86_64-release-$V.apk"
AARS="libXray-$V-arm64-v8a.aar libXray-$V-armeabi-v7a.aar libXray-$V-x86.aar libXray-$V-x86_64.aar"

echo "# scripts/build-release.sh under $("$BASH_BIN" -c 'echo "bash $BASH_VERSION"')"

# --- the stubs
STUBS="$WORK/stubs"
mkdir -p "$STUBS" "$JAVA_HOME/bin" "$ANDROID_HOME/build-tools/37.0.0"
cat >"$STUBS/gradlew" <<'EOF'
#!/bin/sh
# Gradle stub: logs its arguments and the signing key it was given, then writes the release APKs of the version in
# app/build.gradle.kts into build/app/outputs/apk/release, each with a "key=<sha-256>" line that the apksigner stub
# reads, plus the files Gradle leaves there. STUB_APKS: signed (default), unsigned, missing (no x86), extra
# (riscv64 too), twokeys (x86_64 signed with STUB_KEY2), nosig (x86 unsigned), twosigners (x86 with two keys).
root=$(cd "$(dirname "$0")" && pwd)
echo "gradlew $*" >>"$STUB_LOG"
echo "gradlew signing ${SSH_VPN_RELEASE_STORE_FILE:-} ${SSH_VPN_RELEASE_KEY_ALIAS:-}" >>"$STUB_LOG"
[ -z "${STUB_GRADLE_FAIL:-}" ] || { echo "BUILD FAILED" >&2; exit 1; }
v=$(sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts")
out="$root/build/app/outputs/apk/release"
mkdir -p "$out/baselineProfiles/0"
s=${STUB_APKS:-signed}
for abi in universal arm64-v8a armeabi-v7a x86 x86_64; do
  name="app-$abi-release-$v.apk" keys="key=$STUB_KEY"
  case $s/$abi in
  unsigned/*) name="app-$abi-release-unsigned-$v.apk" keys= ;;
  missing/x86) continue ;;
  twokeys/x86_64) keys="key=$STUB_KEY2" ;;
  nosig/x86) keys= ;;
  twosigners/x86) keys="key=$STUB_KEY
key=$STUB_KEY2" ;;
  esac
  printf 'apk %s\n%s\n' "$abi" "$keys" >"$out/$name"
done
[ "$s" != extra ] || printf 'apk riscv64\nkey=%s\n' "$STUB_KEY" >"$out/app-riscv64-release-$v.apk"
echo '{"version": 3}' >"$out/output-metadata.json"
echo dm >"$out/baselineProfiles/0/app-universal-release.dm"
echo hidden >"$out/.hidden"
EOF
cat >"$STUBS/package-xray-core-assets.sh" <<'EOF'
#!/bin/sh
# package-xray-core-assets.sh stub: libXray-<version>-<abi>.aar into $1. STUB_XRAY: missing (no x86), extra
# (riscv64 too), fail (exits 1 after writing them all)
root=$(cd "$(dirname "$0")/../.." && pwd)
echo "xray $*" >>"$STUB_LOG"
v=$(sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts")
mkdir -p "$1"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  [ "${STUB_XRAY:-}/$abi" != missing/x86 ] || continue
  echo "aar $abi" >"$1/libXray-$v-$abi.aar"
done
[ "${STUB_XRAY:-}" != extra ] || echo "aar riscv64" >"$1/libXray-$v-riscv64.aar"
[ "${STUB_XRAY:-}" != fail ]
EOF
cat >"$STUBS/check-cancellation.sh" <<'EOF'
#!/bin/sh
echo check-cancellation >>"$STUB_LOG"
[ -z "${STUB_CHECK_FAIL:-}" ]
EOF
cat >"$STUBS/test.sh" <<'EOF'
#!/bin/sh
echo unit-tests >>"$STUB_LOG"
[ -z "${STUB_TEST_FAIL:-}" ]
EOF
# apksigner <version>: an apksigner stub for build-tools/<version>; "good" prints the signers of the fake APK
# (one line per key= line) and fails for an APK without one, "bad" always fails
apksigner_stub() {
  cat <<EOF
#!/bin/sh
echo "apksigner $1 \$*" >>"\$STUB_LOG"
EOF
  if [ "$2" = good ]; then
    cat <<'EOF'
for a; do apk=$a; done
echo "WARNING: A restricted method in java.lang.System has been called" >&2
keys=$(sed -n 's/^key=\([0-9a-f]\{64\}\)$/\1/p' "$apk")
if [ -z "$keys" ]; then
  echo "DOES NOT VERIFY"
  echo "ERROR: Missing META-INF/MANIFEST.MF" >&2
  exit 1
fi
n=0
for k in $keys; do
  n=$((n + 1))
  echo "Signer #$n certificate DN: CN=test"
  echo "Signer #$n certificate SHA-256 digest: $k"
  echo "Signer #$n certificate SHA-1 digest: 0123456789abcdef0123456789abcdef01234567"
done
EOF
  else
    echo 'exit 1'
  fi
}
apksigner_stub 37.0.0 good >"$ANDROID_HOME/build-tools/37.0.0/apksigner"
cat >"$JAVA_HOME/bin/keytool" <<'EOF'
#!/bin/sh
# keytool stub: -genkeypair writes a fake keystore to -keystore
echo "keytool $*" >>"$STUB_LOG"
while [ $# -gt 0 ]; do
  [ "$1" != -keystore ] || ks=$2
  shift
done
echo keystore >"$ks"
EOF
chmod +x "$STUBS"/* "$JAVA_HOME/bin/keytool" "$ANDROID_HOME/build-tools/37.0.0/apksigner"

# gradle <version>: the fake root's app/build.gradle.kts
gradle() {
  cat <<EOF
plugins {
    id("com.android.application")
}

val appVersionName = "$1"

android {
    defaultConfig {
        versionName = appVersionName
    }
}
EOF
}
# new_root <name> [version]: a fake tree ROOT with the script, its libraries and the stubs; REL is its release dir
new_root() {
  ROOT="$WORK/$1"
  REL="$ROOT/build/app/outputs/apk/release"
  mkdir -p "$ROOT/scripts/lib" "$ROOT/app"
  cp "$TREE/scripts/build-release.sh" "$ROOT/scripts/"
  cp "$TREE/scripts/lib/env.sh" "$TREE/scripts/lib/version.sh" "$TREE/scripts/lib/resolve-gradle.sh" "$ROOT/scripts/lib/"
  cp "$STUBS/gradlew" "$ROOT/"
  cp "$STUBS/package-xray-core-assets.sh" "$STUBS/check-cancellation.sh" "$STUBS/test.sh" "$ROOT/scripts/lib/"
  gradle "${2:-$V}" >"$ROOT/app/build.gradle.kts"
}
# br [args...]: build-release.sh of ROOT, run from WORK with a fresh stub log; sets OUT and RC
br() {
  rm -f "$STUB_LOG"
  OUT="$(cd "$WORK" && "$BASH_BIN" "$ROOT/scripts/build-release.sh" "$@" 2>&1)"
  RC=$?
}

# --- a release build: exactly the release files, SHA256SUMS over them, one signing key
new_root main
mkdir -p "$REL"
echo stale >"$REL/app-universal-release-1.2.2.apk"
br
check "builds: exit 0" '[ $RC = 0 ]'
check "...Gradle :app:assembleRelease once, in the root, with the release key from SSH_VPN_RELEASE_*" \
  'logged "gradlew -p $ROOT :app:assembleRelease" && logged "gradlew signing $WORK/release.keystore release" && [ "$(grep -c "^gradlew -p" "$STUB_LOG")" = 1 ]'
check "...prints the version, its versionCode and the key" 'has "== Сборка $V (versionCode $CODE): :app:assembleRelease, подпись: ключ релиза (SSH_VPN_RELEASE_*)"'
check "...then the Xray files into the release dir" 'logged "xray $REL" && has "== Xray: scripts/lib/package-xray-core-assets.sh"'
check "...the release dir holds exactly the 9 release files and SHA256SUMS (Gradle's other files and an older build are gone)" \
  '[ "$(listing "$REL")" = "$(sorted $APKS $AARS SHA256SUMS)" ]'
check "...SHA256SUMS lists the APKs and the Xray files, in release order, with their sums" 'sums_ok "$REL" $APKS $AARS'
check "...the one signing key is checked on every APK and printed" \
  '[ "$(grep -c "^apksigner 37.0.0 verify --print-certs" "$STUB_LOG")" = 5 ] && has "  SHA-256 сертификата: $K1"'
check "...the summary: the directory, every file with its size, the sums" \
  'has "Релиз $V (versionCode $CODE): $REL" && has "$(printf "  %9s  %s" "$(wc -c <"$REL/app-x86-release-$V.apk" | tr -d " ")" "app-x86-release-$V.apk")" && has "  $(cd "$REL" && sha256 "libXray-$V-x86.aar")" && ! has "другие файлы" && ! has "Без Xray"'
check "...no checks without --test" '! ran check-cancellation && ! ran unit-tests'
check "...nothing written outside the fake root" '[ ! -e "$ROOT/.local" ] && [ -z "$(ls -A "$HOME")" ]'

new_root 'my project'
br
check "a root path with a space builds" '[ $RC = 0 ] && [ "$(listing "$REL")" = "$(sorted $APKS $AARS SHA256SUMS)" ] && sums_ok "$REL" $APKS $AARS'

# --- --no-xray and SSH_VPN_SKIP_XRAY_RELEASE_ASSETS=1: the APKs only
# no_xray_checks <how>: the checks of a build without the Xray files
no_xray_checks() {
  check "$1: the APKs and their SHA256SUMS only, no Xray build" \
    '[ $RC = 0 ] && ! ran xray && [ "$(listing "$REL")" = "$(sorted $APKS SHA256SUMS)" ] && sums_ok "$REL" $APKS'
  check "...says the GitHub release needs the Xray files too" 'has "Без Xray (--no-xray): в релизе GitHub должны быть и libXray-$V-*.aar."'
}
new_root noxray
br --no-xray
no_xray_checks --no-xray
new_root skipxray
SSH_VPN_SKIP_XRAY_RELEASE_ASSETS=1 br
no_xray_checks SSH_VPN_SKIP_XRAY_RELEASE_ASSETS=1
new_root skip0
SSH_VPN_SKIP_XRAY_RELEASE_ASSETS=0 br
check "SSH_VPN_SKIP_XRAY_RELEASE_ASSETS=0 builds the Xray files" '[ $RC = 0 ] && ran xray && [ -s "$REL/libXray-$V-x86.aar" ]'

# --- --out DIR
new_root outroot
mkdir -p "$WORK/out"
echo notes >"$WORK/out/notes.txt"
echo old >"$WORK/out/app-universal-release-1.2.2.apk"
br --out out
check "--out DIR (relative to the current directory): the release files are copied there, the sums hold there" \
  '[ $RC = 0 ] && sums_ok "$WORK/out" $APKS $AARS && cmp -s "$REL/SHA256SUMS" "$WORK/out/SHA256SUMS" && [ "$(listing "$REL")" = "$(sorted $APKS $AARS SHA256SUMS)" ]'
check "...the summary is about DIR, and names the other files there" \
  'has "Релиз $V (versionCode $CODE): $WORK/out" && has "В $WORK/out есть и другие файлы, в релиз они не входят: app-universal-release-1.2.2.apk notes.txt"'
check "...and leaves them alone" '[ "$(cat "$WORK/out/notes.txt")" = notes ] && [ "$(cat "$WORK/out/app-universal-release-1.2.2.apk")" = old ]'
br --no-xray "--out=$WORK/out 2/new"
check "--out=DIR with --no-xray: a new DIR (a path with a space) with the APKs and SHA256SUMS" \
  '[ $RC = 0 ] && [ "$(listing "$WORK/out 2/new")" = "$(sorted $APKS SHA256SUMS)" ] && sums_ok "$WORK/out 2/new" $APKS'
br --out "$REL"
check "--out the release dir itself: no copy, the summary is about it" '[ $RC = 0 ] && has "Релиз $V (versionCode $CODE): $REL" && ! has "другие файлы"'
br --out
check "--out without DIR is a usage error" '[ $RC = 2 ] && has "--out: укажите каталог" && ! ran gradlew'
br --out ''
check "--out '' is a usage error" '[ $RC = 2 ] && has "--out: укажите каталог" && ! ran gradlew'

# --- what Gradle and the Xray script built, wrong
new_root wrong
STUB_APKS=unsigned br
check "unsigned APKs only: refused, with a hint about the signing" \
  '[ $RC = 1 ] && has "Ошибка: собрались только неподписанные APK: " && has " app-universal-release-unsigned-$V.apk" && has "Проверьте настройку подписи" && [ ! -e "$REL/SHA256SUMS" ]'
STUB_APKS=missing br
check "a missing ABI APK is refused" '[ $RC = 1 ] && has "Ошибка: не собраны: app-x86-release-$V.apk." && [ ! -e "$REL/SHA256SUMS" ]'
STUB_XRAY=missing br
check "a missing Xray file is refused" '[ $RC = 1 ] && has "Ошибка: не собраны: libXray-$V-x86.aar." && [ ! -e "$REL/SHA256SUMS" ]'
STUB_APKS=extra br
check "an unexpected APK (a new ABI) is refused, pointing at RELEASE_ABIS" \
  '[ $RC = 1 ] && has "Ошибка: лишние файлы в сборке: app-riscv64-release-$V.apk." && has "RELEASE_ABIS" && [ ! -e "$REL/SHA256SUMS" ]'
STUB_XRAY=extra br
check "an unexpected Xray file is refused" '[ $RC = 1 ] && has "лишние файлы в сборке: libXray-$V-riscv64.aar." && [ ! -e "$REL/SHA256SUMS" ]'
STUB_XRAY=fail br
check "a failing Xray script fails the build" '[ $RC != 0 ] && ! has "Релиз $V" && [ ! -e "$REL/SHA256SUMS" ]'
STUB_GRADLE_FAIL=1 br
check "a failing Gradle fails the build, before Xray" '[ $RC != 0 ] && ! ran xray && ! has "Релиз $V" && [ ! -e "$REL/SHA256SUMS" ]'

# --- the signing key check (apksigner of the highest build-tools)
STUB_APKS=twokeys br
check "two signing keys are refused" '[ $RC = 1 ] && has "Ошибка: app-x86_64-release-$V.apk подписан ключом $K2, а остальные APK — $K1." && [ ! -e "$REL/SHA256SUMS" ]'
STUB_APKS=nosig br
check "an APK that apksigner does not accept is refused, with apksigner's errors" \
  '[ $RC = 1 ] && has "Ошибка: apksigner не принял подпись app-x86-release-$V.apk." && has "Missing META-INF/MANIFEST.MF" && [ ! -e "$REL/SHA256SUMS" ]'
STUB_APKS=twosigners br
check "an APK with two signers is refused" '[ $RC = 1 ] && has "у app-x86-release-$V.apk не одна подпись" && [ ! -e "$REL/SHA256SUMS" ]'
br
check "...apksigner's warnings on stderr are not shown when it succeeds" '[ $RC = 0 ] && ! has "WARNING: A restricted method"'
SDK2="$WORK/sdk2"
for d in 9.0.0:bad 37.0.0:good 38.0.0-rc1:bad 36.1.0:bad; do
  mkdir -p "$SDK2/build-tools/${d%:*}"
  apksigner_stub "${d%:*}" "${d#*:}" >"$SDK2/build-tools/${d%:*}/apksigner"
  chmod +x "$SDK2/build-tools/${d%:*}/apksigner"
done
mkdir -p "$SDK2/build-tools/40.0.0" "$SDK2/build-tools/latest"
apksigner_stub 40.0.0 bad >"$SDK2/build-tools/40.0.0/apksigner"
apksigner_stub latest bad >"$SDK2/build-tools/latest/apksigner"
chmod +x "$SDK2/build-tools/latest/apksigner"
ANDROID_HOME="$SDK2" br
check "the apksigner of the highest X.Y.Z build-tools (37.0.0 > 9.0.0; not 38.0.0-rc1, latest, or 40.0.0 without an executable)" \
  '[ $RC = 0 ] && [ "$(grep "^apksigner" "$STUB_LOG" | cut -d" " -f2 | sort -u)" = 37.0.0 ] && has "SHA-256 сертификата: $K1"'
mkdir -p "$WORK/nosdk"
ANDROID_HOME="$WORK/nosdk" br
check "no apksigner in the SDK: the build passes, the key is reported as not checked" \
  '[ $RC = 0 ] && has "не проверена: нет apksigner в \$ANDROID_HOME/build-tools" && ! ran apksigner'

# --- signing variables: all four, or none
for v in SSH_VPN_RELEASE_STORE_FILE SSH_VPN_RELEASE_KEY_PASSWORD; do
  new_root "partial-$v"
  eval "$v= br"
  check "$v missing (the other three set) is refused before the build" \
    '[ $RC = 1 ] && has "Ошибка: заданы не все переменные подписи, нет: $v." && ! ran gradlew && [ ! -e "$ROOT/.local" ]'
done
SSH_VPN_RELEASE_STORE_PASSWORD='' SSH_VPN_RELEASE_KEY_ALIAS='' br
check "two of them missing: both are named" '[ $RC = 1 ] && has "нет: SSH_VPN_RELEASE_STORE_PASSWORD SSH_VPN_RELEASE_KEY_ALIAS." && ! ran gradlew'
if command -v openssl >/dev/null 2>&1 || command -v uuidgen >/dev/null 2>&1; then
  new_root localkey
  LKS="$ROOT/.local/signing/shadow-ssh-release.keystore"
  SSH_VPN_RELEASE_STORE_FILE='' SSH_VPN_RELEASE_STORE_PASSWORD='' SSH_VPN_RELEASE_KEY_ALIAS='' SSH_VPN_RELEASE_KEY_PASSWORD='' br
  check "none of the four: a local key in .local/signing/, made with \$JAVA_HOME/bin/keytool" \
    '[ $RC = 0 ] && has "создаю локальный ключ подписи в .local/signing/" && ran "keytool -genkeypair" && [ -s "$LKS" ] && logged "gradlew signing $LKS shadow-ssh"'
  check "...the key and its settings are private to the user" \
    '[ "$(mode "$ROOT/.local/signing")" = drwx------ ] && [ "$(mode "$ROOT/.local/signing/release-signing.env")" = -rw------- ] && [ "$(mode "$LKS")" = -rw------- ]'
  check "...the summary says such APKs do not update the GitHub app" 'has "Подпись: локальный ключ $LKS (такие APK не обновят приложение из релизов GitHub)"'
  cp "$LKS" "$WORK/keystore.before"
  SSH_VPN_RELEASE_STORE_FILE='' SSH_VPN_RELEASE_STORE_PASSWORD='' SSH_VPN_RELEASE_KEY_ALIAS='' SSH_VPN_RELEASE_KEY_PASSWORD='' br
  check "...the next build reuses that key" '[ $RC = 0 ] && ! ran keytool && ! has "создаю" && cmp -s "$LKS" "$WORK/keystore.before" && logged "gradlew signing $LKS shadow-ssh"'
else
  echo "ok none of the four SSH_VPN_RELEASE_*: a local key # SKIP no openssl or uuidgen"
fi

# --- the version in app/build.gradle.kts
for c in '1.2|«1.2»' '1.1000.0|«1.1000.0»' 'v1.2.3|«v1.2.3»' '2101.0.0|«2101.0.0»'; do
  new_root bad "${c%|*}"
  br
  check "appVersionName '${c%|*}' is refused before the build" \
    '[ $RC = 1 ] && has "Ошибка: в app/build.gradle.kts appVersionName = ${c#*|}, а не X.Y.Z" && ! ran gradlew'
  rm -rf "$ROOT"
done
new_root twolines
echo "val appVersionName = \"$V\"" >>"$ROOT/app/build.gradle.kts"
br
check "two version lines are refused before the build" '[ $RC = 1 ] && has "должна быть ровно одна строка val appVersionName" && ! ran gradlew'
gradle "$V" | sed '/^val appVersionName/d' >"$ROOT/app/build.gradle.kts"
br
check "no version line is refused before the build" '[ $RC = 1 ] && has "должна быть ровно одна строка val appVersionName" && ! ran gradlew'

# --- --test, SSH_VPN_BUNDLE_XRAY_CORE, options
new_root checks
br --test --no-xray
check "--test: the cancellation guard, then the unit tests, then the build" \
  '[ $RC = 0 ] && [ "$(grep -v "^apksigner" "$STUB_LOG" | head -n 3 | cut -d" " -f1 | tr "\n" " ")" = "check-cancellation unit-tests gradlew " ] && has "== Проверки: scripts/lib/check-cancellation.sh" && has "== Юнит-тесты: scripts/lib/test.sh"'
STUB_TEST_FAIL=1 br --test
check "--test: failing unit tests stop the build" '[ $RC = 1 ] && has "Ошибка: юнит-тесты не прошли." && ! ran gradlew'
STUB_CHECK_FAIL=1 br --test
check "--test: a failing cancellation guard stops everything" '[ $RC = 1 ] && has "Ошибка: scripts/lib/check-cancellation.sh не прошёл." && ! ran unit-tests && ! ran gradlew'
mkdir -p "$ROOT/app/libs"
echo aar >"$ROOT/app/libs/libXray.aar"
SSH_VPN_BUNDLE_XRAY_CORE=1 br --no-xray
check "SSH_VPN_BUNDLE_XRAY_CORE=1 with app/libs/libXray.aar: Gradle gets -PbundleXrayCore=true" \
  '[ $RC = 0 ] && logged "gradlew -p $ROOT -PbundleXrayCore=true :app:assembleRelease"'
br --help
check "--help prints the usage and builds nothing" '[ $RC = 0 ] && has "scripts/build-release.sh [--test] [--no-xray] [--out DIR]" && ! ran gradlew'
br -h
check "-h prints the usage" '[ $RC = 0 ] && has "--no-xray  только APK" && ! ran gradlew'
br --bogus
check "an unknown option is a usage error" '[ $RC = 2 ] && has "неизвестный параметр --bogus" && has "scripts/build-release.sh [--test]" && ! ran gradlew'
br 1.2.3
check "an argument is a usage error" '[ $RC = 2 ] && has "неизвестный параметр 1.2.3" && ! ran gradlew'

echo "# $pass passed, $fail failed"
[ "$fail" = 0 ]
