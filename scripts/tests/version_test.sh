#!/usr/bin/env bash
# Tests of scripts/lib/version.sh: every function runs in a fresh $TEST_BASH (default: bash from PATH, /bin/bash 3.2
# on macOS) under `set -euo pipefail`, as the scripts that source it run. Files go to a temp dir (TMPDIR); the
# repository is only read (RELEASE_ABIS against app/build.gradle.kts and package-xray-core-assets.sh).
#
#   scripts/tests/version_test.sh          KEEP=1 keeps the temp dir
#
# Prints 'ok <name>' / 'not ok <name>: <why>' and exits non-zero on a failure.
# The conditions of check() are eval'd when it runs: single quotes on purpose, and the variables they read are
# set outside them.
# shellcheck disable=SC2016,SC2034
set -u

HERE="$(cd "$(dirname "$0")" && pwd -P)"
TREE="$(cd "$HERE/../.." && pwd -P)"
LIB="$TREE/scripts/lib/version.sh"
BASH_BIN="${TEST_BASH:-bash}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/version-test.XXXXXX")"
WORK="$(cd "$WORK" && pwd -P)"
if [ -z "${KEEP:-}" ]; then trap 'rm -rf "$WORK"' EXIT; else echo "# work dir: $WORK"; fi
# app_version_set's temp files land here: the test checks that none is left behind
mkdir -p "$WORK/tmp"
export TMPDIR="$WORK/tmp"

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
  if eval "$2"; then ok "$1"; else nok "$1" "rc=$RC; condition: $2; stdout: '$OUT'; stderr: '$ERR'"; fi
}
# fn <stdin (printf %b)> <function> [args...]: the function in a fresh BASH_BIN that sourced version.sh under
# set -euo pipefail; sets OUT, ERR, RC
fn() {
  local input="$1"
  shift
  OUT="$(printf '%b' "$input" | "$BASH_BIN" -c 'set -euo pipefail; . "$0"; "$@"' "$LIB" "$@" 2>"$WORK/err")"
  RC=$?
  ERR="$(cat "$WORK/err")"
}
# is <name> <rc> <stdout>: the last fn exited with rc and printed exactly stdout, and nothing on stderr
is() {
  if [ "$RC" = "$2" ] && [ "$OUT" = "$3" ] && [ -z "$ERR" ]; then
    ok "$1"
  else
    nok "$1" "want rc=$2 stdout '$3'; got rc=$RC stdout '$OUT' stderr '$ERR'"
  fi
}
lines() { printf '%s\n' "$@"; }
# ls -l and ls -i, of plain names: the portable way to the mode and the inode (stat differs on BSD and GNU)
# shellcheck disable=SC2012
mode() { ls -l "$1" | cut -c1-10; }
# shellcheck disable=SC2012
inode() { ls -i "$1" | awk '{ print $1 }'; }

echo "# scripts/lib/version.sh under $("$BASH_BIN" -c 'echo "bash $BASH_VERSION"')"

# --- version_valid
for v in 0.0.0 1.2.3 3.7.0 10.20.30 2100.0.0 999999.999999.999999; do
  fn '' version_valid "$v"
  is "version_valid: '$v' is a version" 0 ''
done
for v in '' 1 1.2 1.2.3.4 01.2.3 1.02.3 1.2.03 00.0.0 v1.2.3 V1.2.3 1.2.3-rc1 1.2.3+b ' 1.2.3' '1.2.3 ' \
  $'1.2.3\n' $'1.2.3\n4.5.6' a.b.c 1..3 .1.2 1.2. -1.2.3 1,2,3; do
  fn '' version_valid "$v"
  is "version_valid: '$(printf '%s' "$v" | tr '\n' '|')' is not a version (| is a newline)" 1 ''
done

# --- version_cmp, version_gt
for c in "1.2.3 1.2.3 0" "1.2.3 1.2.4 -1" "1.2.4 1.2.3 1" "1.2.10 1.2.9 1" "1.2.9 1.2.10 -1" "1.10.0 1.9.999 1" \
  "2.0.0 10.0.0 -1" "0.0.0 0.0.1 -1" "1.0.0 0.999.999 1" "0.1.0 0.0.9 1" "3.7.0 3.6.0 1" "3.7.0 3.7.0 0" \
  "100000000000000000000.0.0 99999999999999999999.0.0 1" "0.0.99999999999999999999 0.0.100000000000000000000 -1"; do
  read -r a b want <<<"$c"
  fn '' version_cmp "$a" "$b"
  is "version_cmp $a $b = $want" 0 "$want"
