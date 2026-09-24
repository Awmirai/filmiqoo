CREATE TABLE IF NOT EXISTS watch_party_queue (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    watch_party_id uuid NOT NULL REFERENCES watch_parties(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    suggested_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','playing','played','removed')),
    vote_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS watch_party_queue_votes (
    queue_item_id uuid NOT NULL REFERENCES watch_party_queue(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (queue_item_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_watch_party_queue_rank
    ON watch_party_queue (watch_party_id,status,vote_count DESC,created_at ASC);
