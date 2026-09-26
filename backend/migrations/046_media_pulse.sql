CREATE TABLE IF NOT EXISTS media_pulse_reactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    emoji text NOT NULL CHECK (emoji IN ('🔥','😱','😂','❤️','👀')),
    position_ms bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_media_pulse_title_created
    ON media_pulse_reactions (media_title_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_media_pulse_user_created
    ON media_pulse_reactions (user_id, created_at DESC);
