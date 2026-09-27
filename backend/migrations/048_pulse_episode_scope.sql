ALTER TABLE media_pulse_reactions
    ADD COLUMN IF NOT EXISTS episode_id uuid REFERENCES episodes(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_media_pulse_episode_created
    ON media_pulse_reactions (episode_id, created_at DESC)
    WHERE episode_id IS NOT NULL;