done
fn '' version_gt 1.2.10 1.2.9
is "version_gt: 1.2.10 > 1.2.9 (numbers, not strings)" 0 ''
fn '' version_gt 10.0.0 9.99.999
is "version_gt: 10.0.0 > 9.99.999" 0 ''
fn '' version_gt 1.2.3 1.2.3
is "version_gt: 1.2.3 is not > 1.2.3" 1 ''
fn '' version_gt 1.2.3 1.3.0
is "version_gt: 1.2.3 is not > 1.3.0" 1 ''

# --- version_bump
for c in "1.2.3 patch 1.2.4" "1.2.3 minor 1.3.0" "1.2.3 major 2.0.0" "0.1.0 minor 0.2.0" "0.0.0 patch 0.0.1" \
  "0.1.9 patch 0.1.10" "9.99.999 minor 9.100.0" "3.7.0 major 4.0.0" "3.7.0 minor 3.8.0" "3.7.0 patch 3.7.1"; do
  read -r a b want <<<"$c"
  fn '' version_bump "$a" "$b"
  is "version_bump $a $b = $want" 0 "$want"
done
for k in bogus '' Patch патч; do
  fn '' version_bump 1.2.3 "$k"
  is "version_bump: '$k' is not a bump" 1 ''
done

# --- version_code: X*1000000 + Y*1000 + Z, Y and Z up to 999, at most 2100000000
for c in "3.7.0 3007000" "0.0.0 0" "0.1.0 1000" "1.2.3 1002003" "0.999.999 999999" "2099.999.999 2099999999" \
  "2100.0.0 2100000000"; do
  read -r a want <<<"$c"
  fn '' version_code "$a"
  is "version_code $a = $want" 0 "$want"
done
for v in 2100.0.1 2100.1.0 2101.0.0 9999.0.0 10000.0.0 99999999999999999999.0.0 1.1000.0 1.0.1000 1.9999.0 \
  0.0.99999999999999999999 1.2 v1.2.3 01.2.3 ''; do
  fn '' version_code "$v"
  is "version_code: '$v' does not fit versionCode" 1 ''
done

# --- app_version_parse, app_version_get: exactly one line `val appVersionName = "X.Y.Z"`
GRADLE='plugins {
    id("com.android.application")
}

val appVersionName = "3.7.0"
val appVersionNameSuffix = "-beta"
val myappVersionName = "8.8.8"
var appVersionName2 = "7.7.7"

val appVersionParts = appVersionName.split(".").map(String::toInt)
android {
    defaultConfig {
        versionName = appVersionName
    }
}
// val appVersionName = "9.9.9"
output.outputFileName.set(defaultFileName.removeSuffix(".apk") + "-$appVersionName.apk")
'
fn "$GRADLE" app_version_parse
is "app_version_parse: the one version line among lines that only mention appVersionName" 0 3.7.0
fn ' \tval\tappVersionName \t=\t "1.2.3"\t \n' app_version_parse
is "app_version_parse: spaces and tabs around the parts of the line" 0 1.2.3
fn 'val appVersionName = "x"\n' app_version_parse
is "app_version_parse: the value is not checked" 0 x
fn 'val appVersionName = "1.2.3"' app_version_parse
is "app_version_parse: a last line without a newline" 0 1.2.3
fn 'plugins {}\nval name = appVersionName\n// val appVersionName = "1.2.3"\n' app_version_parse
is "app_version_parse: no version line fails, printing nothing" 1 ''
fn 'val appVersionName = "1.2.3"\nfoo()\n  val appVersionName = "1.2.3"\n' app_version_parse
is "app_version_parse: two version lines fail, printing nothing, even with one value" 1 ''
fn 'val appVersionName = "1.2.3"\nval appVersionName = "1.2.4"\n' app_version_parse
is "app_version_parse: two different version lines fail" 1 ''
fn '' app_version_parse
is "app_version_parse: empty input fails" 1 ''

ROOT="$WORK/root"
mkdir -p "$ROOT/app"
printf '%b' "$GRADLE" >"$ROOT/app/build.gradle.kts"
fn '' app_version_get "$ROOT"
is "app_version_get: reads <root>/app/build.gradle.kts" 0 3.7.0
fn '' app_version_get "$WORK/missing"
check "app_version_get: a missing file fails, printing nothing on stdout" '[ $RC != 0 ] && [ -z "$OUT" ]'
fn '' app_version_get "$TREE"
tree_version=$OUT tree_rc=$RC
fn '' version_code "$tree_version"
check "app_version_get: this repository's app/build.gradle.kts has one version line, X.Y.Z, that fits versionCode" \
  '[ "$tree_rc" = 0 ] && [ $RC = 0 ] && [ -n "$OUT" ]'

