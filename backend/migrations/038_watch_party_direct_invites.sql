CREATE TABLE IF NOT EXISTS watch_party_direct_invites (
    watch_party_id uuid NOT NULL REFERENCES watch_parties(id) ON DELETE CASCADE,
    invited_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','accepted','declined')),
    created_at timestamptz NOT NULL DEFAULT now(),
    responded_at timestamptz,
    PRIMARY KEY (watch_party_id,invited_user_id)
);

CREATE INDEX IF NOT EXISTS idx_watch_party_direct_invites_user
    ON watch_party_direct_invites (invited_user_id,status,created_at DESC);
