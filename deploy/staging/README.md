# Filmiqoo Staging

Staging intentionally runs the backend with `APP_ENV=production` so the same secret strength, HTTPS, origin and object-storage validation used in production is exercised before release.

## GitHub Environment

Create a GitHub Environment named `staging`.

### Variables

- `FILMIQOO_STAGING_BASE_URL` — public HTTPS API base URL, for example `https://staging-api.example.com`
- `STAGING_HOST` — SSH host/IP
- `STAGING_SSH_USER`
- `STAGING_SSH_PORT` — normally `22`
- `STAGING_DEPLOY_DIR` — directory owned by the SSH user, for example `/home/deploy/filmiqoo-staging`
- `FILMIQOO_STAGING_APPLICATION_ID` — Android application id used for staging builds

Optional Firebase client variables for the staging APK:

- `FILMIQOO_STAGING_FIREBASE_API_KEY`
- `FILMIQOO_STAGING_FIREBASE_APP_ID`
- `FILMIQOO_STAGING_FIREBASE_PROJECT_ID`
- `FILMIQOO_STAGING_FIREBASE_SENDER_ID`

### Secrets

- `STAGING_SSH_PRIVATE_KEY`
- `STAGING_KNOWN_HOSTS` — trusted OpenSSH known_hosts line(s); do not generate this blindly inside CI
- `STAGING_ENV_FILE_BASE64` — base64 of the complete private `.env.staging`
- `STAGING_OPS_SECRET` — must match `OPS_SECRET` inside the staging env file

The private staging env is never committed. Start from `.env.staging.example`, replace every placeholder, then encode it:

```bash
base64 -w 0 .env.staging
```

On macOS use:

```bash
base64 < .env.staging | tr -d '\n'
```

## DNS and server prerequisites

Point the staging API hostname to the staging server before the first deployment. Ports 80 and 443 must reach Caddy.

The SSH user must have Docker Engine, Docker Compose v2, permission to run Docker without an interactive sudo prompt, and write access to `STAGING_DEPLOY_DIR`.

PostgreSQL and Redis are not exposed publicly by this compose stack. Object storage is intentionally external/S3-compatible and must be configured through the private staging env.

## Deployment behavior

The staging workflow builds the exact backend commit into an immutable GHCR image, preserves the current staging env for rollback, uploads compose/Caddy/deploy files over SSH, waits for API readiness, automatically restores the previous image/env if readiness fails, then performs HTTPS smoke tests from the GitHub runner.

After a successful server smoke, the workflow also builds a staging Android APK pointed at the staging API.

The staging load-smoke workflow is a separate manual gate and should be run after a successful staging deploy.
