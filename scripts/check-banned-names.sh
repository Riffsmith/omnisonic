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

# Import-boundary exception (D-76): exactly these paths may carry studied
# input-format string keys, because recognizing that input format is their
# job (the converter and its test/fixtures). Every other file is scanned for
# the studied key surface; the name-class scan below applies to all files,
# including the allowlisted ones.
key_allowlist=(
  "core-data/src/main/java/app/omnisonic/core/data/style/StyleConverter.kt"
  "core-data/src/test/java/app/omnisonic/core/data/style/StyleConverterTest.kt"
  "core-data/src/test/resources/style-samples/application-a.json"
  "core-data/src/test/resources/style-samples/application-b.json"
  "core-data/src/test/resources/style-samples/now-playing.json"
)

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
key_token_file=""
cleanup() {
  if [ -n "$key_token_file" ]; then rm -f "$key_token_file"; fi
}
trap cleanup EXIT

# Studied key tokens (fixed strings, one per line) come from
# OMNISONIC_BANNED_KEY_PATTERNS (CI secret), else git config
# omnisonic.bannedKeyPatterns, else the reference key index when present.
# Only dotted spellings are enforceable as fixed strings; the bare section
# markers are covered by the model naming test instead.
load_key_tokens() {
  local from_env="${OMNISONIC_BANNED_KEY_PATTERNS:-}"
  local from_config
  from_config="$(git config --get omnisonic.bannedKeyPatterns || true)"
  local candidate="$from_env"
  if [ -z "$candidate" ]; then
    candidate="$from_config"
  fi
  if [ -n "$candidate" ]; then
    key_token_file="$(mktemp)"
    printf '%s\n' "$candidate" | tr -d '\r' | sed '/^[[:space:]]*$/d' >"$key_token_file"
  elif [ -n "$repo_root" ] && [ -f "$repo_root/reference/extracted/theme/theme_keys.json" ]; then
    key_token_file="$(mktemp)"
    python3 -c '
import json, sys
for entry in json.load(open(sys.argv[1])):
    value = entry.get("string", "") if isinstance(entry, dict) else str(entry)
    if "." in value:
        print(value)
' "$repo_root/reference/extracted/theme/theme_keys.json" >"$key_token_file" 2>/dev/null || {
      rm -f "$key_token_file"
      key_token_file=""
      return
    }
  fi
  if [ -n "$key_token_file" ] && [ ! -s "$key_token_file" ]; then
    rm -f "$key_token_file"
    key_token_file=""
  fi
}

is_allowlisted() {
  local candidate="$1" allowed
  for allowed in "${key_allowlist[@]}"; do
    if [ "$candidate" = "$allowed" ]; then
      return 0
    fi
  done
  return 1
}

load_key_tokens

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
    if [ -n "$key_token_file" ] && ! is_allowlisted "${path#./}"; then
      set +e
      key_lines=$(grep -Fiahn -f "$key_token_file" -- "$path" 2>/dev/null | cut -d: -f1)
      krc=$?
      set -e
      if [ $krc -eq 0 ]; then
        status=1
        while IFS= read -r line_no; do
          if [ -n "$line_no" ]; then
            printf '%s:%s\n' "$path" "$line_no" >&2
          fi
        done <<< "$key_lines"
      elif [ $krc -ge 2 ]; then
        echo "scan failed on: $path (grep exit $krc)" >&2
        status=1
      fi
    fi
  fi
done

if [ $status -ne 0 ]; then
  echo "commit rejected: studied-app reference found; rewrite the listed locations" >&2
fi
exit $status
