ALTER TABLE viewer_profiles
    ADD COLUMN IF NOT EXISTS pin_hash text NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS failed_pin_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pin_locked_until timestamptz;

CREATE INDEX IF NOT EXISTS idx_viewer_profiles_pin_lock
    ON viewer_profiles (user_id, pin_locked_until)
    WHERE pin_hash <> '';
