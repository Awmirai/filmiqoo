ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_version bigint NOT NULL DEFAULT 0;
ALTER TABLE auth_sessions ADD COLUMN IF NOT EXISTS auth_version bigint NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS password_reset_challenges (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_password_reset_user_created
    ON password_reset_challenges (user_id, created_at DESC);

-- The delivery payload is encrypted; raw recovery codes never live in this table.
CREATE TABLE IF NOT EXISTS password_reset_delivery (
    challenge_id uuid PRIMARY KEY REFERENCES password_reset_challenges(id) ON DELETE CASCADE,
    encrypted_payload bytea NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_password_reset_delivery_pending
    ON password_reset_delivery (next_attempt_at) WHERE delivered_at IS NULL;
