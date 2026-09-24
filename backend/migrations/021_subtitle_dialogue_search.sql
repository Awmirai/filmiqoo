CREATE TABLE IF NOT EXISTS subtitle_cues (
    id bigserial PRIMARY KEY,
    media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    language text NOT NULL DEFAULT 'und',
    start_ms bigint NOT NULL,
    end_ms bigint NOT NULL,
    cue_text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (start_ms >= 0),
    CHECK (end_ms >= start_ms),
    CHECK (char_length(cue_text) BETWEEN 1 AND 4000)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_subtitle_cues_unique
    ON subtitle_cues (media_version_id, language, start_ms, end_ms, md5(cue_text));

CREATE INDEX IF NOT EXISTS idx_subtitle_cues_version_time
    ON subtitle_cues (media_version_id, language, start_ms);

CREATE INDEX IF NOT EXISTS idx_subtitle_cues_search
    ON subtitle_cues USING gin (to_tsvector('simple', cue_text));
