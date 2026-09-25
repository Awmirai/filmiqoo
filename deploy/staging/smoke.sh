#!/usr/bin/env bash
set -euo pipefail

: "${FILMIQOO_STAGING_BASE_URL:?FILMIQOO_STAGING_BASE_URL is required}"
: "${FILMIQOO_STAGING_OPS_SECRET:?FILMIQOO_STAGING_OPS_SECRET is required}"

base="${FILMIQOO_STAGING_BASE_URL%/}"

request_status() {
  local method="$1"
  local url="$2"
  local expected="$3"
  shift 3

  local code
  code="$(curl \
    --silent \
    --show-error \
    --location \
    --retry 4 \
    --retry-all-errors \
    --retry-delay 2 \
    --connect-timeout 8 \
    --max-time 20 \
    --request "$method" \
    --output /tmp/filmiqoo-smoke-body \
    --write-out '%{http_code}' \
    "$@" \
    "$url")"

  if [[ "$code" != "$expected" ]]; then
    echo "Smoke failure: $method $url returned $code, expected $expected"
    cat /tmp/filmiqoo-smoke-body || true
    exit 1
  fi
}

request_status GET "$base/healthz" 200
request_status GET "$base/readyz" 200
request_status GET "$base/v1/catalog/home" 200
request_status GET "$base/v1/search?q=matrix" 200

request_status GET "$base/internal/ops/status" 200 \
  -H "X-Filmiqoo-Ops-Secret: $FILMIQOO_STAGING_OPS_SECRET"

request_status POST "$base/v1/telemetry/events" 202 \
  -H "Content-Type: application/json" \
  --data '{"deviceId":"staging-ci","sessionId":"staging-ci","eventType":"staging_deploy_smoke","severity":"info","message":"staging deploy smoke","metadata":{"source":"github-actions"},"appVersion":"staging","platform":"ci"}'

echo "External staging smoke passed."
