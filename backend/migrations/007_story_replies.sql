CREATE TABLE IF NOT EXISTS story_replies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    sender_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_story_replies_story_created
    ON story_replies (story_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_story_replies_sender_created
    ON story_replies (sender_user_id, created_at DESC);
