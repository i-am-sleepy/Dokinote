#!/usr/bin/env bash
# Wait for the GitHub Actions run of the current commit and print the result + the interesting log lines.
# Usage: GH_TOKEN=<token> tools/ci_wait.sh [branch]      (default: current branch; run after `git push`)
set -u
REPO="i-am-sleepy/Dokinote"
BR="${1:-$(git branch --show-current)}"
SHA="$(git rev-parse HEAD)"
api() { curl -s -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO$1"; }
ENC="$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=""))' "$BR")"
for i in $(seq 1 60); do
  r=$(api "/actions/runs?branch=$ENC&per_page=10" | python3 -c '
import sys,json
sha=sys.argv[1]; d=json.load(sys.stdin)
for w in d.get("workflow_runs",[]):
    if w["head_sha"]==sha:
        print(w["status"],w["conclusion"]); break
else: print("queued-or-missing none")' "$SHA")
  echo "$(date +%T) $r"
  case "$r" in completed*) break;; esac
  sleep 20
done
LOGB="ci-log-${BR//\//-}"
echo "== log from branch $LOGB"
curl -s -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw" \
  "https://api.github.com/repos/$REPO/contents/latest.txt?ref=$LOGB" | grep -E "^run:|error:|^e: |FAILED|BUILD|What went wrong|Could not" | head -30
