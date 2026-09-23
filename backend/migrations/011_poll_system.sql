CREATE TABLE IF NOT EXISTS poll_options (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    label text NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    vote_count bigint NOT NULL DEFAULT 0,
    CHECK (char_length(label) BETWEEN 1 AND 120)
);

CREATE TABLE IF NOT EXISTS poll_votes (
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_id uuid NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_poll_options_post_sort
    ON poll_options (post_id,sort_order);
