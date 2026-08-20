#!/usr/bin/env bash
#
# Guard against swallowed coroutine cancellation.
#
# CancellationException is an Exception, so `catch (e: Exception)` inside suspend code
# silently defeats cooperative cancellation: a cleared ViewModel keeps publishing state,
# a cancelled tunnel loop keeps writing to a closed TUN.
#
# Heuristic: any Kotlin file that both (a) contains coroutine code and (b) catches a broad
# Exception must also mention CancellationException. That is cheap, has no false negatives
# for the regression we care about, and needs no compiler.
#
# Files that legitimately do not need it (thread-based, no suspension points) are listed
# in ALLOWLIST below with a reason.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="$ROOT_DIR/app/src/main/java"

# path-suffix : reason
ALLOWLIST=(
  # Pure Thread/Executor based TUN forwarder: contains no suspend functions, so coroutine
  # cancellation never propagates into its catch blocks. Revisit if it gains `suspend`.
  "vpn/KotlinTunForwarder.kt"
)

is_allowlisted() {
  local file="$1"
  local entry
  for entry in "${ALLOWLIST[@]}"; do
    if [[ "$file" == *"$entry" ]]; then
      return 0
    fi
  done
  return 1
}

violations=()

while IFS= read -r file; do
  # (a) does this file run coroutine code?
  if ! grep -qE 'suspend fun|viewModelScope|CoroutineScope|withContext|\.launch \{|\.launch\(' "$file"; then
    continue
  fi

  # (b) does it catch a broad Exception?
  if ! grep -qE 'catch \([^)]*: *Exception\)' "$file"; then
    continue
  fi

  if grep -q 'CancellationException' "$file"; then
    continue
  fi

  if is_allowlisted "$file"; then
    continue
  fi

  violations+=("$file")
done < <(find "$SOURCE_DIR" -name '*.kt' | sort)

if ((${#violations[@]} > 0)); then
  echo "Broad 'catch (e: Exception)' in coroutine code without CancellationException handling:" >&2
  printf '  %s\n' "${violations[@]}" >&2
  cat >&2 <<'EOF'

Fix by rethrowing cancellation before the broad catch:

    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        ...
    }

If the file genuinely cannot be cancelled (no suspension points), add it to ALLOWLIST
in scripts/check-cancellation.sh together with the reason.
EOF
  exit 1
fi

echo "check-cancellation: OK (no swallowed coroutine cancellation found)"
