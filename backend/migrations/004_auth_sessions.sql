CREATE TABLE IF NOT EXISTS auth_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash text NOT NULL UNIQUE,
    device_name text NOT NULL DEFAULT '',
    user_agent text NOT NULL DEFAULT '',
    ip_address inet,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user
    ON auth_sessions (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_active
    ON auth_sessions (expires_at)
    WHERE revoked_at IS NULL;
