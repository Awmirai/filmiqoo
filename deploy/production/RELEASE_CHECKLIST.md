# Filmiqoo 1.0 Production Release Checklist

## Code and CI
- [ ] Backend CI is green: embedded migrations, race detector, smoke tests and go vet.
- [ ] Android CI is green: unit tests, blocking lint, debug APK and minified release smoke.
- [ ] Production Contract Checks are green.
- [ ] Release commit SHA is reviewed and immutable.

## Configuration and secrets
- [ ] APP_ENV=production.
- [ ] JWT, playback, Telegram ingest, object-storage and Ops secrets are long random production values.
- [ ] PostgreSQL and Redis credentials are unique production credentials.
- [ ] PUBLIC_API_BASE_URL and public object-storage endpoint use HTTPS.
- [ ] ALLOWED_ORIGINS contains only intended production origins.
- [ ] Firebase backend service account and Android Firebase client values are configured.
- [ ] Android signing keystore and passwords are present only in secret storage.

## Data safety
- [ ] An encrypted database backup was created immediately before deployment.
- [ ] Backup SHA-256 verification passed.
- [ ] A restore rehearsal has succeeded in a non-production environment for the current backup format.
- [ ] Migration history/checksums are clean.
- [ ] Previous immutable API image SHA is recorded for rollback.

## Capacity and reliability
- [ ] PostgreSQL/Redis pool sizes match the deployment size.
- [ ] Public search rate limit is configured.
- [ ] Realtime connection limit is configured.
- [ ] Daily upload count/byte limits are configured.
- [ ] Normal API request timeout is configured.
- [ ] Staging load-smoke passes p95 and error-rate thresholds.
- [ ] Object storage passes readiness.
- [ ] Playback-origin error rate is acceptable.

## Operations
- [ ] /readyz returns ready.
- [ ] /internal/ops/status reports healthy PostgreSQL, Redis, object storage and configured push delivery.
- [ ] Push outbox has no unexplained dead backlog.
- [ ] Telegram ingest has no unexplained dead-letter backlog.
- [ ] Critical moderation queue has been reviewed.
- [ ] Fatal/error telemetry does not show a new release-blocking fingerprint.
- [ ] Stale upload count is under control.

## Android release
- [ ] Release API URL is HTTPS.
- [ ] Release signing validation passes.
- [ ] R8/minification and resource shrinking pass.
- [ ] Release lint passes with zero errors.
- [ ] Signed APK and AAB are generated.
- [ ] APK/AAB SHA-256 checksums are saved with the release.
- [ ] Push permission, token registration and notification deep links were tested on a physical device.
- [ ] Playback, download, PiP and offline behavior were tested on at least one low/mid-range and one recent Android device.

## Go-live smoke
- [ ] Login/session refresh works.
- [ ] Catalog/search/detail works.
- [ ] Playback token and byte-range streaming work.
- [ ] Download and resume work.
- [ ] Telegram ingest promotes a test item successfully.
- [ ] DM/group message, voice/media attachment and unread/read state work.
- [ ] Push notification arrives and opens the intended destination.
- [ ] Report/block/mute flows work.
- [ ] Privacy export works.
- [ ] Account deletion invalidates active access.
- [ ] Rollback command and previous image are ready before announcing release.