# --- app_version_set: in place, only the value of the version line, nothing on a failure
F="$ROOT/app/build.gradle.kts"
chmod 640 "$F"
before_mode=$(mode "$F") before_inode=$(inode "$F")
printf '%b' "$GRADLE" | sed 's/^val appVersionName = "3.7.0"$/val appVersionName = "3.8.0"/' >"$WORK/expected"
fn '' app_version_set "$ROOT" 3.8.0
is "app_version_set: writes 3.8.0, printing nothing" 0 ''
check "...exactly the version line changed, every other line is intact" 'cmp -s "$F" "$WORK/expected"'
check "...the file keeps its mode and inode" '[ "$(mode "$F")" = "$before_mode" ] && [ "$(inode "$F")" = "$before_inode" ]'
check "...no temp file is left in TMPDIR" '[ -z "$(ls -A "$WORK/tmp")" ]'
fn '' app_version_get "$ROOT"
is "...app_version_get reads it back" 0 3.8.0
cp "$F" "$WORK/before"
for v in 1.2 v3.9.0 03.9.0 3.9.0-rc1 '' '3.9.0" + "x'; do
  fn '' app_version_set "$ROOT" "$v"
  check "app_version_set: '$v' is refused and the file is unchanged" '[ $RC = 1 ] && [ -z "$OUT" ] && cmp -s "$F" "$WORK/before"'
done

printf 'android {\n\t  val appVersionName   =  "1.2.3"  \n}\n' >"$F"
printf 'android {\n\t  val appVersionName   =  "10.0.0"  \n}\n' >"$WORK/expected"
fn '' app_version_set "$ROOT" 10.0.0
check "app_version_set: an indented line keeps its indentation and spacing" '[ $RC = 0 ] && cmp -s "$F" "$WORK/expected"'

for c in 'zero|plugins {}\nval name = appVersionName\n' \
  'two|val appVersionName = "1.2.3"\nfoo()\nval appVersionName = "1.2.3"\n'; do
  printf '%b' "${c#*|}" >"$F"
  cp "$F" "$WORK/before"
  fn '' app_version_set "$ROOT" 2.0.0
  check "app_version_set: a file with ${c%%|*} version lines fails and is unchanged" \
    '[ $RC = 1 ] && [ -z "$OUT" ] && cmp -s "$F" "$WORK/before"'
done
check "...no temp file is left in TMPDIR" '[ -z "$(ls -A "$WORK/tmp")" ]'
fn '' app_version_set "$WORK/missing" 2.0.0
check "app_version_set: a missing file fails and is not created" '[ $RC != 0 ] && [ ! -e "$WORK/missing/app/build.gradle.kts" ]'

# --- release_assets: the files of release X.Y.Z, in upload order
fn '' release_assets 3.7.0
is "release_assets 3.7.0: the 3.7.0 release's APKs, Xray assets and SHA256SUMS" 0 "$(lines \
  app-universal-release-3.7.0.apk app-arm64-v8a-release-3.7.0.apk app-armeabi-v7a-release-3.7.0.apk \
  app-x86-release-3.7.0.apk app-x86_64-release-3.7.0.apk libXray-3.7.0-arm64-v8a.aar \
  libXray-3.7.0-armeabi-v7a.aar libXray-3.7.0-x86.aar libXray-3.7.0-x86_64.aar SHA256SUMS)"
fn '' release_assets 10.0.1 --no-xray
is "release_assets --no-xray: the APKs and SHA256SUMS" 0 "$(lines app-universal-release-10.0.1.apk \
  app-arm64-v8a-release-10.0.1.apk app-armeabi-v7a-release-10.0.1.apk app-x86-release-10.0.1.apk \
  app-x86_64-release-10.0.1.apk SHA256SUMS)"
abis_of() { tr -d ' "' | tr ',' '\n' | LC_ALL=C sort | tr '\n' ' '; }
lib_abis="$("$BASH_BIN" -c '. "$0"; echo "$RELEASE_ABIS"' "$LIB" | tr ' ' ',' | abis_of)"
gradle_abis="$(sed -n 's/^ *include(\(.*\))$/\1/p' "$TREE/app/build.gradle.kts" | abis_of)"
xray_abis="$(sed -n 's/^abis = \[\(.*\)\]$/\1/p' "$TREE/scripts/lib/package-xray-core-assets.sh" | abis_of)"
if [ -n "$lib_abis" ] && [ "$lib_abis" = "$gradle_abis" ] && [ "$lib_abis" = "$xray_abis" ]; then
  ok "RELEASE_ABIS = the ABI splits of app/build.gradle.kts = the ABIs of package-xray-core-assets.sh ($lib_abis)"
