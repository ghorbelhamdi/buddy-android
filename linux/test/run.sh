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

# Runs: the whole of a run, with a stand-in for Claude Code that ends badly. Whatever happens to the agent or to the
# helper, the chat must hear buddy_done (and why, when the agent said nothing), and soon.
for t in bash sleep wc pgrep tr readlink ln env date basename dirname cut awk ls touch chmod cp id; do
  p=$(command -v "$t") && ln -sf "$p" "$H/bin/$t"
done
mkdir -p "$H/.local/bin"
run() { # run <case> <seconds allowed> <what the stand-in does after its first line> → the events' types, in a line
  local name=$1 allowed=$2 ev="$H/events-$1" start=$SECONDS
  printf '#!%s\necho '"'"'{"type":"system","session_id":"abc"}'"'"'\n%s\n' "$H/bin/bash" "$3" > "$H/.local/bin/claude"
  chmod +x "$H/.local/bin/claude"
  : > "$ev"
  HOME=$H BUDDY_TEST_EVENTS=$ev bash ../buddy-run test ask "run$name" claude 0 hi >/dev/null 2>&1 &
  local pid=$!
  if [ "$name" = term ]; then sleep 2; kill -TERM "$pid"; fi
  wait "$pid" 2>/dev/null
  took=$((SECONDS - start))
  got=$(jq -r '.type + (if .quiet then ":quiet" else "" end)' "$ev" 2>/dev/null | paste -sd, -)
  n=$((n + 1))
  if [ "$got" = "$4" ] && [ "$took" -le "$allowed" ]; then echo "ok   run $name"
  else echo "FAIL run $name: got '$got' in ${took}s, expected '$4' within ${allowed}s"; failed=$((failed + 1)); fi
}
run exit3 20 'exit 3' 'system,buddy_error:quiet,buddy_done'
run killed 20 'kill -9 $$' 'system,buddy_error:quiet,buddy_done'
run said 20 'echo "no credit left" >&2; exit 1' 'system,buddy_error,buddy_done'
# what it started keeps its output open for a minute: the run ends when the agent does
run orphan 20 '(sleep 60 &) ; exit 0' 'system,buddy_done'
# the helper itself is stopped from outside mid-run
run term 20 'sleep 60' 'system,buddy_done'
echo "$n fixtures and runs, $failed failed"
[ "$n" -gt 0 ] && [ "$failed" -eq 0 ]
