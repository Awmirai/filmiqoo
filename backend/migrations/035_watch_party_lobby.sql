ALTER TABLE watch_party_members
    ADD COLUMN IF NOT EXISTS ready boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS last_seen_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE watch_parties
    ADD COLUMN IF NOT EXISTS ready_check_enabled boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS watch_party_join_requests (
    watch_party_id uuid NOT NULL REFERENCES watch_parties(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','declined')),
    requested_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    PRIMARY KEY (watch_party_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_watch_party_join_requests_status
    ON watch_party_join_requests (watch_party_id,status,requested_at DESC);

CREATE TABLE IF NOT EXISTS watch_party_reactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    watch_party_id uuid NOT NULL REFERENCES watch_parties(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (char_length(emoji) BETWEEN 1 AND 16)
);

CREATE INDEX IF NOT EXISTS idx_watch_party_reactions_recent
    ON watch_party_reactions (watch_party_id,created_at DESC);
