#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DEPLOY_DIR/../.." && pwd)"
ENV_FILE="$DEPLOY_DIR/.env.production"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this deployment helper as root."
  exit 1
fi
if [[ ! -s "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE"
  exit 1
fi

SHA="$(git -C "$REPO_DIR" rev-parse HEAD)"
SHORT_SHA="${SHA:0:12}"
IMAGE="filmiqoo-api:${SHORT_SHA}"
VERSION="live-${SHORT_SHA}"

read_env() {
  local key="$1"
  awk -v key="$key" 'index($0,key"=")==1 { value=substr($0,length(key)+2) } END { print value }' "$ENV_FILE"
}

write_env() {
  local key="$1" value="$2"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    sed -i "s#^${key}=.*#${key}=${value}#" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

OLD_IMAGE="$(read_env FILMIQOO_API_IMAGE)"
OLD_VERSION="$(read_env FILMIQOO_VERSION)"
OLD_COMMIT="$(read_env FILMIQOO_COMMIT)"
DOMAIN="$(read_env FILMIQOO_DOMAIN)"

# Share a private edge assertion between Caddy and the API. Never print it.
EDGE_SECRET="$(read_env GEO_PROXY_HEADER_SECRET)"
if [[ ${#EDGE_SECRET} -lt 32 ]]; then
  umask 077
  write_env GEO_PROXY_HEADER_SECRET "$(openssl rand -hex 32)"
fi
chmod 600 "$ENV_FILE"

echo "Building $IMAGE from $SHA..."
docker build -t "$IMAGE" "$REPO_DIR/backend"

write_env FILMIQOO_API_IMAGE "$IMAGE"
write_env FILMIQOO_VERSION "$VERSION"
write_env FILMIQOO_COMMIT "$SHA"
chmod 600 "$ENV_FILE"

cd "$DEPLOY_DIR"
docker compose --env-file .env.production run --rm --no-deps caddy \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
if ! docker compose --env-file .env.production up -d --no-build api caddy; then
  echo "API deployment failed; restoring previous image."
  write_env FILMIQOO_API_IMAGE "$OLD_IMAGE"
  write_env FILMIQOO_VERSION "$OLD_VERSION"
  write_env FILMIQOO_COMMIT "$OLD_COMMIT"
  docker compose --env-file .env.production up -d --no-build api caddy || true
  exit 1
fi

ready=0
for _ in $(seq 1 40); do
  if [[ -n "$DOMAIN" ]] && curl -fsS --max-time 5 "https://$DOMAIN/readyz" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done

if [[ "$ready" -ne 1 ]]; then
  echo "New API failed readiness; rolling back."
  write_env FILMIQOO_API_IMAGE "$OLD_IMAGE"
  write_env FILMIQOO_VERSION "$OLD_VERSION"
  write_env FILMIQOO_COMMIT "$OLD_COMMIT"
  docker compose --env-file .env.production up -d --no-build api caddy || true
  exit 1
fi

docker compose --env-file .env.production ps api
echo "Filmiqoo API deployed: $IMAGE"
