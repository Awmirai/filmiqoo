CREATE TABLE IF NOT EXISTS playback_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewer_profile_id uuid REFERENCES viewer_profiles(id) ON DELETE SET NULL,
    started_media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    current_media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    started_at timestamptz NOT NULL DEFAULT now(),
    last_heartbeat_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    position_ms bigint NOT NULL DEFAULT 0,
    duration_ms bigint NOT NULL DEFAULT 0,
    watched_ms bigint NOT NULL DEFAULT 0,
    buffer_count integer NOT NULL DEFAULT 0,
    buffer_ms bigint NOT NULL DEFAULT 0,
    quality_switch_count integer NOT NULL DEFAULT 0,
    network_type text NOT NULL DEFAULT '',
    device_name text NOT NULL DEFAULT '',
    app_version text NOT NULL DEFAULT '',
    completed boolean NOT NULL DEFAULT false,
    exit_reason text NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_playback_sessions_user_started
    ON playback_sessions (user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_playback_sessions_media_started
    ON playback_sessions (started_media_version_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_playback_sessions_active
    ON playback_sessions (user_id, viewer_profile_id, last_heartbeat_at DESC)
    WHERE ended_at IS NULL;
