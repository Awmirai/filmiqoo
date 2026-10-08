CREATE UNIQUE INDEX IF NOT EXISTS idx_media_versions_telegram_file
    ON media_versions (telegram_file_id)
    WHERE source_kind = 'telegram'
      AND telegram_file_id IS NOT NULL
      AND telegram_file_id <> '';
