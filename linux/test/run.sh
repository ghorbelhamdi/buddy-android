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
  # (AGENT=copilot: a stand-in for Copilot instead, whose first line is its own)
  local name=$1 allowed=$2 ev="$H/events-$1" start=$SECONDS agent=${AGENT:-claude} first='{"type":"system","session_id":"abc"}'
  [ "$agent" = copilot ] && first='{"type":"session.start","data":{"sessionId":"abc"}}'
  printf '#!%s\necho '"'"'%s'"'"'\n%s\n' "$H/bin/bash" "$first" "$3" > "$H/.local/bin/$agent"
  chmod +x "$H/.local/bin/$agent"
  : > "$ev"
  HOME=$H BUDDY_TEST_EVENTS=$ev bash ../buddy-run test ask "run$name" "$agent" 0 hi >/dev/null 2>&1 &
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
AGENT=copilot run copilot-orphan 20 '(sleep 60 &) ; exit 0' 'system,buddy_done'
# the helper itself is stopped from outside mid-run
run term 20 'sleep 60' 'system,buddy_done'

# A chat's project folder: its agent starts there (made when it isn't there yet); a path that leaves the workspace,
# by "..", a slash in front or a link, counts for nothing and the agent starts in the workspace.
folder_run() { # folder_run <case> <folder> <where the stand-in must start, inside the workspace ("" = the workspace)>
  local ev="$H/events-$1" got want="$H/.buddy/work${3:+/$3}"
  printf '#!%s\necho '"'"'{"type":"system","session_id":"abc"}'"'"'\npwd > "%s/cwd"\n' "$H/bin/bash" "$H" > "$H/.local/bin/claude"
  chmod +x "$H/.local/bin/claude"
  : > "$ev"; rm -f "$H/cwd"
  HOME=$H BUDDY_TEST_EVENTS=$ev bash ../buddy-run test ask "runf$1" claude 0 hi "" "" "" "" "" "$2" >/dev/null 2>&1
  got=$(cat "$H/cwd" 2>/dev/null)
  n=$((n + 1))
  if [ "$got" = "$want" ]; then echo "ok   run $1"
  else echo "FAIL run $1: started in '$got', expected '$want'"; failed=$((failed + 1)); fi
}
for t in mkdir cat rm pwd; do p=$(command -v "$t") && ln -sf "$p" "$H/bin/$t"; done
folder_run folder-none '' ''
folder_run folder-project 'proj' 'proj'
folder_run folder-nested 'games/my maze' 'games/my maze'
folder_run folder-dotdot '../out' ''
folder_run folder-dotdot-inside 'proj/../../out' ''
folder_run folder-absolute '/etc' ''
mkdir -p "$H/elsewhere" "$H/.buddy/work" && ln -sfn "$H/elsewhere" "$H/.buddy/work/link"
folder_run folder-link 'link' ''
folder_run folder-under-link 'link/deep' ''

# Deleting an incognito chat forgets its agents' records: the id now and the ones before (.old), each file written
# without a line end
forget_run() {
  local S=$H/.buddy/sessions/forgetme
  mkdir -p "$S" "$H/.pi/agent/sessions/p" "$H/.copilot/session-state/c-now" "$H/.copilot/session-state/c-old"
  printf 'p-old\n' > "$S/pi-session.old"; printf 'p-now' > "$S/pi-session"
  printf 'c-old\n' > "$S/copilot-session.old"; printf 'c-now' > "$S/copilot-session"
  touch "$H/.pi/agent/sessions/p/1_p-now.jsonl" "$H/.pi/agent/sessions/p/2_p-old.jsonl"
  HOME=$H bash ../buddy-run test delete forgetme forget >/dev/null 2>&1
  local left
  left=$(ls "$H/.pi/agent/sessions/p" "$H/.copilot/session-state" 2>/dev/null | grep -c 'p-\|c-')
  n=$((n + 1))
  if [ "$left" = 0 ] && [ ! -d "$S" ]; then echo "ok   run forget"
  else echo "FAIL run forget: $left records left"; failed=$((failed + 1)); fi
}
for t in grep ls touch printf mkdir rm; do p=$(command -v "$t") && ln -sf "$p" "$H/bin/$t"; done
forget_run

