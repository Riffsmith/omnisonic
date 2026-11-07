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
    line_numbers=$(grep -Einha -- "$patterns" "$path" 2>/dev/null | cut -d: -f1)
    rc=$?
    set -e
    if [ $rc -eq 0 ]; then
      status=1
      while IFS= read -r line_no; do
        if [ -n "$line_no" ]; then
          printf '%s:%s\n' "$path" "$line_no" >&2
        fi
      done <<< "$line_numbers"
    elif [ $rc -ge 2 ]; then
      echo "scan failed on: $path (grep exit $rc)" >&2
      status=1
    fi
  fi
done

if [ $status -ne 0 ]; then
  echo "commit rejected: studied-app reference found; rewrite the listed locations" >&2
fi
exit $status
