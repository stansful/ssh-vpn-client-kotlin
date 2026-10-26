#!/usr/bin/env bash
# Static checks that the release pieces agree, after the reference project's assets_test.sh: release.yml publishes
# the files release_assets (scripts/lib/version.sh) lists, which scripts/build-release.sh builds; the release is
# tagged plain X.Y.Z with its usual title and made the latest (the in-app updater reads /releases/latest and parses
# tag_name as SemVer); the tag's commit is read one way (tag_commit); the draft is handled by the id the API
# returns; and both workflows build and test the way the docs say.
#
#   scripts/tests/workflows_test.sh
#
# Prints 'ok <name>' / 'not ok <name>: <why>' and exits non-zero on a failure.
# The patterns match the workflows' and scripts' own text, $VERSION and the like included.
# shellcheck disable=SC2016
set -u
# SIGPIPE ignored, as on the GitHub runner (every step inherits that from its Node.js), for these tests and the
# scripts they run: a reader that stops early makes its writer fail with "Broken pipe" there instead of dying quietly
trap "" PIPE

HERE="$(cd "$(dirname "$0")" && pwd -P)"
TREE="$(cd "$HERE/../.." && pwd -P)"
RELEASE="$TREE/.github/workflows/release.yml"
CI="$TREE/.github/workflows/ci.yml"
STATE="$TREE/.github/scripts/release-state.sh"
BUILD="$TREE/scripts/build-release.sh"

pass=0 fail=0
ok() {
  echo "ok $1"
  pass=$((pass + 1))
}
nok() {
  echo "not ok $1: $2"
  fail=$((fail + 1))
}
# check <name> <why> <command...>: ok when the command succeeds
check() {
  local name="$1" why="$2"
  shift 2
  if "$@"; then ok "$name"; else nok "$name" "$why"; fi
}
has() { grep -qF -- "$1" "$2"; }

# shellcheck source=scripts/lib/version.sh
. "$TREE/scripts/lib/version.sh"

check "build-release.sh builds the files release_assets lists" "build-release.sh does not take its list from release_assets" \
  has 'assets="$(release_assets "$version" ${no_xray:+--no-xray} |' "$BUILD"
check "release.yml checks the build against release_assets" "the build job compares the output with another list" \
  has 'expected="$(release_assets "$VERSION" | LC_ALL=C sort)"' "$RELEASE"
check "release.yml uploads exactly the release_assets files" "the publish step uploads another list" \
  has 'done < <(release_assets "$VERSION")' "$RELEASE"
assets="$(release_assets 1.2.3 | tr '\n' ' ')"
check "every release has the universal APK, an APK and an Xray file per ABI, and SHA256SUMS" "release_assets 1.2.3: $assets" \
  test "$assets" = "app-universal-release-1.2.3.apk app-arm64-v8a-release-1.2.3.apk app-armeabi-v7a-release-1.2.3.apk app-x86-release-1.2.3.apk app-x86_64-release-1.2.3.apk libXray-1.2.3-arm64-v8a.aar libXray-1.2.3-armeabi-v7a.aar libXray-1.2.3-x86.aar libXray-1.2.3-x86_64.aar SHA256SUMS "

if has '-f tag_name="$VERSION"' "$RELEASE" && ! grep -Eq 'v\$VERSION|v\$\{VERSION\}|tag_name="v' "$RELEASE"; then
  ok "the release and its tag are the plain version X.Y.Z"
else
  nok "the release and its tag are the plain version X.Y.Z" "release.yml creates or reads a vX.Y.Z tag"
fi
check "the release keeps its title ssh-vpn-client-kotlin-X.Y.Z-release" "another release title" \
  has '-f name="ssh-vpn-client-kotlin-$VERSION-release"' "$RELEASE"
check "the release is published as the latest one, which the in-app updater reads" "no make_latest=true on publish" \
  has '-F draft=false -f make_latest=true' "$RELEASE"
check "the release notes come from release-notes.sh" "the notes come from somewhere else" \
  has 'bash .github/scripts/release-notes.sh > "$RUNNER_TEMP/release-notes.md"' "$RELEASE"

# `git ls-remote` given a pattern leaves out an annotated tag's peeled line, so it reads the tag object, not the
# commit: an annotated tag release-state.sh accepts as retry would fail the publish job after publishing
if has 'tag_sha="$(git ls-remote --tags origin | tag_commit "$VERSION")"' "$RELEASE" &&
  has 'tag_sha="$(tag_commit "$VERSION" <<<"$refs")"' "$STATE" &&
  has 'refs="$(git -C "$ROOT_DIR" ls-remote --tags origin)"' "$STATE" &&
  ! grep -Eq 'ls-remote[^|]*refs/tags' "$RELEASE" "$STATE"; then
  ok "release.yml checks the tag's commit the way release-state.sh reads it (tag_commit over every tag)"
else
  nok "release.yml checks the tag's commit the way release-state.sh reads it" "a tag is read another way (ls-remote with a pattern?)"
fi

# The release list lags behind a draft created a moment ago: the publish step takes the draft's id from the create
# call and never looks the draft up by its tag
if has 'id="$(gh api --method POST "repos/$GH_REPO/releases"' "$RELEASE" && has '-F draft=true --jq .id)"' "$RELEASE" &&
  ! grep -Eq 'gh release (create|upload|edit)' "$RELEASE"; then
  ok "publish works with the id the API returns for the new draft, not a lookup by tag"
else
  nok "publish works with the id the API returns for the new draft" "release.yml creates or finds the draft another way"
fi

check "release.yml builds with scripts/build-release.sh" "the build job builds another way" \
  grep -q '^          scripts/build-release.sh --out "\$RUNNER_TEMP/out"$' "$RELEASE"
check "the CI's release build is scripts/build-release.sh without Xray" "ci.yml builds another way" \
  grep -q '^        run: scripts/build-release.sh --no-xray$' "$CI"
check "release.yml runs the whole CI (ci.yml) before it builds" "no workflow_call of ci.yml" \
  grep -q '^    uses: ./.github/workflows/ci.yml$' "$RELEASE"
check "...and the signed build needs it" "the build job does not need quality" \
  grep -q '^    needs: \[gate, quality, xray\]$' "$RELEASE"

# A push to master starts release.yml only (which calls ci.yml): no second CI run for the same push
if grep -A 2 '^  push:$' "$RELEASE" | grep -q '^      - master$' && grep -q '^    branches-ignore: \[master\]$' "$CI"; then
  ok "a push to master runs release.yml, ci.yml runs for the other branches and pull requests"
else
  nok "a push to master runs release.yml only" "release.yml must trigger on master, ci.yml must ignore master"
fi

# Robolectric 4.17 refuses the SDK 36 tests on Java 17; the release builds stay on the action's default 17
check "the CI's unit tests run on JDK 21" "the unit job does not ask the android-build action for Java 21" \
  grep -q '^          java-version: "21"$' "$CI"
check "the CI runs these tests" "ci.yml does not run scripts/tests/run.sh" \
  grep -q '^        run: scripts/tests/run.sh$' "$CI"

echo "# $pass passed, $fail failed"
[ "$fail" = 0 ]
