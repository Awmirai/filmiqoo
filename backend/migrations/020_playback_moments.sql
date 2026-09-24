CREATE TABLE IF NOT EXISTS playback_moments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    position_ms bigint NOT NULL CHECK (position_ms >= 0),
    body text NOT NULL DEFAULT '',
    reaction text NOT NULL DEFAULT '',
    spoiler boolean NOT NULL DEFAULT false,
    like_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (char_length(body) <= 1200),
    CHECK (char_length(reaction) <= 32),
    CHECK (body <> '' OR reaction <> '')
);

CREATE TABLE IF NOT EXISTS playback_moment_likes (
    moment_id uuid NOT NULL REFERENCES playback_moments(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (moment_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_playback_moments_version_position
    ON playback_moments (media_version_id,position_ms,created_at DESC);
