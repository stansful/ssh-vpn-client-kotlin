#!/usr/bin/env bash
# Tests of scripts/make-release.sh: throwaway repositories, each a clone of its own local bare "origin", in a temp
# dir (TMPDIR), with the script, scripts/lib/version.sh and a small app/build.gradle.kts. The script runs from a
# directory outside the repository, under $TEST_BASH (default: bash from PATH, /bin/bash 3.2 on macOS). git reads
# only this test's config (HOME is the temp dir).
#
#   scripts/tests/make-release_test.sh          KEEP=1 keeps the temp dir
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
if ! WORK="$(mktemp -d "${TMPDIR:-/tmp}/make-release-test.XXXXXX")" || ! WORK="$(cd "$WORK" && pwd -P)"; then
  echo "not ok no temp dir in ${TMPDIR:-/tmp}"
  exit 1
fi
if [ -z "${KEEP:-}" ]; then trap 'rm -rf "$WORK"' EXIT; else echo "# work dir: $WORK"; fi

export HOME="$WORK/home" XDG_CONFIG_HOME="$WORK/home/.config" GIT_CONFIG_NOSYSTEM=1 GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=Test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=Test GIT_COMMITTER_EMAIL=test@example.invalid
# git never finds a repository above the temp dir (a TMPDIR inside some checkout)
GIT_CEILING_DIRECTORIES="$(dirname "$WORK")"
export GIT_CEILING_DIRECTORIES
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_COMMON_DIR
mkdir -p "$HOME"
# the script's temp files (app_version_set) land here, an absolute path whatever the caller's TMPDIR (the script runs
# from WORK)
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
# check <name> <condition (eval'd)>: on failure prints the script's output
check() {
  if eval "$2"; then ok "$1"; else nok "$1" "rc=$RC; condition: $2; output:
$OUT"; fi
}
has() { case $OUT in *"$1"*) return 0 ;; esac; return 1; }

echo "# scripts/make-release.sh under $("$BASH_BIN" -c 'echo "bash $BASH_VERSION"'), $(git --version)"

GF=app/build.gradle.kts
# gradle <version>: the fixture's app/build.gradle.kts with that version
gradle() {
  cat <<EOF
plugins {
    id("com.android.application")
}

val appVersionName = "$1"

val appVersionParts = appVersionName.split(".").map(String::toInt)
val appVersionCode = appVersionParts[0] * 1_000_000 + appVersionParts[1] * 1_000 + appVersionParts[2]

android {
    defaultConfig {
        versionCode = appVersionCode
        versionName = appVersionName
    }
}
EOF
}

# --- the template: origin.git with master (app/build.gradle.kts 0.1.0, the script) and the tag 0.1.0 on its first
# commit
TPL="$WORK/tpl"
mkdir -p "$TPL/seed/app" "$TPL/seed/scripts/lib"
cp "$TREE/scripts/make-release.sh" "$TPL/seed/scripts/"
cp "$TREE/scripts/lib/version.sh" "$TPL/seed/scripts/lib/"
gradle 0.1.0 >"$TPL/seed/$GF"
echo readme >"$TPL/seed/README"
printf 'out/\n' >"$TPL/seed/.gitignore"
(
  set -e
  cd "$TPL/seed"
  git -c init.defaultBranch=master init -q .
  git symbolic-ref HEAD refs/heads/master
  git add -A
  git commit -q -m init
  git tag 0.1.0
  echo more >>README
  git commit -q -am 'fix: something'
  git init -q --bare "$TPL/origin.git"
  git -C "$TPL/origin.git" symbolic-ref HEAD refs/heads/master
  git push -q "$TPL/origin.git" master 0.1.0
) || {
  echo "not ok the test fixture could not be built"
  exit 1
}

