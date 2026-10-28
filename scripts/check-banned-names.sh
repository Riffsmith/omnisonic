#!/usr/bin/env bash
set -euo pipefail

if [ $# -eq 0 ]; then
  echo "usage: $(basename "$0") <file...>" >&2
  exit 2
fi

patterns="${OMNISONIC_BANNED_PATTERNS:-$(git config --get omnisonic.bannedPatterns || true)}"

if [ -z "$patterns" ]; then
  echo "error: no banned-name patterns configured" >&2
  echo "  configure once, outside the repo:" >&2
  echo "    git config omnisonic.bannedPatterns '<regex1>|<regex2>'" >&2
  echo "  or, in CI, set the OMNISONIC_BANNED_PATTERNS environment variable" >&2
  exit 1
fi

status=0
for path in "$@"; do
  case "$path" in
    .gitignore) continue ;;
  esac
  if [ -f "$path" ]; then
    set +e
    matches=$(grep -Ein -- "$patterns" "$path" 2>/dev/null)
    rc=$?
    set -e
    if [ $rc -eq 0 ]; then
      echo "banned pattern in: $path" >&2
      printf '%s\n' "$matches" | sed 's/^/    /' >&2
      status=1
    elif [ $rc -ge 2 ]; then
      echo "scan failed on: $path (grep exit $rc)" >&2
      status=1
    fi
  fi
done

if [ $status -ne 0 ]; then
  echo "commit rejected: studied-app reference found; rewrite the lines above" >&2
fi
exit $status
