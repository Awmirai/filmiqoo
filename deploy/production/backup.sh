#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${ENV_FILE:-.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
BACKUP_ROOT="${BACKUP_ROOT:-./backups}"
BACKUP_PASSWORD_FILE="${BACKUP_PASSWORD_FILE:-./backup-password.txt}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi
if [[ ! -s "$BACKUP_PASSWORD_FILE" ]]; then
  echo "Missing non-empty BACKUP_PASSWORD_FILE: $BACKUP_PASSWORD_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="$BACKUP_ROOT/$timestamp"
mkdir -p "$backup_dir"
chmod 700 "$BACKUP_ROOT" "$backup_dir"

db_name="${POSTGRES_DB:-filmiqoo}"
db_user="${POSTGRES_USER:-filmiqoo}"

echo "Creating encrypted PostgreSQL backup..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump \
    --format=custom \
    --compress=9 \
    --no-owner \
    --no-privileges \
    --dbname="$db_name" \
    --username="$db_user" \
  | openssl enc -aes-256-cbc -pbkdf2 -salt \
      -pass "file:$BACKUP_PASSWORD_FILE" \
      -out "$backup_dir/postgres.dump.enc"

test -s "$backup_dir/postgres.dump.enc"

openssl enc -d -aes-256-cbc -pbkdf2 \
  -pass "file:$BACKUP_PASSWORD_FILE" \
  -in "$backup_dir/postgres.dump.enc" \
  | docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
      pg_restore --list \
  > "$backup_dir/postgres.contents.txt"

cat > "$backup_dir/manifest.txt" <<EOF
created_at_utc=$timestamp
database=$db_name
api_image=${FILMIQOO_API_IMAGE:-unknown}
version=${FILMIQOO_VERSION:-unknown}
commit=${FILMIQOO_COMMIT:-unknown}
EOF

(
  cd "$backup_dir"
  sha256sum postgres.dump.enc postgres.contents.txt manifest.txt > SHA256SUMS
)

echo "Backup verified: $backup_dir"