# new_case <name>: CASE/origin.git (a copy of the template's) and its clone REPO; a name used twice stops the tests
# (the clone would fail and the case would run in the earlier case's repository)
new_case() {
  CASE="$WORK/$1"
  if [ -e "$CASE" ]; then
    echo "not ok the test case name '$1' is used twice"
    exit 1
  fi
  mkdir -p "$CASE"
  cp -R "$TPL/origin.git" "$CASE/origin.git"
  git clone -q "$CASE/origin.git" "$CASE/repo"
  REPO="$CASE/repo"
}
# mr <stdin (printf %b)> [args...]: make-release.sh of REPO, run from WORK; sets OUT and RC
mr() {
  local input="$1"
  shift
  OUT="$(cd "$WORK" && printf '%b' "$input" | "$BASH_BIN" "$REPO/scripts/make-release.sh" "$@" 2>&1)"
  RC=$?
}
g() { git -C "$REPO" "$@"; }
# ver: the value of the version line (or lines), read without scripts/lib/version.sh
ver() { sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' "$REPO/$GF"; }
count() { g rev-list --count HEAD; }
subject() { g log -1 --format=%s; }
origin_ref() { git -C "$CASE/origin.git" rev-parse -q --verify "refs/heads/$1"; }
all_tags() { { g tag -l && git -C "$CASE/origin.git" tag -l; } | LC_ALL=C sort | tr '\n' ' '; }
clean() { [ -z "$(g status --porcelain)" ]; }
# release_commit <old> <new>: HEAD is the release commit of <new>: its subject, and against HEAD^ it changes the
# version line of app/build.gradle.kts from <old> to <new> and nothing else (no other line, file or mode)
release_commit() {
  [ "$(subject)" = "feat: release - version $2" ] &&
    [ "$(g diff --numstat HEAD^ HEAD)" = "$(printf '1\t1\t%s' "$GF")" ] &&
    [ -z "$(g diff --summary HEAD^ HEAD)" ] &&
    [ "$(g diff -U0 HEAD^ HEAD | grep '^[-+][^-+]')" = \
      "$(printf -- '-val appVersionName = "%s"\n+val appVersionName = "%s"' "$1" "$2")" ]
}
# gradle_is <version>: app/build.gradle.kts is the fixture's, byte for byte, with that version
gradle_is() { [ "$(cat "$REPO/$GF")" = "$(gradle "$1")" ]; }

# --- Enter takes the suggestion, the next minor version
new_case enter
before=$(count) head=$(g rev-parse HEAD) origin=$(origin_ref master) tags=$(all_tags)
mr '\n\n'
check "prints the current version and its versionCode" 'has "Текущая версия:         0.1.0 (app/build.gradle.kts, versionCode 1000)"'
check "prints the last release tag" 'has "Последний релизный тег: 0.1.0"'
check "...and no 'not released yet' note for a released version" '! has "Тега 0.1.0 ещё нет"'
check "prints the next patch, minor and major versions" 'has "Следующая: 0.1.1 (patch), 0.2.0 (minor), 1.0.0 (major)."'
check "suggests the next minor version" 'has "Новая версия [0.2.0]: "'
check "shows the diff and asks to commit" 'has "+val appVersionName = \"0.2.0\"" && has "Закоммитить app/build.gradle.kts как «feat: release - version 0.2.0»? [Y/n] "'
check "Enter takes 0.2.0, Enter commits (run from outside the repository)" '[ $RC = 0 ] && [ "$(ver)" = 0.2.0 ] && gradle_is 0.2.0'
check "...exactly one new commit, on the old HEAD" '[ "$(count)" = $((before + 1)) ] && [ "$(g rev-parse HEAD^)" = "$head" ]'
check "...'feat: release - version 0.2.0', only the version line of app/build.gradle.kts" 'release_commit 0.1.0 0.2.0 && clean'
check "...prints the commit" 'has "Коммит $(g rev-parse --short HEAD): feat: release - version 0.2.0"'
check "...never pushes and never asks to: origin and tags untouched" '[ "$(origin_ref master)" = "$origin" ] && [ "$(all_tags)" = "$tags" ] && ! has "Запуш" && ! has "запуш" && ! has "git push"'

new_case 'with space'
mr '' --yes
check "a repository path with a space works" '[ $RC = 0 ] && release_commit 0.1.0 0.2.0 && clean'

# --- bump words at the prompt
for c in 'patch|0.1.1' 'патч|0.1.1' 'minor|0.2.0' 'Минор|0.2.0' 'major|1.0.0' 'мажор|1.0.0' 'MAJOR|1.0.0' '  патч  |0.1.1'; do
  w=${c%|*} want=${c#*|}
  new_case "prompt-$want-$(printf '%s' "$w" | od -An -tx1 | tr -d ' \n')"
  mr "$w\n\n"
  check "prompt: '$w' is $want" '[ $RC = 0 ] && [ "$(ver)" = "$want" ] && release_commit 0.1.0 "$want" && clean'
done

# --- bump words as the argument
new_case words
before=$(count)
for c in 'patch|0.1.1' 'minor|0.2.0' 'major|1.0.0' 'патч|0.1.1' 'минор|0.2.0' 'мажор|1.0.0' 'Patch|0.1.1' 'Мажор|1.0.0'; do
  w=${c%|*} want=${c#*|}
  mr '' "$w" --no-commit
  check "argument '$w' is $want" '[ $RC = 0 ] && has "Новая версия: $want" && [ "$(ver)" = "$want" ] && gradle_is "$want" && [ "$(count)" = "$before" ]'
  g checkout -q -- "$GF"
done
mr '' minor --yes
check "argument minor --yes commits 0.2.0" '[ $RC = 0 ] && release_commit 0.1.0 0.2.0 && clean'

# --- the prompt asks again after a refused version
new_case reask
before=$(count)
mr '0.0.9\n0.1.0\n1.2\nv0.2.0\nV0.2.0\n01.2.3\n0.2.0-rc1\n0.1000.0\n0.2.1000\n2101.0.0\nбамп\n  0.3.0  \ny\n'
check "prompt: a lower version is refused" 'has "версия 0.0.9 не выше текущей 0.1.0. Введите другую версию."'
check "prompt: an existing tag is refused" 'has "тег 0.1.0 уже есть: версия 0.1.0 уже выпускалась. Введите другую версию."'
check "prompt: a malformed version is refused" 'has "«1.2» — не версия X.Y.Z (три числа без ведущих нулей, например 0.2.0)" && has "«01.2.3» — не версия X.Y.Z" && has "«0.2.0-rc1» — не версия X.Y.Z" && has "«бамп» — не версия X.Y.Z"'
check "prompt: a version with v or V is refused" 'has "«v0.2.0»: нужна версия без «v», например 0.2.0" && has "«V0.2.0»: нужна версия без «v», например 0.2.0"'
check "prompt: Y or Z above 999, or a versionCode above 2100000000, is refused" 'has "версия 0.1000.0 не помещается в versionCode" && has "версия 0.2.1000 не помещается в versionCode" && has "версия 2101.0.0 не помещается в versionCode"'
check "prompt: then 0.3.0 (spaces trimmed) is committed" '[ $RC = 0 ] && [ "$(ver)" = 0.3.0 ] && [ "$(count)" = $((before + 1)) ] && release_commit 0.1.0 0.3.0 && clean'

# --- end of input at the prompt
new_case eof
before=$(count)
mr 'abc\n'
check "end of input at the prompt changes nothing" '[ $RC = 1 ] && has "ввод прерван, ничего не изменено" && [ "$(ver)" = 0.1.0 ] && [ "$(count)" = "$before" ] && clean'

# --- refused versions given as the argument
new_case refuse
before=$(count)
for c in "0.0.9|версия 0.0.9 не выше текущей 0.1.0" "0.1.0|тег 0.1.0 уже есть: версия 0.1.0 уже выпускалась" \
  "1.2|«1.2» — не версия X.Y.Z" "1.2.3.4|«1.2.3.4» — не версия X.Y.Z" \
  "v0.2.0|«v0.2.0»: нужна версия без «v», например 0.2.0" "01.2.3|«01.2.3» — не версия X.Y.Z" \
  "1.02.3|«1.02.3» — не версия X.Y.Z" "0.2.0-rc1|«0.2.0-rc1» — не версия X.Y.Z" " 0.2.0|« 0.2.0» — не версия X.Y.Z" \
  "бамп|«бамп» — не версия X.Y.Z" "|версия не указана" "0.1000.0|версия 0.1000.0 не помещается в versionCode" \
  "0.2.1000|версия 0.2.1000 не помещается в versionCode" "2101.0.0|версия 2101.0.0 не помещается в versionCode" \
  "99999.0.0|версия 99999.0.0 не помещается в versionCode"; do
  v=${c%%|*} want=${c#*|}
  mr '' --yes "$v"
  check "argument '$v' is refused: $want" '[ $RC = 1 ] && has "Ошибка: $want" && [ "$(ver)" = 0.1.0 ] && [ "$(count)" = "$before" ] && clean'
done
mr '\n\n' 0.0.9
check "a refused argument does not fall back to the prompt" '[ $RC = 1 ] && ! has "Новая версия [" && [ "$(ver)" = 0.1.0 ] && [ "$(count)" = "$before" ]'

# --- tags: local only, origin only, a legacy annotated vX.Y.Z on origin
new_case tags
g tag 0.5.0
git clone -q "$CASE/origin.git" "$CASE/other"
git -C "$CASE/other" tag 0.7.0
git -C "$CASE/other" tag -a v0.8.0 -m legacy
git -C "$CASE/other" push -q origin 0.7.0 v0.8.0
before=$(count) origin=$(origin_ref master)
mr '' --yes 0.5.0
check "a local tag is refused" '[ $RC = 1 ] && has "тег 0.5.0 уже есть"'
mr '' --yes 0.7.0
check "a tag only on origin is refused" '[ $RC = 1 ] && has "тег 0.7.0 уже есть"'
mr '' --yes 0.8.0
check "a legacy tag v0.8.0 on origin is refused for 0.8.0" '[ $RC = 1 ] && has "тег v0.8.0 уже есть"'
mr '' --yes 0.6.0
check "a version below the highest tag is refused" '[ $RC = 1 ] && has "версия 0.6.0 не выше последнего релизного тега v0.8.0"'
check "...nothing changed" '[ "$(ver)" = 0.1.0 ] && [ "$(count)" = "$before" ] && clean'
mr ''
check "the last tag and the suggestions come from all tags" 'has "Последний релизный тег: v0.8.0" && has "Следующая: 0.8.1 (patch), 0.9.0 (minor), 1.0.0 (major)." && has "Новая версия [0.9.0]: " && [ $RC = 1 ]'
mr '' патч --no-commit
check "a bump word bumps the highest tag, not the current version" '[ $RC = 0 ] && [ "$(ver)" = 0.8.1 ]'
g checkout -q -- "$GF"
mr '' --yes
check "--yes takes the suggested 0.9.0 and commits without a question" '[ $RC = 0 ] && ! has "Закоммитить" && release_commit 0.1.0 0.9.0 && [ "$(count)" = $((before + 1)) ] && clean'
check "--yes does not push" '[ "$(origin_ref master)" = "$origin" ] && ! has "Запуш"'

# --- origin's tags cannot be read: a warning, then the local tags only
new_case badorigin
git clone -q "$CASE/origin.git" "$CASE/other"
git -C "$CASE/other" tag 0.7.0
git -C "$CASE/other" push -q origin 0.7.0
g remote set-url origin "$CASE/missing.git"
mr '' -y
check "unreadable origin tags: a warning, the local tags only, the commit is made" '[ $RC = 0 ] && has "не удалось прочитать теги origin" && has "сравниваю только с локальными тегами" && release_commit 0.1.0 0.2.0'

# --- no release tags at all
new_case notags
g tag -d 0.1.0 >/dev/null
git -C "$CASE/origin.git" tag -d 0.1.0 >/dev/null
mr ''
check "no tags: says so, and that the current version is not released yet" 'has "Релизных тегов пока нет." && has "Тега 0.1.0 ещё нет: если коммит с этой версией уже в master на GitHub, релиз 0.1.0 выйдет и без новой версии." && has "Новая версия [0.2.0]: "'
mr '' 0.1.0 --yes
check "no tags: the current version itself is still refused" '[ $RC = 1 ] && has "версия 0.1.0 не выше текущей 0.1.0"'

# --- a dirty tree
new_case dirty
before=$(count)
echo change >>"$REPO/README"
mr '' --yes 0.2.0
check "a modified file is refused" '[ $RC = 1 ] && has "есть незакоммиченные изменения" && has " M README" && [ "$(ver)" = 0.1.0 ] && [ "$(count)" = "$before" ]'
check "...and stays modified" '[ "$(g status --porcelain)" = " M README" ]'
g checkout -q -- README
gradle 0.5.0 >"$REPO/$GF"
mr '' --yes 0.2.0
check "a modified app/build.gradle.kts is refused and left as it is" '[ $RC = 1 ] && has "есть незакоммиченные изменения" && [ "$(ver)" = 0.5.0 ] && [ "$(count)" = "$before" ]'
g checkout -q -- "$GF"
echo new >"$REPO/newfile"
g add newfile
mr '' --yes 0.2.0
check "a staged file is refused" '[ $RC = 1 ] && has "есть незакоммиченные изменения" && [ "$(ver)" = 0.1.0 ] && [ "$(count)" = "$before" ] && [ "$(g status --porcelain)" = "A  newfile" ]'
g reset -q
mkdir -p "$REPO/out"
echo log >"$REPO/out/build.log"
mr '' --yes 0.2.0
check "an untracked file is only a warning, and stays out of the commit" '[ $RC = 0 ] && has "Внимание: есть файлы не в git, в релиз они не попадут:" && has "  newfile" && release_commit 0.1.0 0.2.0 && [ "$(g status --porcelain)" = "?? newfile" ]'
check "...ignored files are not listed" '! has "build.log"'
new_case untracked
for i in 01 02 03 04 05 06 07 08 09 10 11 12; do echo x >"$REPO/f$i"; done
mr '' --yes 0.2.0
check "more than 10 untracked files: the first 10 and …" '[ $RC = 0 ] && has "  f10" && ! has "f11" && has "  …" && release_commit 0.1.0 0.2.0'

# --- git hooks: they run on the commit's own index, so what a pre-commit hook stages lands in the commit
# hook <commands>: REPO's pre-commit hook
hook() {
  printf '#!/bin/sh\n%s\n' "$1" >"$REPO/.git/hooks/pre-commit"
  chmod +x "$REPO/.git/hooks/pre-commit"
}
no_verify="git commit --no-verify -m 'feat: release - version 0.2.0' -- app/build.gradle.kts"
new_case hooknew
hook 'echo x >>other && git add other'
head=$(g rev-parse HEAD) origin=$(origin_ref master)
mr '' 0.2.0 --yes
check "a hook that stages a new file: the release commit is undone" '[ $RC = 1 ] && has "хук git (pre-commit или другой) изменил релизный коммит" && [ "$(g rev-parse HEAD)" = "$head" ] && [ "$(origin_ref master)" = "$origin" ]'
check "...it names the files the commit had and how to commit past the hook" 'has "  app/build.gradle.kts" && has "  other" && has "scripts/make-release.sh 0.2.0 --no-commit" && has "$no_verify"'
check "...app/build.gradle.kts is back in the tree and the index, the hook's file is left untracked" '[ "$(ver)" = 0.1.0 ] && gradle_is 0.1.0 && [ "$(g status --porcelain)" = "?? other" ]'
new_case hooktracked
hook 'echo x >>README && git add README'
head=$(g rev-parse HEAD)
mr '' 0.2.0 --yes
check "a hook that stages a tracked file: the commit is undone, the hook's change is left unstaged" '[ $RC = 1 ] && has "  README" && [ "$(g rev-parse HEAD)" = "$head" ] && [ "$(ver)" = 0.1.0 ] && [ "$(g status --porcelain)" = " M README" ]'
new_case hookversion
hook "sed 's/\"0.2.0\"/\"9.9.9\"/' $GF >.git/v && cat .git/v >$GF && git add $GF"
head=$(g rev-parse HEAD)
mr '' 0.2.0 --yes
check "a hook that rewrites the version in app/build.gradle.kts: the commit is undone" '[ $RC = 1 ] && has "изменил релизный коммит" && [ "$(g rev-parse HEAD)" = "$head" ] && gradle_is 0.1.0 && clean'
new_case hookline
hook "echo '// formatted' >>$GF && git add $GF"
head=$(g rev-parse HEAD)
mr '' 0.2.0 --yes
check "a hook that adds another line to app/build.gradle.kts: the commit is undone" '[ $RC = 1 ] && has "изменил релизный коммит" && has "  app/build.gradle.kts" && [ "$(g rev-parse HEAD)" = "$head" ] && gradle_is 0.1.0 && clean'
new_case hookfail
hook 'exit 1'
head=$(g rev-parse HEAD)
mr '' 0.2.0 --yes
check "a failing hook: no commit, app/build.gradle.kts is back" '[ $RC = 1 ] && has "git commit не удался, app/build.gradle.kts возвращён" && [ "$(g rev-parse HEAD)" = "$head" ] && gradle_is 0.1.0 && clean'
new_case hookok
hook "git add $GF"
before=$(count)
mr '' 0.2.0 --yes
check "a hook that stages only app/build.gradle.kts (a formatter) keeps the release commit" '[ $RC = 0 ] && [ "$(count)" = $((before + 1)) ] && release_commit 0.1.0 0.2.0 && clean'

# --- no push, ever
new_case nopush
origin=$(origin_ref master) tags=$(all_tags)
mr '\nд\n'
check "the commit stays local: no push question, origin and tags untouched" '[ $RC = 0 ] && release_commit 0.1.0 0.2.0 && [ "$(origin_ref master)" = "$origin" ] && [ "$(all_tags)" = "$tags" ] && ! has "Запуш"'
mr '' 0.3.0 --push
check "--push is not an option" '[ $RC = 2 ] && has "неизвестный параметр --push"'

# --- the confirmation, --no-commit, options
new_case decline
before=$(count)
mr '0.2.0\nнет\n'
check "a declined commit puts app/build.gradle.kts back" '[ $RC = 1 ] && has "Отменено: app/build.gradle.kts возвращён, коммита нет." && gradle_is 0.1.0 && [ "$(count)" = "$before" ] && clean'
mr '0.2.0\n'
check "end of input at the confirmation puts app/build.gradle.kts back" '[ $RC = 1 ] && has "ввод прерван, app/build.gradle.kts возвращён" && gradle_is 0.1.0 && [ "$(count)" = "$before" ] && clean'
mr '0.2.0\nда\n'
check "'да' confirms" '[ $RC = 0 ] && release_commit 0.1.0 0.2.0 && clean'
new_case nocommit
before=$(count)
mr '' 0.2.0 --no-commit
check "--no-commit writes app/build.gradle.kts and commits nothing" '[ $RC = 0 ] && gradle_is 0.2.0 && [ "$(count)" = "$before" ] && [ "$(g status --porcelain)" = " M app/build.gradle.kts" ] && has "Коммит не создан (--no-commit)"'
check "...the change is the version line only" '[ "$(g diff --numstat)" = "$(printf "1\t1\tapp/build.gradle.kts")" ]'
check "...shows the diff and the commit command" 'has "-val appVersionName = \"0.1.0\"" && has "+val appVersionName = \"0.2.0\"" && has "git commit -m '\''feat: release - version 0.2.0'\'' -- app/build.gradle.kts"'
g checkout -q -- "$GF"
mr '\n' --no-commit
check "--no-commit at the prompt: Enter writes 0.2.0, no confirmation" '[ $RC = 0 ] && gradle_is 0.2.0 && ! has "Закоммитить" && [ "$(count)" = "$before" ]'
g checkout -q -- "$GF"
mr '' --bogus
check "an unknown option is a usage error" '[ $RC = 2 ] && has "неизвестный параметр --bogus" && has "scripts/make-release.sh [X.Y.Z|patch|minor|major]"'
mr '' 0.2.0 0.3.0
check "two versions are a usage error" '[ $RC = 2 ] && has "версия указана дважды: 0.2.0 и 0.3.0" && gradle_is 0.1.0'
mr '' --help
check "--help prints the usage" '[ $RC = 0 ] && has "scripts/make-release.sh [X.Y.Z|patch|minor|major] [--yes] [--no-commit]" && ! has "Текущая версия"'
mr '' -h
check "-h prints the usage" '[ $RC = 0 ] && has "--no-commit   только записать версию"'
check "...options change nothing" 'gradle_is 0.1.0 && [ "$(count)" = "$before" ] && clean'

# --- branches
new_case branch
g checkout -q -b feature
origin=$(origin_ref master)
mr '' 0.2.0 --yes
check "another branch: a warning, then the commit on that branch" '[ $RC = 0 ] && has "вы на ветке feature, а релиз выходит из ветки по умолчанию master" && [ "$(g symbolic-ref --short HEAD)" = feature ] && release_commit 0.1.0 0.2.0 && [ "$(origin_ref master)" = "$origin" ]'
new_case detached
g checkout -q --detach
mr '' 0.2.0 --yes
check "a detached HEAD is refused" '[ $RC = 1 ] && has "detached HEAD" && gradle_is 0.1.0 && clean'
new_case behind
git clone -q "$CASE/origin.git" "$CASE/other"
echo other >>"$CASE/other/README"
git -C "$CASE/other" commit -q -am 'fix: other'
git -C "$CASE/other" push -q origin master
g fetch -q origin
origin=$(origin_ref master)
mr '' 0.2.0 --yes
check "a branch behind origin: a warning to pull first, the commit is still made locally" '[ $RC = 0 ] && has "ветка master отстаёт от origin/master на 1 коммит(ов)" && has "сначала git pull" && [ "$(origin_ref master)" = "$origin" ] && release_commit 0.1.0 0.2.0'

# --- no origin, no repository, the version line
new_case noorigin
g remote remove origin
before=$(count)
mr '' --yes
check "without origin: local tags count, the commit is made" '[ $RC = 0 ] && ! has "Внимание" && has "Последний релизный тег: 0.1.0" && release_commit 0.1.0 0.2.0 && [ "$(count)" = $((before + 1)) ]'
g branch -q -m master dev
mr '' --yes
check "neither origin nor master/main: a warning, the commit is made" '[ $RC = 0 ] && has "не удалось определить ветку по умолчанию" && release_commit 0.2.0 0.3.0'

# --- the next minor version of 0.999.0 is 0.1000.0, which does not fit versionCode: the suggestion is 1.0.0
new_case unfit
gradle 0.999.0 >"$REPO/$GF"
g commit -q -am 'feat: release - version 0.999.0'
before=$(count) head=$(g rev-parse HEAD)
# fits: the version in app/build.gradle.kts fits versionCode (Gradle builds it, release-state.sh accepts it)
fits() { "$BASH_BIN" -c '. "$0"; version_code "$1"' "$REPO/scripts/lib/version.sh" "$(ver)" >/dev/null; }
mr '\n\n'
check "prompt: only the bumps that fit versionCode are offered, Enter takes the major one" \
  'has "Следующая: 0.999.1 (patch), 1.0.0 (major)." && has "Новая версия [1.0.0]: " && ! has "0.1000.0" && [ $RC = 0 ] && release_commit 0.999.0 1.0.0'
g reset -q --hard "$head"
mr '0.1000.0\nminor\n1.0.0\nn\n'
check "prompt: 0.1000.0, typed or as minor, is refused" '[ $RC = 1 ] && [ "$(printf "%s\n" "$OUT" | grep -c "версия 0.1000.0 не помещается в versionCode")" = 2 ] && gradle_is 0.999.0 && clean'
mr '' --yes
check "--yes never writes a version that does not fit versionCode: it takes 1.0.0" \
  '[ $RC = 0 ] && fits && release_commit 0.999.0 1.0.0'
g reset -q --hard "$head"
mr '' patch --yes
check "...while patch, 0.999.1, fits and is committed" '[ $RC = 0 ] && release_commit 0.999.0 0.999.1'
g reset -q --hard "$head"
gradle 2100.0.0 >"$REPO/$GF"
g commit -q -am 'feat: release - version 2100.0.0'
before=$(count)
mr '' --yes
check "at 2100.0.0 no version fits versionCode any more: refused, nothing changed" \
  '[ $RC = 1 ] && has "после 2100.0.0 нет версии, которая помещается в versionCode" && gradle_is 2100.0.0 && [ "$(count)" = "$before" ] && clean'

# --- origin's tags cannot be read and the clone has none: no claims about tags
new_case notags-offline
g tag -d 0.1.0 >/dev/null
g remote set-url origin "$CASE/missing.git"
mr '0.2.0\n' --no-commit
check "unreadable origin and no local tags: a warning, no claims about tags or an unreleased version" \
  '[ $RC = 0 ] && has "не удалось прочитать теги origin" && has "Релизные теги неизвестны" && ! has "Релизных тегов пока нет" && ! has "ещё нет"'
g checkout -q -- .
g tag 0.1.0 HEAD~1
mr '0.2.0\n' --no-commit
check "unreadable origin with local tags: the last local tag, marked as such" \
  '[ $RC = 0 ] && has "Последний релизный тег: 0.1.0 (из локальных тегов)" && ! has "ещё нет"'

new_case ahead
gradle 0.3.0 >"$REPO/$GF"
g commit -q -am 'feat: release - version 0.3.0'
mr ''
check "a version above every tag: says it is not released yet and suggests the next minor version" 'has "Тега 0.3.0 ещё нет" && has "Новая версия [0.4.0]: "'
for c in 'x|«x»' '0.1000.0|«0.1000.0»' '1.2|«1.2»' 'v0.3.0|«v0.3.0»'; do
  gradle "${c%|*}" >"$REPO/$GF"
  g commit -q -am "broken ${c%|*}"
  mr '' --yes 0.4.0
  check "appVersionName '${c%|*}' is refused" '[ $RC = 1 ] && has "в app/build.gradle.kts appVersionName = ${c#*|}, а не X.Y.Z" && gradle_is "${c%|*}" && clean'
done
gradle 0.3.0 | sed '/^val appVersionName/d' >"$REPO/$GF"
g commit -q -am 'no version line'
cp "$REPO/$GF" "$WORK/before"
mr '' --yes 0.4.0
check "app/build.gradle.kts without a version line is refused" '[ $RC = 1 ] && has "в app/build.gradle.kts должна быть ровно одна строка val appVersionName = \"X.Y.Z\"" && cmp -s "$REPO/$GF" "$WORK/before" && clean'
{
  gradle 0.3.0
  echo 'val appVersionName = "0.3.0"'
} >"$REPO/$GF"
g commit -q -am 'two version lines'
cp "$REPO/$GF" "$WORK/before"
mr '' --yes 0.4.0
check "app/build.gradle.kts with two version lines is refused, even with one value" '[ $RC = 1 ] && has "должна быть ровно одна строка val appVersionName" && cmp -s "$REPO/$GF" "$WORK/before" && clean'

REPO="$WORK/plain"
mkdir -p "$REPO/scripts/lib" "$REPO/app"
cp "$TREE/scripts/make-release.sh" "$REPO/scripts/"
cp "$TREE/scripts/lib/version.sh" "$REPO/scripts/lib/"
gradle 0.1.0 >"$REPO/$GF"
mr '' 0.2.0 --yes
check "outside a git repository it is refused" '[ $RC = 1 ] && has "не git-репозиторий" && gradle_is 0.1.0'
mr '' --help
check "...--help works there too" '[ $RC = 0 ] && has "scripts/make-release.sh [X.Y.Z|patch|minor|major]"'
git -c init.defaultBranch=master init -q "$REPO"
mr '' 0.2.0 --yes
check "a repository without commits is refused" '[ $RC = 1 ] && has "в репозитории ещё нет коммитов" && gradle_is 0.1.0'
g add scripts
g commit -q -m scripts
mr '' 0.2.0 --yes
check "an app/build.gradle.kts that is not in git is refused" '[ $RC = 1 ] && has "app/build.gradle.kts не в git" && gradle_is 0.1.0 && [ "$(count)" = 1 ]'

REPO="$WORK/outer/sub"
mkdir -p "$REPO/scripts/lib" "$REPO/app"
cp "$TREE/scripts/make-release.sh" "$REPO/scripts/"
cp "$TREE/scripts/lib/version.sh" "$REPO/scripts/lib/"
gradle 0.1.0 >"$REPO/$GF"
git -c init.defaultBranch=master init -q "$WORK/outer"
git -C "$WORK/outer" add -A
git -C "$WORK/outer" commit -q -m init
mr '' 0.2.0 --yes
check "a script in a subdirectory of a repository is refused" '[ $RC = 1 ] && has "не корень git-репозитория" && gradle_is 0.1.0 && [ "$(git -C "$WORK/outer" rev-list --count HEAD)" = 1 ]'

echo "# $pass passed, $fail failed"
[ "$fail" = 0 ]
