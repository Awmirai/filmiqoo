-- Optional cumulative telemetry from new clients is idempotent across HTTP retries.
-- Existing sessions, legacy delta telemetry, progress and resume positions are preserved.
ALTER TABLE playback_sessions ADD COLUMN IF NOT EXISTS client_watched_ms bigint NOT NULL DEFAULT 0;
