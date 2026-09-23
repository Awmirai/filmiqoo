ALTER TABLE media_versions
    ADD COLUMN IF NOT EXISTS stream_hash text NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS telegram_file_id text NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS stream_ready boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS telegram_ingest_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    telegram_chat_id bigint NOT NULL,
    telegram_message_id bigint NOT NULL,
    telegram_file_id text NOT NULL DEFAULT '',
    file_name text NOT NULL,
    file_size_bytes bigint NOT NULL DEFAULT 0,
    mime_type text NOT NULL DEFAULT '',
    caption text NOT NULL DEFAULT '',
    stream_hash text NOT NULL DEFAULT '',
    parsed_kind text NOT NULL DEFAULT 'movie',
    parsed_title text NOT NULL DEFAULT '',
    parsed_season integer,
    parsed_episode integer,
    parsed_year integer,
    parsed_quality text NOT NULL DEFAULT '',
    parsed_source text NOT NULL DEFAULT '',
    parsed_codec text NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'pending_metadata'
        CHECK (status IN ('pending_metadata','resolving','ready','failed','ignored')),
    resolved_media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    resolved_episode_id uuid REFERENCES episodes(id) ON DELETE SET NULL,
    resolved_media_version_id uuid REFERENCES media_versions(id) ON DELETE SET NULL,
    error_text text NOT NULL DEFAULT '',
    received_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (telegram_chat_id, telegram_message_id)
);

CREATE INDEX IF NOT EXISTS idx_telegram_ingest_status
    ON telegram_ingest_items (status, received_at);

CREATE INDEX IF NOT EXISTS idx_telegram_ingest_title
    ON telegram_ingest_items (parsed_title);