# Pi on a service saved in Buddy: what run_pi hands it. A stand-in for Pi answers with what it was given: its --model,
# the providers in its folder's models.json, the relay's entry there and the wait for a local server. The user's own
# models.json keeps its provider and isn't touched, and a run without a service finds the user's files linked again.
mkdir -p "$H/.buddy/providers" "$H/.pi/agent"
echo '{"name":"Test","anthropic":false,"openai":"responses","local":true,"models":{"m1":{"name":"One","window":32768},"v:1":{"name":"","window":0,"image":true}}}' > "$H/.buddy/providers/test.json"
echo '{"name":"Ant","anthropic":true,"openai":"","local":false,"models":{"a1":{"name":"A","window":200000}}}' > "$H/.buddy/providers/ant.json"
echo '{"providers":{"mine":{"baseUrl":"http://127.0.0.1:1/v1"}}}' > "$H/.pi/agent/models.json"
echo '{"theme":"dark"}' > "$H/.pi/agent/settings.json"
cat > "$H/.local/bin/pi" <<EOF
#!$H/bin/bash
d=\$PI_CODING_AGENT_DIR
model=-
while [ \$# -gt 0 ]; do [ "\$1" = --model ] && model=\$2; shift; done
links=\$([ -L "\$d/models.json" ] && [ -L "\$d/settings.json" ] && echo linked || echo own)
said=\$(jq -rn --arg model "\$model" --arg links "\$links" --slurpfile m "\$d/models.json" --slurpfile s "\$d/settings.json" '
  (\$m[0].providers | to_entries | map(select(.key | startswith("buddy-")))[0].value // {}) as \$p
  | [\$model, \$links, (\$m[0].providers | keys | join(",")), (\$p.baseUrl // "-"), (\$p.api // "-"), (\$p.apiKey // "-"),
     ((\$p.models // []) | tojson), (\$s[0].httpIdleTimeoutMs // "-"), (\$s[0].theme // "-")] | join(" ")')
echo '{"type":"session","id":"abc"}'
jq -cn --arg t "\$said" '{type:"message_end", message:{role:"assistant", stopReason:"stop", content:[{type:"text", text:\$t}]}}'
echo '{"type":"agent_settled"}'
EOF
chmod +x "$H/.local/bin/pi"
pi_run() { # pi_run <case> <model> <what the stand-in must say>
  local ev="$H/events-$1" got
  : > "$ev"
  HOME=$H BUDDY_TEST_EVENTS=$ev bash ../buddy-run test ask runpi pi 0 hi "$2" >/dev/null 2>&1
  got=$(jq -r 'select(.type == "assistant") | .message.content[0].text' "$ev" 2>/dev/null)
  n=$((n + 1))
  if [ "$got" = "$3" ]; then echo "ok   run $1"
  else echo "FAIL run $1: got '$got', expected '$3'"; failed=$((failed + 1)); fi
}
before=$(cat "$H/.pi/agent/models.json" "$H/.pi/agent/settings.json")
pi_run pi-service '@test/v:1' 'buddy-test/v:1 own buddy-test,mine http://127.0.0.1:8765/llm/test/o openai-completions $BUDDY_TOKEN [{"id":"m1","name":"One","contextWindow":32768,"maxTokens":8192},{"id":"v:1","name":"v:1","input":["text","image"]}] 900000 dark'
pi_run pi-service-typed '@ant/typed' 'buddy-ant/typed own buddy-ant,mine http://127.0.0.1:8765/llm/ant/a anthropic-messages $BUDDY_TOKEN [{"id":"a1","name":"A","contextWindow":200000,"maxTokens":16384},{"id":"typed","name":"typed"}] - dark'
pi_run pi-own-model 'mine/own' 'mine/own linked mine - - - [] - dark'
n=$((n + 1))
if [ "$before" = "$(cat "$H/.pi/agent/models.json" "$H/.pi/agent/settings.json")" ]; then echo "ok   run pi-user-files"
else echo "FAIL run pi-user-files: the user's own models.json or settings.json changed"; failed=$((failed + 1)); fi
echo "$n fixtures and runs, $failed failed"
[ "$n" -gt 0 ] && [ "$failed" -eq 0 ]
