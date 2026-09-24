CREATE TABLE IF NOT EXISTS push_devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id text NOT NULL,
    provider text NOT NULL CHECK (provider IN ('fcm','apns','webpush')),
    platform text NOT NULL DEFAULT 'android',
    push_token text NOT NULL,
    locale text NOT NULL DEFAULT 'fa',
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, device_id, provider),
    UNIQUE (provider, push_token)
);

CREATE INDEX IF NOT EXISTS idx_push_devices_user_enabled
    ON push_devices (user_id, enabled, last_seen_at DESC);
