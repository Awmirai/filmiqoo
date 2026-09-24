CREATE TABLE IF NOT EXISTS playback_devices (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id text NOT NULL,
    device_name text NOT NULL,
    platform text NOT NULL DEFAULT 'android',
    current_media_version_id uuid REFERENCES media_versions(id) ON DELETE SET NULL,
    position_ms bigint NOT NULL DEFAULT 0,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_playback_devices_user_seen
    ON playback_devices (user_id, last_seen_at DESC);

CREATE TABLE IF NOT EXISTS playback_handoffs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_device_id text NOT NULL,
    target_device_id text NOT NULL,
    media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    position_ms bigint NOT NULL DEFAULT 0,
    state text NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending','accepted','cancelled','expired')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '5 minutes'),
    accepted_at timestamptz,
    CHECK (source_device_id <> target_device_id)
);

CREATE INDEX IF NOT EXISTS idx_playback_handoffs_target_pending
    ON playback_handoffs (user_id, target_device_id, state, created_at DESC);
