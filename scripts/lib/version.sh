# shellcheck shell=bash
# Release versions and release files, shared by scripts/make-release.sh, scripts/build-release.sh,
# scripts/lib/package-xray-core-assets.sh, .github/scripts/*.sh and .github/workflows/release.yml (sourced, not run).
#
# A version is X.Y.Z: three decimal numbers without leading zeros (0 itself is fine). It lives in app/build.gradle.kts
# as the line `val appVersionName = "X.Y.Z"`, from which Gradle derives versionCode X*1000000 + Y*1000 + Z. It is
# also the release's tag (no "v") and part of every APK and Xray asset name. Tags vX.Y.Z count as version X.Y.Z.
# bash 3.2 (macOS) and 5 (Linux), BSD or GNU tools; no sort -V, which BSD sort lacks.

VERSION_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

# The file with the version, relative to the repository root
APP_VERSION_FILE=app/build.gradle.kts
# Its version line, an awk regex (no character classes: mawk, Ubuntu's awk, may lack them); app_version_get and
# app_version_set accept exactly one such line
APP_VERSION_LINE_RE='^[ \t]*val[ \t]+appVersionName[ \t]*=[ \t]*"[^"]*"[ \t]*$'

# The ABIs of the split APKs (app/build.gradle.kts, splits.abi) and of the Xray assets
# (scripts/lib/package-xray-core-assets.sh); the release also has app-universal-release-X.Y.Z.apk
RELEASE_ABIS="arm64-v8a armeabi-v7a x86 x86_64"

# version_valid <s>: whether s is X.Y.Z
version_valid() {
  [[ $1 =~ $VERSION_RE ]]
}

# version_cmp <a> <b>: prints -1, 0 or 1 for a < b, a = b, a > b (both valid). Numbers of any length: without
# leading zeros the longer number is the larger one, and numbers of one length compare as strings.
version_cmp() {
  local a="$1." b="$2." x y
  while [ -n "$a" ]; do
    x=${a%%.*} y=${b%%.*}
    a=${a#*.} b=${b#*.}
    [ "$x" = "$y" ] && continue
    if [ ${#x} -gt ${#y} ] || { [ ${#x} -eq ${#y} ] && [[ $x > $y ]]; }; then
      echo 1
    else
      echo -1
    fi
    return 0
  done
  echo 0
}

# version_gt <a> <b>: whether a > b
version_gt() {
  [ "$(version_cmp "$1" "$2")" = 1 ]
}

# version_bump <X.Y.Z> <patch|minor|major>: prints X.Y.(Z+1), X.(Y+1).0 or (X+1).0.0
version_bump() {
  local x y z
  x=${1%%.*} z=${1##*.} y=${1#*.}
  y=${y%.*}
  case $2 in
  patch) echo "$x.$y.$((z + 1))" ;;
  minor) echo "$x.$((y + 1)).0" ;;
  major) echo "$((x + 1)).0.0" ;;
  *) return 1 ;;
  esac
}

