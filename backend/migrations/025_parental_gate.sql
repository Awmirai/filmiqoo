ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS parental_pin_hash text NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS parental_failed_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS parental_locked_until timestamptz;

CREATE INDEX IF NOT EXISTS idx_user_preferences_parental_lock
    ON user_preferences (parental_locked_until)
    WHERE parental_pin_hash <> '';
