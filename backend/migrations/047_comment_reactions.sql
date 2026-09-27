CREATE TABLE IF NOT EXISTS comment_reactions (
    comment_id uuid NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction text NOT NULL DEFAULT 'like',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_comment_reactions_user
    ON comment_reactions (user_id,created_at DESC);
