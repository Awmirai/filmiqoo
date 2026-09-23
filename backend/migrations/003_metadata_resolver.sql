ALTER TABLE telegram_ingest_items
    ADD COLUMN IF NOT EXISTS telegram_file_numeric_id bigint NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_versions_telegram_source
    ON media_versions (source_kind, source_ref)
    WHERE source_kind='telegram';