# version_code <X.Y.Z>: prints the versionCode app/build.gradle.kts derives from the version, X*1000000 + Y*1000 + Z;
# fails when Y or Z is above 999 (Gradle refuses it) or the code is above 2100000000 (Android's limit)
version_code() {
  local x y z code
  version_valid "$1" || return 1
  x=${1%%.*} z=${1##*.} y=${1#*.}
  y=${y%.*}
  [ ${#x} -le 4 ] && [ ${#y} -le 3 ] && [ ${#z} -le 3 ] || return 1
  code=$((x * 1000000 + y * 1000 + z))
  [ "$code" -le 2100000000 ] || return 1
  echo "$code"
}

# app_version_parse: reads app/build.gradle.kts on stdin and prints the value of its version line; fails, printing
# nothing, unless there is exactly one version line. The value is not checked: see version_valid.
app_version_parse() {
  awk -v re="$APP_VERSION_LINE_RE" '
    $0 ~ re { n++; v = $0; sub(/^[^"]*"/, "", v); sub(/".*$/, "", v) }
    END { if (n != 1) exit 1; print v }'
}

# app_version_get <root>: app_version_parse of <root>/app/build.gradle.kts
app_version_get() {
  app_version_parse <"$1/$APP_VERSION_FILE"
}

# app_version_set <root> <X.Y.Z>: writes the version into the version line of <root>/app/build.gradle.kts, in place
# (the file keeps its inode and mode); fails, changing nothing, unless the file has exactly one version line
app_version_set() {
  local file="$1/$APP_VERSION_FILE" tmp
  version_valid "$2" || return 1
  app_version_parse <"$file" >/dev/null || return 1
  tmp="$(mktemp "${TMPDIR:-/tmp}/app-version.XXXXXX")" || return 1
  if awk -v re="$APP_VERSION_LINE_RE" -v v="$2" '
    $0 ~ re { sub(/"[^"]*"/, "\"" v "\"") }
    { print }' "$file" >"$tmp" && cat "$tmp" >"$file"; then
    rm -f "$tmp"
    return 0
  fi
  rm -f "$tmp"
  return 1
}

# release_assets <X.Y.Z> [--no-xray]: prints the names of the files release X.Y.Z publishes, one per line, in
# upload order: the universal and split APKs, the Xray assets (not with --no-xray), SHA256SUMS over all the others
release_assets() {
  local abi
  echo "app-universal-release-$1.apk"
  for abi in $RELEASE_ABIS; do
    echo "app-$abi-release-$1.apk"
  done
  if [ "${2:-}" != --no-xray ]; then
    for abi in $RELEASE_ABIS; do
      echo "libXray-$1-$abi.aar"
    done
  fi
  echo SHA256SUMS
}

# release_tags: reads tag names, or the lines of `git ls-remote --tags` (refs/tags/<tag>, <tag>^{}), on stdin
# and prints "<X.Y.Z> <tag>" for every release tag (X.Y.Z or vX.Y.Z), once per tag
release_tags() {
  local line ref v seen=' '
  while IFS= read -r line || [ -n "$line" ]; do
    ref=${line##*[[:space:]]}
    ref=${ref#refs/tags/}
    ref=${ref%'^{}'}
    v=${ref#v}
    version_valid "$v" || continue
    case $seen in *" $ref "*) continue ;; esac
    seen="$seen$ref "
    echo "$v $ref"
  done
}

# highest_tag [skip]: reads what release_tags reads and prints "<X.Y.Z> <tag>" of the highest release tag other
# than the tag named skip; nothing when there is none
highest_tag() {
  local skip="${1:-}" v t best='' best_tag=''
  while read -r v t; do
    [ "$t" = "$skip" ] && continue
    if [ -z "$best" ] || version_gt "$v" "$best"; then
      best=$v best_tag=$t
    fi
  done < <(release_tags)
  [ -z "$best" ] || echo "$best $best_tag"
}

# tag_of_version <X.Y.Z>: reads what release_tags reads and prints the first tag of that version (X.Y.Z or
# vX.Y.Z); nothing when there is none
tag_of_version() {
  local v t
  while read -r v t; do
    if [ "$v" = "$1" ]; then
      echo "$t"
      return 0
    fi
  done < <(release_tags)
}

# tag_commit <tag>: reads the lines of `git ls-remote --tags <remote>` (every tag: given a pattern, ls-remote leaves
# out an annotated tag's peeled line) on stdin and prints the commit refs/tags/<tag> is on: the peeled "<tag>^{}"
# line of an annotated tag, else the tag's own line; nothing when there is no such tag
tag_commit() {
  awk -v ref="refs/tags/$1" '
    $2 == ref { sha = $1 }
    $2 == ref "^{}" { peeled = $1 }
    END { if (peeled != "") print peeled; else if (sha != "") print sha }'
}
