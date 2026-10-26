#!/usr/bin/env bash
# Tests of .github/scripts/release-notes.sh: throwaway repositories in a temp dir (TMPDIR) with release tags, a side
# branch and a merge. Runs the script under $TEST_BASH (default: bash from PATH). The notes are the release body
# the in-app update sheet shows: only "- <commit subject>" lines.
#
#   scripts/tests/release-notes_test.sh          KEEP=1 keeps the temp dir
#
# Prints 'ok <name>' / 'not ok <name>: <why>' and exits non-zero on a failure.
# The conditions of check() are eval'd when it runs: single quotes on purpose, and the variables they read are
# set outside them.
# shellcheck disable=SC2016,SC2034
set -u

HERE="$(cd "$(dirname "$0")" && pwd -P)"
TREE="$(cd "$HERE/../.." && pwd -P)"
BASH_BIN="${TEST_BASH:-bash}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/release-notes-test.XXXXXX")"
WORK="$(cd "$WORK" && pwd -P)"
if [ -z "${KEEP:-}" ]; then trap 'rm -rf "$WORK"' EXIT; else echo "# work dir: $WORK"; fi

export HOME="$WORK/home" XDG_CONFIG_HOME="$WORK/home/.config" GIT_CONFIG_NOSYSTEM=1 GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=Test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=Test GIT_COMMITTER_EMAIL=test@example.invalid
GIT_CEILING_DIRECTORIES="$(dirname "$WORK")"
export GIT_CEILING_DIRECTORIES
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GH_REPO GITHUB_REPOSITORY GITHUB_SHA VERSION
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
$OUT"; fi
}
# notes <line...>: the output is exactly these lines
notes() { [ "$OUT" = "$(printf '%s\n' "$@")" ]; }

echo "# .github/scripts/release-notes.sh under $("$BASH_BIN" -c 'echo "bash $BASH_VERSION"'), $(git --version)"

# new_repo <dir>: a repository with the scripts and app/build.gradle.kts 0.2.0, first commit 'feat: first'
new_repo() {
  REPO="$1"
  mkdir -p "$REPO/app" "$REPO/scripts/lib" "$REPO/.github/scripts"
  cp "$TREE/scripts/lib/version.sh" "$REPO/scripts/lib/"
  cp "$TREE/.github/scripts/release-notes.sh" "$REPO/.github/scripts/"
  printf 'plugins {}\n\nval appVersionName = "0.2.0"\n' >"$REPO/app/build.gradle.kts"
  git -c init.defaultBranch=master init -q "$REPO"
  git -C "$REPO" symbolic-ref HEAD refs/heads/master
  git -C "$REPO" add -A
  git -C "$REPO" commit -q -m 'feat: first'
}
c() { git -C "$REPO" commit -q --allow-empty -m "$1"; }
# rn [VAR=value...]: release-notes.sh of REPO, run from WORK; sets OUT (stdout and stderr) and RC
rn() {
  OUT="$(cd "$WORK" && env "$@" "$BASH_BIN" "$REPO/.github/scripts/release-notes.sh" 2>&1)"
  RC=$?
}

# v0.0.9 (legacy, annotated) - fix: second (0.1.0) - [side: side work (0.1.5)] - release commit - topic merge -
# fix: third
new_repo "$WORK/main"
git -C "$REPO" tag -a v0.0.9 -m legacy
c 'fix: second'
git -C "$REPO" tag 0.1.0
git -C "$REPO" checkout -q -b side
c 'side work'
git -C "$REPO" tag 0.1.5
git -C "$REPO" checkout -q master
c 'feat: release - version 0.2.0'
git -C "$REPO" checkout -q -b topic
c 'feat: topic'
c 'feat: release - versioning docs'
git -C "$REPO" checkout -q master
git -C "$REPO" merge -q --no-ff -m 'Merge branch topic' topic
git -C "$REPO" commit -q --allow-empty -m 'fix: third' -m 'A body line that is not a subject.'

rn
check "the commits since the previous release tag in the history (0.1.0, not the side branch's 0.1.5), newest first" \
  '[ $RC = 0 ] && notes "- fix: third" "- feat: release - versioning docs" "- feat: topic"'
check "...only '- <subject>' lines: no header, no merges, no release commits, no bodies" \
  '[ -z "$(printf "%s\n" "$OUT" | grep -v "^- ")" ] && ! printf "%s\n" "$OUT" | grep -q -e Merge -e "version 0.2.0" -e "body line"'
git -C "$REPO" tag 0.2.0
rn
check "a tag of the version itself (a retry) is not the previous one" '[ $RC = 0 ] && notes "- fix: third" "- feat: release - versioning docs" "- feat: topic"'
rn GITHUB_SHA=0.1.0 VERSION=0.1.0
check "GITHUB_SHA and VERSION: the history up to that commit, since the legacy tag v0.0.9" '[ $RC = 0 ] && notes "- fix: second"'
rn GITHUB_SHA="$(git -C "$REPO" rev-parse side)" VERSION=0.1.5
check "GITHUB_SHA on the side branch: since 0.1.0 there" '[ $RC = 0 ] && notes "- side work"'
printf 'val appVersionName = "0.3.0"\n' >"$REPO/app/build.gradle.kts"
rn
check "the version comes from app/build.gradle.kts: 0.3.0, so the tag 0.2.0 is the previous one" '[ $RC = 0 ] && notes "- Release 0.3.0"'

new_repo "$WORK/first"
c 'fix: a'
rn
check "no release tag yet: every commit" '[ $RC = 0 ] && notes "- fix: a" "- feat: first"'
git -C "$REPO" tag 0.1.0
c 'feat: release - version 0.2.0'
rn
check "only a release commit since the last tag: '- Release X.Y.Z'" '[ $RC = 0 ] && notes "- Release 0.2.0"'
rn VERSION=0.2.1
check "...with the version of VERSION" '[ $RC = 0 ] && notes "- Release 0.2.1"'
for v in v0.2.0 0.2 0.2.0-rc1; do
  rn VERSION="$v"
  check "a malformed VERSION '$v' is refused" '[ $RC = 1 ] && [ "$OUT" = "release-notes: «$v» is not X.Y.Z" ]'
done
printf 'val appVersionName = "0.2.0"\nval appVersionName = "0.2.0"\n' >"$REPO/app/build.gradle.kts"
rn
check "app/build.gradle.kts with two version lines is refused" '[ $RC = 1 ] && [ "$OUT" = "release-notes: «» is not X.Y.Z" ]'
rn VERSION=0.2.0
check "...unless VERSION is given" '[ $RC = 0 ] && notes "- Release 0.2.0"'

echo "# $pass passed, $fail failed"
[ "$fail" = 0 ]
