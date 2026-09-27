#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEPLOY_DIR/../.." && pwd)"
LOCK_FILE="/var/lock/filmiqoo-auto-update.lock"
GITHUB_REPO="Awmirai/filmiqoo"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Auto-update must run as root."
  exit 1
fi

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "Another Filmiqoo auto-update is already running."
  exit 0
fi

cd "$REPO_DIR"
git fetch --quiet origin main

LOCAL_SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git rev-parse origin/main)"

if [[ "$LOCAL_SHA" == "$REMOTE_SHA" ]]; then
  exit 0
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Refusing auto-update: repository worktree is not clean."
  git status --short
  exit 2
fi

if ! git merge-base --is-ancestor "$LOCAL_SHA" "$REMOTE_SHA"; then
  echo "Refusing auto-update: local main is not a fast-forward ancestor of origin/main."
  exit 3
fi

CHANGED_FILES="$(git diff --name-only "$LOCAL_SHA..$REMOTE_SHA")"
BACKEND_CHANGED=0
PRODUCTION_CHANGED=0
if grep -q '^backend/' <<<"$CHANGED_FILES"; then BACKEND_CHANGED=1; fi
if grep -q '^deploy/production/' <<<"$CHANGED_FILES"; then PRODUCTION_CHANGED=1; fi

check_workflow() {
  local workflow_name="$1"
  local result
  result="$(python3 - "$GITHUB_REPO" "$REMOTE_SHA" "$workflow_name" <<'PY'
import json, sys, urllib.request
repo, sha, wanted = sys.argv[1:]
url = f"https://api.github.com/repos/{repo}/actions/runs?head_sha={sha}&per_page=100"
req = urllib.request.Request(
    url,
    headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "filmiqoo-production-auto-update",
    },
)
with urllib.request.urlopen(req, timeout=15) as response:
    data = json.load(response)
for run in data.get("workflow_runs", []):
    if run.get("name") == wanted and run.get("event") == "push":
        print(f"{run.get('status')}:{run.get('conclusion') or ''}")
        break
else:
    print("missing:")
PY
)"
  case "$result" in
    completed:success) return 0 ;;
    queued:*|in_progress:*|waiting:*|requested:*|pending:*|missing:*)
      echo "Deferring $REMOTE_SHA: workflow '$workflow_name' is not completed successfully yet ($result)."
      return 10
      ;;
    *)
      echo "Blocking $REMOTE_SHA: workflow '$workflow_name' did not succeed ($result)."
      return 11
      ;;
  esac
}

if [[ "$BACKEND_CHANGED" -eq 1 ]]; then
  check_workflow "Backend CI" || exit 0
fi
if [[ "$BACKEND_CHANGED" -eq 1 || "$PRODUCTION_CHANGED" -eq 1 ]]; then
  check_workflow "Production Contract Checks" || exit 0
fi

echo "Updating Filmiqoo source: $LOCAL_SHA -> $REMOTE_SHA"

if [[ "$BACKEND_CHANGED" -eq 1 ]]; then
  if [[ ! -s "$DEPLOY_DIR/backup-password.txt" ]]; then
    echo "Refusing backend auto-deploy: missing $DEPLOY_DIR/backup-password.txt"
    exit 4
  fi
  echo "Creating verified encrypted database backup before backend deploy..."
  (cd "$DEPLOY_DIR" && bash ./backup.sh)
fi

git merge --ff-only "$REMOTE_SHA"

if [[ "$BACKEND_CHANGED" -eq 1 ]]; then
  echo "Deploying backend at $REMOTE_SHA..."
  if ! bash "$DEPLOY_DIR/update-api.sh"; then
    echo "Backend deploy failed; restoring repository checkout to $LOCAL_SHA."
    git reset --hard "$LOCAL_SHA"
    exit 5
  fi
else
  echo "No backend changes; source synchronized without API restart."
fi

if [[ "$PRODUCTION_CHANGED" -eq 1 && "$BACKEND_CHANGED" -eq 0 ]]; then
  echo "Production deployment files changed; source was synchronized, but infrastructure services were not restarted automatically."
fi

echo "Filmiqoo auto-update complete at $(git rev-parse HEAD)."
