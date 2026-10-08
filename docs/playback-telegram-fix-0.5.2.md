# Playback and Telegram delivery fixes

The production crash report and the original Android regression both showed
`MediaRouteButton` failing with `background can not be translucent: #0`.
The application now supplies an AppCompat theme with opaque colors and protects
both Cast button construction and setup. Online player preparation explicitly
uses the main dispatcher and reports preparation errors in the player. Optional
exit telemetry cannot close the app if the server is unavailable.

The online instrumentation test obtains a playback token from a local test API,
streams an original generated H.264/AAC MP4, verifies decoded video and advancing
playback, and exits through Back. Run `bash scripts/generate-player-test-fixture.sh`
before connected Android tests. No third-party film is used by CI.

The home screen now has a separate recent-catalog shelf, refreshing every eight
seconds while its lifecycle is started and immediately after resume. Manual
refresh is available. Temporary failures preserve the last successful list.

## Telegram incident

The API listener bot and the file-stream bot are distinct identities. Both must
have access to the configured source channel. The API bot was made an
administrator by the owner. Post `filmiqq1/21` had never reached ingest, and its
filename was additionally parsed as `Union County YTS`. That one existing record
was corrected to `Union County` and resolved through the existing internal API.
Catalog readiness and two byte ranges through the production playback endpoint
were verified without deploying code.

The parser now separates the release suffix from the original title. The
listener acknowledges a Telegram update only after durable ingestion succeeds,
retries temporary failures in order, and stops promptly on cancellation.
Older posts predating bot access need explicit backfill; `getUpdates` is not a
channel history API. Polling it manually while the listener runs must be avoided.

The source incorporates the two existing, previously unpublished production
commits (`31b5578`, `d4c5a70`) so a later deployment preserves the internal stream
metadata endpoint and real MTProto hashes. Production `fsb.env` is excluded from
Git. Copy the example and use the same secret for the stream service's
`FILMIQOO_INTERNAL_SECRET` and the API's `TELEGRAM_STREAM_INTERNAL_SECRET`.

The catalog query now includes episode versions and orders by the latest file.
Three observed PostgreSQL failures in watch activity, social access and playback
moments are fixed with migrated-database regression coverage.

## Release boundary

This is a preview APK compatible with the existing server. Production release
builds retain strict Iran access enforcement. This work does not merge main,
deploy the backend, enable geography restrictions, or restart production services.
Permanent parser, listener and SQL changes require a separately approved backend
deployment. The bot permission change and recovery of post 21 are already live.
