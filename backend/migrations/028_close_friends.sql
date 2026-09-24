CREATE TABLE IF NOT EXISTS close_friends (
    owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_user_id, friend_user_id),
    CHECK (owner_user_id <> friend_user_id)
);

CREATE INDEX IF NOT EXISTS idx_close_friends_owner_created
    ON close_friends (owner_user_id, created_at DESC);
