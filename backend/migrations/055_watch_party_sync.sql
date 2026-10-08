-- Additive sync clock; membership/history and existing rooms stay intact.
ALTER TABLE watch_parties
    ADD COLUMN IF NOT EXISTS playback_updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS playback_revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS playback_controller_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

UPDATE watch_parties SET playback_controller_user_id=host_user_id
WHERE playback_controller_user_id IS NULL;
