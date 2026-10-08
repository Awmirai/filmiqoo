-- Metadata-only indexing; no video download or guessed audio tracks.
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS genre_ids jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS popularity double precision NOT NULL DEFAULT 0;
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS vote_count bigint NOT NULL DEFAULT 0;
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS series_status text NOT NULL DEFAULT '';
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS series_type text NOT NULL DEFAULT '';
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS season_count integer;
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS episode_count integer;
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS metadata_indexed_at timestamptz;
ALTER TABLE media_titles ADD COLUMN IF NOT EXISTS metadata_attempted_at timestamptz;
ALTER TABLE media_versions ADD COLUMN IF NOT EXISTS is_dubbed boolean NOT NULL DEFAULT false;
ALTER TABLE media_versions ADD COLUMN IF NOT EXISTS is_persian_dubbed boolean NOT NULL DEFAULT false;
ALTER TABLE media_versions ADD COLUMN IF NOT EXISTS has_persian_subtitle boolean NOT NULL DEFAULT false;
ALTER TABLE media_versions ADD COLUMN IF NOT EXISTS detection_source text NOT NULL DEFAULT '';
ALTER TABLE media_versions ADD COLUMN IF NOT EXISTS detection_confidence text NOT NULL DEFAULT 'NONE';
ALTER TABLE media_versions ADD COLUMN IF NOT EXISTS detection_evidence jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE media_versions ADD COLUMN IF NOT EXISTS detection_version integer NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS telegram_source_conventions (
    telegram_chat_id bigint PRIMARY KEY,
    generic_dub_is_persian boolean NOT NULL DEFAULT false,
    generic_sub_is_persian boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS media_titles_discovery_kind_rating ON media_titles (kind,rating DESC,id) WHERE visibility='public';
CREATE INDEX IF NOT EXISTS media_titles_discovery_kind_year ON media_titles (kind,year DESC,id) WHERE visibility='public';
CREATE INDEX IF NOT EXISTS media_titles_discovery_language ON media_titles (original_language,kind,year) WHERE visibility='public';
CREATE INDEX IF NOT EXISTS media_titles_discovery_country ON media_titles USING gin (origin_countries);
CREATE INDEX IF NOT EXISTS media_titles_discovery_genres ON media_titles USING gin (genre_ids);
CREATE INDEX IF NOT EXISTS media_titles_discovery_status ON media_titles (series_status,kind) WHERE visibility='public';
CREATE INDEX IF NOT EXISTS media_versions_discovery_dub_title ON media_versions (media_title_id) WHERE stream_ready AND is_persian_dubbed;
CREATE INDEX IF NOT EXISTS media_versions_discovery_dub_episode ON media_versions (episode_id) WHERE stream_ready AND is_persian_dubbed;
CREATE INDEX IF NOT EXISTS media_versions_discovery_sub_title ON media_versions (media_title_id) WHERE stream_ready AND has_persian_subtitle;
CREATE INDEX IF NOT EXISTS media_versions_discovery_sub_episode ON media_versions (episode_id) WHERE stream_ready AND has_persian_subtitle;

CREATE INDEX IF NOT EXISTS media_titles_discovery_popularity ON media_titles(kind,popularity DESC,id) WHERE visibility='public';
CREATE INDEX IF NOT EXISTS media_titles_metadata_refresh ON media_titles(metadata_attempted_at,metadata_indexed_at,id) WHERE tmdb_id IS NOT NULL;
