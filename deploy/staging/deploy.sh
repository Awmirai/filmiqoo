#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

ENV_FILE=".env.staging"
ROLLBACK_ENV=".env.staging.rollback"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.yml)

if [[ ! -s "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE"
  exit 2
fi

"${COMPOSE[@]}" config >/dev/null

wait_ready() {
  local attempts="${1:-45}"
  for _ in $(seq 1 "$attempts"); do
    if "${COMPOSE[@]}" exec -T api wget -q -O /dev/null http://127.0.0.1:8080/readyz; then
      return 0
    fi
    sleep 2
  done
  return 1
}

rollback() {
  if [[ ! -s "$ROLLBACK_ENV" ]]; then
    echo "No rollback environment is available."
    return 1
  fi
  echo "Candidate failed readiness. Rolling back staging..."
  cp "$ROLLBACK_ENV" "$ENV_FILE"
  "${COMPOSE[@]}" config >/dev/null
  "${COMPOSE[@]}" pull api
  "${COMPOSE[@]}" up -d --remove-orphans
  if wait_ready 45; then
    echo "Rollback readiness passed."
    return 0
  fi
  echo "Rollback readiness failed."
  return 1
}

if [[ "${1:-}" == "--rollback" ]]; then
  rollback
  exit $?
fi

echo "Pulling staging images..."
"${COMPOSE[@]}" pull

echo "Starting staging candidate..."
if ! "${COMPOSE[@]}" up -d --remove-orphans; then
  rollback || true
  exit 1
fi

if ! wait_ready 60; then
  "${COMPOSE[@]}" ps
  "${COMPOSE[@]}" logs --tail=200 api || true
  rollback || true
  exit 1
fi

image="$(grep -E '^FILMIQOO_API_IMAGE=' "$ENV_FILE" | tail -n1 | cut -d= -f2-)"
commit="$(grep -E '^FILMIQOO_COMMIT=' "$ENV_FILE" | tail -n1 | cut -d= -f2-)"
version="$(grep -E '^FILMIQOO_VERSION=' "$ENV_FILE" | tail -n1 | cut -d= -f2-)"

cat > .deploy-state <<STATE
FILMIQOO_API_IMAGE=$image
FILMIQOO_COMMIT=$commit
FILMIQOO_VERSION=$version
DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
STATE

echo "Staging readiness passed."
cat .deploy-state
