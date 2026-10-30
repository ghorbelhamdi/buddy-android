#!/usr/bin/env bash
# The helper's translation of each agent's output, tested on real recorded runs: every fixtures/<agent>/<case>_<cli
# version>.jsonl goes through buddy-run's stream_<agent>, and its events must match <case>_<cli version>.expected.jsonl
# (compared as sorted JSON). When an agent update changes its output, add the new version's recording next to the old
# one rather than replacing it, as long as Buddy supports both.
#   run.sh            run every fixture
#   run.sh --update   rewrite the expected files from the current helper (check the diff before committing)
set -u
cd "$(dirname "$0")" || exit 2
H=$(mktemp -d)
trap 'rm -rf "$H"' EXIT
# buddy-run sets its own PATH (Alpine's), which has $HOME/bin first: the tools it uses go there, wherever they are here
mkdir -p "$H/bin"
for t in jq cat sed sort paste find rm mv mkdir tail head grep; do
  p=$(command -v "$t") && ln -s "$p" "$H/bin/$t"
done
update=${1:-}
n=0 failed=0
for f in fixtures/*/*.jsonl; do
  case $f in *.expected.jsonl) continue ;; esac
  agent=$(basename "$(dirname "$f")")
  exp=${f%.jsonl}.expected.jsonl
  got=$(HOME=$H BUDDY_TEST_STREAM=$agent bash ../buddy-run test ask fixture "$agent" 0 x <"$f" | jq -S -c .)
  rm -rf "$H/.buddy" "$H/.codex"
  n=$((n + 1))
  if [ "$update" = --update ]; then
    printf '%s\n' "$got" >"$exp"
    echo "updated $exp"
  elif [ ! -f "$exp" ]; then
    echo "MISSING $exp (run.sh --update writes it)"
    failed=$((failed + 1))
  elif ! diff -u <(jq -S -c . "$exp") <(printf '%s\n' "$got") >"$H/diff"; then
    echo "FAIL $f"
    cat "$H/diff"
    failed=$((failed + 1))
  else
    echo "ok   $f"
  fi
done
echo "$n fixtures, $failed failed"
[ "$n" -gt 0 ] && [ "$failed" -eq 0 ]
