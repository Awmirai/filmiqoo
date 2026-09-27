CREATE TABLE IF NOT EXISTS reel_share_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reel_id uuid NOT NULL REFERENCES reels(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    destination text NOT NULL DEFAULT 'system',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reel_share_events_reel_created
    ON reel_share_events (reel_id,created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reel_share_events_user_created
    ON reel_share_events (user_id,created_at DESC);
