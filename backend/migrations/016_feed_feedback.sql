CREATE TABLE IF NOT EXISTS feed_feedback (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type text NOT NULL CHECK (target_type IN ('post','reel','media')),
    target_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('not_interested','show_more')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,target_type,target_id)
);

CREATE INDEX IF NOT EXISTS idx_feed_feedback_user_action
    ON feed_feedback (user_id,action,updated_at DESC);
