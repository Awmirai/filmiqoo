#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="/opt/filmiqoo"
REPO_DIR="$ROOT/repo"
DEPLOY_DIR="$REPO_DIR/deploy/production"
REPO_URL="https://github.com/Awmirai/filmiqoo.git"
API_DOMAIN="api.filmiqo.com"
MEDIA_DOMAIN="media.filmiqo.com"
TLS_EMAIL="ops@filmiqo.com"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this bootstrap as root."
  exit 1
fi

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required before running this bootstrap."
  exit 1
}

apt-get update -y
apt-get install -y git curl openssl ca-certificates

mkdir -p "$ROOT"
if [[ -d "$REPO_DIR/.git" ]]; then
  git -C "$REPO_DIR" fetch origin main
  git -C "$REPO_DIR" reset --hard origin/main
else
  git clone --depth 1 --branch main "$REPO_URL" "$REPO_DIR"
fi

read -r -s -p "TMDB Read Access Token: " TMDB_TOKEN </dev/tty
echo
if [[ -z "$TMDB_TOKEN" ]]; then
  echo "TMDB token is required."
  exit 1
fi

read -r -p "Telegram stream base URL [https://stream.filmiqo.com]: " TELEGRAM_STREAM_BASE_URL </dev/tty
TELEGRAM_STREAM_BASE_URL="${TELEGRAM_STREAM_BASE_URL:-https://stream.filmiqo.com}"
if [[ ! "$TELEGRAM_STREAM_BASE_URL" =~ ^https?:// ]]; then
  echo "Telegram stream base URL must start with http:// or https://"
  exit 1
fi

read -r -p "Telegram API ID (optional for now): " TELEGRAM_API_ID </dev/tty
read -r -s -p "Telegram API hash (optional for now): " TELEGRAM_API_HASH </dev/tty
echo
read -r -s -p "Telegram bot token (optional for now): " TELEGRAM_BOT_TOKEN </dev/tty
echo
read -r -p "Telegram log/channel ID (optional for now): " TELEGRAM_LOG_CHANNEL </dev/tty

randhex() { openssl rand -hex "$1"; }

POSTGRES_PASSWORD="$(randhex 32)"
REDIS_PASSWORD="$(randhex 32)"
JWT_SECRET="$(randhex 48)"
TELEGRAM_INGEST_SECRET="$(randhex 48)"
PLAYBACK_SIGNING_SECRET="$(randhex 48)"
OPS_SECRET="$(randhex 48)"
MINIO_ROOT_USER="fq$(randhex 12)"
MINIO_ROOT_PASSWORD="$(randhex 32)"
BACKUP_PASSWORD="$(randhex 32)"

SHA="$(git -C "$REPO_DIR" rev-parse HEAD)"
SHORT_SHA="${SHA:0:12}"
IMAGE="filmiqoo-api:${SHORT_SHA}"

echo "Building backend image ${IMAGE}..."
docker build -t "$IMAGE" "$REPO_DIR/backend"

cat > "$DEPLOY_DIR/.env.production" <<EOF
FILMIQOO_API_IMAGE=$IMAGE
FILMIQOO_API_ENV_FILE=.env.production
FILMIQOO_VERSION=bootstrap-$SHORT_SHA
FILMIQOO_COMMIT=$SHA
FILMIQOO_DOMAIN=$API_DOMAIN
FILMIQOO_MEDIA_DOMAIN=$MEDIA_DOMAIN
FILMIQOO_TLS_EMAIL=$TLS_EMAIL

POSTGRES_DB=filmiqoo
POSTGRES_USER=filmiqoo
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
REDIS_PASSWORD=$REDIS_PASSWORD

MINIO_ROOT_USER=$MINIO_ROOT_USER
MINIO_ROOT_PASSWORD=$MINIO_ROOT_PASSWORD
OBJECT_STORAGE_BUCKET=filmiqoo-media

APP_ENV=production
HTTP_ADDR=:8080
JWT_SECRET=$JWT_SECRET
TMDB_TOKEN=$TMDB_TOKEN

TELEGRAM_API_ID=$TELEGRAM_API_ID
TELEGRAM_API_HASH=$TELEGRAM_API_HASH
TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN
TELEGRAM_LOG_CHANNEL=$TELEGRAM_LOG_CHANNEL
TELEGRAM_INGEST_SECRET=$TELEGRAM_INGEST_SECRET
TELEGRAM_STREAM_BASE_URL=$TELEGRAM_STREAM_BASE_URL
PLAYBACK_SIGNING_SECRET=$PLAYBACK_SIGNING_SECRET
PLAYBACK_TOKEN_TTL_SECONDS=300
PUBLIC_API_BASE_URL=https://$API_DOMAIN
TELEGRAM_STREAM_HASH_LENGTH=6

AUTH_ACCESS_TTL_MINUTES=15
AUTH_REFRESH_TTL_DAYS=30
ALLOWED_ORIGINS=https://filmiqo.com,https://www.filmiqo.com
MAX_JSON_BODY_BYTES=2097152
AUTH_LOGIN_RATE_LIMIT_PER_MINUTE=10
AUTH_REGISTER_RATE_LIMIT_PER_HOUR=8
AUTH_REFRESH_RATE_LIMIT_PER_MINUTE=30
AUTHENTICATED_WRITE_RATE_LIMIT_PER_MINUTE=240

FIREBASE_PUSH_ENABLED=false
PUSH_MAX_ATTEMPTS=6
TELEGRAM_INGEST_MAX_ATTEMPTS=8
TELEGRAM_INGEST_RETRY_BASE_SECONDS=30
TELEMETRY_RETENTION_DAYS=30
OPS_SECRET=$OPS_SECRET

PUBLIC_SEARCH_RATE_LIMIT_PER_MINUTE=60
REALTIME_CONNECTIONS_PER_USER=8
UPLOAD_DAILY_COUNT_LIMIT=100
UPLOAD_DAILY_BYTES_LIMIT=10737418240
POSTGRES_MAX_CONNS=40
POSTGRES_MIN_CONNS=4
REDIS_POOL_SIZE=60
API_REQUEST_TIMEOUT_SECONDS=20

ALLOW_INTERNAL_PLAINTEXT_DATABASE=true
BACKUP_PASSWORD_FILE=./backup-password.txt
EOF

printf '%s\n' "$BACKUP_PASSWORD" > "$DEPLOY_DIR/backup-password.txt"
chmod 600 "$DEPLOY_DIR/.env.production" "$DEPLOY_DIR/backup-password.txt"

cd "$DEPLOY_DIR"

echo "Validating production compose..."
docker compose --env-file .env.production -f docker-compose.yml config >/dev/null

echo "Starting Filmiqoo production services..."
docker compose --env-file .env.production -f docker-compose.yml up -d

echo "Waiting for HTTPS readiness..."
ready=0
for _ in $(seq 1 45); do
  if curl -fsS --max-time 5 "https://$API_DOMAIN/readyz" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done

docker compose --env-file .env.production -f docker-compose.yml ps

if [[ "$ready" -ne 1 ]]; then
  echo
  echo "API did not become externally ready yet."
  echo "Confirm DNS-only A records for $API_DOMAIN and $MEDIA_DOMAIN point to this VPS."
  echo "Recent Caddy/API logs:"
  docker compose --env-file .env.production -f docker-compose.yml logs --tail=80 caddy api
  exit 1
fi

echo
echo "Filmiqoo backend is online: https://$API_DOMAIN"
echo "Filmiqoo media gateway is configured: https://$MEDIA_DOMAIN"
echo "Secrets are stored only in $DEPLOY_DIR/.env.production (mode 600)."
echo "Do not copy that file into chat or commit it to Git."
