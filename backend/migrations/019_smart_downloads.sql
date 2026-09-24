ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS smart_downloads boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS download_storage_limit_mb bigint NOT NULL DEFAULT 10240;

ALTER TABLE user_preferences
    DROP CONSTRAINT IF EXISTS user_preferences_download_storage_limit_check;
ALTER TABLE user_preferences
    ADD CONSTRAINT user_preferences_download_storage_limit_check
    CHECK (download_storage_limit_mb = 0 OR
           (download_storage_limit_mb >= 1024 AND download_storage_limit_mb <= 204800));
