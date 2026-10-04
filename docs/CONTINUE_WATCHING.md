# Continue watching

The home rail shows the most recently watched version of each movie or series.
Completed titles, unavailable streams and hidden titles are excluded. Older
quality variants and episodes cannot reappear after the latest item is completed.

Playback context resumes the same movie or episode across quality variants using
the active viewer's timeline. Requests without a viewer header retain the legacy
account timeline. A viewer with no progress starts from zero.

The Android player saves progress periodically, on completion, on backgrounding
and before release. The final write survives disposal of the player, captures its
viewer ID, and uses the active local or Cast position. Successful writes notify
the home rail to refresh without reloading the entire catalog.

## Verification

Run `go test -race ./...` and `go vet ./...` from `backend/`.
With migrations applied to a temporary PostgreSQL database, run:

```sh
FILMIQOO_E2E=1 DATABASE_URL='postgres://...' \
  go test -race ./internal/server -run TestContinueWatchingE2E -v
```

The integration test covers viewer isolation, legacy account progress, movie and
episode resume across qualities, rail deduplication, completion, progress writes,
and unavailable or hidden media. Backend CI runs this with its database services.
Android CI runs unit tests, lint and both debug and release compilation.

On a device, verify short playback followed by back navigation, backgrounding,
quality changes, profile switching and completion. Verify Cast separately with a
receiver. Saving requires connectivity; an offline retry queue is not included.
