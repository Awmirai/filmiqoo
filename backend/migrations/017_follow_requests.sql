CREATE TABLE IF NOT EXISTS follow_requests (
    requester_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','accepted','declined','cancelled')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (requester_user_id,target_user_id),
    CHECK (requester_user_id <> target_user_id)
);

CREATE INDEX IF NOT EXISTS idx_follow_requests_target_status
    ON follow_requests (target_user_id,status,updated_at DESC);