else
  nok "RELEASE_ABIS = the ABI splits and the Xray ABIs" "version.sh '$lib_abis', gradle '$gradle_abis', xray '$xray_abis'"
fi

# --- release_tags: tag names or `git ls-remote --tags` lines -> "<X.Y.Z> <tag>", once per tag
TAGS='0.1.0\nv0.2.0\nnot-a-release\n1.2\n\n01.0.0\nV0.5.0\nvv0.6.0\n0.3.0-rc1\n'
TAGS="${TAGS}aaaa\trefs/tags/0.3.0\nbbbb\trefs/tags/v0.4.0\ncccc\trefs/tags/v0.4.0^{}\n"
TAGS="${TAGS}dddd\trefs/heads/0.9.0\n0.1.0\neeee\trefs/tags/0.10.0"
fn "$TAGS" release_tags
is "release_tags: X.Y.Z and vX.Y.Z tags, from names and ls-remote lines (peeled ones once), nothing else" 0 \
  "$(lines '0.1.0 0.1.0' '0.2.0 v0.2.0' '0.3.0 0.3.0' '0.4.0 v0.4.0' '0.10.0 0.10.0')"
fn '' release_tags
is "release_tags: no input, no tags" 0 ''

# --- highest_tag [skip]
fn "$TAGS" highest_tag
is "highest_tag: the highest version (0.10.0 > 0.4.0)" 0 '0.10.0 0.10.0'
fn "$TAGS" highest_tag 0.10.0
is "highest_tag 0.10.0: the highest other tag" 0 '0.4.0 v0.4.0'
fn '0.9.0\n0.10.0\nv0.11.0\n1.0.0-rc1\n' highest_tag
is "highest_tag: a legacy v tag can be the highest" 0 '0.11.0 v0.11.0'
fn '0.9.0\nv0.11.0\n' highest_tag 0.11.0
is "highest_tag 0.11.0 skips only the tag named 0.11.0, not v0.11.0" 0 '0.11.0 v0.11.0'
fn 'nope\n1.2\n' highest_tag
is "highest_tag: nothing without release tags" 0 ''
fn '' highest_tag
is "highest_tag: nothing for no input" 0 ''

# --- tag_of_version
fn "$TAGS" tag_of_version 0.4.0
is "tag_of_version 0.4.0: the legacy tag v0.4.0" 0 v0.4.0
fn "$TAGS" tag_of_version 0.3.0
is "tag_of_version 0.3.0: the tag 0.3.0" 0 0.3.0
fn "$TAGS" tag_of_version 0.9.0
is "tag_of_version: nothing for a version without a tag (a branch named 0.9.0 is not one)" 0 ''
fn "$TAGS" tag_of_version 1.2
is "tag_of_version: nothing for a non-version" 0 ''

# --- tag_commit <tag>: the commit of refs/tags/<tag> in `git ls-remote --tags` lines
REFS='1111\trefs/tags/0.1.0\n2222\trefs/tags/v0.2.0\n3333\trefs/tags/v0.2.0^{}\n'
REFS="${REFS}4444\trefs/tags/10.1.0\n5555\trefs/tags/0.1.0.1\n6666\trefs/tags/0x1y0\n7777\trefs/heads/0.5.0\n"
fn "$REFS" tag_commit 0.1.0
is "tag_commit: a lightweight tag's own line" 0 1111
fn "$REFS" tag_commit v0.2.0
is "tag_commit: an annotated tag's peeled line, not the tag object" 0 3333
fn 'cccc\trefs/tags/0.4.0^{}\naaaa\trefs/tags/0.4.0\n' tag_commit 0.4.0
is "tag_commit: the peeled line wins wherever it is" 0 cccc
fn "$REFS" tag_commit 0.2.0
is "tag_commit: 0.2.0 is not v0.2.0" 0 ''
fn "$REFS" tag_commit 0.5.0
is "tag_commit: only refs/tags/<tag> counts (no branch, no longer names, no dots as wildcards)" 0 ''
fn "$REFS" tag_commit 0.1.0.1
is "tag_commit: a longer tag name is its own tag" 0 5555
fn '' tag_commit 0.1.0
is "tag_commit: nothing for no input" 0 ''

echo "# $pass passed, $fail failed"
[ "$fail" = 0 ]
