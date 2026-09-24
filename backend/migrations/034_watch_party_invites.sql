ALTER TABLE watch_parties
    ADD COLUMN IF NOT EXISTS invite_code text;

UPDATE watch_parties
   SET invite_code=substring(replace(gen_random_uuid()::text,'-',''),1,12)
 WHERE invite_code IS NULL OR invite_code='';

CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_parties_invite_code
    ON watch_parties (invite_code)
    WHERE invite_code IS NOT NULL;

CREATE TABLE IF NOT EXISTS watch_party_reminders (
    watch_party_id uuid NOT NULL REFERENCES watch_parties(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    notified_at timestamptz,
    PRIMARY KEY (watch_party_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_watch_party_reminders_due
    ON watch_party_reminders (user_id,notified_at,created_at);
