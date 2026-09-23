CREATE TABLE IF NOT EXISTS user_mutes (
    muter_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    muted_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (muter_user_id, muted_user_id),
    CHECK (muter_user_id <> muted_user_id)
);

CREATE INDEX IF NOT EXISTS idx_blocks_blocker_created
    ON blocks (blocker_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_mutes_muter_created
    ON user_mutes (muter_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reports_reporter_created
    ON reports (reporter_user_id, created_at DESC);
