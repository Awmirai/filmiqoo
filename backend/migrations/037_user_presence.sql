CREATE TABLE IF NOT EXISTS user_presence (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    media_version_id uuid REFERENCES media_versions(id) ON DELETE SET NULL,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    episode_id uuid REFERENCES episodes(id) ON DELETE SET NULL,
    state text NOT NULL DEFAULT 'offline' CHECK (state IN ('offline','watching')),
    position_ms bigint NOT NULL DEFAULT 0,
    visible_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_presence_visible
    ON user_presence (state,visible_until DESC)
    WHERE state='watching';
