#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${ENV_FILE:-.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"

new_image="${1:-}"
new_commit="${2:-}"
new_version="${3:-}"

if [[ -z "$new_image" || -z "$new_commit" || -z "$new_version" ]]; then
  echo "Usage: ./rollback.sh <immutable-api-image> <commit-sha> <version>" >&2
  exit 1
fi
if [[ "$new_image" != *":"* || "$new_image" == *":release" || "$new_image" == *":latest" ]]; then
  echo "Rollback requires an immutable image tag, not release/latest." >&2
  exit 1
fi

cp "$ENV_FILE" "$ENV_FILE.rollback.bak"
restore_previous() {
  mv "$ENV_FILE.rollback.bak" "$ENV_FILE"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d api >/dev/null 2>&1 || true
}
trap restore_previous ERR

python3 - "$ENV_FILE" "$new_image" "$new_commit" "$new_version" <<'PY'
import pathlib, sys
path=pathlib.Path(sys.argv[1])
updates={
    "FILMIQOO_API_IMAGE":sys.argv[2],
    "FILMIQOO_COMMIT":sys.argv[3],
    "FILMIQOO_VERSION":sys.argv[4],
}
lines=path.read_text().splitlines()
seen=set()
out=[]
for line in lines:
    key=line.split("=",1)[0] if "=" in line else ""
    if key in updates:
        out.append(f"{key}={updates[key]}")
        seen.add(key)
    else:
        out.append(line)
for key,value in updates.items():
    if key not in seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out)+"\n")
PY

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull api
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d api

for _ in $(seq 1 30); do
  if docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T api \
      wget -q -O /dev/null http://127.0.0.1:8080/readyz; then
    rm -f "$ENV_FILE.rollback.bak"
    trap - ERR
    echo "Rollback/deploy succeeded: $new_commit ($new_version)"
    exit 0
  fi
  sleep 2
done

echo "New image failed readiness; restoring previous deployment." >&2
exit 1
