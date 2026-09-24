CREATE TABLE IF NOT EXISTS reel_playback_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reel_id uuid NOT NULL REFERENCES reels(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    watch_ms bigint NOT NULL DEFAULT 0 CHECK (watch_ms >= 0),
    duration_ms bigint NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    completed boolean NOT NULL DEFAULT false,
    rewatched boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reel_playback_events_reel_created
    ON reel_playback_events (reel_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reel_playback_events_user_created
    ON reel_playback_events (user_id, created_at DESC);
