#!/usr/bin/env bash
set -euo pipefail

# Self-test for scripts/check-banned-names.sh.
#
# Pins the output contract required by the clean-room rules: a hit is reported
# as one "path:line" line and nothing else about it — the matched content and
# the configured pattern itself must never reach stdout or stderr, so hook and
# CI logs stay clean of banned strings.
#
# Runs against "$OMNISONIC_SCANNER_UNDER_TEST" when set (default: the sibling
# check-banned-names.sh). Uses placeholder fixture tokens, never the real
# pattern values.

scanner="${OMNISONIC_SCANNER_UNDER_TEST:-$(cd "$(dirname "$0")" && pwd)/check-banned-names.sh}"

token="XXBANMEXX_TOKEN"
other="XXBANMEXX_OTHER"
pattern="${token}|${other}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

out="$work/out.txt"
rc=0
checks=0
fails=0

# Every invocation goes through here: combined stdout+stderr, captured rc.
scan() {
  set +e
  if [ -n "${SCAN_CWD:-}" ]; then
    ( cd "$SCAN_CWD" && "$scanner" "$@" >"$out" 2>&1 )
  else
    "$scanner" "$@" >"$out" 2>&1
  fi
  rc=$?
  set -e
}

leak_free() {
  ! grep -Fq -- "$token" "$out" && ! grep -Fq -- "$other" "$out" && ! grep -Fq -- "$pattern" "$out"
}

lacks() { ! grep -Fq -- "$1" "$out"; }

pass() { echo "ok: $1"; }
fail() {
  echo "FAIL: $1" >&2
  if [ -s "$out" ]; then
    echo "captured output (fixture tokens redacted):" >&2
    sed -e "s/$token/[REDACTED-TOKEN]/g" -e "s/$other/[REDACTED-OTHER]/g" "$out" | sed 's/^/    | /' >&2
  fi
  fails=$((fails + 1))
}

# check "<description>" <command...> — passes when the command succeeds.
check() {
  local desc="$1"
  shift
  checks=$((checks + 1))
  if "$@"; then pass "$desc"; else fail "$desc"; fi
}

export OMNISONIC_BANNED_PATTERNS="$pattern"

# 1. A poisoned file exits 1 and reports each hit as path:line.
a="$work/a.txt"
printf 'clean 1\nclean 2\nhas %s on 3\nclean 4\nclean 5\nclean 6\nhas %s on 7\n' "$token" "$token" >"$a"
scan "$a"
check "poisoned file exits 1" test "$rc" -eq 1
check "reports a.txt:3" grep -Fq -- "$a:3" "$out"
check "reports a.txt:7" grep -Fq -- "$a:7" "$out"
check "no matched content or pattern in output (poisoned file)" leak_free

# 2. A clean file exits 0 with no output at all.
b="$work/b.txt"
printf 'nothing to see here\n' >"$b"
scan "$b"
check "clean file exits 0" test "$rc" -eq 0
check "clean file produces no output" test ! -s "$out"

# 3. Several operands: hits listed per file, clean files never mentioned.
c1="$work/c1.txt"; c2="$work/c2.txt"; c3="$work/c3.txt"
printf 'ok\n%s\n' "$token" >"$c1"
printf 'ok\n' >"$c2"
printf '%s here\n%s there\n' "$other" "$other" >"$c3"
scan "$c1" "$c2" "$c3"
check "multiple operands exit 1" test "$rc" -eq 1
check "reports c1.txt:2" grep -Fq -- "$c1:2" "$out"
check "clean operand is not mentioned" lacks "$c2"
check "reports c3.txt:1" grep -Fq -- "$c3:1" "$out"
check "reports c3.txt:2" grep -Fq -- "$c3:2" "$out"
check "no matched content or pattern in output (multiple operands)" leak_free

# 4. No arguments: usage error, exit 2.
scan
check "no arguments exit 2" test "$rc" -eq 2
check "usage message on stderr" grep -q '^usage:' "$out"

# 5. No patterns configured: exit 1 with the configuration hint.
unset OMNISONIC_BANNED_PATTERNS
SCAN_CWD="$work"
scan "$b"
check "no configured patterns exit 1" test "$rc" -eq 1
check "configuration hint on stderr" grep -q 'no banned-name patterns configured' "$out"
export OMNISONIC_BANNED_PATTERNS="$pattern"
SCAN_CWD=""

# 6. Directories and .gitignore operands are skipped silently.
mkdir -p "$work/dir"
printf '%s inside a directory\n' "$token" >"$work/dir/nested.txt"
printf 'pattern %s in gitignore\n' "$token" >"$work/.gitignore"
SCAN_CWD="$work"
scan .gitignore dir
check "directory and .gitignore operands are skipped" test "$rc" -eq 0
check "skipped operands produce no output" test ! -s "$out"
SCAN_CWD=""

# 7. A binary operand reports path:line without content.
bin="$work/b.bin"
printf 'AA\000BB %s CC\000DD' "$token" >"$bin"
scan "$bin"
check "binary file with a hit exits 1" test "$rc" -eq 1
check "reports the binary hit as path:line" grep -Fq -- "$bin:1" "$out"
check "no matched content or pattern in output (binary file)" leak_free

# 8. An invalid regex reports a scan failure and never echoes the pattern.
saved="$OMNISONIC_BANNED_PATTERNS"
OMNISONIC_BANNED_PATTERNS="${token}("
scan "$b"
OMNISONIC_BANNED_PATTERNS="$saved"
check "invalid regex exits 1" test "$rc" -eq 1
check "scan failure is reported" grep -q 'scan failed on:' "$out"
check "the invalid pattern itself is not echoed" lacks "${token}("

echo "scanner self-test: $checks checks, $fails failures (scanner: $scanner)"
[ "$fails" -eq 0 ]
