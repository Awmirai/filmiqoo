-- Remember a verified/manual completion independently from the CURRENT resume state.
-- A partial rewatch must still be resumable while counting the title once.
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS ever_completed boolean NOT NULL DEFAULT false;
ALTER TABLE viewer_watch_progress ADD COLUMN IF NOT EXISTS ever_completed boolean NOT NULL DEFAULT false;
UPDATE watch_progress SET ever_completed=true WHERE completed=true AND ever_completed=false;
UPDATE viewer_watch_progress SET ever_completed=true WHERE completed=true AND ever_completed=false;
-- Shared by playback and manual completion writes, without changing the current resume state.
CREATE OR REPLACE FUNCTION remember_viewing_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        NEW.ever_completed := OLD.ever_completed OR NEW.ever_completed OR NEW.completed;
    ELSE
        NEW.ever_completed := NEW.ever_completed OR NEW.completed;
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS remember_completion ON watch_progress;
CREATE TRIGGER remember_completion BEFORE INSERT OR UPDATE ON watch_progress
    FOR EACH ROW EXECUTE FUNCTION remember_viewing_completion();
DROP TRIGGER IF EXISTS remember_completion ON viewer_watch_progress;
CREATE TRIGGER remember_completion BEFORE INSERT OR UPDATE ON viewer_watch_progress
    FOR EACH ROW EXECUTE FUNCTION remember_viewing_completion();
CREATE INDEX IF NOT EXISTS idx_playback_viewer_started ON playback_sessions(viewer_profile_id,started_at DESC);
