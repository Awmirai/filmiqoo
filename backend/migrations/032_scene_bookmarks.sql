CREATE TABLE IF NOT EXISTS scene_bookmarks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    position_ms bigint NOT NULL CHECK (position_ms >= 0),
    note text NOT NULL DEFAULT '',
    tag text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, media_version_id, position_ms),
    CHECK (char_length(note) <= 1200),
    CHECK (char_length(tag) <= 48)
);

CREATE INDEX IF NOT EXISTS idx_scene_bookmarks_user_updated
    ON scene_bookmarks (user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_scene_bookmarks_user_version_position
    ON scene_bookmarks (user_id, media_version_id, position_ms);
