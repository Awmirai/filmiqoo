ALTER TABLE users
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_status_check;

ALTER TABLE users
    ADD CONSTRAINT users_status_check
    CHECK (status IN ('active','disabled','banned','pending','deleted'));

CREATE INDEX IF NOT EXISTS idx_users_deleted_at
    ON users (deleted_at)
    WHERE deleted_at IS NOT NULL;
