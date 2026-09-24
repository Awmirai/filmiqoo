CREATE TABLE IF NOT EXISTS post_saves (
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id,user_id)
);

CREATE TABLE IF NOT EXISTS post_share_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    destination text NOT NULL DEFAULT 'system',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_post_saves_user_created
    ON post_saves (user_id,created_at DESC);

CREATE INDEX IF NOT EXISTS idx_post_share_events_post_created
    ON post_share_events (post_id,created_at DESC);
