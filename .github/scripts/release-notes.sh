#!/usr/bin/env bash
# Prints the notes of release $VERSION: the commit subjects since the previous release tag (the highest X.Y.Z or
# vX.Y.Z tag in the history of $GITHUB_SHA other than $VERSION), newest first, one "- " line each, without merges
# and without the release commits of scripts/make-release.sh. The in-app update sheet shows these lines as a list.
#
# Env: GITHUB_SHA (default HEAD), VERSION (default: app/build.gradle.kts).
# Reads the tags and the history of this checkout (release.yml fetches them all).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$HERE/../.." && pwd -P)"
# shellcheck source=scripts/lib/version.sh
. "$ROOT_DIR/scripts/lib/version.sh"

sha="${GITHUB_SHA:-HEAD}"
VERSION="${VERSION:-$(app_version_get "$ROOT_DIR" || true)}"
version_valid "$VERSION" || {
  echo "release-notes: «$VERSION» is not X.Y.Z" >&2
  exit 1
}

prev="$(git -C "$ROOT_DIR" tag --merged "$sha" | highest_tag "$VERSION")"
prev=${prev#* }
changes="$(git -C "$ROOT_DIR" log --no-merges --max-count=200 --pretty=format:'- %s' "${prev:+$prev..}$sha" |
  { grep -v '^- feat: release - version [0-9]' || true; })"

printf '%s\n' "${changes:-- Release $VERSION}"
