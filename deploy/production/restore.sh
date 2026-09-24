#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${ENV_FILE:-.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
BACKUP_DIR="${1:-}"

if [[ -z "$BACKUP_DIR" || ! -d "$BACKUP_DIR" ]]; then
  echo "Usage: CONFIRM_RESTORE=YES ./restore.sh <backup-directory>" >&2
  exit 1
fi
if [[ "${CONFIRM_RESTORE:-}" != "YES" ]]; then
  echo "Set CONFIRM_RESTORE=YES to restore. This replaces current database state." >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

(
  cd "$BACKUP_DIR"
  sha256sum -c SHA256SUMS
)

db_name="${POSTGRES_DB:-filmiqoo}"
db_user="${POSTGRES_USER:-filmiqoo}"

echo "Stopping API before restore..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" stop api

cleanup() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d api >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Restoring PostgreSQL backup..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  pg_restore \
    --clean \
    --if-exists \
    --exit-on-error \
    --no-owner \
    --no-privileges \
    --dbname="$db_name" \
    --username="$db_user" \
  < "$BACKUP_DIR/postgres.dump"

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d api

echo "Waiting for API readiness..."
for _ in $(seq 1 30); do
  if docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T api \
      wget -q -O /dev/null http://127.0.0.1:8080/readyz; then
    trap - EXIT
    echo "Restore completed and API is ready."
    exit 0
  fi
  sleep 2
done

echo "Restore completed but API did not become ready." >&2
exit 1
