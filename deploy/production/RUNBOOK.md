# Filmiqoo Production Runbook

## Release and deploy

1. Release only from a reviewed Git commit.
2. Create an encrypted database backup before every deploy.
3. Deploy the immutable backend image tagged with the exact commit SHA.
4. Keep `FILMIQOO_COMMIT` equal to that SHA and `FILMIQOO_VERSION` equal to the public release.
5. Start/update the stack:
   ```bash
   docker compose --env-file .env.production -f docker-compose.yml up -d
   ```
6. Verify API readiness and the protected operations status endpoint.
7. Check push and Telegram queues before declaring the deploy healthy.

## Iran connection policy

The cinema Android build requires `GET /v1/access` to return HTTP 200 with
`allowed: true`, `country: IR`, `enforced: true` and `policy: iran-only-v1`.
Deploy the API and Caddy changes together before distributing the APK. An old
server with no access endpoint intentionally does not unlock the app.

`bootstrap-vps.sh` generates a private `GEO_PROXY_HEADER_SECRET`. For existing
installations, `update-api.sh` adds it without printing the value and restarts both
API and Caddy. Keep the API port private. Caddy must be the direct internet edge;
placing another proxy in front requires an explicitly trusted client-IP design.
Public `X-Forwarded-For` or country headers never authorize access.

The image includes DB-IP IP-to-Country Lite data (CC BY 4.0). The deployment helper
uses the UTC month as a Docker build argument and image tag, so Docker cannot reuse
an expired dataset. The existing `filmiqoo-auto-update.timer` refreshes it monthly,
including months without source changes, and retries failed downloads on its next
run. Ensure that timer is installed and enabled. For installations without that
timer, run `update-api.sh` at least monthly. Data older than 62 days fails closed.

After deployment, verify `/healthz` and `/readyz`, then `/v1/access` from real
Iranian IPv4 and IPv6 connections and from an outside-Iran connection. The latter
must return HTTP 403. Check login, catalog, playback and downloads in both cases.
This policy checks the network exit country, not legal residence; an Iranian VPN
exit can appear to be inside Iran.

## Database migrations

The API applies embedded migrations before accepting traffic.

Migrations are:
- serialized with a PostgreSQL advisory lock;
- recorded in `schema_migrations`;
- protected by SHA-256 checksums;
- forward-only.

Never edit an already-applied migration. Add a new numbered migration.

A standalone migration command is also available in the backend image/source through `cmd/migrate`.

## Backup

Keep the backup password outside Git and outside the backup directory.

```bash
cd deploy/production
BACKUP_PASSWORD_FILE=/secure/path/backup-password.txt ./backup.sh
```

The backup script encrypts the PostgreSQL custom-format dump with AES-256-CBC/PBKDF2, validates it with `pg_restore --list`, records deployed image/version/commit metadata and writes SHA-256 checksums.

Copy verified backups to a second storage location with independent credentials.

## Restore

Restore only after verifying the target backup and during a controlled maintenance window.

```bash
cd deploy/production
CONFIRM_RESTORE=YES \
BACKUP_PASSWORD_FILE=/secure/path/backup-password.txt \
./restore.sh ./backups/<timestamp>
```

The API is stopped during database replacement and must pass `/readyz` before the restore procedure is considered successful.

Regularly rehearse restore on a non-production environment.

## Application rollback

Rollback to an immutable previous image:

```bash
cd deploy/production
./rollback.sh \
  ghcr.io/awmirai/filmiqoo-api:<previous-commit-sha> \
  <previous-commit-sha> \
  <previous-version>
```

The rollback script updates the deployment metadata, starts the prior image and verifies readiness. If the candidate fails readiness, the prior environment file is restored.

Database migrations are forward-only. If a schema change needs correction, deploy a new corrective migration rather than altering migration history.

## Operations checks

Protected endpoint:

`GET /internal/ops/status`

Header:

`X-Filmiqoo-Ops-Secret: <OPS_SECRET>`

Review:
- PostgreSQL and Redis health/latency;
- Firebase push readiness;
- push outbox states, especially `failed` and `dead`;
- Telegram ingest states, especially `failed` and `dead_letter`;
- fatal/error telemetry fingerprints from the last 24 hours;
- active sessions and stream-ready media counts.

## Android release

Production Android release requires:
- HTTPS API URL;
- release signing credentials;
- Firebase client configuration;
- release unit tests;
- release lint;
- R8/minification/resource shrinking;
- APK and AAB generation;
- SHA-256 checksums.

Signing key material must remain in secret storage and never be committed.

## Incident order

1. `/readyz`
2. `/internal/ops/status`
3. API/reverse-proxy logs
4. PostgreSQL and Redis health
5. push outbox
6. Telegram dead-letter queue
7. crash/error telemetry
8. rollback application image if the incident correlates with a release
9. restore data only when corruption/loss is confirmed and recovery is necessary
